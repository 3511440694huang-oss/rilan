package app.rilan.daily.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import app.rilan.daily.core.NewsRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 导出的文件格式。 */
enum class ExportFormat(val label: String, val extension: String, val mimeType: String) {
    MARKDOWN("Markdown", "md", "text/markdown"),
    CSV("CSV", "csv", "text/csv"),
}

/** 导出的记录范围。 */
enum class ExportRange(val label: String) {
    CURRENT("当前周期"),
    ALL("全部"),
}

/** 一次导出的结果；界面只负责提示，不需要了解存储细节。 */
sealed interface ExportOutcome {
    /** API 29+ 已直接写进系统下载目录。 */
    data class Saved(val displayPath: String, val fileName: String) : ExportOutcome

    /** API 26–28 没有公共下载目录的写权限，改为生成缓存文件交给系统分享。 */
    data class ShareFallback(val file: File, val fileName: String) : ExportOutcome

    data class Failed(val message: String) : ExportOutcome
}

/**
 * 把本机收录的 [NewsRecord] 导出成 Markdown / CSV。
 *
 * - 内容只来自传入的记录，字段与本地 history.json 一一对应，不补、不改、不编造。
 * - API 29+ 走 MediaStore 写「下载/日览」；API 26–28 用缓存文件 + FileProvider 分享兜底。
 * - 文件名固定为 `日览-收录-<日期>.<ext>`；「全部」范围会多一段「全部-」。
 */
object RecordExporter {

    private const val DOWNLOAD_SUBDIR = "日览"
    private const val FILE_PROVIDER_SUFFIX = ".fileprovider"
    private const val EXPORT_DIR = "exports"

    private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val STAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val HEADER_STAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** `日览-收录-2026-11-16.md`；全部范围是 `日览-收录-全部-2026-11-16.md`。 */
    fun fileName(
        range: ExportRange,
        format: ExportFormat,
        today: LocalDate = LocalDate.now(),
    ): String {
        val rangePart = if (range == ExportRange.ALL) "全部-" else ""
        return "日览-收录-$rangePart${today.format(DAY_FORMAT)}.${format.extension}"
    }

    /**
     * 纯文本生成，方便单独验证。
     *
     * @param rangeText 当前周期的时间范围文案，例如「11月3日 - 11月16日」；全部范围传空串。
     */
    fun buildContent(
        records: List<NewsRecord>,
        range: ExportRange,
        format: ExportFormat,
        rangeText: String = "",
        exportedAt: Long = System.currentTimeMillis(),
    ): String {
        val zone = ZoneId.systemDefault()
        val ordered = records.sortedByDescending { it.firstSeenAt }
        val stamp = Instant.ofEpochMilli(exportedAt).atZone(zone).format(HEADER_STAMP_FORMAT)
        val scope = when {
            rangeText.isNotBlank() -> "${range.label}（$rangeText）"
            else -> range.label
        }
        return when (format) {
            ExportFormat.MARKDOWN -> buildMarkdown(ordered, scope, stamp, zone)
            ExportFormat.CSV -> buildCsv(ordered, zone)
        }
    }

