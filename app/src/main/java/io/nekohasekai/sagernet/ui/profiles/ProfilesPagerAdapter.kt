package io.nekohasekai.sagernet.ui.profiles

import android.annotation.SuppressLint
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import io.nekohasekai.sagernet.database.ProxyGroup

/** One page per group in tab order (`display_order`); the page id is the group id. */
class ProfilesPagerAdapter(host: Fragment) : FragmentStateAdapter(host) {

    var groups: List<ProxyGroup> = emptyList()
        private set

    /** Takes [newGroups]; true when pages were added, removed or reordered. */
    @SuppressLint("NotifyDataSetChanged")
    fun submit(newGroups: List<ProxyGroup>): Boolean {
        val structural = newGroups.map { it.id } != groups.map { it.id }
        groups = newGroups
        if (structural) notifyDataSetChanged()
        return structural
    }

    /** Replaces the stored copy of [group]; its index, -1 when it has no page. */
    fun update(group: ProxyGroup): Int {
        val index = indexOf(group.id)
        if (index >= 0) groups = groups.toMutableList().also { it[index] = group }
        return index
    }

    fun indexOf(groupId: Long) = groups.indexOfFirst { it.id == groupId }

    override fun getItemCount() = groups.size

    override fun getItemId(position: Int) = groups[position].id

    override fun containsItem(itemId: Long) = groups.any { it.id == itemId }

    override fun createFragment(position: Int): Fragment = ProfileListFragment.newInstance(groups[position].id)
}
