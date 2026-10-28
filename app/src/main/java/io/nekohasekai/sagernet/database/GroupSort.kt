package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.GroupOrder

enum class GroupSortMethod {
    RAW, BY_TYPE, BY_ADDRESS, BY_NAME, BY_TEST_RESULT, BY_ID, BY_TRAFFIC, BY_SECURITY, BY_LATENCY
}

data class GroupSortAction(
    val method: GroupSortMethod = GroupSortMethod.RAW,
    val descending: Boolean = false,
)

enum class SortInput { MEMBERSHIP, CONFIG, TEST_RESULT, TRAFFIC }

object GroupSort {

    suspend fun sortProfiles(groupId: Long, action: GroupSortAction): Boolean {
        if (groupId <= 0L) return false
        val group = SagerDatabase.groupDao.getById(groupId) ?: return false
        group.order = when (action.method) {
            GroupSortMethod.BY_NAME -> GroupOrder.BY_NAME
            GroupSortMethod.BY_TEST_RESULT, GroupSortMethod.BY_LATENCY -> GroupOrder.BY_DELAY
            else -> GroupOrder.ORIGIN
        }
        SagerDatabase.groupDao.updateGroup(group)
        GroupManager.postReload(groupId)
        return true
    }

    suspend fun setSort(groupId: Long, action: GroupSortAction) {
        sortProfiles(groupId, action)
    }

    fun repositionLater(dirty: Collection<Long>, input: SortInput) {}
    fun resortLater(groupId: Long) {}

    fun bitrateToBps(text: String?): Double {
        if (text.isNullOrBlank()) return 0.0
        val parts = text.trim().split(" ")
        val num = parts[0].toDoubleOrNull() ?: return 0.0
        val unit = parts.getOrNull(1)?.uppercase() ?: return num
        return when {
            unit.startsWith("G") -> num * 1024 * 1024 * 1024
            unit.startsWith("M") -> num * 1024 * 1024
            unit.startsWith("K") -> num * 1024
            else -> num
        }
    }
}
