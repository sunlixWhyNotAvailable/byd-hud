package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DashboardLayoutPolicyTest {
    @Test public void partialWithoutSwitchingReleasesOldOwnershipButDoesNotSelectOemLayout() {
        int command = DashboardLayoutPolicy.layoutCommand(HudPrefs.DASHBOARD_MODE_PARTIAL,
                HudPrefs.DASHBOARD_FORMAT_NO_SWITCHING);
        assertEquals(DashboardLayoutPolicy.NO_LAYOUT_COMMAND, command);
        assertTrue(DashboardLayoutPolicy.shouldReleaseBeforeDashboard(command,
                DashboardLayoutPolicy.OWNERSHIP_AUTOCONTAINER));
        assertTrue(DashboardLayoutPolicy.shouldReleaseBeforeWidget(
                NavAppDisplayController.WIDGET_MODE_MINI, command,
                DashboardLayoutPolicy.OWNERSHIP_AUTOCONTAINER));
        assertFalse(DashboardLayoutPolicy.shouldRetainLeaseForReplacement(command,
                DashboardLayoutPolicy.OWNERSHIP_AUTOCONTAINER));
        assertFalse(DashboardLayoutPolicy.shouldReleaseBeforeDashboard(command,
                DashboardLayoutPolicy.OWNERSHIP_NONE));
        assertEquals(DashboardLayoutPolicy.PROTOCOL_FULL,
                DashboardLayoutPolicy.layoutCommand(HudPrefs.DASHBOARD_MODE_FULL,
                        HudPrefs.DASHBOARD_FORMAT_NO_SWITCHING));
        assertEquals(DashboardLayoutPolicy.AUTOCONTAINER_MINI,
                DashboardLayoutPolicy.layoutCommand(HudPrefs.DASHBOARD_MODE_PARTIAL, -1));
    }
}
