package com.bydhud.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DashboardProjectionPolicyTest {
    @Test
    public void mainDisplayIsDerivedFromObservedTask() {
        assertEquals(
                DashboardProjectionPolicy.ObservedDisplay.MAIN,
                classify(new NavAppDisplayState("com.waze", 12, 0, true, "main"),
                        "com.waze", 8));
    }

    @Test
    public void dashboardRequiresExactLiveOwnerAndDisplay() {
        NavAppDisplayState state = new NavAppDisplayState(
                "com.waze", 12, 8, true, "dashboard");

        assertEquals(DashboardProjectionPolicy.ObservedDisplay.DASHBOARD,
                classify(state, "com.waze", 8));
        assertEquals(DashboardProjectionPolicy.ObservedDisplay.OTHER,
                classify(state, "", 8));
        assertEquals(DashboardProjectionPolicy.ObservedDisplay.OTHER,
                classify(state, "com.waze", 9));
    }

    @Test
    public void missingOrForeignTaskIsUnknown() {
        assertEquals(DashboardProjectionPolicy.ObservedDisplay.UNKNOWN,
                classify(null, "com.waze", 8));
        assertEquals(DashboardProjectionPolicy.ObservedDisplay.UNKNOWN,
                classify(new NavAppDisplayState("com.google.android.apps.maps",
                        12, 8, true, "foreign"), "com.waze", 8));
        assertEquals(DashboardProjectionPolicy.ObservedDisplay.UNKNOWN,
                classify(new NavAppDisplayState("com.waze",
                        -1, NavAppDisplayState.DISPLAY_UNKNOWN, false, "stale"),
                        "com.waze", 8));
    }

    @Test
    public void heightGeometryIsClampedAndVerticallyCentered() {
        DashboardProjectionPolicy.Profile minimumProfile =
                new DashboardProjectionPolicy.Profile(100, 10, 50, 100);
        DashboardProjectionPolicy.Geometry minimum =
                DashboardProjectionPolicy.geometryForProfile(minimumProfile);
        assertEquals(20, minimumProfile.heightPercent);
        assertEquals(1920, minimum.width);
        assertEquals(1920, minimum.bufferWidth);
        assertEquals(144, minimum.height);
        assertEquals(144, minimum.bufferHeight);
        assertEquals(320, minimum.density);
        assertEquals(288, minimum.top);

        DashboardProjectionPolicy.Geometry roundedDown =
                DashboardProjectionPolicy.geometryForProfile(
                        new DashboardProjectionPolicy.Profile(100, 99, 50, 100));
        assertEquals(712, roundedDown.height);
        assertEquals(4, roundedDown.top);

        DashboardProjectionPolicy.Profile maximumProfile =
                new DashboardProjectionPolicy.Profile(100, 120, 50, 100);
        DashboardProjectionPolicy.Geometry maximum =
                DashboardProjectionPolicy.geometryForProfile(maximumProfile);
        assertEquals(100, maximumProfile.heightPercent);
        assertEquals(720, maximum.height);
        assertEquals(0, maximum.top);
    }

    @Test
    public void profileGeometryUsesRemainingSpaceOffsetAndInverseScale() {
        DashboardProjectionPolicy.Geometry geometry = DashboardProjectionPolicy.geometryForProfile(
                new DashboardProjectionPolicy.Profile(90, 80, 50, 150));
        assertEquals(1728, geometry.width);
        assertEquals(576, geometry.height);
        assertEquals(96, geometry.left);
        assertEquals(72, geometry.top);
        assertEquals(1152, geometry.bufferWidth);
        assertEquals(384, geometry.bufferHeight);
        assertEquals(320, geometry.density);
    }

    @Test
    public void widthOffsetUsesRemainingSpaceAtBothEdges() {
        assertEquals(0, DashboardProjectionPolicy.geometryForProfile(
                new DashboardProjectionPolicy.Profile(90, 100, 0, 100)).left);
        assertEquals(96, DashboardProjectionPolicy.geometryForProfile(
                new DashboardProjectionPolicy.Profile(90, 100, 50, 100)).left);
        assertEquals(192, DashboardProjectionPolicy.geometryForProfile(
                new DashboardProjectionPolicy.Profile(90, 100, 100, 100)).left);
    }

    @Test
    public void scaleRangeChangesOnlyBufferDimensions() {
        DashboardProjectionPolicy.Geometry smaller = DashboardProjectionPolicy.geometryForProfile(
                new DashboardProjectionPolicy.Profile(90, 80, 50, 50));
        DashboardProjectionPolicy.Geometry nativeSize = DashboardProjectionPolicy.geometryForProfile(
                new DashboardProjectionPolicy.Profile(90, 80, 50, 100));
        DashboardProjectionPolicy.Geometry larger = DashboardProjectionPolicy.geometryForProfile(
                new DashboardProjectionPolicy.Profile(90, 80, 50, 150));
        assertEquals(1728, smaller.width);
        assertEquals(576, smaller.height);
        assertEquals(3456, smaller.bufferWidth);
        assertEquals(1152, smaller.bufferHeight);
        assertEquals(1728, nativeSize.bufferWidth);
        assertEquals(576, nativeSize.bufferHeight);
        assertEquals(1152, larger.bufferWidth);
        assertEquals(384, larger.bufferHeight);
    }

    @Test
    public void noneModeUsesNativeGeometryRegardlessOfStoredProfile() {
        DashboardProjectionPolicy.Profile nativeProfile =
                DashboardProjectionPolicy.nativeProfileForMode(
                        HudPrefs.DASHBOARD_MODE_NONE,
                        new DashboardProjectionPolicy.Profile(20, 20, 0, 150));
        DashboardProjectionPolicy.Geometry geometry =
                DashboardProjectionPolicy.geometryForProfile(nativeProfile);
        assertEquals(1920, geometry.width);
        assertEquals(720, geometry.height);
        assertEquals(1920, geometry.bufferWidth);
        assertEquals(720, geometry.bufferHeight);
        assertEquals(0, geometry.left);
        assertEquals(0, geometry.top);
    }

    @Test
    public void profileValuesClampToSafeRuntimeBounds() {
        DashboardProjectionPolicy.Profile profile = new DashboardProjectionPolicy.Profile(
                0, 200, -1, 500);
        assertEquals(20, profile.widthPercent);
        assertEquals(100, profile.heightPercent);
        assertEquals(0, profile.offsetPercent);
        assertEquals(150, profile.scalePercent);
    }

    private static DashboardProjectionPolicy.ObservedDisplay classify(
            NavAppDisplayState state,
            String activePackage,
            int activeDisplay) {
        return DashboardProjectionPolicy.classifyObservedDisplay(
                "com.waze", state, activePackage, activeDisplay);
    }
}
