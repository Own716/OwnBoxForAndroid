package io.nekohasekai.sagernet.bg.autoselector

import java.util.Locale

/**
 * Matches node names against country filters and infers ISO-3166 alpha-2 country codes
 * from Unicode flag emojis, common Chinese/English country and city names, or ISO tokens.
 */
object AutoSelectorCountry {

    data class Filter(
        val include: Set<String> = emptySet(),
        val exclude: Set<String> = emptySet(),
    ) {
        val isActive: Boolean get() = include.isNotEmpty() || exclude.isNotEmpty()

        fun matches(countryCode: String): Boolean {
            if (!isActive) return true
            val code = normalizeCode(countryCode)
            if (code.isEmpty()) return false
            if (code in exclude) return false
            if (include.isNotEmpty() && code !in include) return false
            return true
        }
    }

    private val AMBIGUOUS_ISO_TOKENS = setOf(
        "AM", "AS", "AT", "BE", "BY", "DO", "GO", "ID", "IF", "IN", "IS", "IT",
        "ME", "MY", "NO", "OF", "OK", "ON", "OR", "PM", "SO", "TO", "UP"
    )

    private val KNOWN_ISO_CODES = setOf(
        "AD", "AE", "AF", "AG", "AL", "AM", "AO", "AR", "AT", "AU", "AZ",
        "BA", "BB", "BD", "BE", "BF", "BG", "BH", "BJ", "BN", "BO", "BR", "BS", "BT", "BW", "BY", "BZ",
        "CA", "CD", "CG", "CH", "CI", "CL", "CM", "CN", "CO", "CR", "CU", "CV", "CY", "CZ",
        "DE", "DJ", "DK", "DM", "DO", "DZ",
        "EC", "EE", "EG", "ER", "ES", "ET",
        "FI", "FJ", "FR",
        "GA", "GB", "GD", "GE", "GH", "GM", "GN", "GQ", "GR", "GT", "GW", "GY",
        "HK", "HN", "HR", "HT", "HU",
        "ID", "IE", "IL", "IN", "IQ", "IR", "IS", "IT",
        "JM", "JO", "JP",
        "KE", "KG", "KH", "KI", "KM", "KN", "KP", "KR", "KW", "KZ",
        "LA", "LB", "LC", "LI", "LK", "LR", "LS", "LT", "LU", "LV", "LY",
        "MA", "MC", "MD", "ME", "MG", "MK", "ML", "MM", "MN", "MO", "MR", "MT", "MU", "MV", "MW", "MX", "MY", "MZ",
        "NA", "NE", "NG", "NI", "NL", "NO", "NP", "NZ",
        "OM",
        "PA", "PE", "PG", "PH", "PK", "PL", "PT", "PY",
        "QA",
        "RO", "RS", "RU", "RW",
        "SA", "SB", "SC", "SD", "SE", "SG", "SI", "SK", "SL", "SM", "SN", "SO", "SR", "SS", "SV", "SY", "SZ",
        "TD", "TG", "TH", "TJ", "TL", "TM", "TN", "TO", "TR", "TT", "TW", "TZ",
        "UA", "UG", "US", "UY", "UZ",
        "VA", "VC", "VE", "VN", "VU",
        "WS",
        "YE",
        "ZA", "ZM", "ZW"
    )

