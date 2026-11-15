package app.rilan.daily.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.rilan.daily.MainActivity
import app.rilan.daily.R
import app.rilan.daily.RilanApp

/**
 * 让 MCP 服务在 App 退到后台后仍可被外部 AI 访问。
 * 只绑定 127.0.0.1，真正的鉴权在 RilanMcpServer 内完成。
 */
class McpService : Service() {

    private val repo: app.rilan.daily.core.AppRepository
        get() = (application as RilanApp).repo

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            repo.stopMcpServerOnly(markDisabled = true)
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        val result = repo.startMcpServerOnly()
        if (result.isFailure) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { repo.stopMcpServerOnly(markDisabled = false) }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, McpService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val endpoint = repo.mcpState.value.endpoint
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("日览 MCP 服务运行中")
            .setContentText("$endpoint · 外部 AI 可读取日报并操作日程")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "$endpoint\n只监听本机，需要 Bearer token。点按打开扩展页查看配置。"
            ))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "MCP 服务", NotificationManager.IMPORTANCE_LOW).apply {
                description = "日览 MCP 本地服务运行状态"
                setShowBadge(false)
            }
        )
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        const val CHANNEL_ID = "rilan_mcp"
        private const val NOTIFICATION_ID = 8801
        private const val ACTION_STOP = "app.rilan.daily.mcp.STOP"

        fun start(context: Context) {
            val intent = Intent(context, McpService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, McpService::class.java)) }
        }
    }
}
