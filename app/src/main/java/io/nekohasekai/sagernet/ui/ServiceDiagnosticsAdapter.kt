package io.nekohasekai.sagernet.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.utils.ServiceCategory
import io.nekohasekai.sagernet.utils.ServiceStatusItem
import io.nekohasekai.sagernet.utils.ServiceTestState
import io.nekohasekai.sagernet.utils.Theme

class ServiceDiagnosticsAdapter(
    private val allItems: MutableList<ServiceStatusItem>,
    private val onStatsChanged: ((unlocked: Int, blocked: Int, other: Int) -> Unit)? = null,
    private val onItemClick: (ServiceStatusItem) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1

        private const val COLOR_GREEN = 0xFF10B981.toInt()
        private const val COLOR_RED = 0xFFEF4444.toInt()
        private const val COLOR_ORANGE = 0xFFF59E0B.toInt()
        private const val COLOR_BLUE = 0xFF3B82F6.toInt()
        private const val COLOR_GRAY = 0xFF9CA3AF.toInt()
    }

    sealed class DisplayEntry {
        data class Header(
            val title: String,
            val count: Int,
            val iconRes: Int,
            val color: Int
        ) : DisplayEntry()

        data class Item(val service: ServiceStatusItem) : DisplayEntry()
    }

    private var currentCategory: ServiceCategory = ServiceCategory.ALL
    private val displayEntries = mutableListOf<DisplayEntry>()

    init {
        rebuildDisplayEntries()
    }

    fun getCurrentCategoryItems(): List<ServiceStatusItem> {
        return if (currentCategory == ServiceCategory.ALL) {
            allItems.toList()
        } else {
            allItems.filter { it.category == currentCategory }
        }
    }

    fun filterByCategory(category: ServiceCategory) {
        currentCategory = category
        rebuildDisplayEntries()
        notifyDataSetChanged()
    }

    fun updateItem(item: ServiceStatusItem) {
        rebuildDisplayEntries()
        notifyDataSetChanged()
    }

    fun updateAll() {
        rebuildDisplayEntries()
        notifyDataSetChanged()
    }

    private fun rebuildDisplayEntries() {
        val filtered = getCurrentCategoryItems()

        val unlockedList = mutableListOf<ServiceStatusItem>()
        val blockedList = mutableListOf<ServiceStatusItem>()
        val otherList = mutableListOf<ServiceStatusItem>()

        for (item in filtered) {
            when (item.state) {
                ServiceTestState.AVAILABLE -> unlockedList.add(item)
                ServiceTestState.UNAVAILABLE -> blockedList.add(item)
                else -> otherList.add(item)
            }
        }

        // Notify top dashboard stats based on allItems
        val allUnlocked = allItems.count { it.state == ServiceTestState.AVAILABLE }
        val allBlocked = allItems.count { it.state == ServiceTestState.UNAVAILABLE }
        val allOther = allItems.size - allUnlocked - allBlocked
        onStatsChanged?.invoke(allUnlocked, allBlocked, allOther)

        displayEntries.clear()

        // 1. Unlocked Group (✓ 已完成)
        if (unlockedList.isNotEmpty()) {
            displayEntries.add(
                DisplayEntry.Header(
                    title = "已完成 (${unlockedList.size})",
                    count = unlockedList.size,
                    iconRes = R.drawable.ic_baseline_check_circle_24,
                    color = COLOR_GREEN
                )
            )
            for (item in unlockedList) {
                displayEntries.add(DisplayEntry.Item(item))
            }
        }

        // 2. Blocked Group (✗ 未解锁)
        if (blockedList.isNotEmpty()) {
            displayEntries.add(
                DisplayEntry.Header(
                    title = "未解锁 (${blockedList.size})",
                    count = blockedList.size,
                    iconRes = R.drawable.ic_baseline_cancel_24,
                    color = COLOR_RED
                )
            )
            for (item in blockedList) {
                displayEntries.add(DisplayEntry.Item(item))
            }
        }

        // 3. Other Group (待检测 / 其他)
        if (otherList.isNotEmpty()) {
            val title = if (otherList.all { it.state == ServiceTestState.IDLE }) {
                "待检测 (${otherList.size})"
            } else {
                "待检测 / 其他 (${otherList.size})"
            }
            displayEntries.add(
                DisplayEntry.Header(
                    title = title,
                    count = otherList.size,
                    iconRes = R.drawable.ic_baseline_info_24,
                    color = COLOR_ORANGE
                )
            )
            for (item in otherList) {
                displayEntries.add(DisplayEntry.Item(item))
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (displayEntries[position]) {
            is DisplayEntry.Header -> TYPE_HEADER
            is DisplayEntry.Item -> TYPE_ITEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            val view = inflater.inflate(R.layout.item_diagnostics_section_header, parent, false)
            HeaderViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_service_diagnostics, parent, false)
            ItemViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val entry = displayEntries[position]) {
            is DisplayEntry.Header -> (holder as HeaderViewHolder).bind(entry)
            is DisplayEntry.Item -> (holder as ItemViewHolder).bind(entry.service)
        }
    }

    override fun getItemCount(): Int = displayEntries.size

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivIcon = itemView.findViewById<ImageView>(R.id.iv_section_icon)
        private val tvTitle = itemView.findViewById<TextView>(R.id.tv_section_title)

        fun bind(header: DisplayEntry.Header) {
            ivIcon.setImageResource(header.iconRes)
            ivIcon.setColorFilter(header.color)
            tvTitle.text = header.title
            tvTitle.setTextColor(header.color)
        }
    }

    inner class ItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val rootCard = itemView.findViewById<MaterialCardView>(R.id.card_service_root)
        private val viewIconBg = itemView.findViewById<View>(R.id.view_icon_bg)
        private val icon = itemView.findViewById<ImageView>(R.id.service_icon)
        private val tvName = itemView.findViewById<TextView>(R.id.tv_service_name)
        private val tvSub = itemView.findViewById<TextView>(R.id.tv_service_sub)
        private val layoutStatusBadge = itemView.findViewById<LinearLayout>(R.id.layout_status_badge)
        private val viewStatusDot = itemView.findViewById<View>(R.id.view_status_dot)
        private val pbBadgeTesting = itemView.findViewById<ProgressBar>(R.id.pb_badge_testing)
        private val tvBadge = itemView.findViewById<TextView>(R.id.tv_service_badge)
        private val btnRefresh = itemView.findViewById<FrameLayout>(R.id.btn_item_refresh)

        fun bind(item: ServiceStatusItem) {
            val context = itemView.context
            val density = context.resources.displayMetrics.density

            tvName.text = item.name
            icon.setImageResource(item.iconRes)
            tvSub.text = item.subtitle

            val isNight = Theme.usingNightMode(context)
            val isWhite = Theme.isWhiteTheme(context)

            val cardBg = when {
                isNight -> Color.parseColor("#1B1B1E")
                isWhite -> Color.parseColor("#F7F8FA")
                else -> null
            }
            if (cardBg != null) {
                rootCard.setCardBackgroundColor(cardBg)
            }
            val strokeColor = when {
                isNight -> Color.parseColor("#2C2C30")
                isWhite -> Color.parseColor("#E8E9EC")
                else -> null
            }
            if (strokeColor != null) {
                rootCard.strokeColor = strokeColor
            }

            // Circle icon background
            val circleDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isNight) Color.parseColor("#28282D") else Color.parseColor("#EAECEF"))
            }
            viewIconBg.background = circleDrawable

            fun createBadge(color: Int): GradientDrawable {
                val alphaColor = Color.argb(
                    30, // ~12% alpha
                    Color.red(color),
                    Color.green(color),
                    Color.blue(color)
                )
                return GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 8f * density
                    setColor(alphaColor)
                }
            }

            fun setDot(color: Int) {
                val dot = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
                viewStatusDot.background = dot
            }

            when (item.state) {
                ServiceTestState.TESTING -> {
                    viewStatusDot.visibility = View.GONE
                    pbBadgeTesting.visibility = View.VISIBLE
                    tvBadge.text = "检测中"
                    tvBadge.setTextColor(COLOR_BLUE)
                    layoutStatusBadge.background = createBadge(COLOR_BLUE)
                }
                ServiceTestState.AVAILABLE -> {
                    viewStatusDot.visibility = View.VISIBLE
                    pbBadgeTesting.visibility = View.GONE
                    setDot(COLOR_GREEN)
                    tvBadge.text = "已完成"
                    tvBadge.setTextColor(COLOR_GREEN)
                    layoutStatusBadge.background = createBadge(COLOR_GREEN)
                }
                ServiceTestState.RESTRICTED -> {
                    viewStatusDot.visibility = View.VISIBLE
                    pbBadgeTesting.visibility = View.GONE
                    setDot(COLOR_ORANGE)
                    tvBadge.text = "地区受限"
                    tvBadge.setTextColor(COLOR_ORANGE)
                    layoutStatusBadge.background = createBadge(COLOR_ORANGE)
                }
                ServiceTestState.UNAVAILABLE -> {
                    viewStatusDot.visibility = View.VISIBLE
                    pbBadgeTesting.visibility = View.GONE
                    setDot(COLOR_RED)
                    tvBadge.text = "未解锁"
                    tvBadge.setTextColor(COLOR_RED)
                    layoutStatusBadge.background = createBadge(COLOR_RED)
                }
                ServiceTestState.IDLE -> {
                    viewStatusDot.visibility = View.VISIBLE
                    pbBadgeTesting.visibility = View.GONE
                    setDot(COLOR_GRAY)
                    tvBadge.text = "-"
                    tvBadge.setTextColor(COLOR_GRAY)
                    layoutStatusBadge.background = createBadge(COLOR_GRAY)
                }
            }

            val clickAction = View.OnClickListener {
                if (item.state != ServiceTestState.TESTING) {
                    onItemClick(item)
                }
            }
            btnRefresh.setOnClickListener(clickAction)
            rootCard.setOnClickListener(clickAction)
        }
    }
}
