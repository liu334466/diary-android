package com.diary.app

import android.app.Application
import android.os.Looper
import com.diary.core.common.CrashLogger
import com.diary.core.database.search.SearchIndexManager
import com.diary.core.font.FontAvailabilityChecker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 应用入口，Hilt 依赖注入的宿主。
 * 启动时校验导入字体文件，缺失或损坏的字体标记为无效，引用处回退系统字体。
 */
@HiltAndroidApp
class DiaryApplication : Application() {
    @Inject
    lateinit var fontAvailabilityChecker: FontAvailabilityChecker

    @Inject
    lateinit var searchIndexManager: SearchIndexManager

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        CrashLogger.install(this)
        super.onCreate()
        // 字体校验与索引补建放到首帧之后，避免与冷启动争抢磁盘与数据库。
        Looper.getMainLooper().queue.addIdleHandler {
            applicationScope.launch {
                runCatching { searchIndexManager.ensureIndexed() }
                fontAvailabilityChecker.verifyAll()
            }
            false
        }
    }
}
