package io.nekohasekai.sagernet.tools.ip

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.USER_AGENT
import io.nekohasekai.sagernet.ktx.tryProxyOutbound
import io.nekohasekai.sagernet.utils.LandingIpManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject

object IpReputationManager {

    private val cloudKeywords = listOf(
        "Cloudflare" to "Cloudflare Anycast",
        "Amazon" to "AWS (Amazon Web Services)",
        "AWS" to "AWS (Amazon Web Services)",
        "DigitalOcean" to "DigitalOcean",
        "Google" to "Google Cloud Platform",
        "Microsoft" to "Microsoft Azure",
        "Azure" to "Microsoft Azure",
        "Alibaba" to "阿里云 (Alibaba Cloud)",
        "Aliyun" to "阿里云 (Alibaba Cloud)",
        "Tencent" to "腾讯云 (Tencent Cloud)",
        "Hetzner" to "Hetzner Online",
        "OVH" to "OVHcloud",
        "Linode" to "Linode / Akamai",
        "Akamai" to "Akamai",
        "Vultr" to "Vultr / Choopa",
        "Choopa" to "Vultr / Choopa",
        "Oracle" to "Oracle Cloud",
        "Hostinger" to "Hostinger",
        "Contabo" to "Contabo",
        "Datacamp" to "DataCamp / CDN77",
        "Leaseweb" to "Leaseweb",
        "Fastly" to "Fastly",
        "M247" to "M247 Ltd",
        "Zenlayer" to "Zenlayer",
    )

    private fun createHttpClient(): libcore.HTTPClient {
        return Libcore.newHttpClient().apply {
            modernTLS()
            val mixedPort = DataStore.mixedPort
            if (mixedPort > 0) {
                trySocks5(mixedPort.toInt(), "", "")
            } else {
                tryProxyOutbound()
            }
        }
    }

    suspend fun queryIpReputation(): Result<IpReputationReport> = withContext(Dispatchers.IO) {
        val ua = USER_AGENT.takeIf { it.isNotBlank() }
            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

        val source1Deferred = async { fetchIpApi(ua) }
        val source2Deferred = async { fetchIpApiIs(ua) }

        val res1 = source1Deferred.await()
        val res2 = source2Deferred.await()

        if (res1.isFailure && res2.isFailure) {
            val err1 = res1.exceptionOrNull()?.message ?: "ip-api.com 无响应"
            val err2 = res2.exceptionOrNull()?.message ?: "api.ipapi.is 无响应"
            return@withContext Result.failure(Exception("双数据源均请求失败: $err1; $err2"))
        }

        val json1 = res1.getOrNull()
        val json2 = res2.getOrNull()

        val report = synthesizeReputation(json1, json2)
        Result.success(report)
    }

    private fun fetchIpApi(ua: String): Result<JSONObject> {
        var client: libcore.HTTPClient? = null
        val start = System.currentTimeMillis()
        return try {
            client = createHttpClient()
            val req = client.newRequest().apply {
                setURL("http://ip-api.com/json/?fields=status,message,country,countryCode,regionName,city,isp,org,as,asname,reverse,mobile,proxy,hosting,query")
                setUserAgent(ua)
            }
            val resp = req.execute()
            val body = Util.getStringBox(resp.contentString)
            val cost = System.currentTimeMillis() - start
            val json = JSONObject(body)
            if (json.optString("status") == "success") {
                json.put("_cost_ms", cost)
                Result.success(json)
            } else {
                Result.failure(Exception(json.optString("message", "IP 接口错误")))
            }
        } catch (e: Throwable) {
            Result.failure(e)
        } finally {
            runCatching { client?.close() }
        }
    }

    private fun fetchIpApiIs(ua: String): Result<JSONObject> {
        var client: libcore.HTTPClient? = null
        val start = System.currentTimeMillis()
        return try {
            client = createHttpClient()
            val req = client.newRequest().apply {
                setURL("https://api.ipapi.is/")
                setUserAgent(ua)
            }
            val resp = req.execute()
            val body = Util.getStringBox(resp.contentString)
            val cost = System.currentTimeMillis() - start
            val json = JSONObject(body)
            val ip = json.optString("ip")
            if (ip.isNotBlank()) {
                json.put("_cost_ms", cost)
                Result.success(json)
            } else {
                Result.failure(Exception("api.ipapi.is 返回空响应"))
            }
        } catch (e: Throwable) {
            Result.failure(e)
        } finally {
            runCatching { client?.close() }
        }
    }