    // Ordered with multi-word / specific phrases first so e.g. "South Korea" matches KR before "Korea".
    private val KEYWORD_RULES: List<Pair<String, String>> = listOf(
        "hong kong" to "HK",
        "hongkong" to "HK",
        "香港" to "HK",
        "taiwan" to "TW",
        "taipei" to "TW",
        "kaohsiung" to "TW",
        "台湾" to "TW",
        "台灣" to "TW",
        "台北" to "TW",
        "高雄" to "TW",
        "新北" to "TW",
        "macau" to "MO",
        "macao" to "MO",
        "澳门" to "MO",
        "澳門" to "MO",
        "singapore" to "SG",
        "新加坡" to "SG",
        "狮城" to "SG",
        "獅城" to "SG",
        "japan" to "JP",
        "tokyo" to "JP",
        "osaka" to "JP",
        "nagoya" to "JP",
        "saitama" to "JP",
        "日本" to "JP",
        "东京" to "JP",
        "東京" to "JP",
        "大阪" to "JP",
        "名古屋" to "JP",
        "south korea" to "KR",
        "korea" to "KR",
        "seoul" to "KR",
        "busan" to "KR",
        "韩国" to "KR",
        "韓國" to "KR",
        "首尔" to "KR",
        "首爾" to "KR",
        "釜山" to "KR",
        "united states" to "US",
        "america" to "US",
        "los angeles" to "US",
        "san jose" to "US",
        "seattle" to "US",
        "new york" to "US",
        "dallas" to "US",
        "chicago" to "US",
        "miami" to "US",
        "ashburn" to "US",
        "silicon valley" to "US",
        "oregon" to "US",
        "美国" to "US",
        "美國" to "US",
        "洛杉矶" to "US",
        "洛杉磯" to "US",
        "圣何塞" to "US",
        "聖荷西" to "US",
        "西雅图" to "US",
        "西雅圖" to "US",
        "纽约" to "US",
        "紐約" to "US",
        "达拉斯" to "US",
        "芝加哥" to "US",
        "迈阿密" to "US",
        "硅谷" to "US",
        "凤凰城" to "US",
        "弗吉尼亚" to "US",
        "俄勒冈" to "US",
        "united kingdom" to "GB",
        "britain" to "GB",
        "england" to "GB",
        "london" to "GB",
        "英国" to "GB",
        "英國" to "GB",
        "伦敦" to "GB",
        "倫敦" to "GB",
        "germany" to "DE",
        "frankfurt" to "DE",
        "berlin" to "DE",
        "munich" to "DE",
        "德国" to "DE",
        "德國" to "DE",
        "法兰克福" to "DE",
        "法蘭克福" to "DE",
        "柏林" to "DE",
        "慕尼黑" to "DE",
        "france" to "FR",
        "paris" to "FR",
        "marseille" to "FR",
        "法国" to "FR",
        "法國" to "FR",
        "巴黎" to "FR",
        "马赛" to "FR",
        "netherlands" to "NL",
        "holland" to "NL",
        "amsterdam" to "NL",
        "荷兰" to "NL",
        "荷蘭" to "NL",
        "阿姆斯特丹" to "NL",
        "russia" to "RU",
        "moscow" to "RU",
        "saint petersburg" to "RU",
        "俄罗斯" to "RU",
        "俄羅斯" to "RU",
        "莫斯科" to "RU",
        "圣彼得堡" to "RU",
        "新西伯利亚" to "RU",
        "canada" to "CA",
        "toronto" to "CA",
        "vancouver" to "CA",
        "montreal" to "CA",
        "加拿大" to "CA",
        "多伦多" to "CA",
        "温哥华" to "CA",
        "蒙特利尔" to "CA",
        "australia" to "AU",
        "sydney" to "AU",
        "melbourne" to "AU",
        "澳大利亚" to "AU",
        "澳大利亞" to "AU",
        "澳洲" to "AU",
        "悉尼" to "AU",
        "墨尔本" to "AU",
        "india" to "IN",
        "mumbai" to "IN",
        "new delhi" to "IN",
        "bangalore" to "IN",
        "印度" to "IN",
        "孟买" to "IN",
        "新德里" to "IN",
        "turkey" to "TR",
        "türkiye" to "TR",
        "istanbul" to "TR",
        "土耳其" to "TR",
        "伊斯坦布尔" to "TR",
        "brazil" to "BR",
        "sao paulo" to "BR",
        "são paulo" to "BR",
        "巴西" to "BR",
        "圣保罗" to "BR",
        "argentina" to "AR",
        "buenos aires" to "AR",
        "阿根廷" to "AR",
        "布宜诺斯艾利斯" to "AR",
        "italy" to "IT",
        "milan" to "IT",
        "rome" to "IT",
        "意大利" to "IT",
        "義大利" to "IT",
        "米兰" to "IT",
        "罗马" to "IT",
        "spain" to "ES",
        "madrid" to "ES",
        "barcelona" to "ES",
        "西班牙" to "ES",
        "马德里" to "ES",
        "switzerland" to "CH",
        "zurich" to "CH",
        "geneva" to "CH",
        "瑞士" to "CH",
        "苏黎世" to "CH",
        "sweden" to "SE",
        "stockholm" to "SE",
        "瑞典" to "SE",
        "斯德哥尔摩" to "SE",
        "norway" to "NO",
        "oslo" to "NO",
        "挪威" to "NO",
        "奥斯陆" to "NO",
        "finland" to "FI",
        "helsinki" to "FI",
        "芬兰" to "FI",
        "赫尔辛基" to "FI",
        "poland" to "PL",
        "warsaw" to "PL",
        "波兰" to "PL",
        "华沙" to "PL",
        "ukraine" to "UA",
        "kyiv" to "UA",
        "乌克兰" to "UA",
        "烏克蘭" to "UA",
        "基辅" to "UA",
        "belarus" to "BY",
        "minsk" to "BY",
        "白俄罗斯" to "BY",
        "vietnam" to "VN",
        "ho chi minh" to "VN",
        "hanoi" to "VN",
        "越南" to "VN",
        "胡志明" to "VN",
        "河内" to "VN",
        "thailand" to "TH",
        "bangkok" to "TH",
        "泰国" to "TH",
        "泰國" to "TH",
        "曼谷" to "TH",
        "malaysia" to "MY",
        "kuala lumpur" to "MY",
        "马来西亚" to "MY",
        "馬來西亞" to "MY",
        "吉隆坡" to "MY",
        "indonesia" to "ID",
        "jakarta" to "ID",
        "印尼" to "ID",
        "印度尼西亚" to "ID",
        "雅加达" to "ID",
        "philippines" to "PH",
        "manila" to "PH",
        "菲律宾" to "PH",
        "菲律賓" to "PH",
        "马尼拉" to "PH",
        "dubai" to "AE",
        "阿联酋" to "AE",
        "迪拜" to "AE",
        "saudi" to "SA",
        "沙特" to "SA",
        "israel" to "IL",
        "以色列" to "IL",
        "south africa" to "ZA",
        "johannesburg" to "ZA",
        "南非" to "ZA",
        "mexico" to "MX",
        "墨西哥" to "MX",
        "chile" to "CL",
        "智利" to "CL",
        "colombia" to "CO",
        "哥伦比亚" to "CO",
        "ireland" to "IE",
        "dublin" to "IE",
        "爱尔兰" to "IE",
        "都柏林" to "IE",
        "austria" to "AT",
        "vienna" to "AT",
        "奥地利" to "AT",
        "维也纳" to "AT",
        "belgium" to "BE",
        "brussels" to "BE",
        "比利时" to "BE",
        "czech" to "CZ",
        "prague" to "CZ",
        "捷克" to "CZ",
        "布拉格" to "CZ",
        "denmark" to "DK",
        "copenhagen" to "DK",
        "丹麦" to "DK",
        "greece" to "GR",
        "athens" to "GR",
        "希腊" to "GR",
        "portugal" to "PT",
        "lisbon" to "PT",
        "葡萄牙" to "PT",
        "romania" to "RO",
        "罗马尼亚" to "RO",
        "hungary" to "HU",
        "budapest" to "HU",
        "匈牙利" to "HU",
        "bulgaria" to "BG",
        "保加利亚" to "BG",
        "lithuania" to "LT",
        "立陶宛" to "LT",
        "latvia" to "LV",
        "拉脱维亚" to "LV",
        "estonia" to "EE",
        "爱沙尼亚" to "EE",
        "iceland" to "IS",
        "冰岛" to "IS",
        "luxembourg" to "LU",
        "卢森堡" to "LU",
        "kazakhstan" to "KZ",
        "哈萨克斯坦" to "KZ",
        "nigeria" to "NG",
        "尼日利亚" to "NG",
        "egypt" to "EG",
        "埃及" to "EG",
        "pakistan" to "PK",
        "巴基斯坦" to "PK",
        "bangladesh" to "BD",
        "孟加拉" to "BD",
        "nepal" to "NP",
        "尼泊尔" to "NP",
        "cambodia" to "KH",
        "柬埔寨" to "KH",
        "myanmar" to "MM",
        "缅甸" to "MM",
        "laos" to "LA",
        "老挝" to "LA",
        "new zealand" to "NZ",
        "auckland" to "NZ",
        "新西兰" to "NZ",
        "纽西兰" to "NZ",
        "china" to "CN",
        "中国" to "CN",
        "中國" to "CN",
    )

