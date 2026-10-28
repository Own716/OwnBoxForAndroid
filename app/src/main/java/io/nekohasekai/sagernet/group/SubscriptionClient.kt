package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.SagerDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

object SubscriptionClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableStates = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val states: StateFlow<Map<Long, Int>> get() = mutableStates

    fun refreshGroup(groupId: Long, showDiff: Boolean = false) {
        val group = GroupRepo.get(groupId) ?: return
        if (group.type != GroupType.SUBSCRIPTION) return
        scope.launch {
            mutableStates.update { it + (groupId to 2) }
            try {
                val sub = group.subscription ?: return@launch
                RawUpdater.doUpdate(group, sub, GroupManager.userInterface, true)
            } catch (e: Exception) {
            } finally {
                mutableStates.update { it - groupId }
                GroupRepo.postReload(groupId)
            }
        }
    }

    fun refreshAll(onlyAllowed: Boolean = false) {
        scope.launch {
            val subscriptions = SagerDatabase.groupDao.subscriptions()
            for (sub in subscriptions) {
                refreshGroup(sub.id)
            }
        }
    }

    suspend fun groupAction(gid: Long, action: String): String {
        return ""
    }

    suspend fun resolveProfiles(gid: Long, ids: List<Long>): String {
        return ""
    }
}
