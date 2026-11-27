package io.nekohasekai.sagernet.ui.motion

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.doOnPreDraw
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ui.motion.SmoothMotion.animatePopupBloomIn
import io.nekohasekai.sagernet.ui.motion.SmoothMotion.attachSmoothPress

/**
 * Anchored smooth floating popup menu preserving OwnBox Method A appearance:
 * - Zero full-screen dark scrim, zero outer drop shadow
 * - Soft cloud surface + 1dp fine border (@drawable/bg_popup_menu)
 * - Pure text rows (no item icons), group dividers, red danger items
 * - In-place submenu morphing with smooth height & cross-slide transitions
 */
object SmoothMenuPopup {

    class Handle internal constructor(
        private val dismissAction: () -> Unit,
    ) {
        fun dismiss() = dismissAction()
    }

    private val HEADER_IDS = intArrayOf(
        R.id.action_header_order,
        R.id.action_header_layout,
        R.id.action_header_card_style,
        R.id.action_header_card_view_mode,
    )

    private data class MenuEntry(
        val item: MenuItem,
        val isHeader: Boolean,
    )

    private data class MenuLevel(
        val backTitle: CharSequence?,
        val menu: Menu,
    )

    fun showFromMenu(
        context: Context,
        anchor: View,
        menu: Menu,
        gravity: Int = Gravity.END,
        onItemSelected: ((MenuItem) -> Boolean)? = null,
        onDismiss: (() -> Unit)? = null,
    ): Handle? = showFromMenuInternal(context, anchor, menu, gravity, onItemSelected, onDismiss)