    private val ASCII_CODE_TOKEN_REGEX = Regex("[^A-Za-z0-9]+")
    private val LETTERS_AND_DIGITS_PREFIX = Regex("^([A-Za-z]{2})(\\d+).*$")
    private val AIRPORT_CODES = mapOf(
        "HKG" to "HK",
        "TPE" to "TW",
        "KHH" to "TW",
        "MFM" to "MO",
        "NRT" to "JP",
        "HND" to "JP",
        "KIX" to "JP",
        "TYO" to "JP",
        "SIN" to "SG",
        "ICN" to "KR",
        "SEL" to "KR",
        "LAX" to "US",
        "SJC" to "US",
        "SEA" to "US",
        "NYC" to "US",
        "JFK" to "US",
        "ORD" to "US",
        "DFW" to "US",
        "MIA" to "US",
        "SFO" to "US",
        "IAD" to "US",
        "USA" to "US",
        "LHR" to "GB",
        "FRA" to "DE",
        "BER" to "DE",
        "MUC" to "DE",
        "CDG" to "FR",
        "AMS" to "NL",
        "MOW" to "RU",
        "SVO" to "RU",
        "LED" to "RU",
        "YYZ" to "CA",
        "YVR" to "CA",
        "SYD" to "AU",
        "MEL" to "AU",
        "BOM" to "IN",
        "DEL" to "IN",
        "IST" to "TR",
        "GRU" to "BR",
        "MXP" to "IT",
        "MAD" to "ES",
        "ZRH" to "CH",
        "ARN" to "SE",
        "OSL" to "NO",
        "HEL" to "FI",
        "WAW" to "PL",
        "BKK" to "TH",
        "SGN" to "VN",
        "HAN" to "VN",
        "KUL" to "MY",
        "CGK" to "ID",
        "MNL" to "PH",
        "DXB" to "AE",
        "JNB" to "ZA",
        "DUB" to "IE",
        "VIE" to "AT",
        "PRG" to "CZ",
        "AKL" to "NZ",
    )

