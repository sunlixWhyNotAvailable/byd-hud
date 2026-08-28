package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NavigatorPatchPolicyTest {
    @Test
    public void gmapsUsesPipForTheThirdComponentPill() {
        assertEquals("GmsCore", NavigatorPatchStore.Profile.GMAPS.gmsCoreLabel);
        assertEquals("Audio channel", NavigatorPatchStore.Profile.GMAPS.optionalLabel);
        assertEquals("PiP", NavigatorPatchStore.Profile.GMAPS.alertLabel);
    }

    @Test
    public void wazeKeepsDirectAndLanesAsMandatoryGates() {
        assertTrue(NavigatorPatchStore.isPatchEnabled(
                NavigatorPatchStore.Profile.WAZE,
                "PATCHABLE", "PATCHED", "FAILED", "FAILED"));
        assertTrue(NavigatorPatchStore.isPatchEnabled(
                NavigatorPatchStore.Profile.WAZE,
                "PATCHED", "PATCHED", "FAILED", "PATCHABLE"));
        assertFalse(NavigatorPatchStore.isPatchEnabled(
                NavigatorPatchStore.Profile.WAZE,
                "FAILED", "PATCHED", "FAILED", "PATCHABLE"));
        assertFalse(NavigatorPatchStore.isPatchEnabled(
                NavigatorPatchStore.Profile.WAZE,
                "PATCHABLE", "FAILED", "PATCHED", "PATCHED"));
    }

    @Test
    public void gmapsAcceptsAnyIndependentPatchableComponent() {
        assertTrue(NavigatorPatchStore.isPatchEnabled(
                NavigatorPatchStore.Profile.GMAPS, "FAILED", "FAILED", "PATCHABLE"));
        assertTrue(NavigatorPatchStore.isPatchEnabled(
                NavigatorPatchStore.Profile.GMAPS, "FAILED", "PATCHABLE", "FAILED"));
        assertTrue(NavigatorPatchStore.isPatchEnabled(
                NavigatorPatchStore.Profile.GMAPS, "PATCHABLE", "FAILED", "FAILED"));
        assertFalse(NavigatorPatchStore.isPatchEnabled(
                NavigatorPatchStore.Profile.GMAPS, "PATCHED", "PATCHED", "PATCHED"));
    }

    @Test
    public void gmapsAcceptsGmsCoreOnlyPatch() {
        assertTrue(NavigatorPatchStore.isPatchEnabled(
                NavigatorPatchStore.Profile.GMAPS, "FAILED", "PATCHABLE", "FAILED", "FAILED"));
    }

    @Test
    public void installerRequiresExactPreparedOutputIdentity() {
        assertTrue(NavigatorPackageInstaller.matchesPreparedOutputForTest(
                "fingerprint", "26.30", 2630L, "signer",
                "fingerprint", "26.30", 2630L, "signer"));
        assertFalse(NavigatorPackageInstaller.matchesPreparedOutputForTest(
                "fingerprint", "26.30", 2630L, "signer",
                "changed", "26.30", 2630L, "signer"));
        assertFalse(NavigatorPackageInstaller.matchesPreparedOutputForTest(
                "fingerprint", "26.30", 2630L, "signer",
                "fingerprint", "26.31", 2630L, "signer"));
        assertFalse(NavigatorPackageInstaller.matchesPreparedOutputForTest(
                "fingerprint", "26.30", 2630L, "signer",
                "fingerprint", "26.30", 2631L, "signer"));
        assertFalse(NavigatorPackageInstaller.matchesPreparedOutputForTest(
                "fingerprint", "26.30", 2630L, "signer",
                "fingerprint", "26.30", 2630L, "other"));
    }
}
