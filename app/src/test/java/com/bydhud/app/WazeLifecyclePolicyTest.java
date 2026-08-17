package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Field;

public final class WazeLifecyclePolicyTest {
    private static final String WAZE = "com.waze";

    @Test
    public void bindDefersUntilPackageAndServiceAreRunnable() {
        assertEquals("package_missing",
                WazeDirectChannel.bindDeferralReason(false, false, false, false, false));
        assertEquals("package_disabled",
                WazeDirectChannel.bindDeferralReason(true, false, true, true, false));
        assertEquals("service_missing",
                WazeDirectChannel.bindDeferralReason(true, true, false, false, false));
        assertEquals("service_disabled",
                WazeDirectChannel.bindDeferralReason(true, true, true, false, false));
        assertEquals("package_stopped",
                WazeDirectChannel.bindDeferralReason(true, true, true, true, true));
        assertEquals("",
                WazeDirectChannel.bindDeferralReason(true, true, true, true, false));
    }

    @Test
    public void newProbeDropsOldRouteEvidenceUntilFreshEvidenceArrives() {
        NavRouteStateStore store = new NavRouteStateStore();
        store.updateFromVisualRouteEvidence(WAZE, "old-session", "route", 1_000L);
        assertTrue(store.isRouteActive(WAZE, 1_100L));

        store.clearRoute(WAZE, "new-direct-probe", 1_200L);
        assertFalse(store.isRouteActive(WAZE, 1_201L));

        store.updateFromVisualRouteEvidence(WAZE, "new-session", "route", 1_300L);
        assertTrue(store.isRouteActive(WAZE, 1_301L));
    }

    @Test
    public void freshWazeEvidenceStartsProbeBeforeItIsStored() {
        assertTrue(NavHudLiveSender.shouldStartWazeBeforeFreshRouteEvidence(
                true, WAZE, false, ""));
        assertFalse(NavHudLiveSender.shouldStartWazeBeforeFreshRouteEvidence(
                true, WAZE, true, WAZE));
        assertFalse(NavHudLiveSender.shouldStartWazeBeforeFreshRouteEvidence(
                false, WAZE, false, ""));
    }

    @Test
    public void bridgeStartsHostOnlyForPersistedActiveRoute() {
        assertFalse(NavHudLiveSender.shouldStartWazeDirectHost(true, false));
        assertTrue(NavHudLiveSender.shouldStartWazeDirectHost(true, true));
        assertTrue(NavHudLiveSender.shouldStartWazeDirectHost(false, false));
    }

    @Test
    public void stateRecoveryRequiresAnEnabledRuntimeConsumer() {
        assertTrue(NavHudLiveSender.shouldRequestWazeRouteStateForTest(
                false, true, true, false));
        assertTrue(NavHudLiveSender.shouldRequestWazeRouteStateForTest(
                false, true, false, true));
        assertFalse(NavHudLiveSender.shouldRequestWazeRouteStateForTest(
                true, true, true, true));
        assertFalse(NavHudLiveSender.shouldRequestWazeRouteStateForTest(
                false, false, true, true));
        assertFalse(NavHudLiveSender.shouldRequestWazeRouteStateForTest(
                false, true, false, false));
    }

    @Test
    public void lifecycleRestartPolicyCoversAllStates() {
        boolean[][] cases = {
                {false, false, false, true},
                {false, false, true, true},
                {false, true, false, false},
                {false, true, true, false},
                {true, false, false, true},
                {true, false, true, true},
                {true, true, false, true},
                {true, true, true, false}
        };
        for (boolean[] testCase : cases) {
            assertEquals(
                    "changed=" + testCase[0]
                            + " channelActive=" + testCase[1]
                            + " navigating=" + testCase[2],
                    testCase[3],
                    NavHudLiveSender.shouldRestartWazeDirectForLifecycle(
                            testCase[0], testCase[1], testCase[2]));
        }
    }

    @Test
    public void lifecycleRestartResetsOnlyFreshRouteState() {
        assertTrue(NavHudLiveSender.shouldRecoverWazeDirectForLifecycle(false, false));
        assertTrue(NavHudLiveSender.shouldRecoverWazeDirectForLifecycle(false, true));
        assertFalse(NavHudLiveSender.shouldRecoverWazeDirectForLifecycle(true, false));
        assertTrue(NavHudLiveSender.shouldRecoverWazeDirectForLifecycle(true, true));
    }

    @Test
    public void terminalFenceRejectsQueuedStartAndFrameUntilFreshRouteProof() {
        assertFalse(NavHudLiveSender.shouldAcceptWazeNavigationStartAfterTerminalForTest(true));
        assertFalse(NavHudLiveSender.shouldAcceptWazeFrameAfterTerminalForTest(true, true));
        assertTrue(NavHudLiveSender.shouldAcceptWazeNavigationStartAfterTerminalForTest(false));
        assertTrue(NavHudLiveSender.shouldAcceptWazeFrameAfterTerminalForTest(false, true));
    }

