package app.rilan.daily.data

import app.rilan.daily.core.Category
import app.rilan.daily.core.NewsItem
import app.rilan.daily.core.NewsSection
import app.rilan.daily.core.stableId
import app.rilan.daily.plugins.PluginFetcher
import app.rilan.daily.plugins.SourcePlugin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// ─────────────────────────────────────────────────────────────────────────────
// 内部结构
// ─────────────────────────────────────────────────────────────────────────────

/** 条目 + 排序权重：来源插件用发布时间，热搜用热度。 */
private class Scored(val item: NewsItem, val score: Double)

/** 单个数据源的结果：要么有内容，要么有一句简短的中文错误。 */
private class Outcome(
    val category: Category,
    val items: List<Scored> = emptyList(),
    val error: String? = null,
)

/** 一个抓取任务；带上分类，兜底的错误也知道自己属于哪个板块。 */
private class FetchTask(val category: Category, val body: suspend () -> Outcome)

/** 数据源自身可识别的错误（例如接口返回了非 0 的 code）。 */
private class SourceError(message: String) : Exception(message)

// ─────────────────────────────────────────────────────────────────────────────
// 常量
// ─────────────────────────────────────────────────────────────────────────────

/** 同时最多 8 个请求在飞，热搜 + 来源插件一起算；减少慢源拖住整轮刷新。 */
private const val MAX_CONCURRENCY = 10

/** 热搜接口只返回榜单，8 秒读不到就没必要再等；单个源慢不该拖住整轮内容刷新。 */
private const val HOT_CONNECT_TIMEOUT_MS = 5_000
private const val HOT_READ_TIMEOUT_MS = 8_000

/**
 * 插件源（RSS / JSON）的复用窗口。
 *
 * 前台每 3 分钟会刷一次信息流，但热搜 3 分钟一变、RSS 通常 10 分钟以上才更新；
 * 插件在窗口内直接用上一轮成功结果，热搜照常每次都拉，界面依旧「刚刚更新」。
 */
private const val PLUGIN_CACHE_TTL_MS = 10 * 60_000L

private const val WEIBO_URL = "https://weibo.com/ajax/side/hotSearch"
private const val BILIBILI_URL = "https://api.bilibili.com/x/web-interface/popular?ps=20&pn=1"
private const val DOUYIN_URL = "https://www.iesdouyin.com/web/api/v2/hotsearch/billboard/word/"

/** 板块输出顺序。 */
private val SECTION_ORDER = listOf(
    Category.GENERAL,
    Category.AI,
    Category.ZHIHU,
    Category.WEIBO,
    Category.BILIBILI,
    Category.DOUYIN,
    Category.BAIDU,
    Category.SECURITY,
    Category.WORLD,
)

/** B站分区：这些分区本身就是娱乐内容，直接跳过。 */
private val BILI_ENTERTAINMENT_TNAMES = setOf("娱乐", "明星", "综艺", "影视", "电视剧", "电影")

private val WEIBO_HEADERS = mapOf(
    "Referer" to "https://weibo.com/",
    "Accept" to "application/json, text/plain, */*",
)
private val BILIBILI_HEADERS = mapOf(
    "Referer" to "https://www.bilibili.com/",
    "Accept" to "application/json, text/plain, */*",
)
private val DOUYIN_HEADERS = mapOf(
    "Referer" to "https://www.douyin.com/",
    "Accept" to "application/json, text/plain, */*",
)

/** 去重用的标题归一化：抹掉空白和标点符号。 */
private val TITLE_NOISE = Regex("[\\p{P}\\p{S}\\s\\u3000]+")

/**
 * 内容抓取：把启用的来源插件 + 微博 / B站 / 抖音热搜，
 * 按 [Category] 聚合成 [NewsSection]。
 *
 * 设计要点：
 * - 每个源独立执行、独立兜底，单个源失败只会变成该板块的 error 文案；
 * - 并发上限 8（最多 8 个 async 工作者共同消费任务队列），避免同时打十几个连接；
 * - 热搜不抓正文，只出榜单（后续 AI 分析只用标题）；
 * - 不打印任何响应内容，错误只保留一句中文描述。
 */
class ContentFetcher {

    private val pluginFetcher = PluginFetcher()

    /** 插件源最近一次成功结果；RSS 更新慢，没必要跟热搜一样每 3 分钟重新解析。 */
    private class CachedPlugin(val at: Long, val outcome: Outcome)

