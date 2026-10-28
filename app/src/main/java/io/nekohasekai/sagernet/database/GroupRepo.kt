package io.nekohasekai.sagernet.database

object GroupRepo {
    fun get(groupId: Long): ProxyGroup? = SagerDatabase.groupDao.getById(groupId)
    suspend fun postReload(groupId: Long) = GroupManager.postReload(groupId)
    suspend fun postUpdate(groupId: Long) = GroupManager.postUpdate(groupId)
}
