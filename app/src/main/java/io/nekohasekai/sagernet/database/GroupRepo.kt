package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import java.util.concurrent.CopyOnWriteArrayList

object GroupRepo {

    interface Listener {
        suspend fun groupAdd(group: ProxyGroup)
        suspend fun groupUpdated(group: ProxyGroup)
        suspend fun groupRemoved(groupId: Long)
        suspend fun groupUpdated(groupId: Long)
        suspend fun groupsReordered() {}
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    private val dao get() = SagerDatabase.groupDao

    init {
        GroupManager.addListener(object : GroupManager.Listener {
            override suspend fun groupAdd(group: ProxyGroup) {
                notify { groupAdd(group) }
            }

            override suspend fun groupUpdated(group: ProxyGroup) {
                notify { groupUpdated(group) }
            }

            override suspend fun groupRemoved(groupId: Long) {
                notify { groupRemoved(groupId) }
            }

            override suspend fun groupUpdated(groupId: Long) {
                notify { groupUpdated(groupId) }
            }
        })
    }

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private suspend fun notify(what: suspend Listener.() -> Unit) {
        for (listener in listeners) {
            listener.what()
        }
    }

    fun all(): List<ProxyGroup> {
        val groups = dao.allGroups()
        if (groups.isNotEmpty()) return groups
        ensureDefault()
        return dao.allGroups()
    }

    fun ids(): List<Long> {
        val groups = all()
        return groups.map { it.id }
    }

    fun get(id: Long): ProxyGroup? = if (id > 0) dao.getById(id) else null

    fun currentId(): Long {
        val stored = DataStore.selectedGroup
        if (stored > 0 && dao.getById(stored) != null) return stored
        val first = all().firstOrNull()?.id ?: 0L
        setCurrent(first)
        return first
    }

    fun current(): ProxyGroup {
        val id = currentId()
        return get(id) ?: all().first()
    }

    fun setCurrent(id: Long) {
        DataStore.selectedGroup = id
    }

    fun ensureDefault() {
        if (dao.allGroups().isEmpty()) {
            val group = ProxyGroup(
                name = app.getString(R.string.group_default),
                userOrder = 1L
            ).applyDefaultValues()
            dao.createGroup(group)
        }
    }

    suspend fun add(group: ProxyGroup): ProxyGroup {
        val created = GroupManager.createGroup(group)
        notify { groupAdd(created) }
        return created
    }

    suspend fun save(group: ProxyGroup) {
        GroupManager.updateGroup(group)
        notify { groupUpdated(group) }
    }

    suspend fun delete(id: Long): Boolean {
        val all = all()
        if (all.size <= 1) return false
        val group = get(id) ?: return false
        dao.deleteGroup(group)
        if (currentId() == id) {
            val remaining = all.filter { it.id != id }
            if (remaining.isNotEmpty()) setCurrent(remaining.first().id)
        }
        notify { groupRemoved(id) }
        return true
    }

    suspend fun setDisplayOrder(ids: List<Long>) {
        val groups = all()
        ids.forEachIndexed { index, groupId ->
            groups.find { it.id == groupId }?.let { g ->
                g.userOrder = (index + 1).toLong()
                dao.updateGroup(g)
            }
        }
        notify { groupsReordered() }
    }

    suspend fun postReload(groupId: Long) {
        notify { groupUpdated(groupId) }
        GroupManager.postReload(groupId)
    }

    suspend fun postUpdate(group: ProxyGroup) {
        notify { groupUpdated(group) }
        GroupManager.postUpdate(group)
    }

    suspend fun postUpdate(groupId: Long) {
        get(groupId)?.let { postUpdate(it) }
    }
}