    @Test
    public void freshLifecycleProofOpensFenceButSameGenerationSnapshotDoesNot() {
        assertFalse(NavHudLiveSender.shouldOpenFreshWazeRouteForTest(
                true, false, false));
        assertTrue(NavHudLiveSender.shouldOpenFreshWazeRouteForTest(
                true, true, false));
        assertTrue(NavHudLiveSender.shouldOpenFreshWazeRouteForTest(
                true, false, true));
        assertTrue(NavHudLiveSender.isExplicitFreshWazeLifecycleReasonForTest(
                "transition:NEW_ROUTE_RECEIVED:event=state"));
        assertFalse(NavHudLiveSender.isExplicitFreshWazeLifecycleReasonForTest(
                "snapshot_replay_noop:UNAVAILABLE:event=state_snapshot"));
    }

    @Test
    public void directChannelFreshProofRequiresNewBridgeOrExplicitReason() {
        assertFalse(WazeDirectChannel.shouldAcceptFreshRouteProofForTest(
                true, 7L, 6L, false));
        assertFalse(WazeDirectChannel.shouldAcceptFreshRouteProofForTest(
                true, 7L, 0L, false));
        assertTrue(WazeDirectChannel.shouldAcceptFreshRouteProofForTest(
                true, 7L, 8L, false));
        assertTrue(WazeDirectChannel.shouldAcceptFreshRouteProofForTest(
                true, 7L, 7L, true));
        assertTrue(WazeDirectChannel.shouldAcceptFreshRouteProofForTest(
                false, 7L, 0L, false));
    }

    @Test
    public void legacySessionProofBlocksSameGenerationAndOpensOnlyNewSession() {
        assertFalse(NavHudLiveSender.shouldOpenLegacyRearmForTest(true, 5, 5));
        assertTrue(NavHudLiveSender.shouldOpenLegacyRearmForTest(true, 5, 6));
        assertTrue(NavHudLiveSender.shouldOpenLegacyRearmForTest(false, 5, 5));
        assertFalse(NavHudLiveSender.shouldAcceptWazeNavigationStartAfterTerminalForTest(
                true));
    }

    @Test
    public void legacySessionProofUsesTheCallbackChannelFloor() {
        // Surface generation 6 must not clear a pending surface fence merely
        // because it is newer than the independent cluster floor 5.
        assertFalse(NavHudLiveSender.shouldOpenLegacyRearmForChannelForTest(
                true, true, 5, true, 7, 6));
        assertTrue(NavHudLiveSender.shouldOpenLegacyRearmForChannelForTest(
                true, true, 5, true, 7, 8));
        assertFalse(NavHudLiveSender.shouldOpenLegacyRearmForChannelForTest(
                false, true, 5, true, 7, 5));
        assertTrue(NavHudLiveSender.shouldOpenLegacyRearmForChannelForTest(
                false, true, 5, true, 7, 6));
    }

    @Test
    public void newerInactiveSnapshotClosesExistingWazeOwnerOnly() {
        assertTrue(NavHudLiveSender.shouldCloseWazeForSupersedingInactiveSnapshotForTest(
                true, true, false, false, WAZE));
        assertTrue(NavHudLiveSender.shouldCloseWazeForSupersedingInactiveSnapshotForTest(
                true, false, true, false, ""));
        assertTrue(NavHudLiveSender.shouldCloseWazeForSupersedingInactiveSnapshotForTest(
                true, false, false, true, WAZE));
        assertFalse(NavHudLiveSender.shouldCloseWazeForSupersedingInactiveSnapshotForTest(
                true, false, false, true, "com.google.android.apps.maps"));
        assertFalse(NavHudLiveSender.shouldCloseWazeForSupersedingInactiveSnapshotForTest(
                false, true, true, true, WAZE));
    }

    @Test
    public void acceptedLifecycleTerminalClearsSpeedLimitWithoutRuntimeInstance()
            throws Exception {
        Field instance = NavHudLiveSender.class.getDeclaredField("instance");
        instance.setAccessible(true);
        Object previous;
        synchronized (NavHudLiveSender.class) {
            previous = instance.get(null);
            instance.set(null, null);
        }
        DirectSpeedLimitStore.clear(WAZE);
        try {
            DirectSpeedLimitStore.update(WAZE, 50, 50, "km/h", 1L);

            NavHudLiveSender.onWazeRouteLifecycleEvent(
                    null, true, false, 2L, true, "test-start");
            assertTrue(DirectSpeedLimitStore.snapshot(WAZE).isActive());
            assertEquals(50, DirectSpeedLimitStore.snapshot(WAZE).getDisplayValue());

            NavHudLiveSender.onWazeRouteLifecycleEvent(
                    null, false, true, 3L, true, "test-terminal");
            assertFalse(DirectSpeedLimitStore.snapshot(WAZE).isActive());
        } finally {
            DirectSpeedLimitStore.clear(WAZE);
            synchronized (NavHudLiveSender.class) {
                instance.set(null, previous);
            }
        }
    }
}
