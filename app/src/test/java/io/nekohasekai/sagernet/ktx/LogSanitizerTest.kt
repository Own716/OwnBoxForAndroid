package io.nekohasekai.sagernet.ktx

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSanitizerTest {

    private val CANARY_PASSWORD = "CANARY_SECRET_FOR_TEST_P@ssw0rd123"
    private val CANARY_UUID = "CANARY_UUID_12345678-1234-1234-1234-123456789abc"
    private val CANARY_PRIVATE_KEY = "CANARY_WG_KEY_c29tZXJhbmRvbXByaXZhdGVrZXlmb3J3ZzEyMzQ1Njc4"

    @Test
    fun testJsonSensitiveFieldsAreMasked() {
        val rawJson = """
            {
              "type": "vless",
              "server": "example.com",
              "server_port": 443,
              "uuid": "$CANARY_UUID",
              "password": "$CANARY_PASSWORD",
              "private_key": "$CANARY_PRIVATE_KEY",
              "secret": "CANARY_SECRET_AUTH_TOKEN"
            }
        """.trimIndent()

        val sanitized = LogSanitizer.sanitize(rawJson)

        assertFalse("Canary password must not appear in sanitized log", sanitized.contains(CANARY_PASSWORD))
        assertFalse("Canary UUID must not appear in sanitized log", sanitized.contains(CANARY_UUID))
        assertFalse("Canary private key must not appear in sanitized log", sanitized.contains(CANARY_PRIVATE_KEY))
        assertFalse("Canary secret must not appear in sanitized log", sanitized.contains("CANARY_SECRET_AUTH_TOKEN"))
        assertTrue("Password field must be masked with placeholder", sanitized.contains("******"))
    }

    @Test
    fun testUrlUserInfoPasswordIsMasked() {
        val url = "https://myuser:$CANARY_PASSWORD@proxy.example.com:8443/subscribe?token=secret"
        val sanitized = LogSanitizer.sanitize(url)

        assertFalse("Password inside URL must not leak", sanitized.contains(CANARY_PASSWORD))
        assertTrue("URL username should remain for debugging", sanitized.contains("https://myuser:******@"))
    }

    @Test
    fun testAuthHeadersAreMasked() {
        val headerLog = "Sending request with Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIi"
        val sanitized = LogSanitizer.sanitize(headerLog)

        assertFalse("Bearer token must not leak", sanitized.contains("eyJhbGciOiJIUzI1Ni"))
        assertTrue("Authorization header placeholder must be present", sanitized.contains("Authorization: Bearer ******"))
    }

    @Test
    fun testWireGuardPrivateKeyIsMasked() {
        val wgConfig = "[Interface]\nPrivateKey = $CANARY_PRIVATE_KEY\nAddress = 10.0.0.2/32"
        val sanitized = LogSanitizer.sanitize(wgConfig)

        assertFalse("WireGuard private key must not leak", sanitized.contains(CANARY_PRIVATE_KEY))
        assertTrue("WireGuard private key must be masked", sanitized.contains("PrivateKey = ******"))
    }

    @Test
    fun testConfigSummaryDoesNotExposeSecrets() {
        val singBoxConfig = """
            {
              "outbounds": [
                {
                  "type": "shadowsocks",
                  "tag": "ss-out",
                  "server": "1.2.3.4",
                  "password": "$CANARY_PASSWORD"
                },
                {
                  "type": "vmess",
                  "tag": "vmess-out",
                  "uuid": "$CANARY_UUID"
                }
              ],
              "route": { "rules": [] },
              "dns": { "servers": [] }
            }
        """.trimIndent()

        val summary = LogSanitizer.summarizeConfig(singBoxConfig)

        assertFalse("Summary must never contain canary password", summary.contains(CANARY_PASSWORD))
        assertFalse("Summary must never contain canary UUID", summary.contains(CANARY_UUID))
        assertTrue("Summary should contain outbound count", summary.contains("outbounds=2"))
        assertTrue("Summary should list protocol types", summary.contains("shadowsocks") && summary.contains("vmess"))
    }
}
