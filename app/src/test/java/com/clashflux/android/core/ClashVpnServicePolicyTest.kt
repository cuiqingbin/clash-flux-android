package com.clashflux.android.core

import com.clashflux.android.data.AppSettings
import com.clashflux.android.data.PerAppMode
import com.clashflux.android.data.VpnRouteSnapshot
import com.clashflux.android.model.ConnectionStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClashVpnServicePolicyTest {

    @Test
    fun shouldIgnoreDuplicateStart_allowsReconnectDuringStoppingTransition() {
        assertFalse(
            ClashVpnService.shouldIgnoreDuplicateStart(
                restartExisting = false,
                state = ConnectionStatus.Disconnecting,
                activeTransitionKind = ClashVpnService.TransitionKind.STOP,
                hasActiveTransitionJob = true
            )
        )
        assertFalse(
            ClashVpnService.shouldIgnoreDuplicateStart(
                restartExisting = false,
                state = ConnectionStatus.Connecting,
                activeTransitionKind = ClashVpnService.TransitionKind.STOP,
                hasActiveTransitionJob = true
            )
        )
    }

    @Test
    fun shouldIgnoreDuplicateStart_suppressesDuplicateWhenConnectedOrStarting() {
        assertTrue(
            ClashVpnService.shouldIgnoreDuplicateStart(
                restartExisting = false,
                state = ConnectionStatus.Connected,
                activeTransitionKind = null,
                hasActiveTransitionJob = false
            )
        )
        assertTrue(
            ClashVpnService.shouldIgnoreDuplicateStart(
                restartExisting = false,
                state = ConnectionStatus.Connecting,
                activeTransitionKind = ClashVpnService.TransitionKind.START,
                hasActiveTransitionJob = true
            )
        )
        assertFalse(
            ClashVpnService.shouldIgnoreDuplicateStart(
                restartExisting = true,
                state = ConnectionStatus.Connected,
                activeTransitionKind = ClashVpnService.TransitionKind.START,
                hasActiveTransitionJob = true
            )
        )
    }

    @Test
    fun shouldPersistTraffic_throttlesPeriodicWritesUntilIntervalOrForceFlush() {
        assertFalse(
            ClashVpnService.shouldPersistTraffic(
                dirty = false,
                elapsedSinceLastPersistMs = 60_000L,
                forceFlush = true
            )
        )
        assertFalse(
            ClashVpnService.shouldPersistTraffic(
                dirty = true,
                elapsedSinceLastPersistMs = 5_000L,
                forceFlush = false
            )
        )
        assertTrue(
            ClashVpnService.shouldPersistTraffic(
                dirty = true,
                elapsedSinceLastPersistMs = ClashVpnService.TRAFFIC_PERSIST_INTERVAL_MS,
                forceFlush = false
            )
        )
        assertTrue(
            ClashVpnService.shouldPersistTraffic(
                dirty = true,
                elapsedSinceLastPersistMs = 500L,
                forceFlush = true
            )
        )
    }

    @Test
    fun shouldDispatchStopIntent_respectsActiveInstanceOrNonDisconnectedState() {
        assertFalse(
            ClashVpnService.shouldDispatchStopIntent(
                state = ConnectionStatus.Disconnected,
                hasActiveInstance = false
            )
        )
        assertTrue(
            ClashVpnService.shouldDispatchStopIntent(
                state = ConnectionStatus.Disconnected,
                hasActiveInstance = true
            )
        )
        assertTrue(
            ClashVpnService.shouldDispatchStopIntent(
                state = ConnectionStatus.Connecting,
                hasActiveInstance = false
            )
        )
        assertTrue(
            ClashVpnService.shouldDispatchStopIntent(
                state = ConnectionStatus.Connected,
                hasActiveInstance = false
            )
        )
    }

    @Test
    fun hasPendingRouteRestart_detectsSnapshotDriftOnlyWhenConnected() {
        val settings = AppSettings(
            perAppMode = PerAppMode.ALL,
            perAppPackages = emptySet(),
            ipv6Enabled = false,
            bypassLan = true
        )
        val matchingSnapshot = VpnRouteSnapshot(
            perAppMode = PerAppMode.ALL,
            perAppPackages = emptySet(),
            ipv6Enabled = false,
            bypassLan = true
        )

        assertFalse(
            ClashVpnService.hasPendingRouteRestart(
                state = ConnectionStatus.Disconnected,
                snapshot = matchingSnapshot,
                settings = settings.copy(ipv6Enabled = true)
            )
        )
        assertFalse(
            ClashVpnService.hasPendingRouteRestart(
                state = ConnectionStatus.Connected,
                snapshot = null,
                settings = settings.copy(ipv6Enabled = true)
            )
        )
        assertFalse(
            ClashVpnService.hasPendingRouteRestart(
                state = ConnectionStatus.Connected,
                snapshot = matchingSnapshot,
                settings = settings
            )
        )
        assertTrue(
            ClashVpnService.hasPendingRouteRestart(
                state = ConnectionStatus.Connected,
                snapshot = matchingSnapshot,
                settings = settings.copy(ipv6Enabled = true)
            )
        )
        assertTrue(
            ClashVpnService.hasPendingRouteRestart(
                state = ConnectionStatus.Connected,
                snapshot = matchingSnapshot,
                settings = settings.copy(bypassLan = false)
            )
        )
        assertTrue(
            ClashVpnService.hasPendingRouteRestart(
                state = ConnectionStatus.Connected,
                snapshot = matchingSnapshot,
                settings = settings.copy(perAppMode = PerAppMode.BYPASS_SELECTED)
            )
        )
    }

    @Test
    fun clashTileService_canStartTileProfileCheck_blocksDoubleTapAndActiveStates() {
        assertTrue(
            ClashTileService.canStartTileProfileCheck(
                state = ConnectionStatus.Disconnected,
                checkInFlight = false
            )
        )
        assertFalse(
            ClashTileService.canStartTileProfileCheck(
                state = ConnectionStatus.Disconnected,
                checkInFlight = true
            )
        )
        assertFalse(
            ClashTileService.canStartTileProfileCheck(
                state = ConnectionStatus.Connecting,
                checkInFlight = false
            )
        )
        assertFalse(
            ClashTileService.canStartTileProfileCheck(
                state = ConnectionStatus.Connected,
                checkInFlight = false
            )
        )
        assertFalse(
            ClashTileService.canStartTileProfileCheck(
                state = ConnectionStatus.Disconnecting,
                checkInFlight = false
            )
        )
    }
}
