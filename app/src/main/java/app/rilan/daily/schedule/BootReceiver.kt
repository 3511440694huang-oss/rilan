package app.rilan.daily.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机、应用更新、改系统时间 / 换时区后，把每日提醒闹钟重新挂上。
 * 只做一次轻量注册，不在这里做长任务。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != null && action !in SUPPORTED_ACTIONS) return
        val app = context.applicationContext
        runCatching {
            NotificationHelper.ensureChannels(app)
            ReminderScheduler.ensureDaily(app)
        }.onFailure { Log.w(TAG, "restore reminders failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "BootReceiver"

        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
