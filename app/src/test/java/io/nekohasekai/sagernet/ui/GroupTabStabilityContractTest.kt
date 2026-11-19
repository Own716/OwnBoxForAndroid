package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GroupTabStabilityContractTest {

    private fun sourceFile(relative: String): File {
        return sequenceOf(File(relative), File("app", relative), File("../app", relative))
            .firstOrNull { it.exists() } ?: File(relative)
    }

    @Test
    fun rawUpdater_doesNotCallDuplicatePostUpdateBeforeFinish() {
        val content = sourceFile("src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt").readText()
        // finishUpdate already dispatches postUpdate(proxyGroup).
        // Ensure no direct GroupManager.postUpdate exists right above finishUpdate.
        assertFalse(
            "RawUpdater should not post duplicate GroupManager.postUpdate before finishUpdate",
            content.contains("GroupManager.postUpdate(proxyGroup)\n        finishUpdate(proxyGroup)")
        )
    }

    @Test
    fun configurationFragment_blocksDescendantFocusOnTabLayout() {
        val content = sourceFile("src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt").readText()
        assertTrue(
            "TabLayout must block descendant focus to prevent scrolling to last tab on button state changes",
            content.contains("tabLayout.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS")
        )
        assertTrue(
            "Group tab views must not take focus in touch mode",
            content.contains("tabView.isFocusable = false")
        )
    }

    @Test
    fun configurationFragment_skipsNotifyDataSetChangedWhenStructureUnchanged() {
        val content = sourceFile("src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt").readText()
        assertTrue(
            "GroupPagerAdapter should detect sameStructure and skip notifyDataSetChanged()",
            content.contains("sameStructure && sameNames")
        )
        assertTrue(
            "GroupPagerAdapter should update tab titles in-place if only names change",
            content.contains("sameStructure")
        )
    }

    @Test
    fun configurationFragment_implementsSchemeACentering() {
        val content = sourceFile("src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt").readText()
        assertTrue(
            "ConfigurationFragment should implement updateTabBarCenteringPadding for Scheme A",
            content.contains("fun updateTabBarCenteringPadding")
        )
        assertTrue(
            "ConfigurationFragment should implement computeCenteredTabScrollX",
            content.contains("fun computeCenteredTabScrollX")
        )
        assertTrue(
            "ConfigurationFragment should implement centerSelectedTab",
            content.contains("fun centerSelectedTab")
        )
    }

    @Test
    fun floatingCapsuleHelper_cachesDrawablesViaOffscreenBitmap() {
        val content = sourceFile("src/main/java/io/nekohasekai/sagernet/ui/FloatingCapsuleHelper.kt").readText()
        assertTrue(
            "FloatingCapsuleHelper must contain CapsuleBitmapCache",
            content.contains("class CapsuleBitmapCache")
        )
        assertTrue(
            "SingleFloatingCapsuleDrawable must support updateStyle",
            content.contains("fun updateStyle(")
        )
        assertTrue(
            "DualFloatingCapsulesDrawable must support updateStyle",
            content.contains("fun updateStyle(")
        )
    }
}
