package io.nekohasekai.sagernet.ui.profiles

import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import java.util.concurrent.Callable

/** Single-field writes of the profiles screen (scroll position, sort sub-criteria, shown test items). */
internal object GroupFields {

    fun update(groupId: Long, change: (ProxyGroup) -> Unit): ProxyGroup? =
        SagerDatabase.instance.runInTransaction(Callable {
            val dao = SagerDatabase.groupDao
            val group = dao.getById(groupId) ?: return@Callable null
            val before = group.copy()
            change(group)
            if (group != before) dao.updateGroup(group)
            group
        })
}
