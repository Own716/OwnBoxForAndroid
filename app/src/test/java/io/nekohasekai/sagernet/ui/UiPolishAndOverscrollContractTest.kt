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
        assertTrue(layoutSrc.contains("""android:paddingStart="3dp""""))
        assertTrue(layoutSrc.contains("""android:paddingEnd="3dp""""))
        assertTrue(layoutSrc.contains("""android:clipToPadding="true""""))
        assertFalse(layoutSrc.contains("""android:requiresFadingEdge="horizontal""""))
        assertTrue(layoutSrc.contains("TabCapsuleEdgeOverlayView"))
        assertTrue(layoutSrc.contains("group_tab_edge_overlay"))
        assertTrue(layoutSrc.contains("""app:tabIndicatorHeight="32dp""""))
        assertTrue(layoutSrc.contains("""app:tabMinWidth="0dp""""))

        val capsuleSrc = sourceFile("src/main/res/drawable/bg_tab_capsule.xml").readText()
        assertTrue(capsuleSrc.contains("""android:radius="16dp""""))
    }

    @Test
    fun tabCapsuleMetrics_maintainsConcentricGeometry() {
        val m100 = ConfigurationFragment.computeTabCapsuleMetrics(density = 3f, tabFactor = 1.0f)
        assertEquals(114, m100.outerHeightPx)
        assertEquals(9, m100.innerGapPx)
        assertEquals(96, m100.indicatorHeightPx)
        assertEquals(48f, m100.indicatorRadiusPx, 1e-4f)
        assertEquals(m100.innerGapPx.toFloat(), m100.outerHeightPx * 0.5f - m100.indicatorRadiusPx, 1e-4f)
        assertEquals(60, m100.fadingEdgePx)

        val mSmall = ConfigurationFragment.computeTabCapsuleMetrics(density = 3f, tabFactor = 0.7f)
        assertTrue(mSmall.outerHeightPx in 78..162)
        assertEquals(mSmall.innerGapPx.toFloat(), mSmall.outerHeightPx * 0.5f - mSmall.indicatorRadiusPx, 1e-4f)

        val mLarge = ConfigurationFragment.computeTabCapsuleMetrics(density = 3f, tabFactor = 1.3f)
        assertTrue(mLarge.outerHeightPx in 78..162)
        assertEquals(mLarge.innerGapPx.toFloat(), mLarge.outerHeightPx * 0.5f - mLarge.indicatorRadiusPx, 1e-4f)
    }

    @Test
    fun adaptiveScrollableTabPaddings_fillsExactWidthWhenFittingAndPreservesBaseWhenOverflowing() {
        val basePad = 42
        val innerMargin = 6
        val availableWidth = 1000

        // 3 fitting tabs
        val widths3 = listOf(80, 120, 160)
        val pads3 = ConfigurationFragment.computeAdaptiveScrollableTabPaddings(widths3, basePad, innerMargin, availableWidth)
        assertEquals(3, pads3.size)
        val total3 = widths3.indices.sumOf { widths3[it] + pads3[it].startPx + pads3[it].endPx } + innerMargin * 2 * (3 - 1)
        assertEquals(availableWidth, total3)
        assertTrue(pads3.all { it.startPx >= basePad && it.endPx >= basePad })

        // 4 fitting tabs (preserving natural text widths for long labels)
        val widths4 = listOf(72, 90, 210, 88)
        val pads4 = ConfigurationFragment.computeAdaptiveScrollableTabPaddings(widths4, basePad, innerMargin, availableWidth)
        assertEquals(4, pads4.size)
        val total4 = widths4.indices.sumOf { widths4[it] + pads4[it].startPx + pads4[it].endPx } + innerMargin * 2 * (4 - 1)
        assertEquals(availableWidth, total4)
        assertTrue(pads4.all { it.startPx >= basePad && it.endPx >= basePad })

        // 6 overflowing tabs -> keep compact basePaddingPx
        val widths6 = listOf(140, 150, 180, 160, 170, 190)
        val pads6 = ConfigurationFragment.computeAdaptiveScrollableTabPaddings(widths6, basePad, innerMargin, availableWidth)
        assertEquals(6, pads6.size)
        assertTrue(pads6.all { it.startPx == basePad && it.endPx == basePad })
    }

    @Test
    fun configurationFragment_supportsTwoTabFixedFillAndConcentricInnerClip() {
        val src = sourceFile("src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt").readText()
        assertTrue(
            "2-tab mode must use TabLayout.MODE_FIXED + GRAVITY_FILL",
            src.contains("TabLayout.MODE_FIXED") && src.contains("TabLayout.GRAVITY_FILL")
        )
        assertTrue(
            "TabLayout must clip strictly to outer capsule contour with horizontal fading edge disabled and top border redrawn",
            src.contains("outline.setRoundRect(0, 0, view.width, view.height, view.height * 0.5f)") &&
                src.contains("tabLayout.clipToOutline = true") &&
                src.contains("tabLayout.isHorizontalFadingEdgeEnabled = false") &&
                src.contains("groupTabEdgeOverlay?.updateStyle(") &&
                src.contains("computeFixedTabWeights(") &&
                src.contains("if (position == 0) return 0") &&
                src.contains("if (position == slidingTabIndicator.childCount - 1) return maxScrollX")
        )

        val helperSrc = sourceFile("src/main/java/io/nekohasekai/sagernet/ui/FloatingCapsuleHelper.kt").readText()
        assertTrue(
            "TabCapsuleEdgeOverlayView must render EVEN_ODD safety ring, clamped LinearGradient fade, and outer hairline stroke",
            helperSrc.contains("class TabCapsuleEdgeOverlayView") &&
                helperSrc.contains("Path.FillType.EVEN_ODD") &&
                helperSrc.contains("LinearGradient(") &&
                helperSrc.contains("Shader.TileMode.CLAMP") &&
                helperSrc.contains("canvas.drawRoundRect(strokeRect,")
        )
    }

    @Test
    fun fixedTabWeights_handlesEqualProportionalAndOverflowingGroups() {
        val basePad = 42
        val availableWidth = 1000

        // 2 tabs -> equal split
        assertEquals(listOf(1f, 1f), ConfigurationFragment.computeFixedTabWeights(listOf(90, 120), basePad, availableWidth))

        // 3 short fitting tabs -> equal 1f/1f/1f weights
        assertEquals(listOf(1f, 1f, 1f), ConfigurationFragment.computeFixedTabWeights(listOf(90, 80, 100), basePad, availableWidth))

        // 4 fitting tabs with 1 wider tab -> proportional non-null weights, wider tab gets higher weight
        val w4 = ConfigurationFragment.computeFixedTabWeights(listOf(80, 80, 260, 80), basePad, availableWidth)
        assertTrue("Fitting 4 tabs must use MODE_FIXED weights", w4 != null && w4.size == 4)
        assertTrue("All weights must be positive", w4!!.all { it > 0f })
        assertTrue("Wider tab must receive larger weight", w4[2] > w4[0] && w4[2] > w4[1] && w4[2] > w4[3])

        // 6 overflowing tabs -> null (falls back to MODE_SCROLLABLE)
        val w6 = ConfigurationFragment.computeFixedTabWeights(listOf(150, 150, 160, 170, 160, 180), basePad, availableWidth)
        assertEquals(null, w6)
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
