package com.diary.feature.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * 导出文件分享：经 FileProvider 授权后交给系统分享面板，不申请存储权限。
 */
object ExportShare {
    fun chooser(
        context: Context,
        files: List<File>,
        format: ExportFormat,
    ): Intent {
        val uris =
            files.map { file ->
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }
        val mime =
            when (format) {
                ExportFormat.PDF -> "application/pdf"
                ExportFormat.PNG -> "image/png"
            }
        val send =
            if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE)
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        send.type = mime
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "分享日记")
    }
}
