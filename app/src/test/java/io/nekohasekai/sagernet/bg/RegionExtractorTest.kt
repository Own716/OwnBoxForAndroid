package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.utils.RegionExtractor
import org.junit.Assert.assertEquals
import org.junit.Test

class RegionExtractorTest {

    @Test
    fun testRegionExtraction() {
        assertEquals("日本", RegionExtractor.extractRegionName("🇯🇵 日本 01 - 专线"))
        assertEquals("日本", RegionExtractor.extractRegionName("Japan Tokyo BGP"))
        assertEquals("日本", RegionExtractor.extractRegionName("JP-01"))
        assertEquals("新加坡", RegionExtractor.extractRegionName("🇸🇬 新加坡 02 狮城"))
        assertEquals("美国", RegionExtractor.extractRegionName("🇺🇸 美国 洛杉矶 Premium"))
        assertEquals("香港", RegionExtractor.extractRegionName("🇭🇰 香港 HK-05"))
        assertEquals("台湾", RegionExtractor.extractRegionName("🇹🇼 台湾 台北 01"))
        assertEquals("韩国", RegionExtractor.extractRegionName("KR 韩国 首尔 02"))
        assertEquals("德国", RegionExtractor.extractRegionName("DE 德国 法兰克福"))
        assertEquals("英国", RegionExtractor.extractRegionName("UK 英国 伦敦"))
    }

    @Test
    fun testExtractRegionWithGeoFallback() {
        assertEquals("日本", RegionExtractor.extractRegion("Custom Node 1", "Japan"))
        assertEquals("新加坡", RegionExtractor.extractRegion("SG Node", "Singapore"))
        assertEquals("美国", RegionExtractor.extractRegion("US Node", ""))
        assertEquals("香港", RegionExtractor.extractRegion("🇭🇰 HK Node", null))
    }
}
