package app.rilan.daily.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.rilan.daily.RilanApp

/**
 * 开机、应用更新、改系统时间 / 换时区后：
 * 1. 重新挂上每日日程提醒；
 * 2. 按独立开关恢复每日简报闹钟；
 * 3. 「开机自启动」打开时，尝试恢复用户之前启用的 MCP 前台服务。
 *
 * 只做轻量注册与一次启动尝试，不在这里做长任务；
 * Android 12+ 对后台启动前台服务有限制，部分 ROM 会直接拒绝，失败一律静默记日志，不弹提示、不崩溃。
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

        // 每日简报有独立渠道与独立闹钟；开关关闭时 restore 内部只撤闹钟。
        DailyBriefNotifier.restore(app)

        // 只有系统启动 / 应用更新才值得再拉一次前台服务；改时间、换时区只需重挂闹钟。
        if (action == null || action in MCP_RESTORE_ACTIONS) {
            restoreMcpServiceIfAutoStart(app)
        }
    }

    /**
     * 「开机自启动」打开时，恢复用户上次启用的 MCP 前台服务。
     *
     * 走仓库现有入口 AppRepository.ensureMcpRunningIfEnabled，
     * 它内部已经判断 mcpEnabled 且用 runCatching 包住服务启动；这里再兜一层，
     * 防止进程 / 服务状态异常时把开机广播弄崩。
     */
    private fun restoreMcpServiceIfAutoStart(app: Context) {
        if (!DailyBriefNotifier.isAutoStartEnabled(app)) return
        runCatching {
            val host = app as? RilanApp ?: return@runCatching
            host.repo.ensureMcpRunningIfEnabled()
        }.onFailure { Log.w(TAG, "restore mcp service failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "BootReceiver"

        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )

        /** 只有这两类事件才尝试拉起前台服务。 */
        private val MCP_RESTORE_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
