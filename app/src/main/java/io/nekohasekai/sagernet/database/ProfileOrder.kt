package io.nekohasekai.sagernet.database

/** The one profile order shared by the list, the notification, the widgets and the TV controls: user_order. */
object ProfileOrder {

    /** The profile [step] places away from [profileId] inside its group, wrapping around; null below two profiles. */
    fun neighbour(profileId: Long, step: Int): ProxyEntity? {
        val gid = SagerDatabase.proxyDao.getById(profileId)?.groupId ?: return null
        val ids = SagerDatabase.proxyDao.getIdsByGroup(gid)
        if (ids.size < 2) return null
        val index = ids.indexOf(profileId)
        if (index < 0) return null
        return SagerDatabase.proxyDao.getById(ids[(index + step).mod(ids.size)])
    }

    /** Whether [profileId] has a neighbour to switch to. */
    fun canCycle(profileId: Long): Boolean {
        val gid = SagerDatabase.proxyDao.getById(profileId)?.groupId ?: return false
        return SagerDatabase.proxyDao.countByGroup(gid) > 1
    }
}
