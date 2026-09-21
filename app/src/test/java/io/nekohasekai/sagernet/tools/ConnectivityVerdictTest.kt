package io.nekohasekai.sagernet.tools

import io.nekohasekai.sagernet.tools.connectivity.ConnectivityDiagnosticsManager
import io.nekohasekai.sagernet.tools.connectivity.EntryProbeResult
import io.nekohasekai.sagernet.tools.connectivity.NodeUsability
import io.nekohasekai.sagernet.tools.connectivity.StageState
import io.nekohasekai.sagernet.tools.connectivity.TargetProbeResult
import io.nekohasekai.sagernet.tools.connectivity.TunnelProbeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectivityVerdictTest {

    private fun dummyTargets(successIndices: Set<Int>, googleCn: Boolean = false): List<TargetProbeResult> {
        val targets = ConnectivityDiagnosticsManager.createInitialTargets()
        return targets.mapIndexed { index, target ->
            if (index in successIndices) {
                target.copy(
                    state = StageState.SUCCESS,
                    rttMs = 120L,
                    isGoogleCn = if (target.id == "google") googleCn else false,
                )
            } else {
                target.copy(
                    state = StageState.FAILED,
                    rttMs = 0L,
                )
            }
        }
    }

    @Test
    fun failedEntryWithSuccessfulTargetsStillUsable() {
        val entry = EntryProbeResult(state = StageState.FAILED, summary = "握手超时")
        val tunnel = TunnelProbeResult(state = StageState.SUCCESS)
        val targets = dummyTargets(setOf(0, 1, 2, 3, 4, 5))

        val (verdict, explanation) = ConnectivityDiagnosticsManager.synthesizeUsability(entry, tunnel, targets, isConnected = true)
        assertEquals(NodeUsability.FULLY_USABLE, verdict)
        assertTrue(explanation.contains("直连TCP握手受阻"))
    }

    @Test
    fun failedEntryAndFailedTunnelLeadsToUnusable() {
        val entry = EntryProbeResult(state = StageState.FAILED, summary = "握手超时")
        val tunnel = TunnelProbeResult(state = StageState.FAILED)
        val targets = dummyTargets(emptySet())

        val (verdict, _) = ConnectivityDiagnosticsManager.synthesizeUsability(entry, tunnel, targets, isConnected = true)
        assertEquals(NodeUsability.UNUSABLE, verdict)
    }

    @Test
    fun failedTunnelLeadsToUnusable() {
        val entry = EntryProbeResult(state = StageState.SUCCESS, rttMs = 30L)
        val tunnel = TunnelProbeResult(state = StageState.FAILED, summary = "出站失败")
        val targets = dummyTargets(setOf(0, 1, 2, 3, 4, 5))

        val (verdict, _) = ConnectivityDiagnosticsManager.synthesizeUsability(entry, tunnel, targets, isConnected = true)
        assertEquals(NodeUsability.UNUSABLE, verdict)
    }

    @Test
    fun allInternationalTargetsSuccessLeadsToFullyUsable() {
        val entry = EntryProbeResult(state = StageState.SUCCESS, rttMs = 35L)
        val tunnel = TunnelProbeResult(state = StageState.SUCCESS, rttMs = 150L)
        val targets = dummyTargets(setOf(0, 1, 2, 3, 4, 5))

        val (verdict, explanation) = ConnectivityDiagnosticsManager.synthesizeUsability(entry, tunnel, targets, isConnected = true)
        assertEquals(NodeUsability.FULLY_USABLE, verdict)
        assertTrue("Explanation should mention fast and stable access", explanation.contains("畅通无阻"))
    }

    @Test
    fun partialTargetSuccessLeadsToPartiallyUsable() {
        val entry = EntryProbeResult(state = StageState.SUCCESS, rttMs = 35L)
        val tunnel = TunnelProbeResult(state = StageState.SUCCESS, rttMs = 150L)
        // Only 3 of 5 international targets succeed
        val targets = dummyTargets(setOf(0, 3, 4))

        val (verdict, explanation) = ConnectivityDiagnosticsManager.synthesizeUsability(entry, tunnel, targets, isConnected = true)
        assertEquals(NodeUsability.PARTIALLY_USABLE, verdict)
        assertTrue("Explanation should mention failed targets", explanation.contains("受阻或超时"))
    }

    @Test
    fun googleCnDetectionIsReportedInExplanation() {
        val entry = EntryProbeResult(state = StageState.SUCCESS, rttMs = 35L)
        val tunnel = TunnelProbeResult(state = StageState.SUCCESS, rttMs = 150L)
        val targets = dummyTargets(setOf(0, 1, 2, 3, 4, 5), googleCn = true)

        val (verdict, explanation) = ConnectivityDiagnosticsManager.synthesizeUsability(entry, tunnel, targets, isConnected = true)
        assertEquals(NodeUsability.FULLY_USABLE, verdict)
        assertTrue("Explanation should mention Google 送中 note", explanation.contains("Google 送中"))
    }

    @Test
    fun notConnectedLeadsToIndeterminate() {
        val entry = EntryProbeResult(state = StageState.SUCCESS, rttMs = 35L)
        val tunnel = TunnelProbeResult(state = StageState.SUCCESS, rttMs = 150L)
        val targets = dummyTargets(setOf(0, 1, 2, 3, 4, 5))

        val (verdict, explanation) = ConnectivityDiagnosticsManager.synthesizeUsability(entry, tunnel, targets, isConnected = false)
        assertEquals(NodeUsability.INDETERMINATE, verdict)
        assertTrue(explanation.contains("未运行"))
    }
}
