package app.rilan.daily

import android.app.Application
import app.rilan.daily.core.AppRepository
import app.rilan.daily.schedule.DailyBriefNotifier
import app.rilan.daily.schedule.ReminderScheduler

class RilanApp : Application() {
    lateinit var repo: AppRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repo = AppRepository(this)
        ReminderScheduler.ensureDaily(this)
        // 闹钟在强制停止 / 数据清空后会丢，每次进程启动按开关补挂一次；幂等。
        DailyBriefNotifier.restore(this)
    }
}
