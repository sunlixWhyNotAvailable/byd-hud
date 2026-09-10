package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DashboardLayoutPolicyTest {
    @Test
    public void approvedModeAndMethodMatrixSelectsExactCommands() {
        assertEquals(4, DashboardLayoutPolicy.layoutCommand(
                HudPrefs.DASHBOARD_MODE_FULL, HudPrefs.DASHBOARD_FORMAT_NATIVE));
        assertEquals(3, DashboardLayoutPolicy.layoutCommand(
                HudPrefs.DASHBOARD_MODE_PARTIAL, HudPrefs.DASHBOARD_FORMAT_NATIVE));
        assertEquals(16, DashboardLayoutPolicy.layoutCommand(
                HudPrefs.DASHBOARD_MODE_FULL, HudPrefs.DASHBOARD_FORMAT_ALTERNATIVE));
        assertEquals(17, DashboardLayoutPolicy.layoutCommand(
                HudPrefs.DASHBOARD_MODE_PARTIAL, HudPrefs.DASHBOARD_FORMAT_ALTERNATIVE));
        assertEquals(0, DashboardLayoutPolicy.layoutCommand(
                HudPrefs.DASHBOARD_MODE_NONE, HudPrefs.DASHBOARD_FORMAT_ALTERNATIVE));
    }

    @Test
    public void nativeReleasesAnyActualAutoContainerOwnershipFirst() {
        assertFalse(DashboardLayoutPolicy.shouldReleaseBeforeDashboard(
                DashboardLayoutPolicy.PROTOCOL_FULL, DashboardLayoutPolicy.OWNERSHIP_NONE));
        assertTrue(DashboardLayoutPolicy.shouldReleaseBeforeDashboard(
                DashboardLayoutPolicy.PROTOCOL_FULL, DashboardLayoutPolicy.OWNERSHIP_AUTOCONTAINER));
        assertTrue(DashboardLayoutPolicy.shouldReleaseBeforeDashboard(
                DashboardLayoutPolicy.PROTOCOL_PARTIAL, DashboardLayoutPolicy.OWNERSHIP_AUTOCONTAINER));
    }

    @Test
    public void autoContainerTransitionsRetainEitherActualMechanismOwnership() {
        assertFalse(DashboardLayoutPolicy.shouldReleaseBeforeDashboard(
                DashboardLayoutPolicy.AUTOCONTAINER_FULL, DashboardLayoutPolicy.OWNERSHIP_NONE));
        assertFalse(DashboardLayoutPolicy.shouldReleaseBeforeDashboard(
                DashboardLayoutPolicy.AUTOCONTAINER_FULL, DashboardLayoutPolicy.OWNERSHIP_AUTOCONTAINER));
        assertFalse(DashboardLayoutPolicy.shouldReleaseBeforeDashboard(
                DashboardLayoutPolicy.AUTOCONTAINER_MINI, DashboardLayoutPolicy.OWNERSHIP_AUTOCONTAINER));
    }

    @Test
    public void successfulMiniCommandDefinesOwnershipIndependentOfGeometryPreference() {
        assertEquals(DashboardLayoutPolicy.OWNERSHIP_AUTOCONTAINER,
                DashboardLayoutPolicy.ownershipKind(17, true));
        assertFalse(DashboardLayoutPolicy.shouldReleaseBeforeDashboard(
                DashboardLayoutPolicy.AUTOCONTAINER_MINI,
                DashboardLayoutPolicy.ownershipKind(17, true)));
        assertEquals(DashboardLayoutPolicy.OWNERSHIP_AUTOCONTAINER,
                DashboardLayoutPolicy.ownershipKind(0, true));
        assertEquals(DashboardLayoutPolicy.OWNERSHIP_AUTOCONTAINER,
                DashboardLayoutPolicy.ownershipKind(16, false));
    }

    @Test
    public void onlyContinuousAutoContainerReplacementRetainsTheLease() {
        assertTrue(DashboardLayoutPolicy.shouldRetainLeaseForReplacement(
                DashboardLayoutPolicy.AUTOCONTAINER_MINI,
                DashboardLayoutPolicy.OWNERSHIP_AUTOCONTAINER));
        assertTrue(DashboardLayoutPolicy.shouldRetainLeaseForReplacement(
                DashboardLayoutPolicy.AUTOCONTAINER_FULL,
                DashboardLayoutPolicy.OWNERSHIP_AUTOCONTAINER));
        assertFalse(DashboardLayoutPolicy.shouldRetainLeaseForReplacement(
                DashboardLayoutPolicy.PROTOCOL_FULL,
                DashboardLayoutPolicy.OWNERSHIP_AUTOCONTAINER));
    }

    @Test
    public void widgetAndIpcOffReleaseOnlyProvenAutoContainerOwnership() {
        assertTrue(DashboardLayoutPolicy.shouldReleaseBeforeWidget(
                NavAppDisplayController.WIDGET_MODE_MINI,
                DashboardLayoutPolicy.PROTOCOL_PARTIAL,
                DashboardLayoutPolicy.OWNERSHIP_AUTOCONTAINER));
        assertFalse(DashboardLayoutPolicy.shouldReleaseBeforeWidget(
                NavAppDisplayController.WIDGET_MODE_MINI,
                DashboardLayoutPolicy.AUTOCONTAINER_MINI,
                DashboardLayoutPolicy.OWNERSHIP_AUTOCONTAINER));
        assertTrue(DashboardLayoutPolicy.shouldReleaseBeforeWidget(
                NavAppDisplayController.WIDGET_MODE_FULL,
                DashboardLayoutPolicy.PROTOCOL_FULL,
                DashboardLayoutPolicy.OWNERSHIP_AUTOCONTAINER));
        assertFalse(DashboardLayoutPolicy.shouldReleaseBeforeWidget(
                NavAppDisplayController.WIDGET_MODE_FULL,
                DashboardLayoutPolicy.AUTOCONTAINER_FULL,
                DashboardLayoutPolicy.OWNERSHIP_AUTOCONTAINER));
        assertTrue(DashboardLayoutPolicy.shouldReleaseBeforeWidget(
                NavAppDisplayController.WIDGET_MODE_TBT,
                0,
                DashboardLayoutPolicy.OWNERSHIP_AUTOCONTAINER));
        assertEquals(18, DashboardLayoutPolicy.ipcOffCommand(
                DashboardLayoutPolicy.OWNERSHIP_AUTOCONTAINER));
        assertEquals(1, DashboardLayoutPolicy.ipcOffCommand(
                DashboardLayoutPolicy.OWNERSHIP_NONE));
    }
}
