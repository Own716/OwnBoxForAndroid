package io.nekohasekai.sagernet.tools.media

/**
 * Registry of Anthropic Claude officially supported & unsupported regions.
 * Ensures that regions like Hong Kong (HK), Mainland China (CN), Macau (MO), Russia (RU), etc.,
 * are never falsely reported as supported, even if claude.ai/login returns HTTP 200.
 */
object ClaudeRegionRegistry {

    val UNSUPPORTED_REGIONS: Set<String> = setOf(
        "HK", // 中国香港
        "MO", // 中国澳门
        "CN", // 中国大陆
        "RU", // 俄罗斯
        "BY", // 白俄罗斯
        "IR", // 伊朗
        "KP", // 朝鲜
        "SY", // 叙利亚
        "CU", // 古巴
        "VE", // 委内瑞拉
        "MM", // 缅甸
    )

    val SUPPORTED_REGIONS: Set<String> = setOf(
        "US", "GB", "JP", "SG", "TW", "KR", "AU", "CA", "NZ", "DE",
        "FR", "IT", "ES", "NL", "SE", "NO", "FI", "CH", "AT", "BE",
        "DK", "IE", "PL", "PT", "CZ", "RO", "HU", "GR", "BG", "HR",
        "SK", "SI", "EE", "LV", "LT", "CY", "MT", "LU", "IS", "IL",
        "IN", "BR", "MX", "AR", "CL", "CO", "PE", "ZA", "AE", "SA",
        "TR", "TH", "MY", "PH", "VN", "ID",
    )

    fun isRegionSupported(countryCode: String?): Boolean {
        if (countryCode.isNullOrBlank()) return false
        val code = countryCode.trim().uppercase()
        if (code in UNSUPPORTED_REGIONS) return false
        return code in SUPPORTED_REGIONS
    }

    fun isExplicitlyUnsupported(countryCode: String?): Boolean {
        if (countryCode.isNullOrBlank()) return false
        return countryCode.trim().uppercase() in UNSUPPORTED_REGIONS
    }

    fun getRegionExplanation(countryCode: String?): String {
        val code = countryCode?.trim()?.uppercase().orEmpty()
        return when (code) {
            "HK" -> "Anthropic 官方未在中国香港地区开放 Claude 服务（网页虽可载入但登录/对话均被阻断）"
            "CN" -> "Anthropic 官方未在中国大陆地区提供服务"
            "MO" -> "Anthropic 官方未在中国澳门地区提供服务"
            "RU" -> "Anthropic 官方未在俄罗斯地区提供服务"
            in UNSUPPORTED_REGIONS -> "Anthropic 官方未在当前出口所在地区 ($code) 提供服务"
            in SUPPORTED_REGIONS -> "当前地区 ($code) 属于 Anthropic 官方支持服务区"
            else -> "未在 Anthropic 官方常规支持地区名单中，可能受到访问限制"
        }
    }
}
