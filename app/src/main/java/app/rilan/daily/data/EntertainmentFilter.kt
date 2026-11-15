package app.rilan.daily.data

import app.rilan.daily.core.SOFT_ENTERTAINMENT_WORDS
import app.rilan.daily.core.STRICT_ENTERTAINMENT_WORDS
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * 热搜的娱乐向内容过滤。
 *
 * 只用在微博 / B站 / 抖音三个热搜分类上，RSS 新闻不走这里。判定顺序：
 *
 * 1. 命中任一 [STRICT_ENTERTAINMENT_WORDS] 里的严格词 → 过滤；
 * 2. 命中 2 个及以上 [SOFT_ENTERTAINMENT_WORDS] 里的软词 → 过滤；
 * 3. 命中用户自定义屏蔽词（大小写不敏感）→ 过滤；
 * 4. 例外：标题里有警方/法院/事故/地震……这类公共议题词，且没命中严格词 → 保留。
 *    热搜榜上「某地火灾」常常同时带「明星」「电影」等词，这条豁免避免把社会新闻误杀。
 *
 * 所有正则都在 object 初始化时编译一次；用户自定义词按列表缓存，避免逐条重新处理。
 */
object EntertainmentFilter {

    /** 忽略大小写，中文不受影响，`CP`/`cp` 这类才需要。 */
    private val FLAGS: Int = Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE

    /** 公共议题词：命中这些词说明是社会/政策类新闻，不该按娱乐处理。 */
    private const val PUBLIC_ISSUE_WORDS =
        "警方|公安|法院|检察院|事故|去世|遇难|失联|救援|政策|法律|判刑|判决|地震|火灾|" +
            "爆炸|坍塌|车祸|台风|洪水|暴雨|疫情|外交|国防|军演|制裁|关税|选举|立法|监管|处罚|通报|" +
            "失事|坠机|起火|汛情|重污染|立案|起诉|逮捕|拘留"

    private val strictWordList: List<String> = splitWords(STRICT_ENTERTAINMENT_WORDS)
    private val softWordList: List<String> = splitWords(SOFT_ENTERTAINMENT_WORDS)
    private val softPatterns: List<Pattern> = softWordList.map { literal(it) }

    /** 一次 find 就能判断「一个都没有」，省掉逐词匹配。 */
    private val strictAny: Pattern? = anyOf(strictWordList)
    private val softAny: Pattern? = anyOf(softWordList)

    private val publicIssue: Pattern = Pattern.compile(PUBLIC_ISSUE_WORDS)

    /** 自定义词按列表缓存（设置页改词后是新列表，命中不了旧缓存）。 */
    private val customCache = ConcurrentHashMap<List<String>, List<String>>()

    /**
     * 是否应把这条热搜当娱乐内容丢掉。
     *
     * @param title 热搜标题 / 视频标题
     * @param extraWords 用户在设置里自定义的屏蔽词
     */
    fun shouldBlock(title: String, extraWords: List<String> = emptyList()): Boolean {
        val text = title.trim()
        if (text.isEmpty()) return false

        // 严格词优先级最高，公共议题豁免也不能救
        if (strictAny?.matcher(text)?.find() == true) return true

        val customHit = hitCustomWord(text, extraWords)
        val softHits = countSoftHits(text)
        if (!customHit && softHits < 2) return false

        // 社会/政策类新闻放行
        if (publicIssue.matcher(text).find()) return false
        return true
    }

    /** 命中 2 个就够判定，提前返回减少匹配次数。 */
    private fun countSoftHits(text: String): Int {
        if (softAny?.matcher(text)?.find() != true) return 0
        var hits = 0
        for (pattern in softPatterns) {
            if (pattern.matcher(text).find()) {
                hits++
                if (hits >= 2) return hits
            }
        }
        return hits
    }

    private fun hitCustomWord(text: String, extraWords: List<String>): Boolean {
        if (extraWords.isEmpty()) return false
        val words = customWords(extraWords)
        if (words.isEmpty()) return false
        val lower = text.lowercase(Locale.ROOT)
        return words.any { lower.contains(it) }
    }

    private fun customWords(extraWords: List<String>): List<String> {
        customCache[extraWords]?.let { return it }
        val words = extraWords
            .mapNotNull { it.trim().takeIf { word -> word.isNotEmpty() }?.lowercase(Locale.ROOT) }
            .distinct()
        // 设置页频繁试词时不让缓存无限长
        if (customCache.size > 24) customCache.clear()
        customCache[extraWords] = words
        return words
    }

    private fun splitWords(raw: String): List<String> =
        raw.split('|')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.ROOT) }

    /** 词表是中文短语/普通词，全部按字面量处理，避免正则元字符意外生效。 */
    private fun literal(word: String): Pattern = Pattern.compile(Pattern.quote(word), FLAGS)

    private fun anyOf(words: List<String>): Pattern? {
        if (words.isEmpty()) return null
        val joined = words.joinToString("|") { Pattern.quote(it) }
        return Pattern.compile("(?:$joined)", FLAGS)
    }
}
