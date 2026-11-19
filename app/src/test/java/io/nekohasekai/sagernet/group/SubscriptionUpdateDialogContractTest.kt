package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionUpdateDialogContractTest {

    private class MockUserInterface : GroupManager.Interface {
        var successCalls = 0
        var dialogShownList = mutableListOf<String>()
        var failureCalls = 0

        override suspend fun confirm(message: String): Boolean = true
        override suspend fun alert(message: String) {}

        override suspend fun onUpdateSuccess(
            group: ProxyGroup,
            changed: Int,
            added: List<String>,
            updated: Map<String, String>,
            deleted: List<String>,
            duplicate: List<String>,
            byUser: Boolean,
            retained: List<String>
        ) {
            successCalls++
            // GroupInterfaceAdapter contract: If not byUser, do not display dialog or snackbar
            if (!byUser) return

            dialogShownList.add(group.displayName())
        }

        override suspend fun onUpdateFailure(group: ProxyGroup, message: String) {
            failureCalls++
        }
    }

    @Test
    fun testBackgroundAutoUpdateSuppressesDialogs() = runBlocking {
        val ui = MockUserInterface()
        val group = ProxyGroup(id = 1L, name = "MyAirport")

        // Case 1: Background auto-update runs (byUser = false) with changes
        ui.onUpdateSuccess(
            group = group,
            changed = 10,
            added = listOf("Node 1", "Node 2"),
            updated = emptyMap(),
            deleted = emptyList(),
            duplicate = emptyList(),
            byUser = false
        )

        assertEquals(1, ui.successCalls)
        // Background update must suppress dialogs/snackbars completely
        assertTrue(ui.dialogShownList.isEmpty())
    }

    @Test
    fun testUserManualUpdateTriggersDialog() = runBlocking {
        val ui = MockUserInterface()
        val group = ProxyGroup(id = 2L, name = "AirportVIP")

        // Case 2: User manually updates subscription (byUser = true)
        ui.onUpdateSuccess(
            group = group,
            changed = 5,
            added = listOf("Node 3"),
            updated = emptyMap(),
            deleted = emptyList(),
            duplicate = emptyList(),
            byUser = true
        )

        assertEquals(1, ui.successCalls)
        // Manual update preserves original modal diff dialog
        assertEquals(1, ui.dialogShownList.size)
        assertEquals("AirportVIP", ui.dialogShownList[0])
    }
}
