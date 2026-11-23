package io.nekohasekai.sagernet.fmt.internal

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase

/**
 * Resolves effective strategy-group member nodes cleanly for both ConfigBuilder and settings UI.
 */
object BalancerMemberResolver {

    data class FilterSpec(
        val balancerType: Int,
        val targetGroupIds: List<Long>,
        val explicitProxyIds: List<Long>,
        val nameExclude: String = "",
        val nameInclude: String = "",
        val selfId: Long = 0L,
        val frontProxyId: Long = 0L,
        val landingProxyId: Long = 0L,
        val countryFilter: String = "",
        val selectionMode: String = BalancerBean.SELECTION_LATENCY,
        val tiers: List<BalancerBean.PriorityTier> = emptyList(),
    )

    data class ResolutionResult(
        val members: List<ProxyEntity> = emptyList(),
        val groupMatchedCount: Int = 0,
        val extraAddedCount: Int = 0,
        val excludeRegexInvalid: Boolean = false,
        val includeRegexInvalid: Boolean = false,
        val tierRegexInvalid: Boolean = false,
        val tierCounts: List<Int> = emptyList(),
        val memberTierIndices: Map<Long, Int> = emptyMap(),
        val matchedCandidateCount: Int = members.size,
        val availableCount: Int = members.count { it.status != 2 && it.status != 3 },
    ) {
        val totalCount: Int get() = members.size
        val hasRegexError: Boolean get() = excludeRegexInvalid || includeRegexInvalid || tierRegexInvalid
    }

    private data class CompiledTier(
        val groupId: Long,
        val hasRegexText: Boolean,
        val regex: Regex?,
    )

    fun assignTierIndices(
        entities: List<ProxyEntity>,
        tiers: List<BalancerBean.PriorityTier>,
    ): Pair<Map<Long, Int>, Boolean> {
        if (tiers.isEmpty() || entities.isEmpty()) {
            return emptyMap<Long, Int>() to false
        }
        var anyInvalid = false
        val compiled = tiers.map { t ->
            val filter = t.nameFilter.orEmpty()
            val hasText = filter.isNotBlank()
            val regex = if (hasText) {
                runCatching { Regex(filter) }.getOrNull().also {
                    if (it == null) anyInvalid = true
                }
            } else {
                null
            }
            CompiledTier(groupId = t.groupId, hasRegexText = hasText, regex = regex)
        }
        val fallbackTier = tiers.size
        val map = LinkedHashMap<Long, Int>(entities.size)
        for (entity in entities) {
            val name = entity.displayName()
            var matched = fallbackTier
            for (i in compiled.indices) {
                val ct = compiled[i]
                if (ct.groupId > 0L && entity.groupId != ct.groupId) continue
                if (ct.hasRegexText && (ct.regex == null || !ct.regex.containsMatchIn(name))) continue
                matched = i
                break
            }
            map[entity.id] = matched
        }
        return map to anyInvalid
    }

    fun resolve(
        balancer: BalancerBean,
        selfId: Long = 0L,
        frontProxyId: Long = balancer.frontProxy,
        landingProxyId: Long = balancer.landingProxy,
        groupProfilesProvider: (Long) -> List<ProxyEntity> = { gid -> SagerDatabase.proxyDao.getByGroup(gid) },
        entitiesByIdsProvider: (List<Long>) -> List<ProxyEntity> = { ids -> SagerDatabase.proxyDao.getEntities(ids) },
        isGroupDisabled: (Long) -> Boolean = { gid -> if (SagerNet.isInitialized()) DataStore.isGroupDisabled(gid) else false },
    ): ResolutionResult {
        val targetGids = when {
            !balancer.targetGroupIds.isNullOrEmpty() -> balancer.targetGroupIds.filter { it > 0L }
            balancer.targetGroupId > 0L -> listOf(balancer.targetGroupId)
            else -> emptyList()
        }
        return resolve(
            spec = FilterSpec(
                balancerType = balancer.balancerType,
                targetGroupIds = targetGids,
                explicitProxyIds = balancer.proxies.orEmpty(),
                nameExclude = balancer.nameExclude.orEmpty(),
                nameInclude = balancer.nameInclude.orEmpty(),
                selfId = selfId,
                frontProxyId = frontProxyId,
                landingProxyId = landingProxyId,
                countryFilter = balancer.countryFilter.orEmpty(),
                selectionMode = when {
                    balancer.isFallbackMode() -> BalancerBean.SELECTION_FALLBACK
                    balancer.isPriorityMode() -> BalancerBean.SELECTION_PRIORITY
                    else -> BalancerBean.SELECTION_LATENCY
                },
                tiers = balancer.tiers?.mapNotNull { it?.copy() } ?: emptyList(),
            ),
            groupProfilesProvider = groupProfilesProvider,
            entitiesByIdsProvider = entitiesByIdsProvider,
            isGroupDisabled = isGroupDisabled,
        )
    }

