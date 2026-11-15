package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveOutboundTrackerTest {

    @Test
    fun testGetStrategyDisplayName() {
        val balancerLatency = ProxyEntity().apply {
            type = ProxyEntity.TYPE_BALANCER
            putBean(BalancerBean().apply {
                selectionMode = BalancerBean.SELECTION_LATENCY
                balanceEnabled = false
            })
        }
        assertEquals("最快最稳", ActiveOutboundTracker.getStrategyDisplayName(balancerLatency))

        val balancerPriority = ProxyEntity().apply {
            type = ProxyEntity.TYPE_BALANCER
            putBean(BalancerBean().apply {
                selectionMode = BalancerBean.SELECTION_PRIORITY
                balanceEnabled = false
            })
        }
        assertEquals("按优先级", ActiveOutboundTracker.getStrategyDisplayName(balancerPriority))

        val balancerDistributed = ProxyEntity().apply {
            type = ProxyEntity.TYPE_BALANCER
            putBean(BalancerBean().apply {
                selectionMode = BalancerBean.SELECTION_LATENCY
                balanceEnabled = true
            })
        }
        assertEquals("最快最稳 · 流量分配", ActiveOutboundTracker.getStrategyDisplayName(balancerDistributed))
    }

    @Test
    fun testFormatNotificationTitleSingleNode() {
        val singleNode = ProxyEntity().apply {
            id = 100L
            type = 0 // SS
            putBean(ShadowsocksBean().apply {
                name = "香港 01"
            })
        }
        val title = ActiveOutboundTracker.formatNotificationTitle(singleNode)
        assertEquals("香港 01", title)
    }

    @Test
    fun testFormatNotificationTitleBalancer() {
        val balancer = ProxyEntity().apply {
            id = 1L
            type = ProxyEntity.TYPE_BALANCER
            putBean(BalancerBean().apply {
                name = "自动选择"
                strategy = BalancerBean.STRATEGY_LEAST_PING
            })
        }

        val title = ActiveOutboundTracker.formatNotificationTitle(balancer)
        assertEquals("自动选择", title)

        ActiveOutboundTracker.updateActiveLeaf(101L, "日本 02")
        val leafDisplay = ActiveOutboundTracker.getActiveLeafNodeDisplay(balancer)
        assertEquals("日本 02", leafDisplay)
    }

    @Test
    fun testBuildNotificationTextsTemplate1_StrategyGroupShowGroupOn() {
        val balancer = ProxyEntity().apply {
            id = 1L
            type = ProxyEntity.TYPE_BALANCER
            putBean(BalancerBean().apply {
                name = "🇯🇵日本"
                strategy = BalancerBean.STRATEGY_LEAST_PING
            })
        }
        val bundle = ActiveOutboundTracker.buildNotificationTexts(
            profile = balancer,
            leafNode = "🇯🇵Japan 01",
            strategyName = "最低延迟",
            groupName = "机场订阅",
            showGroup = true,
            showDirectSpeed = true,
            proxySpeed = "↑1.2 KB/s ↓5.4 KB/s",
            directSpeed = "↑0 B/s ↓120 B/s"
        )
        assertEquals("🇯🇵日本 · 🇯🇵Japan 01", bundle.title)
        assertEquals("代理: ↑1.2 KB/s ↓5.4 KB/s", bundle.collapsedText)
        assertEquals("代理: ↑1.2 KB/s ↓5.4 KB/s\n直连: ↑0 B/s ↓120 B/s", bundle.bigText)
        assertTrue(!bundle.collapsedText.contains("当前节点"))
        assertTrue(!bundle.bigText.contains("当前节点"))
    }

    @Test
    fun testBuildNotificationTextsTemplate2_StrategyGroupShowGroupOff() {
        val balancer = ProxyEntity().apply {
            id = 1L
            type = ProxyEntity.TYPE_BALANCER
            putBean(BalancerBean().apply {
                name = "🇯🇵日本"
                strategy = BalancerBean.STRATEGY_LEAST_PING
            })
        }
        val bundle = ActiveOutboundTracker.buildNotificationTexts(
            profile = balancer,
            leafNode = "🇯🇵Japan 01",
            strategyName = "最低延迟",
            groupName = "机场订阅",
            showGroup = false,
            showDirectSpeed = true,
            proxySpeed = "↑1.2 KB/s ↓5.4 KB/s",
            directSpeed = "↑0 B/s ↓120 B/s"
        )
        assertEquals("🇯🇵Japan 01", bundle.title)
        assertEquals("代理: ↑1.2 KB/s ↓5.4 KB/s", bundle.collapsedText)
        assertEquals("代理: ↑1.2 KB/s ↓5.4 KB/s\n直连: ↑0 B/s ↓120 B/s", bundle.bigText)
        assertTrue(!bundle.bigText.contains("🇯🇵Japan 01")) // Leaf name not repeated in body
    }

    @Test
    fun testBuildNotificationTextsTemplate3_SingleNodeShowGroupOn() {
        val singleNode = ProxyEntity().apply {
            id = 100L
            type = 0
            putBean(ShadowsocksBean().apply {
                name = "🇸🇬新加坡·移联02"
            })
        }
        val bundle = ActiveOutboundTracker.buildNotificationTexts(
            profile = singleNode,
            leafNode = null,
            strategyName = "规则分流",
            groupName = "吹雪云",
            showGroup = true,
            showDirectSpeed = true,
            proxySpeed = "↑0 B/s ↓0 B/s",
            directSpeed = "↑10 B/s ↓20 B/s"
        )
        assertEquals("吹雪云 · 🇸🇬新加坡·移联02", bundle.title)
        assertEquals("代理: ↑0 B/s ↓0 B/s", bundle.collapsedText)
        assertEquals("代理: ↑0 B/s ↓0 B/s\n直连: ↑10 B/s ↓20 B/s", bundle.bigText)
    }

    @Test
    fun testBuildNotificationTextsTemplate4_SingleNodeShowGroupOff() {
        val singleNode = ProxyEntity().apply {
            id = 100L
            type = 0
            putBean(ShadowsocksBean().apply {
                name = "🇸🇬新加坡·移联02"
            })
        }
        val bundle = ActiveOutboundTracker.buildNotificationTexts(
            profile = singleNode,
            leafNode = null,
            strategyName = "规则分流",
            groupName = "吹雪云",
            showGroup = false,
            showDirectSpeed = false,
            proxySpeed = "↑0 B/s ↓0 B/s",
            directSpeed = "↑0 B/s ↓0 B/s"
        )
        assertEquals("🇸🇬新加坡·移联02", bundle.title)
        assertEquals("代理: ↑0 B/s ↓0 B/s", bundle.collapsedText)
        assertEquals("代理: ↑0 B/s ↓0 B/s", bundle.bigText)
    }

    @Test
    fun testBalancerDisplayMethods() {
        val bean = BalancerBean().apply {
            proxies = arrayListOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L)
            strategy = BalancerBean.STRATEGY_LEAST_PING
        }
        assertEquals("节点 8", bean.displayNodeOnly())
        assertEquals("节点 8 · 🇯🇵日本·移联 02", bean.displayWithNodeName("🇯🇵日本·移联 02"))

        val groupBean = BalancerBean().apply {
            balancerType = BalancerBean.TYPE_GROUP
            targetGroupIds = arrayListOf(10L, 20L)
        }
        assertEquals("分组 2", groupBean.displayNodeOnly())
        assertEquals("分组 2 · 🇯🇵日本", groupBean.displayWithNodeName("🇯🇵日本"))
    }

    @Test
    fun testReset() {
        ActiveOutboundTracker.reset()
        assertEquals(0L, ActiveOutboundTracker.activeLeafProfileId)
        assertEquals("", ActiveOutboundTracker.activeLeafProfileName)
    }
}
