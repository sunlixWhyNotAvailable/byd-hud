package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NavigatorPatchTrustPolicyTest {
    @Test
    public void acceptsConfiguredWazeOrigins() {
        assertOrigin(
                NavigatorPatchStore.Profile.WAZE,
                NavigatorPatchTrustPolicy.WAZE_STOCK_SIGNER,
                false,
                NavigatorPatchTrustPolicy.Origin.WAZE_STOCK);
        assertOrigin(
                NavigatorPatchStore.Profile.WAZE,
                NavigatorPatchTrustPolicy.WAZE_PROJECT_SIGNER,
                true,
                NavigatorPatchTrustPolicy.Origin.WAZE_PROJECT);
    }

    @Test
    public void acceptsConfiguredGMapsOrigins() {
        assertOrigin(
                NavigatorPatchStore.Profile.GMAPS,
                NavigatorPatchTrustPolicy.GMAPS_REVANCED_SOURCE_SIGNER,
                false,
                NavigatorPatchTrustPolicy.Origin.GMAPS_REVANCED_SOURCE);
        assertOrigin(
                NavigatorPatchStore.Profile.GMAPS,
                NavigatorPatchTrustPolicy.GMAPS_PROJECT_SIGNER,
                true,
                NavigatorPatchTrustPolicy.Origin.GMAPS_PROJECT);
    }

    @Test
    public void rejectsConfiguredSignerForWrongProfile() {
        NavigatorPatchTrustPolicy.Decision decision =
                NavigatorPatchTrustPolicy.evaluate(
                        NavigatorPatchStore.Profile.GMAPS,
                        NavigatorPatchTrustPolicy.WAZE_STOCK_SIGNER,
                        false,
                        false);

        assertFalse(decision.accepted);
        assertEquals("TRUST_UNKNOWN_SIGNER", decision.code);
    }

    @Test
    public void rejectsUnknownSignerWithoutOverride() {
        NavigatorPatchTrustPolicy.Decision decision =
                NavigatorPatchTrustPolicy.evaluate(
                        NavigatorPatchStore.Profile.WAZE,
                        "AA".repeat(32),
                        true,
                        false);

        assertFalse(decision.accepted);
        assertEquals("TRUST_UNKNOWN_SIGNER", decision.code);
        assertEquals(null, decision.origin);
    }

    @Test
    public void localSignerRequiresMandatoryPatchMarker() {
        NavigatorPatchTrustPolicy.Decision unpatched =
                NavigatorPatchTrustPolicy.evaluate(
                        NavigatorPatchStore.Profile.WAZE,
                        "BB".repeat(32),
                        false,
                        true);
        NavigatorPatchTrustPolicy.Decision patched =
                NavigatorPatchTrustPolicy.evaluate(
                        NavigatorPatchStore.Profile.WAZE,
                        "BB".repeat(32),
                        true,
                        true);

        assertFalse(unpatched.accepted);
        assertEquals("TRUST_LOCAL_SIGNER_UNPATCHED", unpatched.code);
        assertTrue(patched.accepted);
        assertEquals(NavigatorPatchTrustPolicy.Origin.DEVICE_LOCAL, patched.origin);
    }

    @Test
    public void missingProfileOrSignerHasStableClassification() {
        assertEquals(
                "TRUST_PROFILE_REQUIRED",
                NavigatorPatchTrustPolicy.evaluate(null, "", false, false).code);
        assertEquals(
                "TRUST_SIGNER_MISSING",
                NavigatorPatchTrustPolicy.evaluate(
                        NavigatorPatchStore.Profile.WAZE, null, false, false).code);
    }

    @Test
    public void requireExposesStableTrustErrorCode() {
        try {
            NavigatorPatchTrustPolicy.require(
                    NavigatorPatchStore.Profile.GMAPS,
                    "CC".repeat(32),
                    false,
                    false);
        } catch (NavigatorPatchTrustPolicy.TrustException error) {
            assertEquals("TRUST_UNKNOWN_SIGNER", error.code);
            assertTrue(error.getMessage().startsWith("TRUST_UNKNOWN_SIGNER:"));
            return;
        }
        throw new AssertionError("Unknown signer was accepted");
    }

    private static void assertOrigin(NavigatorPatchStore.Profile profile, String signer,
            boolean marker, NavigatorPatchTrustPolicy.Origin expectedOrigin) {
        NavigatorPatchTrustPolicy.Decision decision =
                NavigatorPatchTrustPolicy.evaluate(profile, signer, marker, false);
        assertTrue(decision.accepted);
        assertEquals("TRUST_ACCEPTED", decision.code);
        assertEquals(expectedOrigin, decision.origin);
    }
}
