package app.rilan.daily.plugins

import app.rilan.daily.core.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePluginTest {

    @Test
    fun parsesRssTemplate() {
        val plugin = SourcePlugin.parse(SourcePlugin.TEMPLATE_RSS).getOrThrow()
        assertEquals("my-rss", plugin.id)
        assertEquals(PluginFormat.RSS, plugin.format)
        assertEquals(Category.GENERAL, plugin.category)
        assertTrue(plugin.url.startsWith("https://"))
    }

    @Test
    fun parsesJsonTemplateMapping() {
        val plugin = SourcePlugin.parse(SourcePlugin.TEMPLATE_JSON).getOrThrow()
        assertEquals(PluginFormat.JSON, plugin.format)
        assertEquals("data.list", plugin.mapping.items)
        assertEquals("hot_value", plugin.mapping.hot)
        assertTrue(plugin.filterEntertainment)
    }

    @Test
    fun rejectsUnsafeId() {
        val bad = SourcePlugin.TEMPLATE_RSS.replace("\"my-rss\"", "\"../escape\"")
        assertTrue(SourcePlugin.parse(bad).isFailure)
    }

    @Test
    fun jsonAllowsEmptyItemsForRootArray() {
        val rootArray = """
            {"schema":1,"id":"x-json","name":"x","category":"GENERAL","type":"json","url":"https://example.com","mapping":{"title":"title"}}
        """.trimIndent()
        val plugin = SourcePlugin.parse(rootArray).getOrThrow()
        assertEquals("", plugin.mapping.items)
    }

    @Test
    fun defaultsContainChineseSources() {
        val ids = SourcePlugin.DEFAULTS.map { it.id }.toSet()
        assertTrue("zhihu-hot" in ids)
        assertTrue("baidu-hot" in ids)
        assertTrue("ithome" in ids)
        assertTrue("chinanews" in ids)
        assertTrue("4hou" in ids)
        assertTrue("bbc-zh" !in ids)
        assertTrue("hackernews" !in ids)
    }

    @Test
    fun zhihuHotMapsQuestionIdToWebUrl() {
        val plugin = SourcePlugin.DEFAULTS.first { it.id == "zhihu-hot" }
        assertEquals(PluginFormat.JSON, plugin.format)
        assertEquals(Category.ZHIHU, plugin.category)
        assertEquals("target.id", plugin.mapping.url)
        assertEquals("https://www.zhihu.com/question/", plugin.mapping.linkPrefix)
        assertEquals("data", plugin.mapping.items)
    }
}