    /** 保存到系统下载目录：API 29+ 直接写，API 26–28 返回分享兜底。 */
    suspend fun saveToDownloads(
        context: Context,
        records: List<NewsRecord>,
        range: ExportRange,
        format: ExportFormat,
        rangeText: String = "",
    ): ExportOutcome = withContext(Dispatchers.IO) {
        if (records.isEmpty()) {
            return@withContext ExportOutcome.Failed("这段时间还没有收录记录，先刷新日报再导出。")
        }
        val name = fileName(range, format)
        val text = buildContent(records, range, format, rangeText)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, name, format, text)
            } else {
                val file = writeCacheFile(context, name, text)
                ExportOutcome.ShareFallback(file, name)
            }
        }.getOrElse { t ->
            ExportOutcome.Failed("导出失败：${friendlyMessage(t)}")
        }
    }

    /** 生成缓存文件并返回可分享的结果（不写公共目录）。 */
    suspend fun prepareForShare(
        context: Context,
        records: List<NewsRecord>,
        range: ExportRange,
        format: ExportFormat,
        rangeText: String = "",
    ): ExportOutcome = withContext(Dispatchers.IO) {
        if (records.isEmpty()) {
            return@withContext ExportOutcome.Failed("这段时间还没有收录记录，先刷新日报再导出。")
        }
        val name = fileName(range, format)
        val text = buildContent(records, range, format, rangeText)
        runCatching {
            ExportOutcome.ShareFallback(writeCacheFile(context, name, text), name)
        }.getOrElse { t ->
            ExportOutcome.Failed("导出失败：${friendlyMessage(t)}")
        }
    }

    /** 系统分享面板；调用方拿到后直接 startActivity 即可。 */
    fun shareIntent(context: Context, file: File, format: ExportFormat): Intent {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            context.packageName + FILE_PROVIDER_SUFFIX,
            file,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "分享收录条目")
    }

    // ── 内容生成 ───────────────────────────────────────────────────────────

    private fun buildMarkdown(
        records: List<NewsRecord>,
        scope: String,
        stamp: String,
        zone: ZoneId,
    ): String = buildString {
        appendLine("# 日览 · 收录条目")
        appendLine()
        appendLine("- 范围：$scope")
        appendLine("- 导出时间：$stamp")
        appendLine("- 条数：${records.size}")
        appendLine()
        appendLine("| 日期 | 首次收录时间 | 分类 | 来源 | 标题 | 链接 |")
        appendLine("| --- | --- | --- | --- | --- | --- |")
        records.forEach { record ->
            append("| ")
            append(mdCell(publishedDate(record, zone)))
            append(" | ")
            append(mdCell(stampOf(record.firstSeenAt, zone)))
            append(" | ")
            append(mdCell(record.category.label))
            append(" | ")
            append(mdCell(record.source.ifBlank { "未知来源" }))
            append(" | ")
            append(mdCell(record.title.ifBlank { "无标题" }))
            append(" | ")
            append(mdCell(record.url))
            appendLine(" |")
        }
        appendLine()
        appendLine("> 由「日览」回望页导出，内容来自本机收录记录。")
    }

    private fun buildCsv(records: List<NewsRecord>, zone: ZoneId): String = buildString {
        // BOM 让 Excel 直接按 UTF-8 打开中文。
        append('\uFEFF')
        appendLine("日期,首次收录时间,分类,来源,标题,链接")
        records.forEach { record ->
            append(csvCell(publishedDate(record, zone))).append(',')
            append(csvCell(stampOf(record.firstSeenAt, zone))).append(',')
            append(csvCell(record.category.label)).append(',')
            append(csvCell(record.source.ifBlank { "未知来源" })).append(',')
            append(csvCell(record.title.ifBlank { "无标题" })).append(',')
            append(csvCell(record.url))
            appendLine()
        }
    }

    private fun publishedDate(record: NewsRecord, zone: ZoneId): String =
        dateOf(record.publishedAt.takeIf { it > 0L } ?: record.firstSeenAt, zone)

    private fun dateOf(millis: Long, zone: ZoneId): String {
        if (millis <= 0L) return ""
        return runCatching {
            Instant.ofEpochMilli(millis).atZone(zone).format(DAY_FORMAT)
        }.getOrDefault("")
    }

    private fun stampOf(millis: Long, zone: ZoneId): String {
        if (millis <= 0L) return ""
        return runCatching {
            Instant.ofEpochMilli(millis).atZone(zone).format(STAMP_FORMAT)
        }.getOrDefault("")
    }

    private fun mdCell(value: String): String = value
        .replace("\r", " ")
        .replace("\n", " ")
        .replace("|", "\\|")
        .trim()

    private fun csvCell(value: String): String {
        val cleaned = value.replace("\r", " ").replace("\n", " ").trim()
        val needsQuote = cleaned.contains(',') || cleaned.contains('"')
        return if (needsQuote) "\"${cleaned.replace("\"", "\"\"")}\"" else cleaned
    }

    // ── 存储 ───────────────────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(
        context: Context,
        fileName: String,
        format: ExportFormat,
        text: String,
    ): ExportOutcome {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, format.mimeType)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + File.separator + DOWNLOAD_SUBDIR,
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("系统没有提供下载目录的写入位置")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("无法写入下载目录")
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        val savedName = runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull().takeUnless { it.isNullOrBlank() } ?: fileName
        return ExportOutcome.Saved("下载/$DOWNLOAD_SUBDIR/$savedName", savedName)
    }

    private fun writeCacheFile(context: Context, fileName: String, text: String): File {
        val dir = File(context.cacheDir, EXPORT_DIR)
        if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("无法创建导出目录")
        pruneOldFiles(dir)
        val file = File(dir, fileName)
        file.writeText(text, Charsets.UTF_8)
        return file
    }

    /** 缓存目录只放最近几天导出的文件，避免长期占空间。 */
    private fun pruneOldFiles(dir: File) {
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        runCatching {
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoff) file.delete()
            }
        }
    }

    private fun friendlyMessage(t: Throwable): String = when (t) {
        is IllegalStateException -> t.message?.takeIf { it.isNotBlank() } ?: "导出失败，请稍后重试"
        else -> t.message?.takeIf { it.isNotBlank() } ?: "导出失败，请稍后重试"
    }
}
