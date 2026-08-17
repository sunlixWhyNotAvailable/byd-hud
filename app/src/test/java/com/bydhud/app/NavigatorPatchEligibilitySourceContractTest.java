package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public final class NavigatorPatchEligibilitySourceContractTest {
    @Test
    public void patchEligibilityDoesNotUseSignerOrCatalogTrust() throws Exception {
        String pipeline = source("NavigatorPatchPipeline.java");
        String policy = source("NavigatorAssetSignerCatalog.java");

        assertFalse(pipeline.contains("requireTrustedSource"));
        assertFalse(pipeline.contains("NavigatorPatchTrustPolicy"));
        assertFalse(policy.contains("static Decision evaluate"));
        assertFalse(policy.contains("evaluateWazeLifecycleV2"));
        assertTrue(policy.contains("must never be used as navigator patch or runtime eligibility"));
    }

    @Test
    public void apkSetStillValidatesPackageTopologyAndSignatureIntegrity() throws Exception {
        String apkSet = source("NavigatorApkSet.java");

        assertTrue(apkSet.contains("ApkVerifier.Builder"));
        assertTrue(apkSet.contains("APK-set signer mismatch"));
        assertTrue(apkSet.contains("APK-set package mismatch"));
        assertTrue(apkSet.contains("APK-set base topology is ambiguous"));
    }

    @Test
    public void componentSchemaScanCacheIsInvalidated() throws Exception {
        String store = source("NavigatorPatchStore.java");

        assertTrue(store.contains("SCAN_CACHE_REVISION = 7"));
    }

    @Test
    public void unchangedOutputCannotBecomeInstallReady() throws Exception {
        String pipeline = source("NavigatorPatchPipeline.java");

        assertTrue(pipeline.contains("if (!directApplied && !optionalApplied)"));
        assertTrue(pipeline.contains("No patch component was applied"));
    }

    private static String source(String name) throws Exception {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve("app/src/main/java/com/bydhud/app/")
                .resolve(name).normalize();
        if (!Files.isRegularFile(file)) {
            file = root.resolve("src/main/java/com/bydhud/app/")
                    .resolve(name).normalize();
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
