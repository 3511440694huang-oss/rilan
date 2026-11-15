package app.rilan.daily.plugins

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

private const val SEED_MARKER = ".seeded"
private const val SEED_V2_MARKER = ".seeded-v2"
private const val README_FILE = "README.txt"

/** 1.1 之前内置但国内直连不可用的英文源，升级时清掉，避免一直报错。 */
private val LEGACY_DEFAULT_IDS = setOf(
    "bbc-zh", "bbc-world", "guardian-world", "aljazeera",
    "hackernews", "gnews-security", "gnews-world",
)

/** 1.2 新增的中文源；升级时只补这些，尊重用户删掉旧源的选择。 */
private val NEW_DEFAULT_IDS = setOf(
    "ifanr", "chinanews", "people", "v2ex", "juejin", "qbitai", "gcores",
    "4hou", "seebug", "chinanews-world", "people-world", "zhihu-hot", "baidu-hot",
)

/** 与 SourcePlugin.parse 内部的 id 规则保持一致，用于校验 originalId，挡住路径穿越。 */
private val SAFE_ID = Regex("^[a-z0-9][a-z0-9._-]{1,47}$")

private val DEFAULT_IDS: Set<String> = SourcePlugin.DEFAULTS.mapTo(HashSet()) { it.id }

/** builtIn 优先，其次分类顺序，最后名称。 */
private val PLUGIN_ORDER: Comparator<SourcePlugin> =
    compareByDescending<SourcePlugin> { it.builtIn }
        .thenBy { it.category.ordinal }
        .thenBy { it.name }

/**
 * 来源插件的文件仓库：一个 `.json` 一个插件。
 *
 * 目录优先用 `Android/data/app.rilan.daily/files/plugins/sources`（用户可以用文件管理器加插件），
 * 外部目录不可用时退回应用私有目录 `filesDir/plugins/sources`。
 *
 * 首次运行（没有 `.seeded` 标记）写入 [SourcePlugin.DEFAULTS] 与 README；
 * 之后即使把插件删光也不会自动补回默认源（删掉整个目录再启动才相当于重置）。
 * 所有读写都在同一把锁内完成，`plugins` / `errors` 通过 StateFlow 通知 UI。
 */
class SourcePluginStore(context: Context) {

    private val appContext = context.applicationContext
    private val lock = Any()
    private val directory: File = resolveDirectory(appContext)

    private val _plugins = MutableStateFlow<List<SourcePlugin>>(emptyList())
    val plugins: StateFlow<List<SourcePlugin>> = _plugins.asStateFlow()

    private val _errors = MutableStateFlow<List<String>>(emptyList())
    val errors: StateFlow<List<String>> = _errors.asStateFlow()

    /** 给 UI 展示的插件目录，便于用户去文件管理器手动添加。 */
    val directoryPath: String = directory.absolutePath

    init {
        runCatching { ensureSeeded() }
        reload()
    }

    // ── 读取 ────────────────────────────────────────────────────────────────

    /** 重新扫描目录；非法 JSON 只记入 [errors]，不影响其它插件。 */
    fun reload() {
        synchronized(lock) {
            val dir = directory
            val foundErrors = ArrayList<String>()
            val loaded = ArrayList<SourcePlugin>()

            val files: List<File> = runCatching {
                dir.listFiles { file -> file.isFile && file.name.endsWith(".json", ignoreCase = true) }
                    ?.sortedBy { it.name }
                    .orEmpty()
            }.getOrElse {
                foundErrors += "插件目录读取失败：${dir.name}"
                emptyList()
            }
            if (!dir.isDirectory) {
                foundErrors += "插件目录不可用：${dir.absolutePath}"
            }

            for (file in files) {
                val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
                if (text == null) {
                    foundErrors += "${file.name}：读取失败"
                    continue
                }
                SourcePlugin.parse(text)
                    .onSuccess { plugin ->
                        if (file.nameWithoutExtension != plugin.id) {
                            foundErrors += "${file.name}：文件名必须与 id 一致（${plugin.id}）"
                        } else {
                            loaded += plugin.copy(builtIn = plugin.id in DEFAULT_IDS)
                        }
                    }
                    .onFailure { t ->
                        foundErrors += "${file.name}：${t.message?.takeIf { it.isNotBlank() } ?: "格式不正确"}"
                    }
            }

            _errors.value = foundErrors
            _plugins.value = loaded.sortedWith(PLUGIN_ORDER)
        }
    }

    fun get(id: String): SourcePlugin? = _plugins.value.firstOrNull { it.id == id }

    // ── 写入 ────────────────────────────────────────────────────────────────

    /**
     * 保存一份插件 JSON。
     *
     * @param originalId 编辑已有插件时传原 id；重命名时旧文件会被删除。
     *                   为 null 表示新建，id 撞车直接失败。
     */
    fun saveJson(raw: String, originalId: String? = null): Result<SourcePlugin> =
        SourcePlugin.parse(raw).mapCatching { parsed ->
            synchronized(lock) {
                val dir = ensureDirectory()
                val original = originalId?.trim().orEmpty()
                if (original.isNotEmpty() && !SAFE_ID.matches(original)) {
                    throw IllegalArgumentException("原插件 id 无效：$original")
                }

                val target = File(dir, "${parsed.id}.json")
                if (target.exists() && (original.isEmpty() || original != parsed.id)) {
                    throw IllegalArgumentException("id 已存在")
                }

                writeAtomically(target, raw.removePrefix("\uFEFF").trim() + "\n")

                if (original.isNotEmpty() && original != parsed.id) {
                    runCatching { File(dir, "$original.json").delete() }
                }
                reload()
                parsed.copy(builtIn = parsed.id in DEFAULT_IDS)
            }
        }