    fun resolve(
        spec: FilterSpec,
        groupProfilesProvider: (Long) -> List<ProxyEntity> = { gid -> SagerDatabase.proxyDao.getByGroup(gid) },
        entitiesByIdsProvider: (List<Long>) -> List<ProxyEntity> = { ids -> SagerDatabase.proxyDao.getEntities(ids) },
        isGroupDisabled: (Long) -> Boolean = { gid -> if (SagerNet.isInitialized()) DataStore.isGroupDisabled(gid) else false },
    ): ResolutionResult {
        var includeRegex: Regex? = null
        var includeInvalid = false
        if (spec.nameInclude.isNotBlank()) {
            val parsed = runCatching { Regex(spec.nameInclude) }.getOrNull()
            if (parsed == null) {
                includeInvalid = true
            } else {
                includeRegex = parsed
            }
        }

        var excludeRegex: Regex? = null
        var excludeInvalid = false
        if (spec.nameExclude.isNotBlank()) {
            val parsed = runCatching { Regex(spec.nameExclude) }.getOrNull()
            if (parsed == null) {
                excludeInvalid = true
            } else {
                excludeRegex = parsed
            }
        }

        fun isEligibleMember(entity: ProxyEntity): Boolean {
            if (entity.id == spec.selfId && spec.selfId > 0L) return false
            if (entity.id == spec.frontProxyId && spec.frontProxyId > 0L) return false
            if (entity.id == spec.landingProxyId && spec.landingProxyId > 0L) return false
            if (entity.type == ProxyEntity.TYPE_BALANCER) return false
            if (isGroupDisabled(entity.groupId)) return false
            return true
        }

        val countryTokens = parseCountryTokens(spec.countryFilter)
        val candidates = ArrayList<ProxyEntity>()
        val seenIds = HashSet<Long>()
        val fromGroupIds = HashSet<Long>()

        val usesGroup = spec.balancerType == BalancerBean.TYPE_GROUP || spec.balancerType == BalancerBean.TYPE_MIXED
        val usesExplicitList = spec.balancerType == BalancerBean.TYPE_LIST || spec.balancerType == BalancerBean.TYPE_MIXED

        if (usesGroup && spec.targetGroupIds.isNotEmpty()) {
            val groupCandidates = spec.targetGroupIds
                .distinct()
                .flatMap { gid -> groupProfilesProvider(gid) }
            for (entity in groupCandidates) {
                if (!seenIds.add(entity.id)) continue
                if (!isEligibleMember(entity)) {
                    seenIds.remove(entity.id)
                    continue
                }
                val name = entity.displayName()
                if (includeRegex != null && !includeRegex.containsMatchIn(name)) {
                    seenIds.remove(entity.id)
                    continue
                }
                if (excludeRegex != null && excludeRegex.containsMatchIn(name)) {
                    seenIds.remove(entity.id)
                    continue
                }
                if (countryTokens.isNotEmpty() && !matchesCountryTokens(name, countryTokens)) {
                    seenIds.remove(entity.id)
                    continue
                }
                candidates.add(entity)
                fromGroupIds.add(entity.id)
            }
        }

        if (usesExplicitList && spec.explicitProxyIds.isNotEmpty()) {
            val distinctIds = spec.explicitProxyIds.filter { it > 0L }.distinct()
            if (distinctIds.isNotEmpty()) {
                val byId = entitiesByIdsProvider(distinctIds).associateBy { it.id }
                for (id in distinctIds) {
                    val entity = byId[id] ?: continue
                    if (!seenIds.add(entity.id)) continue
                    if (!isEligibleMember(entity)) {
                        seenIds.remove(entity.id)
                        continue
                    }
                    if (countryTokens.isNotEmpty() && !matchesCountryTokens(entity.displayName(), countryTokens)) {
                        seenIds.remove(entity.id)
                        continue
                    }
                    candidates.add(entity)
                }
            }
        }

        val matchedCandidateCount = candidates.size
        val availableCount = candidates.count { it.status != 2 && it.status != 3 }

        val ordered = if (spec.selectionMode == BalancerBean.SELECTION_PRIORITY || spec.selectionMode == BalancerBean.SELECTION_FALLBACK) {
            candidates
        } else {
            sortForColdStart(candidates)
        }

        val activeTiers = if (spec.selectionMode == BalancerBean.SELECTION_PRIORITY) spec.tiers else emptyList()
        var tierRegexInvalid = false
        val memberTierIndices: Map<Long, Int>
        val finalMembers: List<ProxyEntity>
        val tierCounts: List<Int>
        if (activeTiers.isNotEmpty()) {
            val (tierMap, hasInvalid) = assignTierIndices(ordered, activeTiers)
            tierRegexInvalid = hasInvalid
            val bucketCount = activeTiers.size + 1
            val buckets = List(bucketCount) { ArrayList<ProxyEntity>() }
            for (entity in ordered) {
                val tIdx = (tierMap[entity.id] ?: activeTiers.size).coerceIn(0, bucketCount - 1)
                buckets[tIdx].add(entity)
            }
            finalMembers = buckets.flatten()
            val finalCounts = IntArray(bucketCount)
            val finalTierMap = LinkedHashMap<Long, Int>(finalMembers.size)
            for (entity in finalMembers) {
                val tIdx = (tierMap[entity.id] ?: activeTiers.size).coerceIn(0, bucketCount - 1)
                finalCounts[tIdx]++
                finalTierMap[entity.id] = tIdx
            }
            tierCounts = finalCounts.toList()
            memberTierIndices = finalTierMap
        } else {
            finalMembers = ordered
            tierCounts = emptyList()
            memberTierIndices = emptyMap()
        }

        val groupMatchedCount = finalMembers.count { it.id in fromGroupIds }
        val extraAddedCount = finalMembers.size - groupMatchedCount

        return ResolutionResult(
            members = finalMembers,
            groupMatchedCount = groupMatchedCount,
            extraAddedCount = extraAddedCount,
            excludeRegexInvalid = excludeInvalid,
            includeRegexInvalid = includeInvalid,
            tierRegexInvalid = tierRegexInvalid,
            tierCounts = tierCounts,
            memberTierIndices = memberTierIndices,
            matchedCandidateCount = matchedCandidateCount,
            availableCount = availableCount,
        )
    }

