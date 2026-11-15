package app.rilan.daily.schedule

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
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
import app.rilan.daily.core.EventKind
import app.rilan.daily.core.ScheduleEvent

/**
 * 通知渠道与通知内容。只依赖 androidx.core，不引用 Compose / 任何 UI 类。
 */
object NotificationHelper {

    const val CHANNEL_ID = "rilan_reminders"
    const val TEST_NOTIFICATION_ID = 9901

    private const val CHANNEL_NAME_FALLBACK = "日程提醒"
    private const val ACTION_VIEW = "查看日程"
    private const val UNTITLED_TITLE = "未命名日程"
    private const val OPEN_REQUEST_CODE = 0x0A
    private const val MAX_NOTE_CHARS = 60
    private const val TAG = "NotificationHelper"

    private val TIME_PATTERN = Regex("""^(\d{1,2}):(\d{2})$""")

    /**
     * 创建（或补齐）提醒渠道。重复调用无副作用。
     */
    fun ensureChannels(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val name = runCatching { app.getString(R.string.notif_channel_reminder) }
            .getOrDefault(CHANNEL_NAME_FALLBACK)
        val description = runCatching { app.getString(R.string.notif_channel_reminder_desc) }
            .getOrDefault("")
        val channel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_DEFAULT)
        if (description.isNotBlank()) channel.setDescription(description)
        runCatching { manager.createNotificationChannel(channel) }
            .onFailure { Log.w(TAG, "create channel failed: ${it.message}") }
    }

    /**
     * 发一条日程提醒。没有通知权限时静默返回。
     */
    fun postReminder(context: Context, event: ScheduleEvent, daysUntil: Long) {
        if (!canPost(context)) return
        ensureChannels(context)
        val app = context.applicationContext
        val body = bodyText(event)
        val open = openAppPendingIntent(app)
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(headlineText(event, daysUntil))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_notification, ACTION_VIEW, open)
            .build()
        notifySafely(app, event.id.hashCode(), notification)
    }

    /**
     * 发一条测试通知，用来确认权限与渠道都正常。
     */
    fun postTest(context: Context) {
        if (!canPost(context)) return
        ensureChannels(context)
        val app = context.applicationContext
        val open = openAppPendingIntent(app)
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("测试提醒")
            .setContentText("通知正常，日程提醒会在每天设定的时间到达。")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_notification, ACTION_VIEW, open)
            .build()
        notifySafely(app, TEST_NOTIFICATION_ID, notification)
    }

    // ── 内部实现 ────────────────────────────────────────────────────────────

    /**
     * 标题文案：生日当天 "今天是 {title} 🎂"，提前 "还有 N 天 · {title}（生日）"；
     * 普通日程 "今天 · {title}" / "还有 N 天 · {title}"。
     */
    private fun headlineText(event: ScheduleEvent, daysUntil: Long): String {
        val title = event.title.trim().ifBlank { UNTITLED_TITLE }
        val birthday = event.kind == EventKind.BIRTHDAY
        return when {
            birthday && daysUntil <= 0L -> "今天是 $title 🎂"
            birthday -> "还有 $daysUntil 天 · $title（生日）"
            daysUntil <= 0L -> "今天 · $title"
            else -> "还有 $daysUntil 天 · $title"
        }
    }

    /** 正文：有时间就带上 HH:mm，再补上类型与备注。 */
    private fun bodyText(event: ScheduleEvent): String {
        val parts = mutableListOf<String>()
        timeLabel(event.time)?.let { parts += it }
        parts += event.kind.label
        val notes = event.notes.trim()
        if (notes.isNotEmpty()) parts += notes.take(MAX_NOTE_CHARS)
        return parts.joinToString(" · ")
    }

    /** 只把真正的 HH:mm 当作时间，其它写法（"早上"、"8点"）不带进通知。 */
    private fun timeLabel(raw: String?): String? {
        val match = TIME_PATTERN.matchEntire(raw?.trim().orEmpty()) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return "%02d:%02d".format(hour, minute)
    }

    /** 点通知 / 点「查看日程」都打开主页。 */
    private fun openAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            context,
            OPEN_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** API 33+ 没有 POST_NOTIFICATIONS 就静默放弃，避免安全异常。 */
    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun notifySafely(context: Context, id: Int, notification: Notification) {
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
            .onFailure { Log.w(TAG, "notify failed: ${it.message}") }
    }
}
