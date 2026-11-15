package app.rilan.daily

import android.app.Application
import app.rilan.daily.core.AppRepository
import app.rilan.daily.schedule.ReminderScheduler

class RilanApp : Application() {
    lateinit var repo: AppRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repo = AppRepository(this)
        ReminderScheduler.ensureDaily(this)
    }
}
