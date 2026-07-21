package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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
}
