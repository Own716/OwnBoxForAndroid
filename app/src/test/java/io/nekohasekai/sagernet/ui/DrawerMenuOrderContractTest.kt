package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DrawerMenuOrderContractTest {

    private fun sourceFile(relative: String): File {
        return sequenceOf(File(relative), File("app", relative), File("../app", relative))
            .firstOrNull { it.exists() } ?: File(relative)
    }

    @Test
    fun drawerMenu_ordersMiscAndAboutItemsAsExpected() {
        val xml = sourceFile("src/main/res/menu/main_drawer_menu.xml").readText()
        val expectedOrder = listOf(
            "@+id/nav_dashboard",
            "@+id/nav_diagnostics",
            "@+id/nav_connections",
            "@+id/nav_live_graphs",
            "@+id/nav_tools",
            "@+id/nav_logcat",
            "@+id/nav_faq",
            "@+id/nav_docs",
        )

        val positions = expectedOrder.map { token ->
            val idx = xml.indexOf(token)
            assertTrue("main_drawer_menu.xml must declare $token", idx >= 0)
            token to idx
        }

        for (i in 0 until positions.size - 1) {
            val (firstToken, firstPos) = positions[i]
            val (secondToken, secondPos) = positions[i + 1]
            assertTrue(
                "$firstToken must appear before $secondToken in main_drawer_menu.xml",
                firstPos < secondPos
            )
        }

        val dashboardItemBlock = xml
            .substringAfter("@+id/nav_dashboard")
            .substringBefore("/>")
        assertTrue(
            "nav_dashboard must default to android:visible=\"false\" to avoid startup flash",
            dashboardItemBlock.contains("android:visible=\"false\"")
        )
    }
}