    /** 改写文件里的 enabled 字段；文件不存在视为失败。 */
    fun setEnabled(id: String, enabled: Boolean): Result<Unit> = runCatching {
        synchronized(lock) {
            val plugin = get(id) ?: throw IllegalArgumentException("插件不存在：$id")
            val target = File(directory, "${plugin.id}.json")
            if (!target.isFile) throw IllegalArgumentException("插件文件不存在：${plugin.id}.json")
            if (plugin.enabled != enabled) {
                writeAtomically(target, plugin.copy(enabled = enabled).toJson() + "\n")
                reload()
            }
        }
    }

    /** 删除插件文件；文件不存在视为失败。 */
    fun delete(id: String): Result<Unit> = runCatching {
        synchronized(lock) {
            val plugin = get(id) ?: throw IllegalArgumentException("插件不存在：$id")
            val target = File(directory, "${plugin.id}.json")
            if (!target.isFile) throw IllegalArgumentException("插件文件不存在：${plugin.id}.json")
            if (!target.delete()) throw IllegalStateException("删除插件失败：${plugin.id}.json")
            reload()
        }
    }

    // ── 目录 / 首次写入 ─────────────────────────────────────────────────────

    private fun ensureSeeded() {
        val dir = directory
        if (!dir.isDirectory && !runCatching { dir.mkdirs() }.getOrDefault(false) && !dir.isDirectory) return

        val marker = File(dir, SEED_MARKER)
        val markerV2 = File(dir, SEED_V2_MARKER)
        val freshInstall = !marker.exists() && !markerV2.exists()
        if (freshInstall) {
            SourcePlugin.DEFAULTS.forEach { plugin ->
                val target = File(dir, "${plugin.id}.json")
                if (!target.exists()) {
                    runCatching { writeAtomically(target, plugin.toJson() + "\n") }
                }
            }
            runCatching { writeAtomically(marker, System.currentTimeMillis().toString()) }
        } else if (!markerV2.exists()) {
            // 老版本升级：移除不可直连的英文内置源，再补上新的中文源
            LEGACY_DEFAULT_IDS.forEach { id ->
                runCatching { File(dir, "$id.json").delete() }
            }
            SourcePlugin.DEFAULTS
                .filter { it.id in NEW_DEFAULT_IDS }
                .forEach { plugin ->
                    val target = File(dir, "${plugin.id}.json")
                    if (!target.exists()) {
                        runCatching { writeAtomically(target, plugin.toJson() + "\n") }
                    }
                }
            runCatching { writeAtomically(File(dir, README_FILE), readmeText(dir)) }
        }
        if (!markerV2.exists()) {
            runCatching { writeAtomically(markerV2, System.currentTimeMillis().toString()) }
        }

        // 用户改过或删掉 README 时不覆盖，只在缺失时补一份。
        val readme = File(dir, README_FILE)
        if (!readme.exists()) {
            runCatching { writeAtomically(readme, readmeText(dir)) }
        }
    }

    private fun readmeText(dir: File): String = buildString {
        appendLine("日览 · 来源插件目录")
        appendLine()
        appendLine("路径：${dir.absolutePath}")
        appendLine("规则：一个 .json 一个插件，文件名必须与 JSON 里的 id 一致（<id>.json）；删掉文件即删除插件。")
        appendLine("默认来源以国内可直连的中文站点为主：知乎热榜、百度热搜、IT之家、少数派、中国新闻网等。")
        appendLine("改完后回到应用刷新内容即可生效；也可以直接在应用内编辑或导入。")
        appendLine()
        appendLine(SourcePlugin.FORMAT_HELP.trim())
        appendLine()
        appendLine("JSON 接口的 mapping.items 支持点路径与数组下标，例如 data.list、items.0.list；")
        appendLine("留空表示响应根就是数组。")
    }

    private fun ensureDirectory(): File {
        val dir = directory
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IllegalStateException("无法创建插件目录：${dir.absolutePath}")
        }
        return dir
    }

    /** 先写 `<文件名>.tmp` 再 rename，避免写到一半的 JSON 让插件加载失败。 */
    private fun writeAtomically(file: File, text: String) {
        val parent = file.parentFile ?: throw IllegalStateException("插件目录不可用")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IllegalStateException("无法创建插件目录：${parent.absolutePath}")
        }
        val tmp = File(parent, "${file.name}.tmp")
        try {
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                if (file.exists() && !file.delete()) throw IllegalStateException("无法替换 ${file.name}")
                if (!tmp.renameTo(file)) throw IllegalStateException("无法保存 ${file.name}")
            }
        } catch (t: IllegalStateException) {
            throw t
        } catch (t: Throwable) {
            throw IllegalStateException("写入插件失败：${t.message ?: "未知错误"}")
        } finally {
            if (tmp.exists()) runCatching { tmp.delete() }
        }
    }
}

/** 优先外部可见目录，拿不到时退回应用私有目录。 */
private fun resolveDirectory(context: Context): File {
    val base = runCatching { context.getExternalFilesDir(null) }.getOrNull() ?: context.filesDir
    return File(File(base, "plugins"), "sources")
}
