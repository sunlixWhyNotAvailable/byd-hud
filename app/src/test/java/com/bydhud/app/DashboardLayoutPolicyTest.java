package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DashboardLayoutPolicyTest {
    @Test
    public void fullAlwaysUsesNativeProtocolAndReleasesAnyRealLeaseFirst() {
        assertTrue(DashboardLayoutPolicy.usesFullProtocol(HudPrefs.DASHBOARD_MODE_FULL));
        assertFalse(DashboardLayoutPolicy.shouldReleaseBeforeDashboard(
                HudPrefs.DASHBOARD_MODE_FULL, DashboardLayoutPolicy.OWNERSHIP_NONE));
        assertTrue(DashboardLayoutPolicy.shouldReleaseBeforeDashboard(
                HudPrefs.DASHBOARD_MODE_FULL, DashboardLayoutPolicy.OWNERSHIP_MINI));
        assertTrue(DashboardLayoutPolicy.shouldReleaseBeforeDashboard(
                HudPrefs.DASHBOARD_MODE_FULL, DashboardLayoutPolicy.OWNERSHIP_LEGACY));
        assertEquals(4, DashboardLayoutPolicy.PROTOCOL_FULL);
    }

    @Test
    public void miniReusesOnlyConfirmedMiniOwnership() {
        assertFalse(DashboardLayoutPolicy.usesFullProtocol(HudPrefs.DASHBOARD_MODE_PARTIAL));
        assertFalse(DashboardLayoutPolicy.shouldReleaseBeforeDashboard(
                HudPrefs.DASHBOARD_MODE_PARTIAL, DashboardLayoutPolicy.OWNERSHIP_NONE));
        assertFalse(DashboardLayoutPolicy.shouldReleaseBeforeDashboard(
                HudPrefs.DASHBOARD_MODE_PARTIAL, DashboardLayoutPolicy.OWNERSHIP_MINI));
        assertTrue(DashboardLayoutPolicy.shouldReleaseBeforeDashboard(
                HudPrefs.DASHBOARD_MODE_PARTIAL, DashboardLayoutPolicy.OWNERSHIP_LEGACY));
        assertEquals(17, DashboardLayoutPolicy.AUTOCONTAINER_MINI);
    }

    @Test
    public void successfulMiniCommandDefinesOwnershipIndependentOfGeometryPreference() {
        assertEquals(DashboardLayoutPolicy.OWNERSHIP_MINI,
                DashboardLayoutPolicy.ownershipKind(17, true));
        assertFalse(DashboardLayoutPolicy.shouldReleaseBeforeDashboard(
                HudPrefs.DASHBOARD_MODE_PARTIAL,
                DashboardLayoutPolicy.ownershipKind(17, true)));
        assertEquals(DashboardLayoutPolicy.OWNERSHIP_LEGACY,
                DashboardLayoutPolicy.ownershipKind(0, true));
        assertEquals(DashboardLayoutPolicy.OWNERSHIP_LEGACY,
                DashboardLayoutPolicy.ownershipKind(16, false));
    }

    @Test
    public void onlyContinuousMiniReplacementRetainsTheLease() {
        assertTrue(DashboardLayoutPolicy.shouldRetainMiniLeaseForReplacement(
                HudPrefs.DASHBOARD_MODE_PARTIAL,
                DashboardLayoutPolicy.OWNERSHIP_MINI));
        assertFalse(DashboardLayoutPolicy.shouldRetainMiniLeaseForReplacement(
                HudPrefs.DASHBOARD_MODE_FULL,
                DashboardLayoutPolicy.OWNERSHIP_MINI));
        assertFalse(DashboardLayoutPolicy.shouldRetainMiniLeaseForReplacement(
                HudPrefs.DASHBOARD_MODE_PARTIAL,
                DashboardLayoutPolicy.OWNERSHIP_LEGACY));
    }

    @Test
    public void widgetAndIpcOffReleaseOnlyProvenAutoContainerOwnership() {
        assertFalse(DashboardLayoutPolicy.shouldReleaseBeforeWidget(
                NavAppDisplayController.WIDGET_MODE_MINI,
                DashboardLayoutPolicy.OWNERSHIP_MINI));
        assertTrue(DashboardLayoutPolicy.shouldReleaseBeforeWidget(
                NavAppDisplayController.WIDGET_MODE_MINI,
                DashboardLayoutPolicy.OWNERSHIP_LEGACY));
        assertTrue(DashboardLayoutPolicy.shouldReleaseBeforeWidget(
                NavAppDisplayController.WIDGET_MODE_FULL,
                DashboardLayoutPolicy.OWNERSHIP_MINI));
        assertTrue(DashboardLayoutPolicy.shouldReleaseBeforeWidget(
                NavAppDisplayController.WIDGET_MODE_TBT,
                DashboardLayoutPolicy.OWNERSHIP_MINI));
        assertEquals(18, DashboardLayoutPolicy.ipcOffCommand(
                DashboardLayoutPolicy.OWNERSHIP_MINI));
        assertEquals(1, DashboardLayoutPolicy.ipcOffCommand(
                DashboardLayoutPolicy.OWNERSHIP_NONE));
    }
}
