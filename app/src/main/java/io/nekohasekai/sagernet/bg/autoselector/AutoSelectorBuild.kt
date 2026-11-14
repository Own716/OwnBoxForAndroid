package io.nekohasekai.sagernet.bg.autoselector

/**
 * Metadata recorded during `ConfigBuilder.buildConfig` for the top-level running
 * AutoSelector balancer so `AutoSelectorRuntime` can map Go outbound tags back to
 * OwnBox `ProxyEntity.id` values and supervise background ranking/rebuild cycles.
 */
data class AutoSelectorBuild(
    val selectorId: Long,
    val selectorName: String,
    val groupTag: String,
    val members: Map<String, Long>,
    val memberIds: List<Long>,
    val trackedGroups: List<Long>,
    val testUrl: String,
    val balance: Boolean,
    val poolCap: Int,
    val buildLimit: Int,
    val resultValidityMins: Int,
)
