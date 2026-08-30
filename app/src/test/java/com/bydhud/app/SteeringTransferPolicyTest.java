package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Guards the small, pure key-event admission contract used by the runtime service. */
public final class SteeringTransferPolicyTest {
    @Test
    public void learningCapturesOnlyFirstNonRepeatDown() {
        assertTrue(SteeringTransferPolicy.isFirstDown(
                SteeringTransferPolicy.ACTION_DOWN, 0));
        assertFalse(SteeringTransferPolicy.isFirstDown(
                SteeringTransferPolicy.ACTION_DOWN, 1));
        assertFalse(SteeringTransferPolicy.isFirstDown(
                SteeringTransferPolicy.ACTION_UP, 0));
    }

    @Test
    public void taskEvidenceMustBeAuthoritativeAndFresh() {
        assertTrue(SteeringTransferPolicy.hasFreshTaskEvidence(
                true, 1_000L, 5_000L, 4_000L));
        assertFalse(SteeringTransferPolicy.hasFreshTaskEvidence(
                true, 1_000L, 5_001L, 4_000L));
        assertFalse(SteeringTransferPolicy.hasFreshTaskEvidence(
                false, 1_000L, 1_000L, 4_000L));
        assertFalse(SteeringTransferPolicy.hasFreshTaskEvidence(
                true, 0L, 1_000L, 4_000L));
    }

    @Test
    public void taskScanCannotPublishAcrossInvalidationOrShutdown() {
        assertTrue(SteeringTransferPolicy.canPublishTaskEvidence(
                true, false, true, 4L, 4L));
        assertFalse(SteeringTransferPolicy.canPublishTaskEvidence(
                true, false, true, 4L, 5L));
        assertFalse(SteeringTransferPolicy.canPublishTaskEvidence(
                true, true, true, 4L, 4L));
        assertFalse(SteeringTransferPolicy.canPublishTaskEvidence(
                false, false, true, 4L, 4L));
    }

    @Test
    public void noMappingOrUnknownCachePassesThrough() {
        assertFalse(SteeringTransferPolicy.canAdmitMappedPress(
                309, SteeringTransferPreferences.NO_KEY_CODE,
                true, true, false));
        assertFalse(SteeringTransferPolicy.canAdmitMappedPress(
                309, 309, false, true, false));
        assertFalse(SteeringTransferPolicy.canAdmitMappedPress(
                309, 309, true, false, false));
    }

    @Test
    public void shutdownBlocksMappedPress() {
        assertFalse(SteeringTransferPolicy.canAdmitMappedPress(
                309, 309, true, true, true));
    }

    @Test
    public void explicitProfilesOverrideSelectedMode() {
        assertTrue(SteeringTransferPolicy.resolveDashboardMode(
                SteeringTransferPreferences.PROFILE_PARTIAL,
                HudPrefs.DASHBOARD_MODE_FULL) == HudPrefs.DASHBOARD_MODE_PARTIAL);
        assertTrue(SteeringTransferPolicy.resolveDashboardMode(
                SteeringTransferPreferences.PROFILE_FULL,
                HudPrefs.DASHBOARD_MODE_PARTIAL) == HudPrefs.DASHBOARD_MODE_FULL);
    }
}
