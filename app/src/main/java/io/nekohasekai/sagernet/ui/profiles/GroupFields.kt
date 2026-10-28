package io.nekohasekai.sagernet.ui.profiles

import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase

internal object GroupFields {

    fun update(groupId: Long, change: (ProxyGroup) -> Unit): ProxyGroup? {
        val dao = SagerDatabase.groupDao
        val group = dao.getById(groupId) ?: return null
        change(group)
        dao.updateGroup(group)
        return group
    }
}
