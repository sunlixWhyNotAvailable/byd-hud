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
    public void acceptsOnlyExactMorpheGMapsArtifact() {
        NavigatorPatchTrustPolicy.Decision exact = evaluateMorphe(
                NavigatorPatchTrustPolicy.GMAPS_MORPHE_SOURCE_SIGNER,
                NavigatorPatchTrustPolicy.GMAPS_MORPHE_VERSION_NAME,
                NavigatorPatchTrustPolicy.GMAPS_MORPHE_VERSION_CODE,
                NavigatorPatchTrustPolicy.GMAPS_MORPHE_SET_FINGERPRINT);

        assertTrue(exact.accepted);
        assertEquals("TRUST_ACCEPTED", exact.code);
        assertEquals(NavigatorPatchTrustPolicy.Origin.GMAPS_MORPHE_SOURCE, exact.origin);
    }

    @Test
    public void rejectsMorpheArtifactWithWrongFingerprint() {
        NavigatorPatchTrustPolicy.Decision decision = evaluateMorphe(
                NavigatorPatchTrustPolicy.GMAPS_MORPHE_SOURCE_SIGNER,
                NavigatorPatchTrustPolicy.GMAPS_MORPHE_VERSION_NAME,
                NavigatorPatchTrustPolicy.GMAPS_MORPHE_VERSION_CODE,
                "AA".repeat(32));

        assertFalse(decision.accepted);
        assertEquals("TRUST_GMAPS_MORPHE_ARTIFACT_MISMATCH", decision.code);
    }

    @Test
    public void rejectsMorpheArtifactWithWrongVersion() {
        NavigatorPatchTrustPolicy.Decision wrongName = evaluateMorphe(
                NavigatorPatchTrustPolicy.GMAPS_MORPHE_SOURCE_SIGNER,
                "26.30.09.950492156",
                NavigatorPatchTrustPolicy.GMAPS_MORPHE_VERSION_CODE,
                NavigatorPatchTrustPolicy.GMAPS_MORPHE_SET_FINGERPRINT);
        NavigatorPatchTrustPolicy.Decision wrongCode = evaluateMorphe(
                NavigatorPatchTrustPolicy.GMAPS_MORPHE_SOURCE_SIGNER,
                NavigatorPatchTrustPolicy.GMAPS_MORPHE_VERSION_NAME,
                NavigatorPatchTrustPolicy.GMAPS_MORPHE_VERSION_CODE + 1L,
                NavigatorPatchTrustPolicy.GMAPS_MORPHE_SET_FINGERPRINT);

        assertFalse(wrongName.accepted);
        assertEquals("TRUST_GMAPS_MORPHE_ARTIFACT_MISMATCH", wrongName.code);
        assertFalse(wrongCode.accepted);
        assertEquals("TRUST_GMAPS_MORPHE_ARTIFACT_MISMATCH", wrongCode.code);
    }

    @Test
    public void rejectsMorpheArtifactWithUnknownSigner() {
        NavigatorPatchTrustPolicy.Decision decision = evaluateMorphe(
                "CC".repeat(32),
                NavigatorPatchTrustPolicy.GMAPS_MORPHE_VERSION_NAME,
                NavigatorPatchTrustPolicy.GMAPS_MORPHE_VERSION_CODE,
                NavigatorPatchTrustPolicy.GMAPS_MORPHE_SET_FINGERPRINT);

        assertFalse(decision.accepted);
        assertEquals("TRUST_UNKNOWN_SIGNER", decision.code);
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

    @Test
    public void lifecycleV2AcceptsOnlyExactCanonicalProjectAsset() {
        NavigatorPatchTrustPolicy.Decision exact =
                NavigatorPatchTrustPolicy.evaluateWazeLifecycleV2(
                        NavigatorPatchTrustPolicy.WAZE_PROJECT_SIGNER,
                        true, false, false);
        NavigatorPatchTrustPolicy.Decision wrongHash =
                NavigatorPatchTrustPolicy.evaluateWazeLifecycleV2(
                        NavigatorPatchTrustPolicy.WAZE_PROJECT_SIGNER,
                        false, false, true);

        assertTrue(exact.accepted);
        assertEquals(NavigatorPatchTrustPolicy.Origin.WAZE_PROJECT, exact.origin);
        assertFalse(wrongHash.accepted);
        assertEquals("TRUST_WAZE_PROJECT_ASSET_MISMATCH", wrongHash.code);
    }

    @Test
    public void lifecycleV2LocalSignerRequiresCurrentPatchVerification() {
        NavigatorPatchTrustPolicy.Decision current =
                NavigatorPatchTrustPolicy.evaluateWazeLifecycleV2(
                        "BB".repeat(32), false, true, true);
        NavigatorPatchTrustPolicy.Decision stale =
                NavigatorPatchTrustPolicy.evaluateWazeLifecycleV2(
                        "BB".repeat(32), false, true, false);

        assertTrue(current.accepted);
        assertEquals(NavigatorPatchTrustPolicy.Origin.DEVICE_LOCAL, current.origin);
        assertFalse(stale.accepted);
        assertEquals("TRUST_LOCAL_LIFECYCLE_V2_UNVERIFIED", stale.code);
    }

    @Test
    public void lifecycleV2RejectsStockAndUnknownSigners() {
        NavigatorPatchTrustPolicy.Decision stock =
                NavigatorPatchTrustPolicy.evaluateWazeLifecycleV2(
                        NavigatorPatchTrustPolicy.WAZE_STOCK_SIGNER,
                        false, false, true);
        NavigatorPatchTrustPolicy.Decision unknown =
                NavigatorPatchTrustPolicy.evaluateWazeLifecycleV2(
                        "CC".repeat(32), false, false, true);

        assertFalse(stock.accepted);
        assertEquals("TRUST_WAZE_STOCK_LIFECYCLE_V2_UNAVAILABLE", stock.code);
        assertFalse(unknown.accepted);
        assertEquals("TRUST_UNKNOWN_SIGNER", unknown.code);
    }

    private static void assertOrigin(NavigatorPatchStore.Profile profile, String signer,
            boolean marker, NavigatorPatchTrustPolicy.Origin expectedOrigin) {
        NavigatorPatchTrustPolicy.Decision decision =
                NavigatorPatchTrustPolicy.evaluate(profile, signer, marker, false);
        assertTrue(decision.accepted);
        assertEquals("TRUST_ACCEPTED", decision.code);
        assertEquals(expectedOrigin, decision.origin);
    }

    private static NavigatorPatchTrustPolicy.Decision evaluateMorphe(
            String signer, String versionName, long versionCode, String fingerprint) {
        return NavigatorPatchTrustPolicy.evaluate(
                NavigatorPatchStore.Profile.GMAPS, signer,
                versionName, versionCode, fingerprint, false, false);
    }
}
