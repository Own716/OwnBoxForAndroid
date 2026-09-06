package io.nekohasekai.sagernet.utils

object CountryFlagUtils {

    private val REGIONAL_INDICATOR_REGEX = Regex("[\uD83C][\uDDE6-\uDDFF]{2}")

    fun hasFlag(name: String): Boolean {
        return REGIONAL_INDICATOR_REGEX.containsMatchIn(name)
    }

    fun getFlag(name: String): String? {
        if (hasFlag(name)) return null

        val upper = name.uppercase()

        return when {
            containsAny(upper, "香港", "HK", "HONG KONG", "HONGKONG") -> "🇭🇰"
            containsAny(upper, "台湾", "TW", "TAIWAN", "台北") -> "🇹🇼"
            containsAny(upper, "日本", "JP", "JAPAN", "TOKYO", "OSAKA", "东京", "大阪") -> "🇯🇵"
            containsAny(upper, "新加坡", "SG", "SINGAPORE", "狮城") -> "🇸🇬"
            containsAny(upper, "美国", "US", "USA", "UNITED STATES", "AMERICA", "洛杉矶", "硅谷", "纽约") -> "🇺🇸"
            containsAny(upper, "韩国", "KR", "KOREA", "首尔") -> "🇰🇷"
            containsAny(upper, "英国", "UK", "GB", "GREAT BRITAIN", "LONDON", "伦敦") -> "🇬🇧"
            containsAny(upper, "德国", "DE", "GERMANY", "FRANKFURT", "法兰克福") -> "🇩🇪"
            containsAny(upper, "法国", "FR", "FRANCE", "PARIS", "巴黎") -> "🇫🇷"
            containsAny(upper, "加拿大", "CA", "CANADA") -> "🇨🇦"
            containsAny(upper, "澳大利亚", "澳洲", "AU", "AUSTRALIA", "SYDNEY", "悉尼") -> "🇦🇺"
            containsAny(upper, "俄罗斯", "RU", "RUSSIA", "MOSCOW", "莫斯科") -> "🇷🇺"
            containsAny(upper, "荷兰", "NL", "NETHERLANDS", "AMSTERDAM") -> "🇳🇱"
            containsAny(upper, "印度", "IN", "INDIA", "MUMBAI") -> "🇮🇳"
            containsAny(upper, "土耳其", "TR", "TURKEY", "ISTANBUL") -> "🇹🇷"
            containsAny(upper, "阿根廷", "AR", "ARGENTINA") -> "🇦🇷"
            containsAny(upper, "马来西亚", "MY", "MALAYSIA") -> "🇲🇾"
            containsAny(upper, "泰国", "TH", "THAILAND") -> "🇹🇭"
            containsAny(upper, "菲律宾", "PH", "PHILIPPINES") -> "🇵🇭"
            containsAny(upper, "越南", "VN", "VIETNAM") -> "🇻🇳"
            containsAny(upper, "印度尼西亚", "印尼", "ID", "INDONESIA") -> "🇮🇩"
            containsAny(upper, "巴西", "BR", "BRAZIL") -> "🇧🇷"
            containsAny(upper, "意大利", "IT", "ITALY") -> "🇮🇹"
            containsAny(upper, "西班牙", "ES", "SPAIN") -> "🇪🇸"
            containsAny(upper, "瑞士", "CH", "SWITZERLAND") -> "🇨🇭"
            containsAny(upper, "瑞典", "SE", "SWEDEN") -> "🇸🇪"
            containsAny(upper, "挪威", "NO", "NORWAY") -> "🇳🇴"
            containsAny(upper, "爱尔兰", "IE", "IRELAND") -> "🇮🇪"
            containsAny(upper, "南非", "ZA", "SOUTH AFRICA") -> "🇿🇦"
            containsAny(upper, "阿联酋", "AE", "UAE", "DUBAI", "迪拜") -> "🇦🇪"
            else -> null
        }
    }

    private fun containsAny(text: String, vararg keywords: String): Boolean {
        return keywords.any { keyword ->
            if (keyword.length <= 2 && keyword.all { it.isLetter() }) {
                Regex("(?i)(^|[^A-Z])$keyword([^A-Z]|$)").containsMatchIn(text)
            } else {
                text.contains(keyword, ignoreCase = true)
            }
        }
    }

    fun formatWithFlag(name: String): String {
        val flag = getFlag(name) ?: return name
        return "$flag $name"
    }
}