    private data class CountryDef(
        val iso: String,
        val keywords: List<String>,
    )

    private val COUNTRY_DEFS = listOf(
        CountryDef("HK", listOf("香港", "HK", "HONG KONG", "HONGKONG")),
        CountryDef("TW", listOf("台湾", "TW", "TAIWAN", "台北")),
        CountryDef("JP", listOf("日本", "JP", "JAPAN", "TOKYO", "OSAKA", "东京", "大阪")),
        CountryDef("SG", listOf("新加坡", "SG", "SINGAPORE", "狮城")),
        CountryDef("US", listOf("美国", "US", "USA", "UNITED STATES", "AMERICA", "洛杉矶", "硅谷", "西雅图", "纽约", "芝加哥", "达拉斯")),
        CountryDef("KR", listOf("韩国", "KR", "KOREA", "SEOUL", "首尔")),
        CountryDef("GB", listOf("英国", "UK", "GB", "GREAT BRITAIN", "UNITED KINGDOM", "LONDON", "伦敦")),
        CountryDef("DE", listOf("德国", "DE", "GERMANY", "FRANKFURT", "法兰克福")),
        CountryDef("FR", listOf("法国", "FR", "FRANCE", "PARIS", "巴黎")),
        CountryDef("CA", listOf("加拿大", "CA", "CANADA", "多伦多", "温哥华")),
        CountryDef("AU", listOf("澳大利亚", "澳洲", "AU", "AUSTRALIA", "SYDNEY", "悉尼", "墨尔本")),
        CountryDef("RU", listOf("俄罗斯", "RU", "RUSSIA", "MOSCOW", "莫斯科")),
        CountryDef("NL", listOf("荷兰", "NL", "NETHERLANDS", "AMSTERDAM")),
        CountryDef("IN", listOf("印度", "IN", "INDIA", "MUMBAI")),
        CountryDef("TR", listOf("土耳其", "TR", "TURKEY", "ISTANBUL", "伊斯坦布尔")),
        CountryDef("AR", listOf("阿根廷", "AR", "ARGENTINA")),
        CountryDef("MY", listOf("马来西亚", "MY", "MALAYSIA", "大马")),
        CountryDef("TH", listOf("泰国", "TH", "THAILAND", "BANGKOK", "曼谷")),
        CountryDef("PH", listOf("菲律宾", "PH", "PHILIPPINES")),
        CountryDef("VN", listOf("越南", "VN", "VIETNAM", "胡志明")),
        CountryDef("ID", listOf("印度尼西亚", "印尼", "ID", "INDONESIA", "雅加达")),
        CountryDef("BR", listOf("巴西", "BR", "BRAZIL")),
        CountryDef("IT", listOf("意大利", "IT", "ITALY", "MILAN", "米兰", "罗马")),
        CountryDef("ES", listOf("西班牙", "ES", "SPAIN", "MADRID", "马德里")),
        CountryDef("CH", listOf("瑞士", "CH", "SWITZERLAND", "ZURICH", "苏黎世")),
        CountryDef("SE", listOf("瑞典", "SE", "SWEDEN", "STOCKHOLM")),
        CountryDef("NO", listOf("挪威", "NO", "NORWAY")),
        CountryDef("IE", listOf("爱尔兰", "IE", "IRELAND")),
        CountryDef("PL", listOf("波兰", "PL", "POLAND", "WARSAW")),
        CountryDef("FI", listOf("芬兰", "FI", "FINLAND", "HELSINKI")),
        CountryDef("AT", listOf("奥地利", "AT", "AUSTRIA", "VIENNA")),
        CountryDef("BE", listOf("比利时", "BE", "BELGIUM")),
        CountryDef("DK", listOf("丹麦", "DK", "DENMARK")),
        CountryDef("PT", listOf("葡萄牙", "PT", "PORTUGAL")),
        CountryDef("ZA", listOf("南非", "ZA", "SOUTH AFRICA")),
        CountryDef("MX", listOf("墨西哥", "MX", "MEXICO")),
        CountryDef("AE", listOf("阿联酋", "迪拜", "AE", "UAE", "UNITED ARAB EMIRATES", "DUBAI")),
    )

