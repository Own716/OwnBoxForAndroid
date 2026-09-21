package io.nekohasekai.sagernet.tools.connectivity

enum class StageState {
    IDLE,
    RUNNING,
    SUCCESS,
    WARNING,
    FAILED,
    SKIPPED,
}

enum class NodeUsability(val title: String, val colorHex: String, val summary: String) {
    FULLY_USABLE(
        "可正常使用",
        "#059669",
        "节点入口畅通，代理核心隧道正常，国际与国内主流网络均可高速稳定访问",
    ),
    PARTIALLY_USABLE(
        "部分可用",
        "#F59E0B",
        "代理链路已建立，但部分海外站点或特定网络服务访问受阻，需注意分流规则",
    ),
    UNUSABLE(
        "不可用",
        "#DC2626",
        "节点入口无法连通或代理核心出站链路中断，当前节点无法正常提供代理服务",
    ),
    INDETERMINATE(
        "检测结果不确定",
        "#64748B",
        "未连接代理服务或网络环境存在严重异常，未能完成有效连通性诊断",
    );
}

data class EntryProbeResult(
    val state: StageState = StageState.IDLE,
    val host: String = "",
    val port: Int = 0,
    val dnsResolvedIp: String? = null,
    val rttMs: Long = 0L,
    val summary: String = "未开始",
    val errorDetail: String? = null,
)

data class TunnelProbeResult(
    val state: StageState = StageState.IDLE,
    val mixedPort: Int = 0,
    val rttMs: Long = 0L,
    val summary: String = "未开始",
    val errorDetail: String? = null,
)

data class TargetProbeResult(
    val id: String,
    val name: String,
    val targetUrl: String,
    val iconRes: Int,
    var state: StageState = StageState.IDLE,
    var statusCode: Int = 0,
    var rttMs: Long = 0L,
    var summary: String = "等待检测...",
    var detail: String = "",
    var isJitterRecovered: Boolean = false,
    var isGoogleCn: Boolean = false,
)

data class ConnectivityReport(
    val nodeName: String,
    val targetHost: String,
    val targetPort: Int,
    val entryResult: EntryProbeResult,
    val tunnelResult: TunnelProbeResult,
    val targetProbes: List<TargetProbeResult>,
    val usability: NodeUsability,
    val conclusion: String,
)
