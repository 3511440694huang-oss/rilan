package app.rilan.daily.schedule

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.rilan.daily.MainActivity
import app.rilan.daily.R
import app.rilan.daily.core.Prefs
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/**
 * 每日「今日简报」通知的后端。
 *
 * 和 [ReminderScheduler] 的日程提醒相互独立：独立渠道、独立闹钟、独立开关，
 * 一天只发一条，用固定通知 ID 覆盖，不会因为重复注册堆通知。
 *
 * 时间用 [AlarmManager.setInexactRepeating]（RTC_WAKEUP + INTERVAL_DAY），
 * 不申请 SCHEDULE_EXACT_ALARM；系统省电策略下可能晚几分钟，符合「每日简报」的定位。
 *
 * 开关与时间以 AppSettings（JsonCodec 持久化）为唯一来源：
 * 本类只负责闹钟与通知，不自己存设置。AppRepository.saveSettings 在设置变化时调用 [schedule]
 * 立即生效；App 启动 / 开机后调用 [restore] 按当前设置重新挂上闹钟。
 */
object DailyBriefNotifier {

    /** 每日简报专用渠道，和日程提醒的 `rilan_reminders` 分开，用户可单独静音。 */
    const val CHANNEL_ID = "rilan_daily_brief"

    /** 固定通知 ID：每天覆盖上一条，历史不会堆积。 */
    const val NOTIFICATION_ID = 9902

    /** 闹钟广播 action；与 ReminderReceiver 的 action 不同，互不干扰。 */
    const val ACTION_DAILY_BRIEF = "app.rilan.daily.action.DAILY_BRIEF"

    // ── 对外入口（phase2 接线用）─────────────────────────────────────────────

    /**
     * 按开关与时间立即生效。
     *
     * [enabled] 为 true 时挂上（或替换）每天 [hour]:[minute] 的非精确闹钟；
     * 为 false 时撤掉闹钟。重复调用是幂等的。
     *
     * 注意：这里不持久化设置（设置存 AppSettings），调用方负责先保存再调用。
     */
    fun schedule(context: Context, enabled: Boolean, hour: Int, minute: Int) {
        val app = context.applicationContext
        val minuteOfDay = hour.coerceIn(0, 23) * 60 + minute.coerceIn(0, 59)
        if (enabled) {
            reschedule(app, minuteOfDay)
        } else {
            cancelAlarm(app)
        }
    }

    /**
     * 只撤掉已注册的闹钟，不影响 AppSettings 里的开关。
     *
     * 用户关掉「每日通知」走设置保存 → [schedule] 传 enabled = false；
     * 这里的 cancel 是给「清空 / 重装」这类场景用的低层入口。
     */
    fun cancel(context: Context) {
        cancelAlarm(context.applicationContext)
    }

    /**
     * 立刻发一条简报（设置页「试发一条」用），不改变已注册的闹钟。
     *
     * @return true 表示通知已交给系统；没有通知权限 / 渠道被关时返回 false，由调用方给提示。
     */
    fun sendNow(context: Context): Boolean = runCatching {
        postDailyBrief(context.applicationContext)
    }.getOrElse {
        Log.w(TAG, "send now failed: ${it.message}")
        false
    }

    /**
     * 按 AppSettings 里保存的开关恢复闹钟。开机、应用更新、App 启动时调用。
     *
     * 内部吞掉所有异常：恢复失败最多是少一条通知，不允许影响其它恢复逻辑或让进程崩溃。
     */
    fun restore(context: Context) {
        val app = context.applicationContext
        runCatching {
            val settings = Prefs(app).loadSettings()
            if (settings.dailyNoticeEnabled) {
                reschedule(app, settings.dailyNoticeMinuteOfDay)
            } else {
                cancelAlarm(app)
            }
        }.onFailure { Log.w(TAG, "restore daily brief failed: ${it.message}") }
    }

    // ── 通知 ────────────────────────────────────────────────────────────────

    /** 创建（或补齐）每日简报渠道；重复调用无副作用。 */
    fun ensureChannels(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.setDescription(CHANNEL_DESCRIPTION)
        runCatching { manager.createNotificationChannel(channel) }
            .onFailure { Log.w(TAG, "create daily channel failed: ${it.message}") }
    }

