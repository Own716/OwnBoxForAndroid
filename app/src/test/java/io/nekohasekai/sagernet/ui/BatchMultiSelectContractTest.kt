package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BatchMultiSelectContractTest {

    private fun sourceFile(relative: String): File {
        return sequenceOf(File(relative), File("app", relative), File("../app", relative))
            .firstOrNull { it.exists() } ?: File(relative)
    }

    @Test
    fun menu_containsBatchActionsAndMultiSelectItem() {
        val menuContent = sourceFile("src/main/res/menu/add_profile_menu.xml").readText()
        assertTrue(
            "Menu must define action_batch_multiselect in overflow menu",
            menuContent.contains("action_batch_multiselect")
        )
        assertTrue(
            "Menu must define action_batch_select_all",
            menuContent.contains("action_batch_select_all")
        )
        assertTrue(
            "Menu must define action_batch_url_test",
            menuContent.contains("action_batch_url_test")
        )
        assertTrue(
            "Menu must define action_batch_speed_test",
            menuContent.contains("action_batch_speed_test")
        )
        assertTrue(
            "Menu must define action_batch_delete",
            menuContent.contains("action_batch_delete")
        )
    }

    @Test
    fun strings_containAllBatchTranslations() {
        val stringsEn = sourceFile("src/main/res/values/strings.xml").readText()
        val stringsZh = sourceFile("src/main/res/values-zh-rCN/strings.xml").readText()

        val requiredKeys = listOf(
            "action_batch_multiselect",
            "batch_selected_count",
            "select_all",
            "deselect_all",
            "invert_selection",
            "batch_delete_confirm_title",
            "batch_delete_confirm_message",
            "batch_delete_completed",
            "batch_skip_active_profile",
            "batch_no_selection_hint",
            "batch_speed_test_confirm_title",
            "batch_speed_test_confirm_message"
        )

        for (key in requiredKeys) {
            assertTrue("values/strings.xml must contain $key", stringsEn.contains("name=\"$key\""))
            assertTrue("values-zh-rCN/strings.xml must contain $key", stringsZh.contains("name=\"$key\""))
        }
    }

    @Test
    fun configurationFragment_implementsBatchMultiSelectContracts() {
        val content = sourceFile("src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt").readText()

        assertTrue(
            "ConfigurationFragment must declare isBatchMode",
            content.contains("var isBatchMode: Boolean")
        )
        assertTrue(
            "ConfigurationFragment must implement enterBatchMode",
            content.contains("fun enterBatchMode()")
        )
        assertTrue(
            "ConfigurationFragment must implement exitBatchMode",
            content.contains("fun exitBatchMode()")
        )
        assertTrue(
            "ConfigurationFragment must implement invertSelectionInBatchMode",
            content.contains("fun invertSelectionInBatchMode()")
        )
        assertTrue(
            "ConfigurationFragment must implement toggleSelectAllInBatchMode",
            content.contains("fun toggleSelectAllInBatchMode()")
        )
        assertTrue(
            "ConfigurationFragment must implement batchUrlTest",
            content.contains("fun batchUrlTest()")
        )
        assertTrue(
            "ConfigurationFragment must implement batchSpeedTest",
            content.contains("fun batchSpeedTest()")
        )
        assertTrue(
            "ConfigurationFragment must implement batchDelete",
            content.contains("fun batchDelete()")
        )
        assertTrue(
            "isSelectedProfile must consider isBatchMode",
            content.contains("multiSelect || isBatchMode")
        )
        assertTrue(
            "urlTest must support targetProfileIds",
            content.contains("fun urlTest(targetProfileIds: Set<Long>?")
        )
        assertTrue(
            "speedTest must support targetProfileIds",
            content.contains("fun speedTest(targetProfileIds: Set<Long>?")
        )
    }

    @Test
    fun configurationHolder_doesNotEnterBatchModeOnLongClickWhenNotInBatchMode() {
        val content = sourceFile("src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt").readText()

        // Verify that ConfigurationHolder's view.setOnLongClickListener does not call enterBatchMode
        val longClickSection = content
            .substringAfter("view.isLongClickable = true")
            .substringAfter("view.setOnLongClickListener")
            .substringBefore("private fun selectProfile")
        assertFalse(
            "Long clicking a card when not in batch mode must NOT enter batch mode",
            longClickSection.contains("enterBatchMode()")
        )
    }
}
