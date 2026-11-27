package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.ui.motion.SmoothMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UiPolishAndOverscrollContractTest {

    private fun sourceFile(relative: String): File {
        return sequenceOf(File(relative), File("app", relative), File("../app", relative))
            .firstOrNull { it.exists() } ?: File(relative)
    }

    @Test
    fun computeDampedOverscrollDp_isSymmetricMonotonicAndBounded() {
        assertEquals(0f, SmoothMotion.computeDampedOverscrollDp(0f, 54f), 1e-4f)
        assertEquals(0f, SmoothMotion.computeDampedOverscrollDp(100f, 0f), 1e-4f)

        val p1 = SmoothMotion.computeDampedOverscrollDp(24f, 54f)
        val p2 = SmoothMotion.computeDampedOverscrollDp(120f, 54f)
        val p3 = SmoothMotion.computeDampedOverscrollDp(260f, 54f)
        assertTrue(p1 > 0f)
        assertTrue(p2 > p1)
        assertTrue(p3 > p2)
        assertTrue(p3 < 54f)

        assertEquals(-p2, SmoothMotion.computeDampedOverscrollDp(-120f, 54f), 1e-4f)
    }

    @Test
    fun smoothMotionOverscroll_doesNotShiftRecyclerViewAndHandlesApi31PullDistance() {
        val src = sourceFile("src/main/java/io/nekohasekai/sagernet/ui/motion/SmoothMotion.kt").readText()
        assertTrue(
            "Overscroll EdgeEffect must implement getDistance()",
            src.contains("override fun getDistance(): Float")
        )
        assertTrue(
            "Overscroll EdgeEffect must implement onPullDistance()",
            src.contains("override fun onPullDistance(deltaDistance: Float, displacement: Float): Float")
        )
        assertFalse(
            "attachSmoothOverscroll must not assign RecyclerView.translationY directly",
            src.contains("translationY = sign * dampedDp * density")
        )
    }

    @Test
    fun preferenceDecoration_tracksChildTranslationYWithoutItemScalePress() {
        val src = sourceFile("src/main/java/moe/matsuri/nb4a/ui/TelegramGroupedPreferenceDecoration.kt").readText()
        assertTrue(
            "onDraw must track child translationY",
            src.contains("+ first.translationY") && src.contains("+ last.translationY")
        )
        assertTrue(
            "onDrawOver must track child translationY",
            src.contains("child.bottom.toFloat() + child.translationY")
        )
        assertFalse(
            "Settings preference rows should not scale on press",
            src.contains("view.attachSmoothPress(")
        )
    }

    @Test
    fun groupTabLayout_appliesHorizontalInsetAndFadingEdge() {
        val layoutSrc = sourceFile("src/main/res/layout/layout_group_list.xml").readText()
        assertTrue(layoutSrc.contains("""android:paddingStart="10dp""""))
        assertTrue(layoutSrc.contains("""android:paddingEnd="10dp""""))
        assertTrue(layoutSrc.contains("""android:clipToPadding="true""""))
        assertTrue(layoutSrc.contains("""android:requiresFadingEdge="horizontal""""))
    }

    @Test
    fun configurationFragment_restoresNonSubscriptionTopInfoCard() {
        val src = sourceFile("src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt").readText()
        assertTrue(
            "Non-subscription card binding must bind title and node count",
            src.contains("bindNonSubscriptionCard(root, card, currentGroup)") &&
                src.contains("bindNonSubscriptionCard(root, card, proxyGroup)")
        )
        assertTrue(
            "ConfigurationAdapter must reset smooth press state on recycle/detach",
            src.contains("onViewDetachedFromWindow(holder: ConfigurationHolder)") &&
                src.contains("resetSmoothPressState()")
        )
    }
}
