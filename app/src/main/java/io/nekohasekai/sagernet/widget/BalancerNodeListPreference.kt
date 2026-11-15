package io.nekohasekai.sagernet.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.getColour
import moe.matsuri.nb4a.Protocols.getProtocolColor
import java.util.Collections

class BalancerNodeListPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
) : Preference(context, attrs, defStyleAttr, defStyleRes) {

    companion object {
        private const val PAYLOAD_PRIORITY_INDEX = "priority_index"

        fun <T> moveItem(list: MutableList<T>, from: Int, to: Int) {
            if (from == to || from !in list.indices || to !in list.indices) return
            if (from < to) {
                for (i in from until to) {
                    Collections.swap(list, i, i + 1)
                }
            } else {
                for (i in from downTo to + 1) {
                    Collections.swap(list, i, i - 1)
                }
            }
        }
    }

    init {
        layoutResource = R.layout.layout_balancer_node_list_pref
        isSelectable = false
    }

    private var currentItems: MutableList<ProxyEntity> = ArrayList()
    private var groupNames: Map<Long, String> = emptyMap()
    private var boundRecyclerView: RecyclerView? = null
    private var boundAdapter: NodeAdapter? = null
    private var attachedHelper: ItemTouchHelper? = null

    private var onMoveListener: ((from: Int, to: Int) -> Unit)? = null
    private var onOrderCommittedListener: (() -> Unit)? = null
    private var onRemoveListener: ((position: Int) -> Unit)? = null
    private var onReplaceListener: ((position: Int, entity: ProxyEntity) -> Unit)? = null

    fun bindCallbacks(
        onMove: (from: Int, to: Int) -> Unit,
        onOrderCommitted: () -> Unit,
        onRemove: (position: Int) -> Unit,
        onReplace: (position: Int, entity: ProxyEntity) -> Unit,
    ) {
        onMoveListener = onMove
        onOrderCommittedListener = onOrderCommitted
        onRemoveListener = onRemove
        onReplaceListener = onReplace
    }

    fun submitList(nodes: List<ProxyEntity>, groups: Map<Long, String>) {
        currentItems = ArrayList(nodes)
        groupNames = HashMap(groups)
        boundAdapter?.notifyDataSetChanged()
    }

    fun notifyItemRemovedAt(position: Int) {
        if (position in currentItems.indices) {
            currentItems.removeAt(position)
            boundAdapter?.let { adapter ->
                adapter.notifyItemRemoved(position)
                if (position < currentItems.size) {
                    adapter.notifyItemRangeChanged(
                        position,
                        currentItems.size - position,
                        PAYLOAD_PRIORITY_INDEX,
                    )
                }
            }
        }
    }

    fun notifyItemReplacedAt(position: Int, entity: ProxyEntity) {
        if (position in currentItems.indices) {
            currentItems[position] = entity
            boundAdapter?.notifyItemChanged(position)
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.isDividerAllowedAbove = false
        holder.isDividerAllowedBelow = false

        val recyclerView = holder.findViewById(R.id.node_recycler_view) as? RecyclerView ?: return
        if (boundRecyclerView !== recyclerView) {
            boundRecyclerView = recyclerView
            recyclerView.layoutManager = FixedLinearLayoutManager(recyclerView)
            recyclerView.itemAnimator = DefaultItemAnimator()
            val adapter = NodeAdapter()
            boundAdapter = adapter
            recyclerView.adapter = adapter

            val helper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN,
                0,
            ) {
                override fun isLongPressDragEnabled() = false

                override fun onMove(
                    rv: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder,
                ): Boolean {
                    val from = viewHolder.bindingAdapterPosition
                    val to = target.bindingAdapterPosition
                    if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) {
                        return false
                    }
                    moveItem(currentItems, from, to)
                    onMoveListener?.invoke(from, to)
                    adapter.notifyItemMoved(from, to)
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

                override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                    super.clearView(rv, viewHolder)
                    rv.parent?.requestDisallowInterceptTouchEvent(false)
                    rv.post {
                        adapter.notifyItemRangeChanged(0, currentItems.size, PAYLOAD_PRIORITY_INDEX)
                    }
                    onOrderCommittedListener?.invoke()
                }
            })
            helper.attachToRecyclerView(recyclerView)
            attachedHelper = helper
        } else {
            boundAdapter?.notifyDataSetChanged()
        }
    }

    private inner class NodeAdapter : RecyclerView.Adapter<NodeHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NodeHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.layout_balancer_node_item, parent, false)
            return NodeHolder(view)
        }

        override fun getItemCount(): Int = currentItems.size

        override fun onBindViewHolder(holder: NodeHolder, position: Int) {
            holder.bind(currentItems[position], position)
        }

        override fun onBindViewHolder(
            holder: NodeHolder,
            position: Int,
            payloads: MutableList<Any>,
        ) {
            if (payloads.contains(PAYLOAD_PRIORITY_INDEX)) {
                holder.bindIndex(position)
            } else {
                super.onBindViewHolder(holder, position, payloads)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private inner class NodeHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val priorityIndex: TextView = itemView.findViewById(R.id.priority_index)
        private val nodeName: TextView = itemView.findViewById(R.id.node_name)
        private val nodeProtocol: TextView = itemView.findViewById(R.id.node_protocol)
        private val nodeGroupMeta: TextView = itemView.findViewById(R.id.node_group_meta)
        private val nodeStatus: TextView = itemView.findViewById(R.id.node_status)
        private val removeButton: ImageButton = itemView.findViewById(R.id.remove_button)
        private val dragHandle: ImageView = itemView.findViewById(R.id.drag_handle)

        init {
            dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    boundRecyclerView?.parent?.requestDisallowInterceptTouchEvent(true)
                    attachedHelper?.startDrag(this)
                    true
                } else {
                    false
                }
            }
            removeButton.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onRemoveListener?.invoke(pos)
                }
            }
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos in currentItems.indices) {
                    onReplaceListener?.invoke(pos, currentItems[pos])
                }
            }
        }

        fun bindIndex(position: Int) {
            priorityIndex.text = (position + 1).toString()
        }

        fun bind(entity: ProxyEntity, position: Int) {
            val ctx = itemView.context
            bindIndex(position)
            nodeName.text = entity.displayName()
            nodeProtocol.text = entity.displayType()
            nodeProtocol.setTextColor(ctx.getProtocolColor(entity.type))

            val groupName = groupNames[entity.groupId]?.takeIf { it.isNotBlank() }
            nodeGroupMeta.isVisible = groupName != null
            nodeGroupMeta.text = if (groupName != null) " · $groupName" else ""

            when (entity.status) {
                1 -> {
                    val ping = entity.ping
                    if (ping > 0) {
                        val color = when {
                            ping < 300 -> ctx.getColour(R.color.material_green_500)
                            ping < 800 -> Color.parseColor("#FFA000")
                            else -> ctx.getColour(R.color.material_red_500)
                        }
                        nodeStatus.isVisible = true
                        nodeStatus.text = "$ping ms"
                        nodeStatus.setTextColor(color)
                    } else {
                        nodeStatus.isVisible = false
                    }
                }
                2, 3 -> {
                    nodeStatus.isVisible = true
                    nodeStatus.setText(R.string.unavailable)
                    nodeStatus.setTextColor(ctx.getColour(R.color.material_red_500))
                }
                else -> {
                    nodeStatus.isVisible = false
                }
            }
        }
    }
}
