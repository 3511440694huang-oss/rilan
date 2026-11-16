package app.rilan.daily.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 每日简报闹钟的落点：只发一条本地通知，不做网络请求、不读写仓库。
 *
 * 任何异常都吞掉并记日志，不允许让接收器崩溃。
 *
 * 注意：需要在 AndroidManifest.xml 中注册才会收到广播（phase2 完成）：
 *
 * ```
 * <receiver android:name=".schedule.DailyBriefReceiver" android:exported="false" />
 * ```
 */
class DailyBriefReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != null && action != DailyBriefNotifier.ACTION_DAILY_BRIEF) return
        val app = context.applicationContext
        runCatching {
            // 用户可能刚好在闹钟触发时关掉了开关：以当前开关为准，避免多发一条。
            if (!DailyBriefNotifier.isEnabled(app)) return@runCatching
            DailyBriefNotifier.postDailyBrief(app)
        }.onFailure { Log.w(TAG, "daily brief run failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "DailyBriefReceiver"
    }
}