    fun normalizeCode(raw: String): String {
        val code = raw.trim().uppercase(Locale.US)
        return when (code) {
            "UK" -> "GB"
            else -> if (code.length == 2 && code.all { it in 'A'..'Z' }) code else ""
        }
    }

    fun parseFilter(raw: String?): Filter {
        if (raw.isNullOrBlank()) return Filter()
        val include = LinkedHashSet<String>()
        val exclude = LinkedHashSet<String>()
        for (part in raw.split(',', ';', ' ', '\n', '\r', '\t')) {
            val token = part.trim()
            if (token.isEmpty()) continue
            if (token.startsWith('!')) {
                val code = normalizeCode(token.substring(1))
                if (code.isNotEmpty()) {
                    exclude.add(code)
                }
            } else {
                val code = normalizeCode(token)
                if (code.isNotEmpty()) {
                    include.add(code)
                }
            }
        }
        return Filter(include = include, exclude = exclude)
    }

    fun inferCountryCode(name: String?): String {
        if (name.isNullOrBlank()) return ""

        // 1. Unicode Regional Indicator flag emojis (e.g. 🇯🇵 -> JP, 🇭🇰 -> HK).
        val flagCode = extractFlagEmojiCode(name)
        if (flagCode.isNotEmpty()) return flagCode

        // 2. Chinese & English country/city phrases.
        val lower = name.lowercase(Locale.US)
        for ((keyword, code) in KEYWORD_RULES) {
            if (keyword.all { it.code < 128 }) {
                if (containsAsciiPhrase(lower, keyword)) return code
            } else {
                if (name.contains(keyword, ignoreCase = true)) return code
            }
        }

        // 3. Airport / 3-letter country codes and unambiguous ISO-2 tokens.
        for (rawToken in name.split(ASCII_CODE_TOKEN_REGEX)) {
            if (rawToken.isEmpty()) continue
            val upper = rawToken.uppercase(Locale.US)
            AIRPORT_CODES[upper]?.let { return it }

            val numberedMatch = LETTERS_AND_DIGITS_PREFIX.matchEntire(rawToken)
            if (numberedMatch != null) {
                val candidate = normalizeTokenCode(numberedMatch.groupValues[1])
                if (candidate.isNotEmpty()) return candidate
            }

            if (upper.length == 2) {
                val normalized = normalizeTokenCode(upper)
                if (normalized.isEmpty()) continue
                if (normalized !in AMBIGUOUS_ISO_TOKENS || rawToken == upper) {
                    // For ambiguous English words ("in", "it", "no", "is", "at", "by", "id", "my"),
                    // require all-uppercase standalone token ("HK", "IT", "IN") or numbered suffix ("IT01").
                    if (normalized !in AMBIGUOUS_ISO_TOKENS || rawToken == upper) {
                        // Even in uppercase, skip generic status words like "OK", "ON", "NO" unless paired elsewhere.
                        if (normalized !in setOf("OK", "ON", "SO", "PM", "AM", "TO", "IF", "IS")) {
                            return normalized
                        }
                    }
                }
            }
        }

        return ""
    }