    /**
     * 发一条今日简报。没有通知权限时静默跳过（Android 13+ 未授权时直接 notify 会抛安全异常）。
     *
     * 注意：这里不检查开关，因为「试发一条」在开关关闭时也应能确认通知是否正常；
     * 闹钟落点 [DailyBriefReceiver] 会先检查开关。
     */
    @SuppressLint("MissingPermission")
    fun postDailyBrief(context: Context): Boolean {
        val app = context.applicationContext
        if (!canPost(app)) return false
        ensureChannels(app)
        val body = runCatching { buildBody(app) }.getOrDefault(FALLBACK_BODY)
        val open = openAppPendingIntent(app)
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(TITLE)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_notification, ACTION_OPEN, open)
            .build()
        return runCatching {
            NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, notification)
        }.onFailure {
            Log.w(TAG, "post daily brief failed: ${it.message}")
        }.isSuccess
    }

    // ── 设置读取（唯一来源是 AppSettings，见 core/Models.kt / core/JsonCodec.kt）────────

    fun isEnabled(context: Context): Boolean =
        runCatching { Prefs(context).loadSettings().dailyNoticeEnabled }
            .getOrDefault(DEFAULT_DAILY_ENABLED)

    /** 当天第几分钟（0..1439），对应 AppSettings.dailyNoticeMinuteOfDay。 */
    fun minuteOfDay(context: Context): Int =
        runCatching { Prefs(context).loadSettings().dailyNoticeMinuteOfDay }
            .getOrDefault(DEFAULT_MINUTE_OF_DAY)
            .coerceIn(0, MINUTES_PER_DAY - 1)

    /** 开机自启动开关，对应 AppSettings.autoStartEnabled；只影响开机后 MCP 前台服务恢复。 */
    fun isAutoStartEnabled(context: Context): Boolean =
        runCatching { Prefs(context).loadSettings().autoStartEnabled }
            .getOrDefault(DEFAULT_AUTO_START)

    // ── 内部实现 ────────────────────────────────────────────────────────────

    /** 幂等注册：先撤同一个 PendingIntent 的旧闹钟，再挂新的非精确重复闹钟。 */
    private fun reschedule(app: Context, minuteOfDay: Int) {
        val alarmManager = app.getSystemService(AlarmManager::class.java) ?: return
        ensureChannels(app)
        val operation = alarmPendingIntent(app)
        runCatching {
            alarmManager.cancel(operation)
            alarmManager.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                nextTriggerMillis(minuteOfDay),
                AlarmManager.INTERVAL_DAY,
                operation,
            )
        }.onFailure { Log.w(TAG, "reschedule failed: ${it.message}") }
    }

    private fun cancelAlarm(app: Context) {
        val alarmManager = app.getSystemService(AlarmManager::class.java) ?: return
        val operation = alarmPendingIntent(app)
        runCatching { alarmManager.cancel(operation) }
            .onFailure { Log.w(TAG, "cancel alarm failed: ${it.message}") }
    }

    private fun alarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, DailyBriefReceiver::class.java)
            .setAction(ACTION_DAILY_BRIEF)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 点通知 / 点「看看今天」都打开主页；manifest 里 MainActivity 是 singleTask，会复用已有界面。 */
    private fun openAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        return PendingIntent.getActivity(
            context,
            OPEN_REQUEST_CODE,
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

    /** 正文：天气缓存摘要 + 今天到期的日程 / 待办；没数据时给中性文案。 */
    private fun buildBody(app: Context): String {
        val parts = buildList {
            runCatching { weatherSummary(app) }.getOrNull()?.let { add(it) }
            runCatching { scheduleSummary(app) }.getOrNull()?.let { add(it) }
        }
        return if (parts.isEmpty()) FALLBACK_BODY else parts.joinToString(" · ")
    }

    /** 例：`北京 18° 多云`。缓存太旧（超过 36 小时）就不用，避免把人带偏。 */
    private fun weatherSummary(app: Context): String? {
        val weather = Prefs(app).loadWeatherCache() ?: return null
        if (weather.updatedAt <= 0L) return null
        val age = System.currentTimeMillis() - weather.updatedAt
        if (age > WEATHER_FRESH_MILLIS) return null
        val place = weather.locationLabel.trim().ifBlank { "天气" }
        return "$place ${weather.currentTemp.roundToInt()}° ${weather.condition}"
    }

    /** 例：`今天 2 件：09:00 交周报、18:30 买菜`。没有当天事项时返回 null。 */
    private fun scheduleSummary(app: Context): String? {
        val events = ScheduleStore(app).snapshot()
        if (events.isEmpty()) return null
        val today = LocalDate.now()
        val todayText = today.toString()
        val due = events.filter { event ->
            Recurrence.occursOn(event, today) && !event.isDoneOn(todayText)
        }
        if (due.isEmpty()) return null
        val shown = due.take(MAX_SCHEDULE_ITEMS).map { event ->
            val clock = event.time?.trim().orEmpty()
            val title = event.title.trim().ifBlank { UNTITLED }.take(MAX_TITLE_CHARS)
            if (clock.isEmpty()) title else "$clock $title"
        }
        val suffix = if (due.size > MAX_SCHEDULE_ITEMS) " 等 ${due.size} 件" else ""
        return "今天 ${due.size} 件：${shown.joinToString("、")}$suffix"
    }

    /** API 33+ 没有 POST_NOTIFICATIONS 就静默放弃，避免安全异常。 */
    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    // ── 常量 ────────────────────────────────────────────────────────────────

    private const val TAG = "DailyBriefNotifier"

    /**
     * 开关 / 时间 / 自启动的真实来源是 AppSettings；这里只是解码失败时的兜底默认值，
     * 必须与 core/Models.kt 里 AppSettings 的默认值保持一致（opt-in：默认关闭）。
     */
    private const val DEFAULT_DAILY_ENABLED = false
    private const val DEFAULT_MINUTE_OF_DAY = 8 * 60
    private const val DEFAULT_AUTO_START = false

    private const val MINUTES_PER_DAY = 24 * 60
    private const val WEATHER_FRESH_MILLIS = 36L * 60 * 60 * 1000
    private const val MAX_SCHEDULE_ITEMS = 2
    private const val MAX_TITLE_CHARS = 16

    private const val TITLE = "日览 · 该看看今天了"
    private const val FALLBACK_BODY = "天气、信息流和日程已经准备好了，点开看看今天。"
    private const val ACTION_OPEN = "看看今天"
    private const val CHANNEL_NAME = "每日简报"
    private const val CHANNEL_DESCRIPTION = "每天固定时间发一条天气与待办摘要"
    private const val UNTITLED = "未命名日程"

    /** 独立 requestCode：和 ReminderScheduler 的 0x524C、NotificationHelper 的 0x0A 都不同。 */
    private const val ALARM_REQUEST_CODE = 0x5344
    private const val OPEN_REQUEST_CODE = 0x5345
}
