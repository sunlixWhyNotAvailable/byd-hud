package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class GMapsDirectFallbackPolicyTest {
    @Test
    public void directPackagesDoNotRequireLegacyCaptureAtStart() {
        assertFalse(MainActivity.requiresLegacyCaptureRuntimeReady("com.waze"));
        assertFalse(MainActivity.requiresLegacyCaptureRuntimeReady(
                GMapsDirectChannel.PACKAGE_NAME));
        assertTrue(MainActivity.requiresLegacyCaptureRuntimeReady(
                "com.google.android.apps.maps"));
    }

    @Test
    public void fallbackRequiresReadyCaptureAndAnActiveDirectPackage() {
        assertTrue(NavHudLiveSender.shouldActivateGMapsLegacyFallback(
                true, GMapsDirectChannel.PACKAGE_NAME, false, false, true));
        assertFalse(NavHudLiveSender.shouldActivateGMapsLegacyFallback(
                true, GMapsDirectChannel.PACKAGE_NAME, false, false, false));
        assertFalse(NavHudLiveSender.shouldActivateGMapsLegacyFallback(
                true, GMapsDirectChannel.PACKAGE_NAME, true, false, true));
        assertFalse(NavHudLiveSender.shouldActivateGMapsLegacyFallback(
                true, GMapsDirectChannel.PACKAGE_NAME, false, true, true));
        assertFalse(NavHudLiveSender.shouldActivateGMapsLegacyFallback(
                true, "com.google.android.apps.maps", false, false, true));
    }

    @Test
    public void activeFallbackStopsWhenCaptureIsLostAndSurvivesDirectStart() {
        assertTrue(NavHudLiveSender.shouldDeactivateGMapsLegacyFallback(true, false));
        assertFalse(NavHudLiveSender.shouldDeactivateGMapsLegacyFallback(true, true));
        assertFalse(NavHudLiveSender.shouldDeactivateGMapsLegacyFallback(false, false));
        assertTrue(NavHudLiveSender.shouldKeepGMapsFallbackOnDirectStart(true));
        assertFalse(NavHudLiveSender.shouldKeepGMapsFallbackOnDirectStart(false));
    }

    @Test
    public void speedBeforeSyntheticFirstFrameSurvivesUntilExplicitStart() {
        String owner = GMapsDirectChannel.PACKAGE_NAME;
        DirectSpeedLimitStore.clear(owner);
        DirectSpeedLimitStore.update(owner, 50, 50, "km/h", 1L);

        if (NavHudLiveSender.shouldClearGMapsSpeedLimitOnDirectStart("first-frame")) {
            DirectSpeedLimitStore.clear(owner);
        }
        assertTrue(DirectSpeedLimitStore.snapshot(owner).isActive());

        if (NavHudLiveSender.shouldClearGMapsSpeedLimitOnDirectStart("start")) {
            DirectSpeedLimitStore.clear(owner);
        }
        assertFalse(DirectSpeedLimitStore.snapshot(owner).isActive());
    }
}
