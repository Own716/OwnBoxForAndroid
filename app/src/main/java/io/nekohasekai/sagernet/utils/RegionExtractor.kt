package io.nekohasekai.sagernet.utils

object RegionExtractor {

    private data class RegionRule(val name: String, val patterns: List<String>)

    private val rules = listOf(
        RegionRule("香港", listOf("香港", "🇭🇰", "hk", "hongkong", "hong kong")),
        RegionRule("日本", listOf("日本", "🇯🇵", "japan", "tokyo", "osaka", "jp", "东京", "大阪")),
        RegionRule("新加坡", listOf("新加坡", "🇸🇬", "singapore", "狮城", "sg")),
        RegionRule("美国", listOf("美国", "🇺🇸", "usa", "us", "united states", "america", "洛杉矶", "硅谷", "西雅图", "纽约", "芝加哥", "达拉斯")),
        RegionRule("台湾", listOf("台湾", "🇹🇼", "taiwan", "台北", "tw")),
        RegionRule("韩国", listOf("韩国", "🇰🇷", "korea", "seoul", "kr", "首尔")),
        RegionRule("英国", listOf("英国", "🇬🇧", "uk", "great britain", "london", "gb", "伦敦")),
        RegionRule("德国", listOf("德国", "🇩🇪", "germany", "frankfurt", "de", "法兰克福")),
        RegionRule("法国", listOf("法国", "🇫🇷", "france", "paris", "fr", "巴黎")),
        RegionRule("加拿大", listOf("加拿大", "🇨🇦", "canada", "ca", "多伦多", "温哥华")),
        RegionRule("澳大利亚", listOf("澳大利亚", "澳洲", "🇦🇺", "australia", "sydney", "au", "悉尼", "墨尔本")),
        RegionRule("俄罗斯", listOf("俄罗斯", "🇷🇺", "russia", "moscow", "ru", "莫斯科")),
        RegionRule("土耳其", listOf("土耳其", "🇹🇷", "turkey", "istanbul", "tr", "伊斯坦布尔")),
        RegionRule("阿根廷", listOf("阿根廷", "🇦🇷", "argentina", "ar")),
        RegionRule("印度", listOf("印度", "🇮🇳", "india", "mumbai", "in")),
        RegionRule("马来西亚", listOf("马来西亚", "🇲🇾", "malaysia", "my", "大马")),
        RegionRule("泰国", listOf("泰国", "🇹🇭", "thailand", "bangkok", "th", "曼谷")),
        RegionRule("越南", listOf("越南", "🇻🇳", "vietnam", "vn", "胡志明")),
        RegionRule("菲律宾", listOf("菲律宾", "🇵🇭", "philippines", "ph")),
        RegionRule("巴西", listOf("巴西", "🇧🇷", "brazil", "br")),
        RegionRule("荷兰", listOf("荷兰", "🇳🇱", "netherlands", "amsterdam", "nl")),
        RegionRule("瑞士", listOf("瑞士", "🇨🇭", "switzerland", "zurich", "ch", "苏黎世")),
        RegionRule("瑞典", listOf("瑞典", "🇸🇪", "sweden", "stockholm", "se")),
        RegionRule("意大利", listOf("意大利", "🇮🇹", "italy", "milan", "it", "米兰", "罗马")),
        RegionRule("西班牙", listOf("西班牙", "🇪🇸", "spain", "madrid", "es", "马德里")),
        RegionRule("印度尼西亚", listOf("印尼", "印度尼西亚", "🇮🇩", "indonesia", "id", "雅加达")),
        RegionRule("墨西哥", listOf("墨西哥", "🇲🇽", "mexico", "mx")),
        RegionRule("南非", listOf("南非", "🇿🇦", "south africa", "za")),
        RegionRule("阿联酋", listOf("阿联酋", "迪拜", "🇦🇪", "uae", "united arab emirates", "dubai", "ae"))
    )

    /**
     * Extracts a clean 2~4 Chinese character region name for status bar capsule display.
     */
    fun extractRegionName(nodeName: String?): String {
        if (nodeName.isNullOrBlank()) return ""
        val lower = nodeName.lowercase()

        for (rule in rules) {
            for (pattern in rule.patterns) {
                if (pattern.all { it.isLetter() && it.code < 128 }) {
                    // For pure ASCII abbreviations like "jp", "us", "hk", match with word boundary or non-letters
                    val regex = Regex("(?i)(^|[^a-z0-9])${Regex.escape(pattern)}([^a-z0-9]|\$)")
                    if (regex.containsMatchIn(nodeName)) {
                        return rule.name
                    }
                } else {
                    if (lower.contains(pattern)) {
                        return rule.name
                    }
                }
            }
        }

        // Fallback: extract continuous Chinese characters if available (up to 4 chars)
        val chineseMatch = Regex("[\\u4e00-\\u9fa5]{2,4}").find(nodeName)
        if (chineseMatch != null) {
            return chineseMatch.value
        }

        // Final fallback: first 4 characters
        return nodeName.take(4).trim()
    }

    /**
     * Extracts region by giving priority to GeoIP resolved country, fallback to node name.
     */
    fun extractRegion(nodeName: String?, geoIpCountry: String? = null): String {
        if (!geoIpCountry.isNullOrBlank()) {
            val fromGeo = extractRegionName(geoIpCountry)
            if (fromGeo.isNotBlank()) return fromGeo
        }
        return extractRegionName(nodeName)
    }
}
