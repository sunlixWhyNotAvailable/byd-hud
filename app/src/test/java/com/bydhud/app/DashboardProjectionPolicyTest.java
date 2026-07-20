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

    private static DashboardProjectionPolicy.ObservedDisplay classify(
            NavAppDisplayState state,
            String activePackage,
            int activeDisplay) {
        return DashboardProjectionPolicy.classifyObservedDisplay(
                "com.waze", state, activePackage, activeDisplay);
    }
}
