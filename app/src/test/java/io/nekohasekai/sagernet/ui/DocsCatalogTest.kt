package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocsCatalogTest {

    private fun containsEmojiOrSymbolPictograph(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val charCount = Character.charCount(cp)
            if (cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp == 0xFE0F) {
                return true
            }
            i += charCount
        }
        return false
    }

    private fun allTextBlobs(): List<String> {
        val list = ArrayList<String>()
        DocsCatalog.categories.forEach { cat ->
            list.add(cat.chipLabel)
            list.add(cat.headerTitle)
            list.add(cat.headerDesc)
        }
        DocsCatalog.entries.forEach { e ->
            list.add(e.title)
            list.add(e.badge)
            list.add(e.summary)
            list.add(e.desc)
            list.add(e.notes)
            list.add(e.recommendation)
            list.add(e.keywords)
        }
        return list
    }

    @Test
    fun catalogStructureAndCoverageAreValid() {
        val categoryIds = DocsCatalog.categories.map { it.id }
        assertTrue(categoryIds.isNotEmpty())
        assertEquals(categoryIds.size, categoryIds.distinct().size)
        assertFalse(categoryIds.contains(DocsCatalog.CATEGORY_ALL))

        val entryIds = DocsCatalog.entries.map { it.id }
        assertTrue(entryIds.size >= 40)
        assertEquals(entryIds.size, entryIds.distinct().size)

        DocsCatalog.entries.forEach { entry ->
            assertTrue("Unknown categoryId ${entry.categoryId}", entry.categoryId in categoryIds)
            assertTrue("Empty title for ${entry.id}", entry.title.isNotBlank())
            assertTrue("Empty badge for ${entry.id}", entry.badge.isNotBlank())
            assertTrue("Empty summary for ${entry.id}", entry.summary.isNotBlank())
            assertTrue("Empty desc for ${entry.id}", entry.desc.isNotBlank())
            assertTrue("Empty notes for ${entry.id}", entry.notes.isNotBlank())
            assertTrue("Empty recommendation for ${entry.id}", entry.recommendation.isNotBlank())
            assertTrue("Empty keywords for ${entry.id}", entry.keywords.isNotBlank())
        }

        DocsCatalog.categories.forEach { cat ->
            assertTrue(
                "Category ${cat.id} should contain at least one entry",
                DocsCatalog.entries.any { it.categoryId == cat.id },
            )
        }
    }

    @Test
    fun catalogHasNoEmojisRawTitleKeysOrStaleLegacyContent() {
        val rawTitleKeyPattern = Regex("""\([a-z][A-Za-z0-9_]*\)""")
        DocsCatalog.entries.forEach { entry ->
            assertFalse(
                "Title '${entry.title}' must not expose raw variable names",
                rawTitleKeyPattern.containsMatchIn(entry.title),
            )
        }

        val forbiddenLegacyStrings = listOf(
            "2.9.10",
            "least-load",
            "consistent-hash",
            "round-robin",
            "最大纳入节点数",
            "rank_top_n",
            "standby_size",
        )
        allTextBlobs().forEach { text ->
            assertFalse("Emoji found in docs text: $text", containsEmojiOrSymbolPictograph(text))
            forbiddenLegacyStrings.forEach { legacy ->
                assertFalse(
                    "Stale legacy term '$legacy' found in docs text: $text",
                    text.contains(legacy, ignoreCase = true),
                )
            }
        }
    }

    @Test
    fun catalogReflectsLatestV261124FeaturesAndDefaults() {
        val joined = allTextBlobs().joinToString("\n")
        assertTrue("Expected default MTU 1360", joined.contains("1360"))
        assertTrue("Expected default balancer tolerance 30 ms", joined.contains("30 ms"))
        assertTrue("Expected default speed test simple download + 8000ms", joined.contains("简单下载") && joined.contains("8000"))
        assertTrue("Expected fallback strategy coverage", joined.contains("故障转移"))
        assertTrue("Expected connections monitor coverage", joined.contains("分流连接"))
        assertTrue("Expected HWID subscription toggle coverage", joined.contains("HWID"))
        assertTrue("Expected DNS quick presets coverage", joined.contains("快速预设"))
        assertTrue("Expected smart auto-stop without location permission", joined.contains("无需定位权限"))
        assertTrue("Expected AnyTLS mTLS coverage", joined.contains("AnyTLS") && joined.contains("mTLS"))
        assertTrue("Expected IPv6 dual-stack fallback coverage", joined.contains("回退至 IPv4"))
        assertFalse("resolveDestination must not be recommended ON with FakeDNS", joined.contains("「解析目标地址」建议开启"))
        assertTrue("Expected batch multi-select coverage", joined.contains("反选") && joined.contains("速度测试本组"))
        assertTrue("Expected compact vs detailed card speed rule", joined.contains("互斥替换") && joined.contains("双行"))
        assertTrue("Expected live update island coverage", joined.contains("实况通知") && joined.contains("灵动胶囊"))
        assertTrue("Expected smart auto-stop coverage", joined.contains("智能启停"))
        assertTrue("Expected QUIC blocking coverage", joined.contains("阻断 QUIC"))
        assertTrue("Expected Wi-Fi vs cellular FAQ coverage", joined.contains("连 Wi-Fi 时部分机场更新订阅"))
    }

    @Test
    fun filterSupportsCategoryMultiTokenAndInternalKeySearch() {
        val allItems = DocsCatalog.filter(DocsCatalog.CATEGORY_ALL, "")
        val headers = allItems.filterIsInstance<DocListItem.Header>()
        val entries = allItems.filterIsInstance<DocListItem.Entry>()
        assertEquals(DocsCatalog.categories.size, headers.size)
        assertEquals(DocsCatalog.entries.size, entries.size)

        val strategyOnly = DocsCatalog.filter("strategy_group", "")
        assertEquals(1, strategyOnly.filterIsInstance<DocListItem.Header>().size)
        assertTrue(strategyOnly.filterIsInstance<DocListItem.Entry>().all { it.entry.categoryId == "strategy_group" })

        val wifiHits = DocsCatalog.filter(DocsCatalog.CATEGORY_ALL, "wifi 订阅")
            .filterIsInstance<DocListItem.Entry>()
            .map { it.entry.id }
        assertTrue(wifiHits.contains("faq_wifi_failed_cellular_ok"))

        val toleranceHits = DocsCatalog.filter(DocsCatalog.CATEGORY_ALL, "策略组 容差")
            .filterIsInstance<DocListItem.Entry>()
            .map { it.entry.id }
        assertTrue(toleranceHits.contains("strategy_tolerance_and_interrupt"))
        assertTrue(toleranceHits.contains("faq_balancer_interval_tolerance"))

        val keyHits = DocsCatalog.filter(DocsCatalog.CATEGORY_ALL, "showDirectSpeed")
            .filterIsInstance<DocListItem.Entry>()
            .map { it.entry.id }
        assertEquals(listOf("ui_notification_speed_and_group"), keyHits)

        val empty = DocsCatalog.filter(DocsCatalog.CATEGORY_ALL, "nonexistent_token_xyz_12345")
        assertTrue(empty.isEmpty())
    }
}
