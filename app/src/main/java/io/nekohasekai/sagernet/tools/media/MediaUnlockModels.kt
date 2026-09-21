package io.nekohasekai.sagernet.tools.media

enum class UnlockStatus(val title: String, val colorHex: String) {
    UNLOCKED("原生解锁", "#059669"),
    PARTIAL("部分解锁", "#F59E0B"),
    REGION_UNSUPPORTED("地区不支持", "#EF4444"),
    BLOCKED("未解锁/被拦截", "#DC2626"),
    CHALLENGE("触发风控验证", "#EA580C"),
    TIMEOUT("检测超时", "#D97706"),
    NOT_CONNECTED("未连接", "#64748B"),
    TESTING("检测中...", "#3B82F6");
}

data class MediaUnlockItem(
    val id: String,
    val name: String,
    val category: String,
    val iconRes: Int,
    var status: UnlockStatus = UnlockStatus.TESTING,
    var badgeText: String = "检测中...",
    var description: String = "正在探测平台授权与网络连通性...",
    var region: String? = null,
    var latencyMs: Long = 0L,
)
