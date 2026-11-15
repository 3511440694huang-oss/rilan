package app.rilan.daily.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.rilan.daily.core.Prefs
import java.time.LocalDate

/**
 * 每日闹钟的落点：读一遍日程，按「优先级允许的提前天数 + 还有几天」发通知，并记录去重 key。
 *
 * 只做本地轻量计算，不发网络请求；任何异常都不允许让接收器崩溃。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        runCatching { deliver(app) }
            .onFailure { Log.w(TAG, "reminder run failed: ${it.message}") }
    }

    private fun deliver(app: Context) {
        NotificationHelper.ensureChannels(app)

        val prefs = Prefs(app)
        val events = ScheduleStore(app).snapshot()
        if (events.isEmpty()) return

        val today = LocalDate.now()
        val notified = prefs.lastNotifiedKeys().toMutableSet()
        var delivered = false

        events.forEach { event ->
            val days = Recurrence.daysUntil(event, today) ?: return@forEach
            // LOW 只在当天，MEDIUM 0..3，HIGH 0..7
            if (days < 0L || days > event.priority.reminderDays.toLong()) return@forEach
            val occurrence = Recurrence.nextOccurrence(event, today) ?: return@forEach
            val key = "${event.id}:$today:$occurrence"
            // 同一 event + 同一次发生 + 同一天，只提醒一次
            if (!notified.add(key)) return@forEach
            NotificationHelper.postReminder(app, event, days)
            delivered = true
        }

        if (delivered) prefs.saveLastNotifiedKeys(notified)
    }

    companion object {
        private const val TAG = "ReminderReceiver"
    }
}
