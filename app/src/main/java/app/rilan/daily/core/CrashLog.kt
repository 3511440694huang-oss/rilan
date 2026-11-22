package app.rilan.daily.core

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import app.rilan.daily.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把最近一次未捕获异常写在本机，供「扩展 → 更多 → 崩溃日志」查看。
 *
 * 只做最低限度的文件写入，不联网、不上传；写入失败也绝不能影响系统原本的崩溃处理。
 * 之前的版本闪退后没有任何现场，只能靠猜；从这里开始，下次闪退可以直接看到堆栈。
 */
object CrashLog {

    private const val FILE_NAME = "last-crash.txt"
    private const val MAX_CHARS = 60_000

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            // 交回系统 / 上一个 handler，保持原来的崩溃行为与日志。
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Log.e("RilanCrash", "uncaught on ${thread.name}", error)
                Process.killProcess(Process.myPid())
            }
        }
    }

    fun read(context: Context): String? = runCatching {
        val file = file(context)
        if (!file.exists()) null else file.readText(Charsets.UTF_8).takeIf { it.isNotBlank() }
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        val body = buildString {
            appendLine("日览崩溃记录")
            appendLine("时间：$time")
            appendLine("版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("机型：${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("系统：Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("线程：${thread.name}")
            appendLine()
            appendLine(error.stackTraceToString())
        }.take(MAX_CHARS)
        file(context).writeText(body, Charsets.UTF_8)
    }
}
