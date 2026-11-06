package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.view.*
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.databinding.LayoutGroupItemBinding
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.ListListener
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.toBytesString
import java.lang.NumberFormatException
import java.text.SimpleDateFormat
import java.util.*
import android.graphics.Color

class GroupFragment : ToolbarFragment(R.layout.layout_group),
    Toolbar.OnMenuItemClickListener {

    lateinit var activity: MainActivity
    lateinit var groupListView: RecyclerView
    lateinit var layoutManager: LinearLayoutManager
    lateinit var groupAdapter: GroupAdapter
    lateinit var undoManager: UndoSnackbarManager<ProxyGroup>

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity = requireActivity() as MainActivity

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.menu_group)
        toolbar.inflateMenu(R.menu.add_group_menu)
        toolbar.setOnMenuItemClickListener(this)

        groupListView = view.findViewById(R.id.group_list)
        updateBottomPadding()
        layoutManager = FixedLinearLayoutManager(groupListView)
        groupListView.layoutManager = layoutManager
        groupAdapter = GroupAdapter()
        GroupManager.addListener(groupAdapter)
        groupListView.adapter = groupAdapter

        undoManager = UndoSnackbarManager(activity, groupAdapter)

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START
        ) {
            override fun getSwipeDirs(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ): Int {
                val proxyGroup = (viewHolder as GroupHolder).proxyGroup
                if (proxyGroup.id in GroupUpdater.updating) {
                    return 0
                }
                return super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun getDragDirs(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ): Int {
                val proxyGroup = (viewHolder as GroupHolder).proxyGroup
                if (proxyGroup.ungrouped || proxyGroup.id in GroupUpdater.updating) {
                    return 0
                }
                return super.getDragDirs(recyclerView, viewHolder)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                groupAdapter.remove(index)
                undoManager.remove(index to (viewHolder as GroupHolder).proxyGroup)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
            ): Boolean {
                groupAdapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) {
                super.clearView(recyclerView, viewHolder)
                groupAdapter.commitMove()
            }
        }).attachToRecyclerView(groupListView)

    }

    fun updateBottomPadding() {
        if (!::groupListView.isInitialized) return
        groupListView.clipToPadding = false
        groupListView.updatePadding(bottom = dp2px(if (DataStore.showBottomBar) 80 else 4))
    }

    private var lastCompactGroupCards: Boolean? = null

    override fun onResume() {
        super.onResume()
        if (::groupAdapter.isInitialized) {
            if (lastCompactGroupCards != null && lastCompactGroupCards != DataStore.compactGroupCards) {
                groupAdapter.notifyDataSetChanged()
            }
            lastCompactGroupCards = DataStore.compactGroupCards
        }
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_new_group -> {
                startActivity(Intent(context, GroupSettingsActivity::class.java))
            }

            R.id.action_update_all -> {
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                    .setMessage(R.string.update_all_subscription)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        val subGroups = SagerDatabase.groupDao.allGroups().filter { it.type == GroupType.SUBSCRIPTION }
                        if (subGroups.isEmpty()) return@setPositiveButton
                        runOnDefaultDispatcher {
                            supervisorScope {
                                val tasks = subGroups.map { group ->
                                    async {
                                        runCatching {
                                            GroupUpdater.executeUpdate(group, false)
                                        }.getOrElse { e ->
                                            Logs.w("Batch update failed for ${group.displayName()}: ${e.readableMessage}")
                                            false
                                        }
                                    }
                                }
                                val results = tasks.awaitAll()
                                val successCount = results.count { it }
                                val failCount = results.count { !it }
                                onMainDispatcher {
                                    val msg = if (failCount == 0) {
                                        "全部订阅更新完成（共 ${successCount} 个）"
                                    } else {
                                        "订阅更新完成：${successCount} 个成功，${failCount} 个失败"
                                    }
                                    safeSnackbar(msg)
                                }
                            }
                        }
                    }
                    .setNegativeButton(R.string.no, null)
                    .show()
            }
        }
        return true
    }

    private lateinit var selectedGroup: ProxyGroup

    private val exportProfiles =
        registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
            if (data != null) {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(selectedGroup.id)
                    val links = profiles.joinToString("\n") { it.toStdLink(compact = true) }
                    try {
                        val resolver = (context ?: MessageStore.getCurrentActivity() ?: SagerNet.application).contentResolver
                        resolver.openOutputStream(data)!!.bufferedWriter().use {
                            it.write(links)
                        }
                        onMainDispatcher {
                            safeSnackbar(R.string.action_export_msg)
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            safeSnackbar(e.readableMessage)
                        }
                    }

                }
            }
        }

    inner class GroupAdapter : RecyclerView.Adapter<GroupHolder>(),
        GroupManager.Listener,
        UndoSnackbarManager.Interface<ProxyGroup> {

        val groupList = ArrayList<ProxyGroup>()

        suspend fun reload() {
            // 自动清理历史遗留的多余空未分组（老版本升级容错）
            val allInDb = SagerDatabase.groupDao.allGroups()
            val ungroupedInDb = allInDb.filter { it.ungrouped }
            if (ungroupedInDb.size > 1) {
                for (ug in ungroupedInDb) {
                    if (SagerDatabase.proxyDao.countByGroup(ug.id) == 0L && SagerDatabase.groupDao.allGroups().filter { it.ungrouped }.size > 1) {
                        SagerDatabase.groupDao.deleteById(ug.id)
                    }
                }
            }
            val groups = SagerDatabase.groupDao.allGroups().toMutableList()
            if (groups.size > 1) {
                groups.removeAll { it.ungrouped && SagerDatabase.proxyDao.countByGroup(it.id) == 0L }
            }
            groupList.clear()
            groupList.addAll(groups)
            groupListView.post {
                notifyDataSetChanged()
            }
        }

        init {
            setHasStableIds(true)

            runOnDefaultDispatcher {
                reload()
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupHolder {
            return GroupHolder(LayoutGroupItemBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: GroupHolder, position: Int) {
            holder.bind(groupList[position])
        }

        override fun getItemCount(): Int {
            return groupList.size
        }

        override fun getItemId(position: Int): Long {
            return groupList[position].id
        }

        private val updated = HashSet<ProxyGroup>()

        fun move(from: Int, to: Int) {
            val first = groupList[from]
            var previousOrder = first.userOrder
            val (step, range) = if (from < to) Pair(1, from until to) else Pair(
                -1, to + 1 downTo from
            )
            for (i in range) {
                val next = groupList[i + step]
                val order = next.userOrder
                next.userOrder = previousOrder
                previousOrder = order
                groupList[i] = next
                updated.add(next)
            }
            first.userOrder = previousOrder
            groupList[to] = first
            updated.add(first)
            notifyItemMoved(from, to)
        }

        fun commitMove() = runOnDefaultDispatcher {
            updated.forEach { SagerDatabase.groupDao.updateGroup(it) }
            updated.clear()
        }

        fun remove(index: Int) {
            groupList.removeAt(index)
            notifyItemRemoved(index)
        }

        override fun undo(actions: List<Pair<Int, ProxyGroup>>) {
            for ((index, item) in actions) {
                groupList.add(index, item)
                notifyItemInserted(index)
            }
        }

        override fun commit(actions: List<Pair<Int, ProxyGroup>>) {
            val groups = actions.map { it.second }
            runOnDefaultDispatcher {
                GroupManager.deleteGroup(groups)
                reload()
            }
        }

        override suspend fun groupAdd(group: ProxyGroup) {
            groupList.add(group)
            delay(300L)

            onMainDispatcher {
                undoManager.flush()
                notifyItemInserted(groupList.size - 1)

                if (group.type == GroupType.SUBSCRIPTION) {
                    GroupUpdater.startUpdate(group, true)
                }
            }
        }

        override suspend fun groupRemoved(groupId: Long) {
            val index = groupList.indexOfFirst { it.id == groupId }
            if (index == -1) return
            onMainDispatcher {
                undoManager.flush()
                if (SagerDatabase.groupDao.allGroups().size <= 2) {
                    runOnDefaultDispatcher {
                        reload()
                    }
                } else {
                    groupList.removeAt(index)
                    notifyItemRemoved(index)
                }
            }
        }

        override suspend fun groupUpdated(group: ProxyGroup) {
            val index = groupList.indexOfFirst { it.id == group.id }
            if (index == -1) {
                reload()
                return
            }
            groupList[index] = group
            onMainDispatcher {
                undoManager.flush()

                notifyItemChanged(index)
            }
        }

        override suspend fun groupUpdated(groupId: Long) {
            val index = groupList.indexOfFirst { it.id == groupId }
            if (index == -1) {
                reload()
                return
            }
            onMainDispatcher {
                notifyItemChanged(index)
            }
        }

    }

    override fun onDestroy() {
        if (::groupAdapter.isInitialized) {
            GroupManager.removeListener(groupAdapter)
        }

        super.onDestroy()

        if (!::undoManager.isInitialized) return
        undoManager.flush()
    }

    inner class GroupHolder(binding: LayoutGroupItemBinding) :
        RecyclerView.ViewHolder(binding.root),
        PopupMenu.OnMenuItemClickListener {

        lateinit var proxyGroup: ProxyGroup
        val groupName = binding.groupName
        val groupStatus = binding.groupStatus
        val groupTraffic = binding.groupTraffic
        val groupTrafficProgress = binding.groupTrafficProgress
        val groupUser = binding.groupUser
        val editButton = binding.edit
        val optionsButton = binding.options
        val updateButton = binding.groupUpdate
        val subscriptionUpdateProgress = binding.subscriptionUpdateProgress

        override fun onMenuItemClick(item: MenuItem): Boolean {

            fun export(link: String) {
                val success = SagerNet.trySetPrimaryClip(link)
                safeSnackbar(if (success) R.string.action_export_msg else R.string.action_export_err)
            }

            when (item.itemId) {
                R.id.action_universal_qr -> {
                    QRCodeDialog(
                        proxyGroup.toUniversalLink(), proxyGroup.displayName()
                    ).showAllowingStateLoss(parentFragmentManager)
                }

                R.id.action_universal_clipboard -> {
                    export(proxyGroup.toUniversalLink())
                }

                R.id.action_export_clipboard -> {
                    runOnDefaultDispatcher {
                        val profiles = SagerDatabase.proxyDao.getByGroup(selectedGroup.id)
                        val links = profiles.joinToString("\n") { it.toStdLink(compact = true) }
                        onMainDispatcher {
                            SagerNet.trySetPrimaryClip(links)
                            safeSnackbar(R.string.copy_toast_msg)
                        }
                    }
                }

                R.id.action_export_file -> {
                    startFilesForResult(exportProfiles, "profiles_${proxyGroup.displayName()}.txt")
                }

                R.id.action_toggle_group_disabled -> {
                    val currentDisabled = DataStore.isGroupDisabled(proxyGroup.id)
                    val newDisabled = !currentDisabled
                    DataStore.setGroupDisabled(proxyGroup.id, newDisabled)
                    groupAdapter.notifyItemChanged(bindingAdapterPosition)
                    val msg = if (newDisabled) R.string.subscription_disabled else R.string.subscription_enabled
                    safeSnackbar(msg)
                    runOnDefaultDispatcher {
                        if (newDisabled && DataStore.selectedProxy > 0L) {
                            val currentProxy = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
                            if (currentProxy != null && currentProxy.groupId == proxyGroup.id) {
                                val fallback = SagerDatabase.proxyDao.getAll().firstOrNull { !DataStore.isGroupDisabled(it.groupId) }
                                DataStore.selectedProxy = fallback?.id ?: 0L
                            }
                        }
                        GroupManager.postReload(proxyGroup.id)
                    }
                }

                R.id.action_clear -> {
                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                        .setMessage(R.string.clear_profiles_message)
                        .setPositiveButton(R.string.yes) { _, _ ->
                            runOnDefaultDispatcher {
                                GroupManager.clearGroup(proxyGroup.id)
                                groupAdapter.reload()
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }

                R.id.action_delete_group -> {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.delete)
                        .setMessage(R.string.group_delete_confirm_prompt)
                        .setPositiveButton(R.string.yes) { _, _ ->
                            val groupId = proxyGroup.id
                            undoManager.flush()
                            runOnDefaultDispatcher {
                                if (DataStore.selectedProxy > 0L) {
                                    val currentProxy = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
                                    if (currentProxy != null && currentProxy.groupId == groupId) {
                                        val fallback = SagerDatabase.proxyDao.getAll().firstOrNull {
                                            it.groupId != groupId && !DataStore.isGroupDisabled(it.groupId)
                                        }
                                        DataStore.selectedProxy = fallback?.id ?: 0L
                                    }
                                }
                                if (DataStore.selectedGroup == groupId) {
                                    val remaining = SagerDatabase.groupDao.allGroups().filter { it.id != groupId }
                                    DataStore.selectedGroup = remaining.firstOrNull()?.id ?: 0L
                                }
                                GroupManager.deleteGroup(groupId)
                                onMainDispatcher {
                                    safeSnackbar(getString(R.string.group_deleted, proxyGroup.displayName()))
                                }
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }

            return true
        }

        private fun formatRelativeTime(seconds: Long): String {
            if (seconds <= 0L) return getString(R.string.group_status_empty_subscription)
            val diffSec = (System.currentTimeMillis() / 1000L) - seconds
            return when {
                diffSec < 60L -> "刚刚"
                diffSec < 3600L -> "${diffSec / 60} 分钟前"
                diffSec < 86400L -> "${diffSec / 3600} 小时前"
                diffSec < 30L * 86400L -> "${diffSec / 86400} 天前"
                else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(seconds * 1000L))
            }
        }

        private fun renderSubscriptionInfo(
            expireMillis: Long,
            usedBytes: Long,
            totalBytes: Long,
            lastUpdatedSec: Long,
            fallbackTrafficStr: String? = null,
            fallbackUsedStr: String? = null
        ) {
            val secondaryColor = itemView.context.getColorAttr(android.R.attr.textColorSecondary)
            val isExpired = expireMillis > 0L && System.currentTimeMillis() > expireMillis

            if (expireMillis > 0L) {
                val dateStr = SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(Date(expireMillis))
                if (isExpired) {
                    groupUser.text = "· 已过期（到期时间：$dateStr）"
                    groupUser.setTextColor(Color.parseColor("#E53935"))
                } else {
                    groupUser.text = "· 到期时间：$dateStr"
                    groupUser.setTextColor(secondaryColor)
                }
            } else {
                groupUser.text = "· 到期时间：长期有效"
                groupUser.setTextColor(secondaryColor)
            }

            if (totalBytes > 0L) {
                groupTrafficProgress.isVisible = true
                val percent = ((usedBytes.toDouble() / totalBytes.toDouble()) * 100).toInt().coerceIn(0, 100)
                groupTrafficProgress.progress = percent
                if (isExpired || percent >= 90) {
                    groupTrafficProgress.setIndicatorColor(Color.parseColor("#E53935"))
                } else {
                    groupTrafficProgress.setIndicatorColor(itemView.context.getColorAttr(R.attr.colorPrimary))
                }
            } else {
                groupTrafficProgress.isGone = true
            }

            val usageStr = if (totalBytes > 0L) {
                "${usedBytes.toBytesString()} / ${totalBytes.toBytesString()}"
            } else if (usedBytes > 0L) {
                "${usedBytes.toBytesString()} / 不限量"
            } else if (fallbackTrafficStr != null) {
                if (fallbackUsedStr != null) "$fallbackUsedStr / $fallbackTrafficStr" else fallbackTrafficStr
            } else {
                "不限量"
            }

            val timeStr = if (lastUpdatedSec > 0L) {
                formatRelativeTime(lastUpdatedSec)
            } else {
                getString(R.string.group_status_empty_subscription)
            }

            groupStatus.text = if (totalBytes > 0L || usedBytes > 0L || fallbackTrafficStr != null || lastUpdatedSec > 0L) {
                "$usageStr · $timeStr"
            } else {
                getString(R.string.group_status_empty_subscription)
            }
        }

        private fun renderBasicInfo(size: Long) {
            val secondaryColor = itemView.context.getColorAttr(android.R.attr.textColorSecondary)
            groupUser.text = "· 本地配置"
            groupUser.setTextColor(secondaryColor)
            groupTrafficProgress.isGone = true
            groupStatus.text = if (size == 0L) {
                getString(R.string.group_status_empty)
            } else {
                getString(R.string.group_status_proxies, size)
            }
        }

        fun bind(group: ProxyGroup) {
            proxyGroup = group

            itemView.setOnClickListener { }

            val isDisabled = DataStore.isGroupDisabled(proxyGroup.id)
            editButton.isVisible = true
            updateButton.isInvisible = proxyGroup.type != GroupType.SUBSCRIPTION
            groupName.text = if (isDisabled) "${proxyGroup.displayName()} ${getString(R.string.group_disabled_status)}" else proxyGroup.displayName()
            itemView.alpha = if (isDisabled) 0.6f else 1.0f

            editButton.setOnClickListener {
                startActivity(Intent(it.context, GroupSettingsActivity::class.java).apply {
                    putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id)
                })
            }

            updateButton.setOnClickListener {
                GroupUpdater.startUpdate(proxyGroup, true)
            }

            optionsButton.setOnClickListener {
                selectedGroup = proxyGroup

                val popup = PopupMenu(requireContext(), it)
                popup.menuInflater.inflate(R.menu.group_action_menu, popup.menu)

                val disabled = DataStore.isGroupDisabled(proxyGroup.id)
                popup.menu.findItem(R.id.action_toggle_group_disabled)?.title =
                    getString(if (disabled) R.string.group_enable else R.string.group_disable)

                if (proxyGroup.type != GroupType.SUBSCRIPTION) {
                    popup.menu.removeItem(R.id.action_share_subscription)
                }
                popup.setOnMenuItemClickListener(this)
                moe.matsuri.nb4a.ui.MenuScrimHelper.showPopupMenuWithScrim(popup, activity)
            }

            if (proxyGroup.id in GroupUpdater.updating) {
                subscriptionUpdateProgress.isVisible = true

                if (!GroupUpdater.progress.containsKey(proxyGroup.id)) {
                    subscriptionUpdateProgress.isIndeterminate = true
                } else {
                    subscriptionUpdateProgress.isIndeterminate = false
                    GroupUpdater.progress[proxyGroup.id]?.let {
                        subscriptionUpdateProgress.max = it.max
                        subscriptionUpdateProgress.progress = it.progress
                    }
                }

                updateButton.isInvisible = true
                editButton.isGone = true
            } else {
                subscriptionUpdateProgress.isVisible = false
                updateButton.isInvisible = proxyGroup.type != GroupType.SUBSCRIPTION
                editButton.isVisible = true
            }

            if (proxyGroup.type == GroupType.SUBSCRIPTION) {
                if (!DataStore.compactGroupCards) {
                    groupTrafficProgress.isGone = true
                    val subscription = proxyGroup.subscription
                    groupUser.text = subscription?.link.orEmpty()
                    groupUser.setTextColor(itemView.context.getColorAttr(android.R.attr.textColorSecondary))
                    runOnDefaultDispatcher {
                        val size = SagerDatabase.proxyDao.countByGroup(group.id)
                        val timeStr = if (subscription?.lastUpdated != null && subscription.lastUpdated > 0) {
                            formatRelativeTime(subscription.lastUpdated.toLong())
                        } else ""
                        val countStr = getString(R.string.group_status_proxies, size)
                        onMainDispatcher {
                            groupStatus.text = if (timeStr.isNotEmpty()) "$countStr · $timeStr" else countStr
                        }
                    }
                    return
                }

                val subscription = proxyGroup.subscription
                var usedBytes = 0L
                var totalBytes = 0L
                var expireMillis = 0L
                val lastUpdatedSec = subscription?.lastUpdated?.toLong() ?: 0L

                if (subscription != null) {
                    if (!subscription.subscriptionUserinfo.isNullOrBlank()) {
                        val info = subscription.subscriptionUserinfo
                        fun extract(pattern: String): Long {
                            return Regex(pattern, RegexOption.IGNORE_CASE).find(info)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                        }
                        val up = extract("upload=([0-9]+)")
                        val down = extract("download=([0-9]+)")
                        usedBytes = up + down
                        totalBytes = extract("total=([0-9]+)")
                        val exp = extract("expire=([0-9]+)")
                        if (exp > 0L) {
                            expireMillis = if (exp > 100_000_000_000L) exp else exp * 1000L
                        }
                    } else if (subscription.bytesUsed != null || subscription.bytesRemaining != null) {
                        usedBytes = subscription.bytesUsed ?: 0L
                        totalBytes = (subscription.bytesUsed ?: 0L) + (subscription.bytesRemaining ?: 0L)
                        if (subscription.expiryDate != null && subscription.expiryDate > 0) {
                            val exp = subscription.expiryDate.toLong()
                            expireMillis = if (exp > 100_000_000_000L) exp else exp * 1000L
                        }
                    }
                }

                renderSubscriptionInfo(expireMillis, usedBytes, totalBytes, lastUpdatedSec)

                runOnDefaultDispatcher {
                    val size = SagerDatabase.proxyDao.countByGroup(group.id)
                    var fallbackExpireMillis = expireMillis
                    var fallbackTrafficStr: String? = null
                    var fallbackUsedStr: String? = null

                    if (usedBytes == 0L && totalBytes == 0L && expireMillis <= 0L && size > 0L) {
                        val expireRegex = Regex(".*(?:套餐到期|到期时间|过期时间|到期)[：:]\\s*([0-9]{4}[-/][0-9]{2}[-/][0-9]{2})", RegexOption.IGNORE_CASE)
                        val trafficRegex = Regex(".*(?:剩余流量|可用流量|剩余)[：:]\\s*([0-9.]+\\s*[KMGT]?B|无限|不限|不限量)", RegexOption.IGNORE_CASE)
                        val usedRegex = Regex(".*(?:已用流量|已用|已使用)[：:]\\s*([0-9.]+\\s*[KMGT]?B)", RegexOption.IGNORE_CASE)
                        val allGroupProfiles = SagerDatabase.proxyDao.getByGroup(group.id)
                        for (p in allGroupProfiles) {
                            val name = p.displayName()
                            if (fallbackExpireMillis <= 0L) {
                                val m = expireRegex.find(name)
                                if (m != null) {
                                    try {
                                        val dateStr = m.groupValues[1].replace('/', '-')
                                        fallbackExpireMillis = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateStr)?.time ?: 0L
                                    } catch (_: Throwable) {}
                                }
                            }
                            if (fallbackTrafficStr == null) {
                                val m = trafficRegex.find(name)
                                if (m != null) {
                                    fallbackTrafficStr = m.groupValues[1].trim()
                                }
                            }
                            if (fallbackUsedStr == null) {
                                val m = usedRegex.find(name)
                                if (m != null) {
                                    fallbackUsedStr = m.groupValues[1].trim()
                                }
                            }
                        }
                    }

                    if (fallbackExpireMillis != expireMillis || fallbackTrafficStr != null || fallbackUsedStr != null) {
                        onMainDispatcher {
                            if (proxyGroup.id != group.id) return@onMainDispatcher
                            renderSubscriptionInfo(
                                fallbackExpireMillis,
                                usedBytes,
                                totalBytes,
                                lastUpdatedSec,
                                fallbackTrafficStr,
                                fallbackUsedStr
                            )
                        }
                    }
                }
            } else {
                renderBasicInfo(0L)
                runOnDefaultDispatcher {
                    val size = SagerDatabase.proxyDao.countByGroup(group.id)
                    onMainDispatcher {
                        if (proxyGroup.id != group.id) return@onMainDispatcher
                        renderBasicInfo(size)
                    }
                }
            }

        }
    }

}