    private val pluginCache = ConcurrentHashMap<String, CachedPlugin>()

    /**
     * 抓取全部内容。永不抛网络/解析异常：失败信息放在 [NewsSection.error] 里。
     *
     * @param force 忽略插件级缓存，所有来源都重新请求（用户手动刷新 / 设置变更后）。
     *
     * 只返回有内容或有错误的板块，顺序固定为
     * GENERAL、AI、WEIBO、BILIBILI、DOUYIN、SECURITY、WORLD。
     */
    suspend fun fetchAll(
        plugins: List<SourcePlugin>,
        blockedWords: List<String> = emptyList(),
        includeWeibo: Boolean = true,
        includeBilibili: Boolean = true,
        includeDouyin: Boolean = true,
        force: Boolean = false,
    ): List<NewsSection> {
        val now = System.currentTimeMillis()
        val tasks = ArrayList<FetchTask>(plugins.size + 3)
        if (pluginCache.size > 128) pluginCache.clear()

        plugins
            .filter { it.enabled && it.url.isNotBlank() }
            .forEach { plugin ->
                // 插件 id + url 一起做键：用户改了地址就会自然失效。
                val key = "${plugin.id}|${plugin.url}"
                val cached = pluginCache[key]
                tasks += FetchTask(plugin.category) {
                    if (!force && cached != null && now - cached.at < PLUGIN_CACHE_TTL_MS) {
                        cached.outcome
                    } else {
                        loadPlugin(plugin, blockedWords).also { outcome ->
                            // 只缓存成功结果；失败下一轮重试，不把错误钉住 10 分钟。
                            if (outcome.error == null || outcome.items.isNotEmpty()) {
                                pluginCache[key] = CachedPlugin(System.currentTimeMillis(), outcome)
                            }
                        }
                    }
                }
            }

        // 三个内置热搜与来源插件一起排队，同样受并发上限约束；可在扩展页关掉
        if (includeWeibo) tasks += FetchTask(Category.WEIBO) { loadWeibo(blockedWords) }
        if (includeBilibili) tasks += FetchTask(Category.BILIBILI) { loadBilibili(blockedWords) }
        if (includeDouyin) tasks += FetchTask(Category.DOUYIN) { loadDouyin(blockedWords) }

        return buildSections(runLimited(tasks), now)
    }

    // ── 并发调度 ────────────────────────────────────────────────────────────