    private fun normalizeTokenCode(token: String): String {
        val code = when (val upper = token.uppercase(Locale.US)) {
            "UK" -> "GB"
            else -> upper
        }
        return if (code in KNOWN_ISO_CODES) code else ""
    }

    private fun containsAsciiPhrase(haystackLower: String, needleLower: String): Boolean {
        var fromIndex = 0
        while (true) {
            val idx = haystackLower.indexOf(needleLower, fromIndex)
            if (idx < 0) return false
            val beforeOk = idx == 0 || !haystackLower[idx - 1].isAsciiLetterOrDigit()
            val end = idx + needleLower.length
            val afterOk = end >= haystackLower.length || !haystackLower[end].isAsciiLetterOrDigit()
            if (beforeOk && afterOk) return true
            fromIndex = idx + 1
        }
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean {
        return (this in 'a'..'z') || (this in 'A'..'Z') || (this in '0'..'9')
    }

    private fun extractFlagEmojiCode(text: String): String {
        var i = 0
        while (i < text.length) {
            val cp1 = text.codePointAt(i)
            val charCount1 = Character.charCount(cp1)
            val nextIdx = i + charCount1
            if (cp1 in 0x1F1E6..0x1F1FF && nextIdx < text.length) {
                val cp2 = text.codePointAt(nextIdx)
                if (cp2 in 0x1F1E6..0x1F1FF) {
                    val c1 = ('A'.code + (cp1 - 0x1F1E6)).toChar()
                    val c2 = ('A'.code + (cp2 - 0x1F1E6)).toChar()
                    val code = normalizeCode("$c1$c2")
                    if (code.isNotEmpty()) return code
                }
            }
            i = nextIdx
        }
        return ""
    }
}
