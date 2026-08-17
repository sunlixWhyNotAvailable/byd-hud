package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NavigatorPatchOperationPolicyTest {
    @Test
    public void onlyLocalCheckAndPatchPhasesAreCancellable() {
        NavigatorPatchStore.OperationSnapshot check = new NavigatorPatchStore.OperationSnapshot(
                NavigatorPatchStore.Profile.WAZE, NavigatorPatchStore.OP_CHECK,
                NavigatorPatchStore.SCANNING, "scan", "token", 10L, 40,
                "", 0L, false, false);
        NavigatorPatchStore.OperationSnapshot select = new NavigatorPatchStore.OperationSnapshot(
                NavigatorPatchStore.Profile.WAZE, NavigatorPatchStore.OP_SELECT,
                NavigatorPatchStore.COPYING, "select", "token", 11L, 10,
                "", 0L, false, false);
        NavigatorPatchStore.OperationSnapshot cancelled = new NavigatorPatchStore.OperationSnapshot(
                NavigatorPatchStore.Profile.WAZE, NavigatorPatchStore.OP_PATCH,
                NavigatorPatchStore.CANCELLED, "cancelled", "token", 12L, 0,
                "cancelled", 0L, false, false);
        NavigatorPatchStore.OperationSnapshot installer = new NavigatorPatchStore.OperationSnapshot(
                NavigatorPatchStore.Profile.WAZE, NavigatorPatchStore.OP_PATCH,
                NavigatorPatchStore.INSTALL_PREPARING, "installer", "token", 13L, 100,
                "", 100L, false, false);
        NavigatorPatchStore.OperationSnapshot installedVerify =
                new NavigatorPatchStore.OperationSnapshot(
                        NavigatorPatchStore.Profile.WAZE, NavigatorPatchStore.OP_PATCH,
                        NavigatorPatchStore.INSTALLED_VERIFY, "verify", "token", 14L, 100,
                        "", 100L, false, false);
        assertTrue(NavigatorPatchStore.canCancel(check));
        assertFalse(NavigatorPatchStore.canCancel(select));
        assertFalse(NavigatorPatchStore.canCancel(cancelled));
        assertFalse(NavigatorPatchStore.canCancel(installer));
        assertFalse(NavigatorPatchStore.canCancel(installedVerify));
        assertTrue(cancelled.terminal());
    }

    @Test
    public void olderReadyOperationStaysAheadOfSecondProfile() {
        NavigatorPatchStore.OperationSnapshot waze = new NavigatorPatchStore.OperationSnapshot(
                NavigatorPatchStore.Profile.WAZE, NavigatorPatchStore.OP_PATCH,
                NavigatorPatchStore.READY_TO_INSTALL, "ready", "waze-token", 10L, 100,
                "", 100L, false, false);
        NavigatorPatchStore.OperationSnapshot gmaps = new NavigatorPatchStore.OperationSnapshot(
                NavigatorPatchStore.Profile.GMAPS, NavigatorPatchStore.OP_PATCH,
                NavigatorPatchStore.READY_TO_INSTALL, "ready", "gmaps-token", 20L, 100,
                "", 200L, false, false);
        assertTrue(NavigatorPatchStore.readyBefore(waze, gmaps.readyAt));
        assertFalse(NavigatorPatchStore.readyBefore(gmaps, waze.readyAt));
    }

    @Test
    public void stagedOutputVerificationIsNotInstalledVerification() {
        assertTrue(NavigatorPatchStore.isInstalledVerificationPhase(
                NavigatorPatchStore.INSTALLED_VERIFY));
        assertFalse(NavigatorPatchStore.isInstalledVerificationPhase(
                NavigatorPatchStore.OUTPUT_VERIFY));
    }
}