    /**
     * 固定工作者消费任务队列：任意时刻最多 [MAX_CONCURRENCY] 个协程在跑，
     * 且慢的源不会像 chunked 那样卡住同批其他源。
     */
    private suspend fun runLimited(tasks: List<FetchTask>): List<Outcome> = coroutineScope {
        val slots = arrayOfNulls<Outcome>(tasks.size)
        val cursor = AtomicInteger(0)
        val workers = minOf(MAX_CONCURRENCY, tasks.size)

        (0 until workers).map {
            async(Dispatchers.IO) {
                while (true) {
                    val index = cursor.getAndIncrement()
                    if (index >= tasks.size) break
                    val task = tasks[index]
                    slots[index] = try {
                        task.body()
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (t: Throwable) {
                        Outcome(task.category, error = shortError(t))
                    }
                }
            }
        }.awaitAll()

        slots.filterNotNull()
    }

    // ── 板块聚合 ────────────────────────────────────────────────────────────

    private fun buildSections(outcomes: List<Outcome>, now: Long): List<NewsSection> {
        val grouped = outcomes.groupBy { it.category }
        val sections = ArrayList<NewsSection>(SECTION_ORDER.size)

        for (category in SECTION_ORDER) {
            val group = grouped[category] ?: continue
            val items = merge(group, limitOf(category))
            val failures = group.mapNotNull { it.error }.distinct()
            if (items.isEmpty() && failures.isEmpty()) continue

            val message = when {
                failures.isEmpty() -> null
                items.isEmpty() -> brief(failures.joinToString("；"))
                else -> brief("部分来源加载失败：" + failures.joinToString("；"))
            }
            sections += NewsSection(
                category = category,
                items = items,
                updatedAt = now,
                error = message,
            )
        }
        return sections
    }

    /** 合并同板块的多个源：按权重排序 → 去重 → 截断 → 重排 rank。 */
    private fun merge(group: List<Outcome>, limit: Int): List<NewsItem> {
        val ordered = group
            .flatMap { it.items }
            .sortedWith(compareByDescending<Scored> { it.score }.thenByDescending { it.item.publishedAt })

        val seenIds = HashSet<String>(ordered.size * 2)
        val seenTitles = HashSet<String>(ordered.size * 2)
        val seenUrls = HashSet<String>(ordered.size * 2)
        val picked = ArrayList<NewsItem>(limit)

        for (scored in ordered) {
            if (picked.size >= limit) break
            val item = scored.item
            val key = titleKey(item.title)
            if (key.isEmpty()) continue
            if (!seenIds.add(item.id)) continue
            if (!seenTitles.add(key)) continue
            if (item.url.isNotEmpty() && !seenUrls.add(item.url)) continue
            picked += item.copy(rank = picked.size + 1)
        }
        return picked
    }

    /**
     * 每个板块最多保留多少条。
     *
     * 1.7 起信息流不再在界面上截断，这里就是最终可见条数；把上限提到
     * 「各来源一次抓回来的量级」，向下滑能看到当天全部抓到的内容。
     */
    private fun limitOf(category: Category): Int = when (category) {
        Category.GENERAL -> 40
        Category.SECURITY, Category.WORLD -> 20
        else -> 30
    }

    private fun titleKey(title: String): String =
        title.lowercase(Locale.ROOT).replace(TITLE_NOISE, "")

    // ── 来源插件 ────────────────────────────────────────────────────────────

    /** 插件内部已经过滤、排序、截断，这里只映射成合并用的 Scored。 */
    private suspend fun loadPlugin(plugin: SourcePlugin, blockedWords: List<String>): Outcome {
        val result = pluginFetcher.fetch(plugin, blockedWords)
        return Outcome(
            category = plugin.category,
            items = result.items.map { Scored(it, it.publishedAt.toDouble()) },
            error = result.error?.let { "${plugin.name}：$it" },
        )
    }

    // ── 微博热搜 ────────────────────────────────────────────────────────────

    private suspend fun loadWeibo(blockedWords: List<String>): Outcome = try {
        val body = Http.getText(
            WEIBO_URL,
            headers = WEIBO_HEADERS,
            connectTimeoutMs = HOT_CONNECT_TIMEOUT_MS,
            readTimeoutMs = HOT_READ_TIMEOUT_MS,
        )
        val root = JSONObject(body)
        val realtime = root.optJSONObject("data")?.optJSONArray("realtime")
            ?: throw SourceError("热搜数据格式异常")

        val now = System.currentTimeMillis()
        val picked = ArrayList<Scored>(realtime.length())
        for (i in 0 until realtime.length()) {
            val node = realtime.optJSONObject(i) ?: continue
            // 广告位（is_ad）不展示
            if (node.optBoolean("is_ad", false) || node.optInt("is_ad", 0) == 1) continue

            val word = RssParser.plainText(node.optString("word")).trim().trim('#')
            val scheme = RssParser.plainText(node.optString("word_scheme")).trim().trim('#')
            val title = word.ifBlank { scheme }
            if (title.isEmpty()) continue
            if (EntertainmentFilter.shouldBlock(title, blockedWords)) continue

            val heat = node.optDouble("num", 0.0).let { if (it.isFinite() && it > 0.0) it else 0.0 }
            picked += Scored(
                NewsItem(
                    id = stableId("weibo", title),
                    title = title,
                    url = "https://s.weibo.com/weibo?q=" + urlEncode("#$title#"),
                    source = "微博热搜",
                    category = Category.WEIBO,
                    publishedAt = now,
                    hotValue = if (heat > 0.0) heat.toLong().toString() else "",
                ),
                heat,
            )
        }
        if (picked.isEmpty()) Outcome(Category.WEIBO, error = "微博热搜暂时为空")
        else Outcome(Category.WEIBO, picked)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (t: Throwable) {
        Outcome(Category.WEIBO, error = shortError(t))
    }

    // ── B站热门 ─────────────────────────────────────────────────────────────

    private suspend fun loadBilibili(blockedWords: List<String>): Outcome = try {
        val body = Http.getText(
            BILIBILI_URL,
            headers = BILIBILI_HEADERS,
            connectTimeoutMs = HOT_CONNECT_TIMEOUT_MS,
            readTimeoutMs = HOT_READ_TIMEOUT_MS,
        )
        val root = JSONObject(body)
        val code = root.optInt("code", -1)
        if (code != 0) throw SourceError("B站接口返回 $code")

        val list = root.optJSONObject("data")?.optJSONArray("list")
            ?: throw SourceError("热门数据格式异常")

        val picked = ArrayList<Scored>(list.length())
        for (i in 0 until list.length()) {
            val node = list.optJSONObject(i) ?: continue
            // 娱乐分区整段跳过，剩下的再走通用的娱乐词过滤
            val tname = node.optString("tname").trim()
            if (tname in BILI_ENTERTAINMENT_TNAMES) continue

            val title = RssParser.plainText(node.optString("title"))
            if (title.isEmpty()) continue
            if (EntertainmentFilter.shouldBlock(title, blockedWords)) continue

            val bvid = node.optString("bvid").trim()
            val ownerJson = node.optJSONObject("owner")
            val owner = (if (ownerJson == null) "" else ownerJson.optString("name")).trim().ifBlank { "UP主" }
            val stat = node.optJSONObject("stat")
            val view = if (stat == null) 0L else stat.optLong("view", 0L)
            val seconds = node.optLong("pubdate", 0L)

            picked += Scored(
                NewsItem(
                    id = stableId("bilibili", bvid.ifBlank { title }),
                    title = title,
                    url = if (bvid.isEmpty()) "" else "https://www.bilibili.com/video/$bvid",
                    source = "B站 · $owner",
                    category = Category.BILIBILI,
                    publishedAt = if (seconds > 1_000_000_000L) seconds * 1000L else 0L,
                    hotValue = if (view > 0L) view.toString() else "",
                ),
                view.toDouble(),
            )
        }
        if (picked.isEmpty()) Outcome(Category.BILIBILI, error = "B站热门暂时为空")
        else Outcome(Category.BILIBILI, picked)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (t: Throwable) {
        Outcome(Category.BILIBILI, error = shortError(t))
    }

    // ── 抖音热搜 ────────────────────────────────────────────────────────────

    private suspend fun loadDouyin(blockedWords: List<String>): Outcome = try {
        val body = Http.getText(
            DOUYIN_URL,
            headers = DOUYIN_HEADERS,
            connectTimeoutMs = HOT_CONNECT_TIMEOUT_MS,
            readTimeoutMs = HOT_READ_TIMEOUT_MS,
        )
        val root = JSONObject(body)
        val list = root.optJSONArray("word_list")
            ?: root.optJSONObject("data")?.optJSONArray("word_list")
            ?: throw SourceError("热搜数据格式异常")

        val now = System.currentTimeMillis()
        val picked = ArrayList<Scored>(list.length())
        for (i in 0 until list.length()) {
            val node = list.optJSONObject(i) ?: continue
            val word = RssParser.plainText(node.optString("word")).trim()
            if (word.isEmpty()) continue
            if (EntertainmentFilter.shouldBlock(word, blockedWords)) continue

            val heat = node.optDouble("hot_value", 0.0).let { if (it.isFinite() && it > 0.0) it else 0.0 }
            picked += Scored(
                NewsItem(
                    id = stableId("douyin", word),
                    title = word,
                    url = "https://www.douyin.com/search/" + urlEncode(word),
                    source = "抖音热搜",
                    category = Category.DOUYIN,
                    publishedAt = now,
                    hotValue = if (heat > 0.0) heat.toLong().toString() else "",
                ),
                heat,
            )
        }
        if (picked.isEmpty()) Outcome(Category.DOUYIN, error = "抖音热搜暂时为空")
        else Outcome(Category.DOUYIN, picked)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (t: Throwable) {
        Outcome(Category.DOUYIN, error = shortError(t))
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    /** 只需要 query 里可用的编码，空格变 + 对微博/抖音的搜索结果都有效。 */
    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** 把异常翻译成一句中文，绝不把响应正文抛给上层。 */
    private fun shortError(t: Throwable): String = when (t) {
        is SourceError -> t.message ?: "加载失败"
        is HttpException -> t.message?.takeIf { it.isNotBlank() } ?: "网络请求失败"
        is SocketTimeoutException -> "请求超时"
        is IOException -> "网络连接失败"
        is JSONException -> "数据格式异常"
        else -> "加载失败"
    }

    /** 板块错误文案整体限长，避免首屏排版被撑坏。 */
    private fun brief(text: String): String =
        if (text.length <= 140) text else text.take(139).trimEnd() + "…"
}
