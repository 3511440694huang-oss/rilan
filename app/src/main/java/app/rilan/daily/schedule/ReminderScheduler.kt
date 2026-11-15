package app.rilan.daily.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import app.rilan.daily.core.Prefs
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 每日固定时刻的提醒闹钟。
 *
 * 用 setInexactRepeating（RTC_WAKEUP + INTERVAL_DAY），不申请精确闹钟权限；
 * 重复注册会替换同一个 PendingIntent，不会堆积闹钟。
 */
object ReminderScheduler {

    /** 固定 requestCode：保证每次注册替换的是同一个 PendingIntent。 */
    private const val REQUEST_CODE = 0x524C
    private const val ACTION_DAILY_REMINDER = "app.rilan.daily.action.DAILY_REMINDER"
    private const val DEFAULT_MINUTE_OF_DAY = 8 * 60 + 30
    private const val MINUTES_PER_DAY = 24 * 60
    private const val TAG = "ReminderScheduler"

    /**
     * 用 Prefs 里保存的 reminderMinuteOfDay 注册每日提醒；顺手确保渠道存在。
     */
    fun ensureDaily(context: Context) {
        val app = context.applicationContext
        val minuteOfDay = runCatching { Prefs(app).loadSettings().reminderMinuteOfDay }
            .getOrDefault(DEFAULT_MINUTE_OF_DAY)
        NotificationHelper.ensureChannels(app)
        reschedule(app, minuteOfDay)
    }

    /**
     * 以「当天第几分钟」重新注册；该时刻今天已过就从明天开始。时区取本地。
     */
    fun reschedule(context: Context, minuteOfDay: Int) {
        val app = context.applicationContext
        val alarmManager = app.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = nextTriggerMillis(minuteOfDay)
        val operation = reminderPendingIntent(app)
        runCatching {
            alarmManager.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                AlarmManager.INTERVAL_DAY,
                operation,
            )
        }.onFailure { Log.w(TAG, "reschedule failed: ${it.message}") }
    }

    /**
     * 立即发一条测试通知（不改变已注册的闹钟）。
     */
    fun sendTestNotification(context: Context) {
        NotificationHelper.postTest(context.applicationContext)
    }

    // ── 内部实现 ────────────────────────────────────────────────────────────

    private fun reminderPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION_DAILY_REMINDER)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 今天该时刻若已过，则取明天同一时刻；全部按本地时区计算。 */
    private fun nextTriggerMillis(minuteOfDay: Int): Long {
        val minute = minuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val today = now.toLocalDate().atTime(minute / 60, minute % 60).atZone(zone)
        val target = if (today.isAfter(now)) today else today.plusDays(1)
        return target.toInstant().toEpochMilli()
    }
}
