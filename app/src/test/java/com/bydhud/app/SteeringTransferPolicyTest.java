package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Key consumption is independent of worker eligibility; only a first down can request work. */
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
    public void anyDeliveredConfiguredRawCodeIsConsumed() {
        for (int code : new int[] {0, 294, 304, 305, 313, 1000}) {
            assertTrue(SteeringTransferPolicy.isMappedKey(code, code));
            assertFalse(SteeringTransferPolicy.isMappedKey(code + 1, code));
        }
    }

    @Test
    public void repeatedDownCannotToggleAgainBeforeUpOrTailRecovery() {
        assertTrue(SteeringTransferPolicy.shouldStartTransfer(0, 0, false));
        assertFalse(SteeringTransferPolicy.shouldStartTransfer(0, 0, true));
        assertFalse(SteeringTransferPolicy.shouldStartTransfer(0, 1, true));
        assertFalse(SteeringTransferPolicy.shouldStartTransfer(1, 0, true));
        assertTrue(SteeringTransferPolicy.shouldStartTransfer(0, 0, false));
    }

    @Test
    public void orphanRepeatOrUpIsConsumedWithoutStartingWork() {
        assertTrue(SteeringTransferPolicy.isMappedKey(305, 305));
        assertFalse(SteeringTransferPolicy.shouldStartTransfer(0, 1, false));
        assertFalse(SteeringTransferPolicy.shouldStartTransfer(0, 99, false));
        assertFalse(SteeringTransferPolicy.shouldStartTransfer(1, 0, false));
    }

    @Test
    public void resettingOnlyKeyAssignmentRestoresStockHandling() {
        assertFalse(SteeringTransferPolicy.isMappedKey(305, SteeringTransferPreferences.NO_KEY_CODE));
        assertFalse(SteeringTransferPolicy.isMappedKey(-1, SteeringTransferPreferences.NO_KEY_CODE));
        assertTrue(SteeringTransferPolicy.isMappedKey(305, 305));
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

    @Test
    public void selectedProfileKeepsTheCurrentDashboardMode() {
        for (int mode : new int[] {HudPrefs.DASHBOARD_MODE_NONE,
                HudPrefs.DASHBOARD_MODE_PARTIAL, HudPrefs.DASHBOARD_MODE_FULL}) {
            assertTrue(SteeringTransferPolicy.resolveDashboardMode(
                    SteeringTransferPreferences.PROFILE_SELECTED, mode) == mode);
        }
    }
}
