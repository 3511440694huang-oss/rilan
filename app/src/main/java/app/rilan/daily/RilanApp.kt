package app.rilan.daily

import android.app.Application
import app.rilan.daily.core.AppRepository
import app.rilan.daily.core.CrashLog
import app.rilan.daily.schedule.DailyBriefNotifier
import app.rilan.daily.schedule.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RilanApp : Application() {
    lateinit var repo: AppRepository
        private set

    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 先装崩溃记录，再构造仓库：启动阶段闪退也要留下堆栈。
        CrashLog.install(this)
        repo = AppRepository(this)
        // 闹钟恢复走系统服务，但没必要占着主线程；放到 IO 线程顺序执行，幂等。
        startupScope.launch {
            runCatching { ReminderScheduler.ensureDaily(this@RilanApp) }
            // 闹钟在强制停止 / 数据清空后会丢，每次进程启动按开关补挂一次。
            runCatching { DailyBriefNotifier.restore(this@RilanApp) }
        }
    }
}
