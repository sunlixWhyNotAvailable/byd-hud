package com.bydhud.app;

import org.junit.Test;

import static org.junit.Assert.*;

/** Event sequences for admission; no Android process or Binder execution is claimed. */
public final class WazeStartAdmissionTest {
    private static WazeStartAdmission runtime(boolean bridge) {
        WazeStartAdmission state = new WazeStartAdmission();
        state.updateRuntime(true, 10L, bridge);
        return state;
    }

    @Test public void bootAndRepeatedSupervisorStartsNeverGrantColdProbe() {
        WazeStartAdmission state = runtime(false);
        for (long now = 1_000; now <= 60_000; now += 1_000) {
            state.updateRuntime(true, 10L, false);
            assertNull(state.acquire(now));
        }
    }

    @Test public void processPresenceNeedsIndependentWindowEvidence() {
        WazeStartAdmission state = runtime(false);
        state.observedProcess(state.epoch(), true, 1_000L);
        assertNull(state.acquire(1_000L));
        assertFalse(state.observedWindow(state.epoch(), -1L)); // No service/root/window.
        assertNull(state.acquire(1_000L));
        assertTrue(state.observedWindow(state.epoch(), 1_001L));
        assertEquals(WazeStartAdmission.Source.LEGACY_WINDOW, state.acquire(1_001L).source);

        WazeStartAdmission windowOnly = runtime(false);
        windowOnly.observedWindow(windowOnly.epoch(), 1_000L);
        assertNull(windowOnly.acquire(1_000L));
    }

    @Test public void processChecksNeverRenewTheFiveSecondWindowGrant() {
        WazeStartAdmission state = runtime(false);
        state.observedWindow(state.epoch(), 1_000L);
        assertFalse(state.hasFreshLegacyWindow(state.epoch(), 999L));
        state.observedProcess(state.epoch(), true, 6_000L);
        assertNotNull(state.acquire(6_000L));
        state.observedProcess(state.epoch(), true, 6_001L);
        assertNull(state.acquire(6_001L));
        state.observedWindow(state.epoch(), 900L); // Late reads cannot replace a newer proof.
        assertNull(state.acquire(6_001L));
        state.observedWindow(state.epoch(), 6_002L);
        assertNotNull(state.acquire(6_002L));
        state.observedProcess(state.epoch(), true, 3_600_000L);
        assertNull(state.acquire(3_600_000L));
    }

    @Test public void hudRestartOrAllConsumersOffCannotReuseAnOldWindow() {
        WazeStartAdmission state = runtime(false);
        state.observedWindow(state.epoch(), 1_000L);
        state.observedProcess(state.epoch(), true, 1_000L);
        assertNotNull(state.acquire(1_000L));
        long oldEpoch = state.epoch();
        state.updateRuntime(false, 10L, false);
        state.updateRuntime(true, 10L, false);
        assertFalse(state.observedWindow(oldEpoch, 1_001L));
        state.observedProcess(state.epoch(), true, 1_001L);
        assertNull(state.acquire(1_001L));

        WazeStartAdmission restarted = runtime(false);
        restarted.observedProcess(restarted.epoch(), true, 1_001L);
        assertNull(restarted.acquire(1_001L));
        restarted.acceptedRoute(true, true, false, 1_002L);
        assertEquals(WazeStartAdmission.Source.LIVE_ROUTE, restarted.acquire(1_002L).source);
    }

    @Test public void liveRouteReceivedBeforeSenderCreationSurvivesRuntimeActivation() {
        WazeStartAdmission state = new WazeStartAdmission();
        state.updateRuntime(false, 10L, true);
        state.acceptedRoute(true, true, false, 1_000L);
        assertNull(state.acquire(1_000L));
        state.updateRuntime(true, 10L, true);
        assertEquals(WazeStartAdmission.Source.LIVE_ROUTE, state.acquire(1_500L).source);
    }

    @Test public void freshProofExpiresButNeverDelaysStart() {
        WazeStartAdmission state = runtime(true);
        state.acceptedRoute(true, true, false, 1_000L);
        assertNull(state.acquire(999L));
        assertNotNull(state.acquire(1_000L));
        assertNotNull(state.acquire(6_000L));
        assertNull(state.acquire(6_001L));
        state.acceptedRoute(true, true, false, 6_100L);
        assertNotNull(state.acquire(6_100L));
    }

    @Test public void savedOrInactiveStateCannotSeedColdConnection() {
        WazeStartAdmission state = runtime(true);
        // The store may retain active=true after a nonterminal navigating=false event.
        state.acceptedRoute(true, false, false, 1_000L);
        assertNull(state.acquire(1_000L));
        state.acceptedRoute(false, false, false, 1_001L);
        assertNull(state.acquire(1_001L));
    }

    @Test public void establishedNavigationCanRecoverLongAfterFreshnessWindow() {
        WazeStartAdmission state = runtime(true);
        state.acceptedRoute(true, true, false, 1_000L);
        WazeStartAdmission.Permit first = state.acquire(1_000L);
        state.established(first, false);
        WazeStartAdmission.Permit reconnect = state.acquire(3_600_000L);
        assertEquals(WazeStartAdmission.Source.ACTIVE_RECOVERY, reconnect.source);
        assertTrue(state.isCurrent(first));
    }