    fun synthesizeReputation(json1: JSONObject?, json2: JSONObject?): IpReputationReport {
        val ip = json1?.optString("query")?.ifBlank { null }
            ?: json2?.optString("ip")?.ifBlank { null }
            ?: "0.0.0.0"

        val country = json1?.optString("country")?.ifBlank { null }
            ?: json2?.optJSONObject("location")?.optString("country")?.ifBlank { null }
            ?: "未知"

        val countryCode = json1?.optString("countryCode")?.ifBlank { null }
            ?: json2?.optJSONObject("location")?.optString("country_code")?.ifBlank { null }
            ?: ""

        val flag = LandingIpManager.countryCodeToFlagEmoji(countryCode)

        val city = json1?.optString("city")?.ifBlank { null }
            ?: json2?.optJSONObject("location")?.optString("city")?.ifBlank { null }
            ?: "未知城市"

        val region = json1?.optString("regionName")?.ifBlank { null }
            ?: json2?.optJSONObject("location")?.optString("state")?.ifBlank { null }
            ?: ""

        val isp = json1?.optString("isp")?.ifBlank { null }
            ?: json2?.optJSONObject("asn")?.optString("org")?.ifBlank { null }
            ?: json2?.optJSONObject("company")?.optString("name")?.ifBlank { null }
            ?: "未知运营商"

        val org = json1?.optString("org")?.ifBlank { null }
            ?: json2?.optJSONObject("company")?.optString("name")?.ifBlank { null }
            ?: isp

        val asn = json1?.optString("as")?.ifBlank { null }
            ?: json2?.optJSONObject("asn")?.let { asnObj ->
                val num = asnObj.optInt("asn", 0)
                val orgName = asnObj.optString("org", "")
                if (num > 0) "AS$num $orgName".trim() else orgName
            }?.ifBlank { null }
            ?: "未知 ASN"

        val reverseDns = json1?.optString("reverse")?.ifBlank { null }
            ?: json2?.optJSONObject("asn")?.optString("descr")?.ifBlank { null }
            ?: "无反向解析记录"

        // --- Tri-State Extraction ---
        // Source 1 (ip-api)
        val s1Hosting = if (json1 != null && json1.has("hosting")) TriState.fromBoolean(json1.optBoolean("hosting")) else TriState.UNKNOWN
        val s1Proxy = if (json1 != null && json1.has("proxy")) TriState.fromBoolean(json1.optBoolean("proxy")) else TriState.UNKNOWN
        val s1Mobile = if (json1 != null && json1.has("mobile")) TriState.fromBoolean(json1.optBoolean("mobile")) else TriState.UNKNOWN

        // Source 2 (ipapi.is)
        val companyType = json2?.optJSONObject("company")?.optString("type")?.lowercase()
        val asnType = json2?.optJSONObject("asn")?.optString("type")?.lowercase()
        val hasDcObj = json2?.has("datacenter") == true

        val s2DcRaw = if (json2 != null && json2.has("is_datacenter")) json2.optBoolean("is_datacenter") else null
        val s2Datacenter = when {
            s2DcRaw == true || companyType == "hosting" || asnType == "hosting" || hasDcObj -> TriState.TRUE
            s2DcRaw == false -> TriState.FALSE
            else -> TriState.UNKNOWN
        }

        val s2Proxy = if (json2 != null && json2.has("is_proxy")) TriState.fromBoolean(json2.optBoolean("is_proxy")) else TriState.UNKNOWN
        val s2Vpn = if (json2 != null && json2.has("is_vpn")) TriState.fromBoolean(json2.optBoolean("is_vpn")) else TriState.UNKNOWN
        val s2Tor = if (json2 != null && json2.has("is_tor")) TriState.fromBoolean(json2.optBoolean("is_tor")) else TriState.UNKNOWN
        val s2Abuser = if (json2 != null && json2.has("is_abuser")) TriState.fromBoolean(json2.optBoolean("is_abuser")) else TriState.UNKNOWN
        val s2Mobile = if (json2 != null && json2.has("is_mobile")) TriState.fromBoolean(json2.optBoolean("is_mobile")) else TriState.UNKNOWN

        // Cloud provider signature match
        val combinedSearch = "$asn $org $isp".uppercase()
        var matchedCloud: String? = null
        for ((keyword, brand) in cloudKeywords) {
            if (combinedSearch.contains(keyword.uppercase())) {
                matchedCloud = brand
                break
            }
        }

        // --- Conflict Detection ---
        var hasConflict = false
        var conflictReason: String? = null

        if (json1 != null && json2 != null) {
            // Check datacenter / hosting disagreement
            if (s1Hosting == TriState.TRUE && s2Datacenter == TriState.FALSE) {
                hasConflict = true
                conflictReason = "数据源存在分歧：ip-api.com 判定为机房托管，而 api.ipapi.is 判定为非数据中心（系统采取严格风控标准介入）"
            } else if (s1Hosting == TriState.FALSE && s2Datacenter == TriState.TRUE) {
                hasConflict = true
                conflictReason = "数据源存在分歧：ip-api.com 未标记机房，但 api.ipapi.is 识别到机房数据中心特征（系统已上调机房概率）"
            } else if (s1Proxy == TriState.TRUE && (s2Proxy == TriState.FALSE && s2Vpn == TriState.FALSE)) {
                hasConflict = true
                conflictReason = "数据源存在分歧：ip-api.com 标记为公共代理，而 api.ipapi.is 未发现 VPN 标记"
            }
        }

        // --- Synthesis & Dimension Scoring ---
        val finalHosting = when {
            matchedCloud != null -> TriState.TRUE
            s1Hosting == TriState.TRUE || s2Datacenter == TriState.TRUE -> TriState.TRUE
            s1Hosting == TriState.FALSE && s2Datacenter == TriState.FALSE -> TriState.FALSE
            s1Hosting == TriState.FALSE || s2Datacenter == TriState.FALSE -> TriState.FALSE
            else -> TriState.UNKNOWN
        }

        val finalProxy = when {
            s1Proxy == TriState.TRUE || s2Proxy == TriState.TRUE || s2Vpn == TriState.TRUE -> TriState.TRUE
            s1Proxy == TriState.FALSE && s2Proxy == TriState.FALSE -> TriState.FALSE
            s1Proxy == TriState.FALSE || s2Proxy == TriState.FALSE -> TriState.FALSE
            else -> TriState.UNKNOWN
        }

        val finalMobile = when {
            s1Mobile == TriState.TRUE || s2Mobile == TriState.TRUE -> TriState.TRUE
            s1Mobile == TriState.FALSE && s2Mobile == TriState.FALSE -> TriState.FALSE
            s1Mobile == TriState.FALSE || s2Mobile == TriState.FALSE -> TriState.FALSE
            else -> TriState.UNKNOWN
        }

        // Dimension 1: Datacenter Probability (0 - 100)
        val datacenterProbability = when {
            matchedCloud != null -> 95
            s1Hosting == TriState.TRUE && s2Datacenter == TriState.TRUE -> 98
            s1Hosting == TriState.TRUE || s2Datacenter == TriState.TRUE -> if (hasConflict) 75 else 85
            s1Hosting == TriState.FALSE && s2Datacenter == TriState.FALSE -> 5
            s1Hosting == TriState.FALSE || s2Datacenter == TriState.FALSE -> 25
            else -> 50
        }

        // Dimension 2: Proxy / VPN Likelihood (0 - 100)
        val proxyVpnLikelihood = when {
            s2Tor == TriState.TRUE -> 99
            s2Vpn == TriState.TRUE -> 90
            s1Proxy == TriState.TRUE && s2Proxy == TriState.TRUE -> 92
            s1Proxy == TriState.TRUE || s2Proxy == TriState.TRUE -> 70
            s1Proxy == TriState.FALSE && s2Proxy == TriState.FALSE && s2Vpn == TriState.FALSE -> 4
            s1Proxy == TriState.FALSE || s2Proxy == TriState.FALSE -> 15
            else -> 35
        }

        // Dimension 3: Abuse Record (0 - 100)
        val abuseRecord = when {
            s2Abuser == TriState.TRUE -> 90
            s2Abuser == TriState.FALSE -> 3
            else -> 20
        }

        // Dimension 4: Automation Tendency (0 - 100)
        val automationTendency = ((datacenterProbability * 0.65) + (proxyVpnLikelihood * 0.35)).toInt().coerceIn(5, 95)

        // Dimension 5: Residential Score (0 - 100)
        val residentialScore = when {
            finalMobile == TriState.TRUE -> 88
            datacenterProbability >= 85 -> (100 - datacenterProbability).coerceIn(2, 15)
            datacenterProbability >= 70 -> (100 - datacenterProbability).coerceIn(15, 35)
            datacenterProbability <= 10 && proxyVpnLikelihood <= 10 -> 96
            datacenterProbability <= 25 && proxyVpnLikelihood <= 25 -> 80
            else -> (100 - datacenterProbability).coerceIn(25, 75)
        }

        // Dimension 6: Confidence Score (0 - 100)
        val confidenceScore = when {
            json1 != null && json2 != null && !hasConflict -> 96
            json1 != null && json2 != null && hasConflict -> 68
            json1 != null || json2 != null -> 58
            else -> 10
        }

        // Overall Score (0 - 100): Pure Residential = 85..100, Low Risk = 65..84, Medium = 40..64, High = 0..39
        val weightedScore = (
            residentialScore * 0.50 +
            (100 - datacenterProbability) * 0.25 +
            (100 - proxyVpnLikelihood) * 0.15 +
            (100 - abuseRecord) * 0.10
        ).toInt()

        val finalScore = when {
            s2Abuser == TriState.TRUE -> weightedScore.coerceAtMost(25)
            s2Tor == TriState.TRUE -> weightedScore.coerceAtMost(20)
            datacenterProbability >= 90 -> weightedScore.coerceAtMost(38)
            datacenterProbability >= 75 -> weightedScore.coerceAtMost(58)
            else -> weightedScore
        }.coerceIn(0, 100)

        val level = when {
            finalScore >= 85 -> ReputationLevel.PURE
            finalScore >= 65 -> ReputationLevel.LOW_RISK
            finalScore >= 40 -> ReputationLevel.MEDIUM_RISK
            finalScore > 0 -> ReputationLevel.HIGH_RISK
            else -> ReputationLevel.UNKNOWN
        }

        val humanTrafficRatio = ((residentialScore * 0.70) + (100 - automationTendency) * 0.30).toInt().coerceIn(5, 95)
        val machineTrafficRatio = 100 - humanTrafficRatio

        // Scenario Suitability Assessment
        val scenarios = listOf(
            ScenarioSuitability(
                id = "web",
                name = "日常网页与搜索",
                status = when {
                    finalScore >= 65 -> ScenarioStatus.EXCELLENT
                    finalScore >= 40 -> ScenarioStatus.GOOD
                    else -> ScenarioStatus.CAUTION
                },
                reason = if (finalScore >= 65) "访问通常流畅，极少触发人机验证" else "机房或风控 IP 在部分严控站点可能出现验证码",
            ),
            ScenarioSuitability(
                id = "streaming",
                name = "流媒体影音播放",
                status = when {
                    proxyVpnLikelihood >= 80 || s2Tor == TriState.TRUE -> ScenarioStatus.RISKY
                    datacenterProbability >= 75 -> ScenarioStatus.CAUTION
                    finalScore >= 75 -> ScenarioStatus.EXCELLENT
                    else -> ScenarioStatus.GOOD
                },
                reason = when {
                    proxyVpnLikelihood >= 80 -> "代理特征明显，易被 Netflix/Disney+ 等封禁"
                    datacenterProbability >= 75 -> "机房出口易受流媒体版权限制，可能仅支持自制剧"
                    else -> "原生纯净出口，流媒体版权解锁率高"
                },
            ),
            ScenarioSuitability(
                id = "ai",
                name = "AI 对话与学术",
                status = when {
                    proxyVpnLikelihood >= 85 || s2Tor == TriState.TRUE -> ScenarioStatus.RISKY
                    datacenterProbability >= 75 -> ScenarioStatus.CAUTION
                    finalScore >= 75 -> ScenarioStatus.EXCELLENT
                    else -> ScenarioStatus.GOOD
                },
                reason = when {
                    proxyVpnLikelihood >= 85 -> "易触发 Cloudflare 403 阻断或限制对话"
                    datacenterProbability >= 75 -> "机房 IP 易遇 Cloudflare 验证码，建议保持节点固定"
                    else -> "住宅属性极佳，ChatGPT / Claude 风控通过率高"
                },
            ),
            ScenarioSuitability(
                id = "finance",
                name = "账号注册与金融支付",
                status = when {
                    finalScore >= 80 -> ScenarioStatus.EXCELLENT
                    finalScore >= 60 -> ScenarioStatus.GOOD
                    else -> ScenarioStatus.RISKY
                },
                reason = if (finalScore >= 80) "极低欺诈风险评级，适合账号注册与敏感支付" else "高风控特征 IP 易触发账号异常封禁或支付拦截",
            ),
        )

        // Data Source Transparency Status
        val sourceStatuses = mutableListOf<IpDataSourceStatus>()
        if (json1 != null) {
            val cost = json1.optLong("_cost_ms", 0L)
            sourceStatuses.add(
                IpDataSourceStatus(
                    sourceName = "ip-api.com",
                    isSuccess = true,
                    latencyMs = cost,
                    summary = "托管(hosting)=${if (s1Hosting.isTrue) "是" else "否"}，代理(proxy)=${if (s1Proxy.isTrue) "是" else "否"}",
                    rawDetails = "ISP: $isp | Org: $org",
                ),
            )
        } else {
            sourceStatuses.add(
                IpDataSourceStatus(
                    sourceName = "ip-api.com",
                    isSuccess = false,
                    latencyMs = 0L,
                    summary = "请求超时或接口无响应",
                ),
            )
        }

        if (json2 != null) {
            val cost = json2.optLong("_cost_ms", 0L)
            sourceStatuses.add(
                IpDataSourceStatus(
                    sourceName = "api.ipapi.is",
                    isSuccess = true,
                    latencyMs = cost,
                    summary = "机房(datacenter)=${if (s2Datacenter.isTrue) "是" else "否"}，VPN=${if (s2Vpn.isTrue) "是" else "否"}，滥用=${if (s2Abuser.isTrue) "是" else "否"}",
                    rawDetails = "Company Type: ${companyType ?: "unknown"} | ASN Type: ${asnType ?: "unknown"}",
                ),
            )
        } else {
            sourceStatuses.add(
                IpDataSourceStatus(
                    sourceName = "api.ipapi.is",
                    isSuccess = false,
                    latencyMs = 0L,
                    summary = "请求超时或接口无响应",
                ),
            )
        }

        return IpReputationReport(
            ip = ip,
            country = country,
            countryCode = countryCode,
            flag = flag,
            city = city,
            region = region,
            isp = isp,
            org = org,
            asn = asn,
            reverseDns = reverseDns,
            score = finalScore,
            level = level,
            humanTrafficRatio = humanTrafficRatio,
            machineTrafficRatio = machineTrafficRatio,
            residentialScore = residentialScore,
            datacenterProbability = datacenterProbability,
            proxyVpnLikelihood = proxyVpnLikelihood,
            abuseRecord = abuseRecord,
            automationTendency = automationTendency,
            confidenceScore = confidenceScore,
            isHosting = finalHosting,
            isProxy = finalProxy,
            isVpn = s2Vpn,
            isTor = s2Tor,
            isAbuser = s2Abuser,
            isMobile = finalMobile,
            scenarios = scenarios,
            sourceStatuses = sourceStatuses,
            hasConflict = hasConflict,
            conflictDescription = conflictReason,
        )
    }
}