    private fun showFromMenuInternal(
        context: Context,
        anchor: View,
        menu: Menu,
        anchorGravity: Int,
        onItemSelected: ((MenuItem) -> Boolean)?,
        onDismiss: (() -> Unit)?,
    ): Handle? {
        if (!anchor.isAttachedToWindow) return null
        val entries = collectVisibleEntries(menu)
        if (entries.isEmpty()) return null

        val dm = context.resources.displayMetrics
        val density = dm.density
        val popupWidthPx = (224f * density).toInt().coerceAtMost((dm.widthPixels * 0.78f).toInt())
        val cornerRadiusPx = context.resources.getDimension(R.dimen.popup_corner_radius)
        val maxCardHeightPx = (dm.heightPixels * 0.62f).toInt()

        val primaryColor = context.getColorAttr(R.attr.colorPrimary)
        val textPrimary = context.getColorAttr(android.R.attr.textColorPrimary)
        val textSecondary = context.getColorAttr(android.R.attr.textColorSecondary)
        val strokeColor = context.getColorAttr(R.attr.popupMenuStrokeColor)
        val rippleColor = context.getColorAttr(R.attr.colorControlHighlight)
        val dividerColor = ColorUtils.setAlphaComponent(
            strokeColor,
            ((Color.alpha(strokeColor) * 0.65f).toInt()).coerceIn(28, 160),
        )

        val card = FrameLayout(context).apply {
            setBackgroundResource(R.drawable.bg_popup_menu)
            elevation = 0f
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
                }
            }
            clipToOutline = true
            clipChildren = true
            clipToPadding = true
        }

        val contentSlot = FrameLayout(context).apply {
            clipChildren = true
            clipToPadding = false
        }
        card.addView(
            contentSlot,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val popup = PopupWindow(
            card,
            popupWidthPx,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = 0f
            animationStyle = 0
        }

        var isDismissing = false
        var activeHeightAnim: ValueAnimator? = null
        val stack = ArrayList<MenuLevel>().apply {
            add(MenuLevel(backTitle = null, menu = menu))
        }

        var currentPivotXRatio = 0.88f
        var currentPivotYRatio = 0.06f

        fun dismissAnimated() {
            if (isDismissing) return
            isDismissing = true
            activeHeightAnim?.cancel()
            if (!SmoothMotion.isEnabled() || !card.isAttachedToWindow) {
                if (popup.isShowing) popup.dismiss()
                return
            }
            card.animate().cancel()
            card.animate()
                .alpha(0f)
                .scaleX(0.92f)
                .scaleY(0.92f)
                .setDuration(110L)
                .setInterpolator(SmoothMotion.smoothInInterpolator)
                .withEndAction {
                    if (popup.isShowing) popup.dismiss()
                }
                .start()
        }

        popup.setOnDismissListener {
            activeHeightAnim?.cancel()
            onDismiss?.invoke()
        }

        fun makeRowRipple(): RippleDrawable {
            val r = cornerRadiusPx * 0.45f
            val mask = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = r
                setColor(Color.WHITE)
            }
            return RippleDrawable(ColorStateList.valueOf(rippleColor), null, mask)
        }

        fun createDividerView(): View = View(context).apply {
            setBackgroundColor(dividerColor)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (0.75f * density).toInt().coerceAtLeast(1),
            ).apply {
                val marginH = (16f * density).toInt()
                val marginV = (3f * density).toInt()
                setMargins(marginH, marginV, marginH, marginV)
            }
        }

        fun createCheckBadge(item: MenuItem): View? {
            if (!item.isCheckable) return null
            val isCheckbox = item.itemId == R.id.action_hide_unavailable
            if (isCheckbox) {
                val boxSize = (18f * density).toInt()
                val box = TextView(context).apply {
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTypeface(null, Typeface.BOLD)
                    includeFontPadding = false
                    val bg = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 5f * density
                        if (item.isChecked) {
                            setColor(primaryColor)
                            setStroke((1f * density).toInt().coerceAtLeast(1), primaryColor)
                        } else {
                            setColor(Color.TRANSPARENT)
                            setStroke((1.4f * density).toInt().coerceAtLeast(1), textSecondary)
                        }
                    }
                    background = bg
                    if (item.isChecked) {
                        text = "✓"
                        val onPrimary = if (ColorUtils.calculateLuminance(primaryColor) > 0.55) Color.BLACK else Color.WHITE
                        setTextColor(onPrimary)
                    } else {
                        text = ""
                    }
                    layoutParams = LinearLayout.LayoutParams(boxSize, boxSize).apply {
                        marginStart = (10f * density).toInt()
                    }
                }
                return box
            }
            val checkView = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(primaryColor)
                text = if (item.isChecked) "✓" else ""
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    (20f * density).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = (8f * density).toInt()
                }
            }
            return checkView
        }

        lateinit var renderCurrentLevel: (slideDir: Int) -> Unit

        fun dispatchLeafClick(item: MenuItem) {
            if (isDismissing) return
            dismissAnimated()
            card.post {
                val handledByMenu = runCatching {
                    val m = item.javaClass.getMethod("invoke")
                    (m.invoke(item) as? Boolean) == true
                }.getOrDefault(false)
                if (!handledByMenu) {
                    onItemSelected?.invoke(item)
                }
            }
        }

        fun buildLevelView(level: MenuLevel): View {
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }

            if (level.backTitle != null) {
                val backRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val padH = (16f * density).toInt()
                    val padV = (9f * density).toInt()
                    setPadding(padH, padV, padH, padV)
                    minimumHeight = (38f * density).toInt()
                    background = makeRowRipple()
                    isClickable = true
                    isFocusable = true
                    attachSmoothPress(0.975f)
                    setOnClickListener {
                        if (stack.size > 1) {
                            stack.removeAt(stack.lastIndex)
                            renderCurrentLevel(-1)
                        }
                    }
                }
                val arrow = TextView(context).apply {
                    text = "‹"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    setTextColor(textSecondary)
                    includeFontPadding = false
                    setPadding(0, 0, (8f * density).toInt(), (1f * density).toInt())
                }
                val label = TextView(context).apply {
                    text = level.backTitle
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(textPrimary)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                backRow.addView(arrow)
                backRow.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                container.addView(backRow)
                container.addView(createDividerView())
            }

            val levelEntries = collectVisibleEntries(level.menu)
            var lastGroupId: Int? = null
            for (entry in levelEntries) {
                val item = entry.item
                val gid = item.groupId
                if (lastGroupId != null && lastGroupId != gid) {
                    container.addView(createDividerView())
                }
                lastGroupId = gid

                if (entry.isHeader) {
                    val headerTv = TextView(context).apply {
                        text = item.title
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(primaryColor)
                        val padH = (16f * density).toInt()
                        setPadding(padH, (8f * density).toInt(), padH, (4f * density).toInt())
                    }
                    container.addView(headerTv)
                    continue
                }

                val hasSub = item.hasSubMenu() && (item.subMenu?.size() ?: 0) > 0
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val padH = (16f * density).toInt()
                    val padV = (9f * density).toInt()
                    setPadding(padH, padV, padH, padV)
                    minimumHeight = (39f * density).toInt()
                    background = makeRowRipple()
                    isClickable = item.isEnabled
                    isFocusable = item.isEnabled
                    alpha = if (item.isEnabled) 1f else 0.45f
                    if (item.isEnabled) {
                        attachSmoothPress(0.975f)
                        setOnClickListener {
                            val sub = item.subMenu
                            if (hasSub && sub != null) {
                                stack.add(MenuLevel(backTitle = item.title, menu = sub))
                                renderCurrentLevel(1)
                            } else {
                                dispatchLeafClick(item)
                            }
                        }
                    }
                }

                val titleTv = TextView(context).apply {
                    text = item.title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(textPrimary)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }
                row.addView(titleTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                if (hasSub) {
                    val chevron = TextView(context).apply {
                        text = "▸"
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setTextColor(textSecondary)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            marginStart = (10f * density).toInt()
                        }
                    }
                    row.addView(chevron)
                } else {
                    createCheckBadge(item)?.let { row.addView(it) }
                }

                container.addView(row)
            }

            val scroll = ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(container)
            }
            return object : FrameLayout(context) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val cappedSpec = MeasureSpec.makeMeasureSpec(maxCardHeightPx, MeasureSpec.AT_MOST)
                    super.onMeasure(widthMeasureSpec, cappedSpec)
                }
            }.apply {
                addView(
                    scroll,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        }

        renderCurrentLevel = { slideDir ->
            val level = stack.last()
            val newContent = buildLevelView(level)
            val oldHeight = contentSlot.height

            activeHeightAnim?.cancel()
            contentSlot.removeAllViews()
            contentSlot.addView(
                newContent,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )

            if (slideDir != 0 && SmoothMotion.isEnabled() && oldHeight > 0) {
                val wSpec = View.MeasureSpec.makeMeasureSpec(
                    (card.width - card.paddingLeft - card.paddingRight).coerceAtLeast(popupWidthPx - (4f * density).toInt()),
                    View.MeasureSpec.EXACTLY,
                )
                val hSpec = View.MeasureSpec.makeMeasureSpec(maxCardHeightPx, View.MeasureSpec.AT_MOST)
                newContent.measure(wSpec, hSpec)
                val targetHeight = newContent.measuredHeight

                contentSlot.layoutParams = contentSlot.layoutParams.apply {
                    height = oldHeight
                }
                activeHeightAnim = ValueAnimator.ofInt(oldHeight, targetHeight).apply {
                    duration = 200L
                    interpolator = SmoothMotion.smoothOutInterpolator
                    addUpdateListener { anim ->
                        contentSlot.layoutParams = contentSlot.layoutParams.apply {
                            height = anim.animatedValue as Int
                        }
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            contentSlot.layoutParams = contentSlot.layoutParams.apply {
                                height = ViewGroup.LayoutParams.WRAP_CONTENT
                            }
                        }
                    })
                    start()
                }

                val shiftPx = slideDir * 20f * density
                newContent.translationX = shiftPx
                newContent.alpha = 0f
                newContent.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(170L)
                    .setInterpolator(SmoothMotion.smoothOutInterpolator)
                    .start()
            } else {
                contentSlot.layoutParams = contentSlot.layoutParams.apply {
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
            }
        }

        renderCurrentLevel(0)

        card.measure(
            View.MeasureSpec.makeMeasureSpec(popupWidthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxCardHeightPx, View.MeasureSpec.AT_MOST),
        )
        val measuredW = card.measuredWidth
        val measuredH = card.measuredHeight

        val anchorLoc = IntArray(2)
        anchor.getLocationOnScreen(anchorLoc)
        val marginH = (10f * density).toInt()
        val statusPad = (34f * density).toInt()
        val bottomPad = (20f * density).toInt()

        val preferRight = (anchorGravity and Gravity.END) == Gravity.END ||
            (anchorGravity and Gravity.RIGHT) == Gravity.RIGHT ||
            (anchorLoc[0] + anchor.width * 0.5f > dm.widthPixels * 0.5f)
        val rawX = if (preferRight) anchorLoc[0] + anchor.width - measuredW else anchorLoc[0]
        val clampedX = rawX.coerceIn(marginH, (dm.widthPixels - measuredW - marginH).coerceAtLeast(marginH))

        val preferTop = (anchorGravity and Gravity.TOP) == Gravity.TOP
        val spaceBelow = dm.heightPixels - bottomPad - (anchorLoc[1] + anchor.height)
        val spaceAbove = anchorLoc[1] - statusPad
        val openAbove = if (preferTop) spaceAbove >= measuredH * 0.6f || spaceAbove > spaceBelow
        else (spaceBelow < measuredH && spaceAbove > spaceBelow)

        val rawY = if (openAbove) {
            anchorLoc[1] - measuredH + (6f * density).toInt()
        } else {
            anchorLoc[1] + anchor.height + (4f * density).toInt()
        }
        val clampedY = rawY.coerceIn(statusPad, (dm.heightPixels - measuredH - bottomPad).coerceAtLeast(statusPad))

        currentPivotXRatio = if (preferRight) 0.90f else 0.10f
        currentPivotYRatio = if (openAbove) 0.94f else 0.06f

        card.doOnPreDraw {
            it.animatePopupBloomIn(currentPivotXRatio, currentPivotYRatio)
        }

        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, clampedX, clampedY)

        return Handle { dismissAnimated() }
    }

    private fun collectVisibleEntries(menu: Menu): List<MenuEntry> {
        val list = ArrayList<MenuEntry>(menu.size())
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            if (!item.isVisible) continue
            list.add(MenuEntry(item = item, isHeader = item.itemId in HEADER_IDS))
        }
        return list
    }
}