    @Test public void terminalRejectsQueuedBindAndPresenceResultUntilNewEvidence() {
        WazeStartAdmission state = runtime(false);
        long requestEpoch = state.epoch();
        state.observedWindow(requestEpoch, 1_000L);
        assertTrue(state.observedProcess(requestEpoch, true, 1_000L));
        WazeStartAdmission.Permit queued = state.acquire(1_000L);
        state.acceptedRoute(false, false, true, 1_001L);
        assertFalse(state.isCurrent(queued));
        assertFalse(state.observedProcess(requestEpoch, true, 1_002L));
        assertNull(state.acquire(1_002L));
        assertFalse(state.observedWindow(requestEpoch, 1_003L));
        assertNull(state.acquire(1_003L));
        state.observedWindow(state.epoch(), 1_003L);
        assertTrue(state.observedProcess(state.epoch(), true, 1_003L));
        assertNotNull(state.acquire(1_003L));
        assertFalse(state.isCurrent(queued));
    }

    @Test public void windowReadyWithoutNavigationDoesNotAuthorizePermanentRecovery() {
        WazeStartAdmission state = runtime(false);
        state.observedWindow(state.epoch(), 1_000L);
        state.observedProcess(state.epoch(), true, 1_000L);
        WazeStartAdmission.Permit permit = state.acquire(1_000L);
        state.established(permit, false);
        assertNull(state.acquire(6_001L));
        // The original allowed bind may finish its handshake beyond the initial window.
        assertTrue(state.isCurrent(permit));
        state.established(permit, true);
        assertEquals(WazeStartAdmission.Source.ACTIVE_RECOVERY, state.acquire(60_000L).source);
    }

    @Test public void absentOrUnavailableLegacyCheckRevokesUnconsumedEvidence() {
        WazeStartAdmission state = runtime(false);
        state.observedWindow(state.epoch(), 1_000L);
        state.observedProcess(state.epoch(), true, 1_000L);
        assertNotNull(state.acquire(1_000L));
        assertFalse(state.observedProcess(state.epoch(), false, 1_001L));
        assertNull(state.acquire(1_001L));
    }

    @Test public void learningV2DuringLegacyCheckPreventsProbe() {
        WazeStartAdmission state = runtime(false);
        long query = state.epoch();
        state.observedWindow(query, 1_000L);
        state.observedProcess(query, true, 1_000L);
        WazeStartAdmission.Permit legacy = state.acquire(1_000L);
        state.updateRuntime(true, 10L, true);
        assertFalse(state.observedProcess(query, true, 1_001L));
        assertFalse(state.isCurrent(legacy));
        assertNull(state.acquire(1_001L));
        state.acceptedRoute(true, true, false, 1_002L);
        assertEquals(WazeStartAdmission.Source.LIVE_ROUTE, state.acquire(1_002L).source);
    }

    @Test public void hudOffWithTbtConsumerRetainsRouteButAllConsumersOffInvalidates() {
        WazeStartAdmission state = runtime(true);
        state.acceptedRoute(true, true, false, 1_000L);
        WazeStartAdmission.Permit original = state.acquire(1_000L);
        state.established(original, false);
        state.updateRuntime(true, 10L, true); // TBT remains enabled after HUD OFF.
        assertTrue(state.isCurrent(original));
        assertNotNull(state.acquire(60_000L));
        state.updateRuntime(false, 10L, true);
        assertFalse(state.isCurrent(original));
        state.updateRuntime(true, 10L, true);
        assertNull(state.acquire(60_001L));
    }

    @Test public void packageReplacementRejectsPendingChecksAndEstablishedRecovery() {
        WazeStartAdmission state = runtime(false);
        long oldEpoch = state.epoch();
        state.observedWindow(oldEpoch, 1_000L);
        state.observedProcess(oldEpoch, true, 1_000L);
        WazeStartAdmission.Permit original = state.acquire(1_000L);
        state.established(original, true);
        state.updateRuntime(true, 20L, false);
        assertFalse(state.isCurrent(original));
        assertFalse(state.observedProcess(oldEpoch, true, 1_001L));
        assertNull(state.acquire(1_001L));
    }

    @Test public void failedOrLateHandshakeCannotPromoteRevokedPermit() {
        WazeStartAdmission state = runtime(true);
        state.acceptedRoute(true, true, false, 1_000L);
        WazeStartAdmission.Permit queued = state.acquire(1_000L);
        state.invalidate();
        state.established(queued, true);
        assertNull(state.acquire(1_001L));
    }

    @Test public void inactiveNewGenerationClosesBothHostModes() {
        WazeStartAdmission state = runtime(true);
        state.acceptedRoute(true, true, false, 1_000L);
        WazeStartAdmission.Permit cluster = state.acquire(1_000L);
        state.established(cluster, false);
        WazeStartAdmission.Permit surface = state.acquire(10_000L);
        state.acceptedRoute(false, false, false, 10_001L);
        assertFalse(state.isCurrent(cluster));
        assertFalse(state.isCurrent(surface));
        assertNull(state.acquire(10_001L));
    }
}
