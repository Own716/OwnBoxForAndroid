package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SmoothMenuPopupContractTest {

    private fun sourceFile(relative: String): File {
        return sequenceOf(File(relative), File("app", relative), File("../app", relative))
            .firstOrNull { it.exists() } ?: File(relative)
    }

    @Test
    fun smoothMenuPopup_usesMainLooperWithoutInvokeReflection() {
        val content = sourceFile("src/main/java/io/nekohasekai/sagernet/ui/motion/SmoothMenuPopup.kt").readText()

        assertFalse(
            "SmoothMenuPopup must not reflectively call MenuItemImpl.invoke()",
            content.contains("getMethod(\"invoke\")")
        )
        assertFalse(
            "SmoothMenuPopup must not use card.post after popup.dismiss()",
            content.contains("card.post")
        )
        assertTrue(
            "SmoothMenuPopup must dispatch callbacks via Looper.getMainLooper()",
            content.contains("Looper.getMainLooper()")
        )
        assertTrue(
            "SmoothMenuPopup must dispatch level menu actions via Menu.performIdentifierAction",
            content.contains("performIdentifierAction(")
        )
    }

    @Test
    fun menuScrimHelper_doesNotReflectPopupMenuFields() {
        val content = sourceFile("src/main/java/moe/matsuri/nb4a/ui/MenuScrimHelper.kt").readText()

        assertFalse(
            "MenuScrimHelper must not reflect PopupMenu.mMenuItemClickListener",
            content.contains("\"mMenuItemClickListener\"")
        )
        assertFalse(
            "MenuScrimHelper must not reflect PopupMenu.mAnchor",
            content.contains("\"mAnchor\"")
        )
    }

    @Test
    fun popupCallSites_passExplicitAnchorAndListener() {
        val configContent = sourceFile("src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt").readText()
        val groupContent = sourceFile("src/main/java/io/nekohasekai/sagernet/ui/GroupFragment.kt").readText()

        val configCalls = configContent.split("showPopupMenuWithScrim(").drop(1)
        assertTrue("ConfigurationFragment must have at least 2 showPopupMenuWithScrim call sites", configCalls.size >= 2)
        for (snippet in configCalls) {
            val args = snippet.substringBefore(")")
            assertTrue("ConfigurationFragment call site must pass anchor =", args.contains("anchor ="))
            assertTrue("ConfigurationFragment call site must pass onItemSelected =", args.contains("onItemSelected ="))
        }

        val groupCalls = groupContent.split("showPopupMenuWithScrim(").drop(1)
        assertTrue("GroupFragment must have at least 1 showPopupMenuWithScrim call site", groupCalls.isNotEmpty())
        for (snippet in groupCalls) {
            val args = snippet.substringBefore(")")
            assertTrue("GroupFragment call site must pass anchor =", args.contains("anchor ="))
            assertTrue("GroupFragment call site must pass onItemSelected =", args.contains("onItemSelected ="))
        }

        val onMenuItemBlock = configContent
            .substringAfter("override fun onMenuItemClick(item: MenuItem): Boolean")
            .substringBefore("fun confirmSpeedTest")
        assertTrue(
            "ConfigurationFragment.onMenuItemClick must return false only in else branch and return true otherwise",
            onMenuItemBlock.contains("else -> return false") && onMenuItemBlock.contains("return true")
        )
    }
}
