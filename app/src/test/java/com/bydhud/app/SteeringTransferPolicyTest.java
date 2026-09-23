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
        }
        assertFalse(SteeringTransferPolicy.isMappedKey(295, 294));
        assertFalse(SteeringTransferPolicy.isMappedKey(313, 304));
        assertFalse(SteeringTransferPolicy.isMappedKey(1001, 1000));
    }

    @Test
    public void nativeLongAliasesShareOneButtonFamily() {
        for (int[] pair : new int[][] {{305, 306}, {304, 312}, {88, 303}, {87, 302}}) {
            assertTrue(SteeringTransferPolicy.isMappedKey(pair[0], pair[1]));
            assertTrue(SteeringTransferPolicy.isMappedKey(pair[1], pair[0]));
            assertTrue(SteeringTransferPolicy.isNativeLongAlias(pair[1]));
            assertTrue(SteeringTransferPolicy.hasNativeLongAlias(pair[0]));
        }
        assertFalse(SteeringTransferPolicy.isNativeLongAlias(294));
        assertFalse(SteeringTransferPolicy.isNativeLongAlias(353));
        assertFalse(SteeringTransferPolicy.hasNativeLongAlias(294));
        assertFalse(SteeringTransferPolicy.hasNativeLongAlias(353));
    }

    @Test
    public void knownDiagnosticScopeIncludesSteeringButtonFamilies() {
        for (int code : new int[] {305, 306, 304, 312, 88, 303, 87, 302,
                294, 353, 313}) {
            assertTrue(SteeringTransferPolicy.isKnownSteeringKey(code));
        }
        for (int code : new int[] {295, 354, 314, 1000, -1}) {
            assertFalse(SteeringTransferPolicy.isKnownSteeringKey(code));
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
    public void mainMovesOutWhileOwnedAndForeignDisplaysReturnToMain() {
        assertTrue(SteeringTransferPolicy.toggleMovesToDashboard(
                DashboardProjectionPolicy.ObservedDisplay.MAIN));
        assertFalse(SteeringTransferPolicy.toggleMovesToDashboard(
                DashboardProjectionPolicy.ObservedDisplay.OTHER));
        assertTrue(SteeringTransferPolicy.canReturnToMain(
                DashboardProjectionPolicy.ObservedDisplay.DASHBOARD));
        assertTrue(SteeringTransferPolicy.canReturnToMain(
                DashboardProjectionPolicy.ObservedDisplay.OTHER));
        assertFalse(SteeringTransferPolicy.canReturnToMain(
                DashboardProjectionPolicy.ObservedDisplay.UNKNOWN));
        NavAppDisplayState task = new NavAppDisplayState("com.waze", 42, 4, true, "foreign");
        assertTrue(SteeringTransferPolicy.canToggleTask(
                task, DashboardProjectionPolicy.ObservedDisplay.OTHER));
        assertFalse(SteeringTransferPolicy.canToggleTask(
                task, DashboardProjectionPolicy.ObservedDisplay.UNKNOWN));
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
