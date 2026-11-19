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
            // GroupInterfaceAdapter contract:
            // 1. If not byUser (background auto update / app open check), suppress all dialogs completely
            if (!byUser) return

            // 2. If byUser (manual update / update all), always show dialog for each subscription
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
    fun testUserManualUpdateTriggersDialogEvenWithoutChanges() = runBlocking {
        val ui = MockUserInterface()
        val group = ProxyGroup(id = 2L, name = "AirportVIP")

        // Case 2: User manually updates subscription without changes (changed = 0)
        ui.onUpdateSuccess(
            group = group,
            changed = 0,
            added = emptyList(),
            updated = emptyMap(),
            deleted = emptyList(),
            duplicate = emptyList(),
            byUser = true
        )

        assertEquals(1, ui.successCalls)
        // Manual update MUST display dialog to confirm result to user
        assertEquals(1, ui.dialogShownList.size)
        assertEquals("AirportVIP", ui.dialogShownList[0])
    }

    @Test
    fun testManualUpdateAllTriggersIndividualDialogsForEachSubscription() = runBlocking {
        val ui = MockUserInterface()
        val groups = listOf(
            ProxyGroup(id = 1L, name = "Sub 1"),
            ProxyGroup(id = 2L, name = "Sub 2"),
            ProxyGroup(id = 3L, name = "Sub 3")
        )

        // Case 3: User manually clicks "Update All", every subscription triggers its own dialog
        groups.forEach { group ->
            ui.onUpdateSuccess(
                group = group,
                changed = 2,
                added = listOf("NewNode"),
                updated = emptyMap(),
                deleted = emptyList(),
                duplicate = emptyList(),
                byUser = true
            )
        }

        assertEquals(3, ui.successCalls)
        assertEquals(3, ui.dialogShownList.size)
        assertEquals("Sub 1", ui.dialogShownList[0])
        assertEquals("Sub 2", ui.dialogShownList[1])
        assertEquals("Sub 3", ui.dialogShownList[2])
    }
}
