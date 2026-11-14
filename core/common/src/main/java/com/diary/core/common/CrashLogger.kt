package com.diary.core.common

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃兜底：把未捕获异常写入应用私有目录，最多保留 5 份，随后交回系统默认处理。
 * 日志仅保存在本机，由设置页主动导出，不上传。
 */
object CrashLogger {
    private const val DIR_NAME = "logs"
    private const val PREFIX = "crash-"
    private const val MAX_FILES = 5

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(appContext, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
                ?: runCatching { android.os.Process.killProcess(android.os.Process.myPid()) }
        }
    }

    fun directory(context: Context): File =
        File(context.filesDir, DIR_NAME).apply {
            if (!isDirectory) mkdirs()
        }

    fun files(context: Context): List<File> =
        directory(context)
            .listFiles()
            ?.filter { it.isFile && it.name.startsWith(PREFIX) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    fun latest(context: Context): File? = files(context).firstOrNull()

    /** 系统分享面板导出最近一份崩溃日志。 */
    fun shareIntent(context: Context): Intent? {
        val file = latest(context) ?: return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "导出诊断日志")
    }

    private fun write(
        context: Context,
        thread: Thread,
        throwable: Throwable,
    ) {
        val directory = directory(context)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val stack =
            StringWriter().also { writer ->
                throwable.printStackTrace(PrintWriter(writer))
            }
        val version =
            runCatching {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                "${info.versionName} (${info.longVersionCode})"
            }.getOrDefault("unknown")

        File(directory, "$PREFIX$stamp.log").writeText(
            buildString {
                appendLine("time: ${DiaryFormats.dateTime(System.currentTimeMillis())}")
                appendLine("version: $version")
                appendLine(
                    "device: ${Build.MANUFACTURER} ${Build.MODEL}, " +
                        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                )
                appendLine("thread: ${thread.name}")
                appendLine()
                append(stack)
            },
        )

        files(context).drop(MAX_FILES).forEach { it.delete() }
    }
}
