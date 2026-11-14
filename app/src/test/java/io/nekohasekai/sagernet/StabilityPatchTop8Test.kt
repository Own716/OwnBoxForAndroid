package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.ui.WebdavBackupPaths
import io.nekohasekai.sagernet.ui.reorderRuleItems
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StabilityPatchTop8Test {

    private fun sourceFile(relative: String): File {
        return sequenceOf(File(relative), File("app", relative), File("../app", relative))
            .firstOrNull { it.exists() } ?: File(relative)
    }

    @Test
    fun reorderRuleItems_movesUpAndDownPreservingMonotonicUserOrder() {
        val a = RuleEntity(id = 1L, name = "A", userOrder = 10L)
        val b = RuleEntity(id = 2L, name = "B", userOrder = 20L)
        val c = RuleEntity(id = 3L, name = "C", userOrder = 30L)
        val d = RuleEntity(id = 4L, name = "D", userOrder = 40L)
        val list = mutableListOf(a, b, c, d)

        // Move adapter pos 1 (A) down to pos 3 (after C) -> [B, C, A, D] with orders [10, 20, 30, 40]
        val downChanged = reorderRuleItems(list, from = 1, to = 3)
        assertEquals(listOf(1L, 2L, 3L, 4L).let { listOf(2L, 3L, 1L, 4L) }, list.map { it.id })
        assertEquals(listOf(10L, 20L, 30L, 40L), list.map { it.userOrder })
        assertEquals(3, downChanged.size)

        // Move adapter pos 3 (A) back up to pos 1 -> [A, B, C, D] with orders [10, 20, 30, 40]
        val upChanged = reorderRuleItems(list, from = 3, to = 1)
        assertEquals(listOf(1L, 2L, 3L, 4L), list.map { it.id })
        assertEquals(listOf(10L, 20L, 30L, 40L), list.map { it.userOrder })
        assertEquals(3, upChanged.size)

        // Adjacent upward move: pos 4 (D) -> pos 3 -> [A, B, D, C]
        reorderRuleItems(list, from = 4, to = 3)
        assertEquals(listOf(1L, 2L, 4L, 3L), list.map { it.id })
        assertEquals(listOf(10L, 20L, 30L, 40L), list.map { it.userOrder })

        // No-op & header/out-of-bounds guards
        assertTrue(reorderRuleItems(list, from = 2, to = 2).isEmpty())
        assertTrue(reorderRuleItems(list, from = 0, to = 2).isEmpty())
        assertTrue(reorderRuleItems(list, from = 5, to = 1).isEmpty())
    }

    @Test
    fun webdavBackupPaths_unifiesOwnBoxDefaultAndFallsBackToLegacyThrone() {
        assertEquals("OwnBox", WebdavBackupPaths.normalizeBackupPath(null))
        assertEquals("OwnBox", WebdavBackupPaths.normalizeBackupPath(""))
        assertEquals("OwnBox", WebdavBackupPaths.normalizeBackupPath("///"))
        assertEquals("custom/sub", WebdavBackupPaths.normalizeBackupPath("/custom/sub/"))

        assertEquals(listOf("OwnBox", "Throne"), WebdavBackupPaths.restoreCandidates(null))
        assertEquals(listOf("OwnBox", "Throne"), WebdavBackupPaths.restoreCandidates("OwnBox"))
        assertEquals(listOf("OwnBox", "Throne"), WebdavBackupPaths.restoreCandidates("/OwnBox/"))
        assertEquals(listOf("custom/sub"), WebdavBackupPaths.restoreCandidates("/custom/sub/"))
        assertEquals(listOf("Throne"), WebdavBackupPaths.restoreCandidates("Throne"))

        assertEquals(
            "https://dav.example.com/root/OwnBox",
            WebdavBackupPaths.buildDirectoryUrl("https://dav.example.com/root".toHttpUrl(), "OwnBox").toString()
        )
    }

    @Test
    fun serviceReloadContract_separatesProfileSwitchFromConfigReload() {
        val baseService = sourceFile("src/main/java/io/nekohasekai/sagernet/bg/BaseService.kt").readText()
        assertTrue(baseService.contains("fun reload(allowSelector: Boolean = false)"))
        assertTrue(baseService.contains("if (allowSelector && canReloadSelector())"))
        assertTrue(baseService.contains("reload(allowSelector = true)"))

        val nodeSwitchFiles = listOf(
            "src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt",
            "src/main/java/io/nekohasekai/sagernet/ui/SwitchActivity.kt",
            "src/main/java/io/nekohasekai/sagernet/ui/NodeSelectDialogActivity.kt",
            "src/main/java/io/nekohasekai/sagernet/QuickToggleShortcut.kt"
        )
        for (rel in nodeSwitchFiles) {
            val content = sourceFile(rel).readText()
            assertTrue("$rel should call SagerNet.switchProfileService", content.contains("SagerNet.switchProfileService("))
            assertFalse("$rel should not call SagerNet.reloadService", content.contains("SagerNet.reloadService()"))
        }

        val dataStore = sourceFile("src/main/java/io/nekohasekai/sagernet/database/DataStore.kt").readText()
        assertTrue(dataStore.contains("configurationStore.stringToInt(Key.SPEED_INTERVAL) { 1000 }"))
        assertTrue(dataStore.contains("""configurationStore.getString("webdavPath") ?: "OwnBox""""))
    }
}
