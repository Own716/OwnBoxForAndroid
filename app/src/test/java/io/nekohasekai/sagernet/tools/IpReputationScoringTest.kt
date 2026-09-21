package io.nekohasekai.sagernet.tools

import io.nekohasekai.sagernet.tools.ip.IpReputationManager
import io.nekohasekai.sagernet.tools.ip.ReputationLevel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IpReputationScoringTest {

    @Test
    fun pureResidentialIpProducesHighScoreAndPureLevel() {
        val json1 = JSONObject().apply {
            put("query", "73.189.45.12")
            put("country", "United States")
            put("countryCode", "US")
            put("city", "San Francisco")
            put("isp", "Comcast Cable")
            put("org", "Comcast IP Services")
            put("as", "AS7922 COMCAST-7922")
            put("hosting", false)
            put("proxy", false)
            put("mobile", false)
        }

        val json2 = JSONObject().apply {
            put("ip", "73.189.45.12")
            put("is_datacenter", false)
            put("is_proxy", false)
            put("is_vpn", false)
            put("is_tor", false)
            put("is_abuser", false)
            put("is_mobile", false)
            put("company", JSONObject().apply { put("type", "isp") })
            put("asn", JSONObject().apply { put("type", "isp") })
        }

        val report = IpReputationManager.synthesizeReputation(json1, json2)

        assertEquals("73.189.45.12", report.ip)
        assertTrue("Pure residential score must be >= 85, was ${report.score}", report.score >= 85)
        assertEquals(ReputationLevel.PURE, report.level)
        assertTrue("Human traffic ratio must be high (>= 75%), was ${report.humanTrafficRatio}%", report.humanTrafficRatio >= 75)
        assertTrue("Datacenter probability must be low (<= 15%), was ${report.datacenterProbability}%", report.datacenterProbability <= 15)
        assertTrue("Confidence must be high without conflict (>= 90%), was ${report.confidenceScore}%", report.confidenceScore >= 90)
        assertFalse("There should be no conflict", report.hasConflict)
    }

    @Test
    fun datacenterHostingProducesLowScoreAndHighRiskLevel() {
        val json1 = JSONObject().apply {
            put("query", "104.21.56.78")
            put("country", "United States")
            put("countryCode", "US")
            put("isp", "Cloudflare, Inc.")
            put("org", "Cloudflare")
            put("as", "AS13335 CLOUDFLARENET")
            put("hosting", true)
            put("proxy", false)
        }

        val json2 = JSONObject().apply {
            put("ip", "104.21.56.78")
            put("is_datacenter", true)
            put("is_proxy", false)
            put("company", JSONObject().apply { put("type", "hosting") })
        }

        val report = IpReputationManager.synthesizeReputation(json1, json2)

        assertTrue("Datacenter score must be <= 40, was ${report.score}", report.score <= 40)
        assertEquals(ReputationLevel.HIGH_RISK, report.level)
        assertTrue("Datacenter probability must be >= 85%, was ${report.datacenterProbability}%", report.datacenterProbability >= 85)
    }

    @Test
    fun dataSourceConflictIsDetectedAndPenalizesConfidence() {
        // ip-api says hosting=false, but ipapi.is identifies datacenter=true
        val json1 = JSONObject().apply {
            put("query", "45.76.12.34")
            put("hosting", false)
            put("proxy", false)
        }

        val json2 = JSONObject().apply {
            put("ip", "45.76.12.34")
            put("is_datacenter", true)
            put("company", JSONObject().apply { put("type", "hosting") })
        }

        val report = IpReputationManager.synthesizeReputation(json1, json2)

        assertTrue("Conflict flag must be set", report.hasConflict)
        assertNotNull("Conflict description must be provided", report.conflictDescription)
        assertTrue("Confidence must be penalized on conflict (<= 75%), was ${report.confidenceScore}%", report.confidenceScore <= 75)
        assertTrue("Datacenter probability must rise on conflict (>= 70%), was ${report.datacenterProbability}%", report.datacenterProbability >= 70)
    }

    @Test
    fun abuserOrTorDrasticallyLimitsMaximumScore() {
        val json1 = JSONObject().apply {
            put("query", "185.220.101.5")
            put("hosting", true)
            put("proxy", true)
        }

        val json2 = JSONObject().apply {
            put("ip", "185.220.101.5")
            put("is_datacenter", true)
            put("is_tor", true)
            put("is_abuser", true)
        }

        val report = IpReputationManager.synthesizeReputation(json1, json2)

        assertTrue("Tor / Abuser score must be <= 25, was ${report.score}", report.score <= 25)
        assertEquals(ReputationLevel.HIGH_RISK, report.level)
    }
}