    private val ASCII_BOUNDARY_CACHE = HashMap<String, Regex>()

    private fun asciiBoundaryRegex(code: String): Regex = ASCII_BOUNDARY_CACHE.getOrPut(code) {
        Regex("(?i)(^|[^A-Z])${Regex.escape(code)}([^A-Z]|$)")
    }

    private fun extractFlagIsoCodes(text: String): Set<String> {
        val result = HashSet<String>()
        var i = 0
        while (i + 3 < text.length) {
            val cp1 = Character.codePointAt(text, i)
            val c1 = Character.charCount(cp1)
            if (cp1 in 0x1F1E6..0x1F1FF && i + c1 < text.length) {
                val cp2 = Character.codePointAt(text, i + c1)
                val c2 = Character.charCount(cp2)
                if (cp2 in 0x1F1E6..0x1F1FF) {
                    val iso = "${('A'.code + (cp1 - 0x1F1E6)).toChar()}${('A'.code + (cp2 - 0x1F1E6)).toChar()}"
                    result.add(if (iso == "UK") "GB" else iso)
                    i += c1 + c2
                    continue
                }
            }
            i += c1
        }
        return result
    }

    private fun normalizeTokenIso(rawToken: String): String? {
        val flagIsos = extractFlagIsoCodes(rawToken)
        if (flagIsos.isNotEmpty()) return flagIsos.first()
        val upper = rawToken.trim().uppercase()
        if (upper.isEmpty()) return null
        if (upper == "UK") return "GB"
        for (def in COUNTRY_DEFS) {
            if (def.iso == upper || def.keywords.any { it.equals(upper, ignoreCase = true) }) {
                return def.iso
            }
        }
        if (upper.length == 2 && upper.all { it in 'A'..'Z' }) {
            return upper
        }
        return null
    }

    private fun parseCountryTokens(filter: String): List<String> {
        if (filter.isBlank()) return emptyList()
        return filter.split(Regex("[,;\\s|]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun matchesCountryTokens(name: String, tokens: List<String>): Boolean {
        val flagIsos = extractFlagIsoCodes(name)
        return tokens.any { rawToken ->
            val targetIso = normalizeTokenIso(rawToken)
            if (targetIso != null) {
                if (targetIso in flagIsos) return@any true
                val def = COUNTRY_DEFS.firstOrNull { it.iso == targetIso }
                if (def != null) {
                    def.keywords.any { kw ->
                        if (kw.length <= 3 && kw.all { it in 'A'..'Z' }) {
                            asciiBoundaryRegex(kw).containsMatchIn(name)
                        } else {
                            name.contains(kw, ignoreCase = true)
                        }
                    }
                } else {
                    asciiBoundaryRegex(targetIso).containsMatchIn(name)
                }
            } else {
                name.contains(rawToken, ignoreCase = true)
            }
        }
    }

    /**
     * Orders strategy members for cold-start latency strategies so healthy lowest-ping nodes
     * take index 0 before the first background URLTest probe completes.
     */
    fun sortForColdStart(entities: List<ProxyEntity>): List<ProxyEntity> {
        if (entities.size <= 1) return entities
        return entities.sortedWith(
            compareBy<ProxyEntity> { entity ->
                when {
                    entity.status == 1 && entity.ping > 0 -> 0
                    entity.status != 2 && entity.status != 3 && entity.status >= 0 && entity.ping > 0 -> 1
                    entity.status != 2 && entity.status != 3 && entity.status >= 0 -> 2
                    else -> 3
                }
            }.thenBy { entity ->
                if (entity.ping > 0 && entity.status != 2 && entity.status != 3 && entity.status >= 0) {
                    entity.ping
                } else {
                    Int.MAX_VALUE
                }
            },
        )
    }
}
