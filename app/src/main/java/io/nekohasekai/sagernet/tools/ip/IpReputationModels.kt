package io.nekohasekai.sagernet.tools.ip

enum class TriState {
    TRUE,
    FALSE,
    UNKNOWN;

    val isTrue: Boolean get() = this == TRUE
    val isFalse: Boolean get() = this == FALSE
    val isUnknown: Boolean get() = this == UNKNOWN

    companion object {
        fun fromBoolean(value: Boolean?): TriState {
            return when (value) {
                true -> TRUE
                false -> FALSE
                null -> UNKNOWN
            }
        }
    }
}

enum class ReputationLevel(val title: String, val colorHex: String) {
    PURE("极高纯净度 (原生住宅宽带)", "#059669"),
    LOW_RISK("低风险 (优质商业/混合网络)", "#10B981"),
    MEDIUM_RISK("中等风险 (疑似托管/部分风控)", "#F59E0B"),
    HIGH_RISK("高风险 (机房/代理/黑名单)", "#E11D48"),
    UNKNOWN("未知风险 (数据不足/接口受限)", "#64748B");
}

enum class ScenarioStatus(val title: String, val colorHex: String) {
    EXCELLENT("极佳", "#059669"),
    GOOD("良好", "#10B981"),
    CAUTION("需验证", "#F59E0B"),
    RISKY("高危受限", "#E11D48"),
    UNKNOWN("未知", "#64748B");
}

data class ScenarioSuitability(
    val id: String,
    val name: String,
    val status: ScenarioStatus,
    val reason: String,
)

data class IpDataSourceStatus(
    val sourceName: String,
    val isSuccess: Boolean,
    val latencyMs: Long,
    val summary: String,
    val rawDetails: String? = null,
)

data class IpReputationReport(
    val ip: String,
    val country: String,
    val countryCode: String,
    val flag: String,
    val city: String,
    val region: String,
    val isp: String,
    val org: String,
    val asn: String,
    val reverseDns: String,

    // Core Reputation Metrics (0 - 100)
    val score: Int,
    val level: ReputationLevel,
    val humanTrafficRatio: Int,
    val machineTrafficRatio: Int,

    // 6 Granular Dimension Metrics (0 - 100)
    val residentialScore: Int,
    val datacenterProbability: Int,
    val proxyVpnLikelihood: Int,
    val abuseRecord: Int,
    val automationTendency: Int,
    val confidenceScore: Int,

    // Tri-state Evidence Breakdown
    val isHosting: TriState,
    val isProxy: TriState,
    val isVpn: TriState,
    val isTor: TriState,
    val isAbuser: TriState,
    val isMobile: TriState,

    // Scenario Ratings
    val scenarios: List<ScenarioSuitability>,

    // Data Source Transparency
    val sourceStatuses: List<IpDataSourceStatus>,
    val hasConflict: Boolean,
    val conflictDescription: String?,
)
