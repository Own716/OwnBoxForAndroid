package io.nekohasekai.sagernet.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.DeflaterOutputStream

class RuleSetLocalAndCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun buildBinarySrs(ruleCount: Int): ByteArray {
        val headerAndBody = ByteArrayOutputStream().apply {
            // SRS magic bytes + version(3)
            write(byteArrayOf(0x53, 0x52, 0x53, 0x03))
            DeflaterOutputStream(this).use { deflater ->
                deflater.write(ruleCount)
                if (ruleCount > 0) {
                    // Append minimal non-empty payload so populated SRS test resembles real binary payload
                    deflater.write(byteArrayOf(0x00, 0x01, 0x02))
                }
            }
        }
        return headerAndBody.toByteArray()
    }

    @Test
    fun emptySrsPlaceholderIsNotTreatedAsPopulatedCache() {
        val srsDir = tempFolder.newFolder("srs")
        val placeholderFile = File(srsDir, "geosite-cn.srs")
        placeholderFile.writeBytes(buildBinarySrs(ruleCount = 0))

        assertTrue(placeholderFile.length() > 0L)
        assertFalse(RuleSets.isPopulatedSrsCache(placeholderFile))
        assertNull(RuleSets.findCachedSrsFile(srsDir, "geosite:cn"))
    }

    @Test
    fun populatedCacheMatchesHyphenAndLegacyUnderscoreVariants() {
        val srsDir = tempFolder.newFolder("srs2")
        // Write empty placeholder under hyphen name, and populated cache under legacy underscore name
        File(srsDir, "geoip-cn.srs").writeBytes(buildBinarySrs(ruleCount = 0))
        val populatedUnderscore = File(srsDir, "geoip_cn.srs")
        populatedUnderscore.writeBytes(buildBinarySrs(ruleCount = 1))

        val found = RuleSets.findCachedSrsFile(srsDir, "geoip:cn")
        assertNotNull(found)
        assertEquals(populatedUnderscore.name, found?.name)
        assertTrue(RuleSets.isPopulatedSrsCache(populatedUnderscore))
    }

    @Test
    fun localRuleSetLookupSupportsBothRulesDirAndExternalFilesDir() {
        val rulesDir = tempFolder.newFolder("rulesets")
        val extDir = tempFolder.newFolder("external")
        val filesDir = tempFolder.newFolder("files")
        val dirs = listOf(rulesDir, extDir, filesDir)

        val customSrs = File(rulesDir, "my-custom.srs").apply { writeText("srs") }
        val customDb = File(extDir, "geoip-lite.db").apply { writeText("db") }
        val bothFileInRules = File(rulesDir, "shared.srs").apply { writeText("from-rules") }
        File(extDir, "shared.srs").apply { writeText("from-ext") }

        assertEquals(customSrs, RuleSetCatalog.findLocalRuleSetFile("local:my-custom.srs", dirs))
        assertEquals(customDb, RuleSetCatalog.findLocalRuleSetFile("local:geoip-lite.db", dirs))
        // Priority preserves rulesets/ first for existing user files
        assertEquals(bothFileInRules, RuleSetCatalog.findLocalRuleSetFile("local:shared.srs", dirs))
        assertEquals("local-geoip-lite", RuleSetCatalog.localRuleSetTag("local:geoip-lite.db"))
    }
}
