package com.bydhud.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class HudRuntimeUpgradeGuardTest {
    @Test
    public void versionDecisionSkipsFreshInstallResetAndResetsOnlyOnChange() {
        assertEquals(
                HudRuntimeUpgradeGuard.VersionStartDecision.INITIALIZE,
                HudRuntimeUpgradeGuard.versionStartDecision(0, 75));
        assertEquals(
                HudRuntimeUpgradeGuard.VersionStartDecision.UNCHANGED,
                HudRuntimeUpgradeGuard.versionStartDecision(75, 75));
        assertEquals(
                HudRuntimeUpgradeGuard.VersionStartDecision.HARD_RESET,
                HudRuntimeUpgradeGuard.versionStartDecision(74, 75));
    }

    @Test
    public void delayedPackageReplaceDoesNotRearmHandledInstall() {
        assertEquals(false, HudRuntimeUpgradeGuard.shouldArmPackageReplaceHardReset(
                200L, 200L, false));
        assertEquals(true, HudRuntimeUpgradeGuard.shouldArmPackageReplaceHardReset(
                201L, 200L, false));
        assertEquals(true, HudRuntimeUpgradeGuard.shouldArmPackageReplaceHardReset(
                200L, 200L, true));
        assertEquals(true, HudRuntimeUpgradeGuard.shouldArmPackageReplaceHardReset(
                0L, 0L, false));
    }
}
