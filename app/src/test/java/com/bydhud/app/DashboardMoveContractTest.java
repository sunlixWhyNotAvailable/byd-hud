package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

public final class DashboardMoveContractTest {
    @Test
    public void dashboardModePreferenceMigratesTheLegacyFullscreenBoolean() throws Exception {
        assertEquals(HudPrefs.DASHBOARD_MODE_NONE,
                HudPrefs.normalizeDashboardScreenMode(-1));
        assertEquals(HudPrefs.DASHBOARD_MODE_PARTIAL,
                HudPrefs.normalizeDashboardScreenMode(HudPrefs.DASHBOARD_MODE_PARTIAL));
        assertEquals(HudPrefs.DASHBOARD_MODE_FULL,
                HudPrefs.normalizeDashboardScreenMode(3));

        java.nio.file.Path file = Paths.get(
                "app/src/main/java/com/bydhud/app/HudPrefs.java");
        if (!Files.exists(file)) {
            file = Paths.get("src/main/java/com/bydhud/app/HudPrefs.java");
        }
        String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(source.contains("if (!preferences.contains(KEY_DASHBOARD_SCREEN_MODE))"));
        assertTrue(source.contains("preferences.getBoolean(KEY_FULLSCREEN_DASHBOARD, true)"));
        assertTrue(source.contains("? DASHBOARD_MODE_FULL : DASHBOARD_MODE_NONE"));
        assertTrue(source.contains("putInt(KEY_DASHBOARD_SCREEN_MODE, migrated)"));
    }

    @Test
    public void projectionPersistsTheExplicitModeForStickyRecovery() throws Exception {
        java.nio.file.Path controller = Paths.get(
                "app/src/main/java/com/bydhud/app/NavAppDisplayController.java");
        if (!Files.exists(controller)) {
            controller = Paths.get("src/main/java/com/bydhud/app/NavAppDisplayController.java");
        }
        String controllerSource = new String(
                Files.readAllBytes(controller), StandardCharsets.UTF_8);
        assertTrue(controllerSource.contains("KEY_ACTIVE_MODE"));
        assertTrue(controllerSource.contains("persistedDashboardMode()"));
        assertTrue(controllerSource.contains(
                "persistDashboardProjection(normalized, normalizedMode, reason)"));

        java.nio.file.Path service = Paths.get(
                "app/src/main/java/com/bydhud/app/ClusterProjectionService.java");
        if (!Files.exists(service)) {
            service = Paths.get("src/main/java/com/bydhud/app/ClusterProjectionService.java");
        }
        String serviceSource = new String(
                Files.readAllBytes(service), StandardCharsets.UTF_8);
        assertTrue(serviceSource.contains("EXTRA_MODE"));
        assertTrue(serviceSource.contains(
                "requestProjection(packageName, dashboardMode, reason)"));
        assertTrue(serviceSource.contains(
                "requestProjection(packageName, dashboardMode, \"restore:\""));
        int resizeStart = serviceSource.indexOf("private void resizeActiveProjection(");
        int resizeEnd = serviceSource.indexOf(
                "private void recoverProjectionAfterResizeFailure(", resizeStart);
        String resize = serviceSource.substring(resizeStart, resizeEnd);
        assertTrue(resize.contains("projectionGeometryValid = false;"));
        assertTrue(resize.contains("surfaceGeneration++;"));
        assertTrue(resize.contains("projectionGeometryValid = true;"));
        assertTrue(resize.contains("|| !projectionGeometryValid"));
        assertTrue(resize.contains("view.getHolder().setFixedSize(oldBufferWidth, oldBufferHeight)"));
        assertTrue(resize.contains("display.resize(oldBufferWidth, oldBufferHeight"));
        assertTrue(resize.contains("params.width = oldWidth;"));
        assertTrue(resize.contains("params.x = oldLeft;"));
        int requestStart = serviceSource.indexOf("private void requestProjection(");
        int requestEnd = serviceSource.indexOf("private void returnPackageToMain(", requestStart);
        String request = serviceSource.substring(requestStart, requestEnd);
        int invalidMoveGuard = request.indexOf(
                "virtualDisplay != existing || !projectionGeometryValid");
        assertTrue(invalidMoveGuard >= 0);
        assertTrue(request.indexOf("movePackageToDisplay(", invalidMoveGuard) > invalidMoveGuard);
    }

    @Test
    public void autoContainerPolicyOnlySelectsExplicitTransitions() {
        assertEquals(16, NavAppDisplayController.autoContainerValueForTest(
                true, HudPrefs.DASHBOARD_MODE_FULL, true));
        assertEquals(17, NavAppDisplayController.autoContainerValueForTest(
                true, HudPrefs.DASHBOARD_MODE_PARTIAL, true));
        assertEquals(0, NavAppDisplayController.autoContainerValueForTest(
                true, HudPrefs.DASHBOARD_MODE_NONE, true));
        assertEquals(0, NavAppDisplayController.autoContainerValueForTest(
                true, HudPrefs.DASHBOARD_MODE_FULL, false));
        assertEquals(0, NavAppDisplayController.autoContainerValueForTest(
                false, HudPrefs.DASHBOARD_MODE_FULL, true));
        assertTrue(NavAppDisplayController.isUserRequestedReturnForTest(
                "ui-independent-dashboard-explicit"));
        assertFalse(NavAppDisplayController.isUserRequestedReturnForTest("shutdown"));
        assertFalse(NavAppDisplayController.isUserRequestedReturnForTest("hud-switch-to-gmaps"));
        assertTrue(NavAppDisplayController.isDirectNavigatorReplacement(
                "com.waze", GMapsDirectChannel.PACKAGE_NAME));
        assertTrue(NavAppDisplayController.isDirectNavigatorReplacement(
                GMapsDirectChannel.PACKAGE_NAME, "com.waze"));
        assertFalse(NavAppDisplayController.isDirectNavigatorReplacement(
                "com.waze", "com.waze"));
        assertFalse(NavAppDisplayController.isDirectNavigatorReplacement(
                "com.waze", "com.example.navigation"));
        assertTrue(NavAppDisplayController.shouldPrepareAutoContainerLeaseTransfer(
                "com.waze", GMapsDirectChannel.PACKAGE_NAME, "com.waze", 7L));
        assertFalse(NavAppDisplayController.shouldPrepareAutoContainerLeaseTransfer(
                "", GMapsDirectChannel.PACKAGE_NAME, "com.waze", 7L));
        assertFalse(NavAppDisplayController.shouldPrepareAutoContainerLeaseTransfer(
                "com.waze", GMapsDirectChannel.PACKAGE_NAME, "com.waze", 0L));
    }

    @Test
    public void failedSuccessorReleaseRequiresBothTasksNoOwnerAndExactLease() {
        assertTrue(NavAppDisplayController.shouldReleaseAutoContainerLeaseAfterFailedSuccessorForTest(
                true, true, true,
                "com.waze", GMapsDirectChannel.PACKAGE_NAME,
                "com.waze", 7L, "com.waze", 7L));
        assertFalse(NavAppDisplayController.shouldReleaseAutoContainerLeaseAfterFailedSuccessorForTest(
                false, true, true,
                "com.waze", GMapsDirectChannel.PACKAGE_NAME,
                "com.waze", 7L, "com.waze", 7L));
        assertFalse(NavAppDisplayController.shouldReleaseAutoContainerLeaseAfterFailedSuccessorForTest(
                true, false, true,
                "com.waze", GMapsDirectChannel.PACKAGE_NAME,
                "com.waze", 7L, "com.waze", 7L));
        assertFalse(NavAppDisplayController.shouldReleaseAutoContainerLeaseAfterFailedSuccessorForTest(
                true, true, false,
                "com.waze", GMapsDirectChannel.PACKAGE_NAME,
                "com.waze", 7L, "com.waze", 7L));
        assertFalse(NavAppDisplayController.shouldReleaseAutoContainerLeaseAfterFailedSuccessorForTest(
                true, true, true,
                "com.waze", GMapsDirectChannel.PACKAGE_NAME,
                "com.waze", 7L, GMapsDirectChannel.PACKAGE_NAME, 7L));
        assertFalse(NavAppDisplayController.shouldReleaseAutoContainerLeaseAfterFailedSuccessorForTest(
                true, true, true,
                "com.waze", GMapsDirectChannel.PACKAGE_NAME,
                "com.waze", 7L, "com.waze", 8L));
        assertFalse(NavAppDisplayController.shouldReleaseAutoContainerLeaseAfterFailedSuccessorForTest(
                true, true, true,
                "com.waze", "com.example.navigation",
                "com.waze", 7L, "com.waze", 7L));
    }

    @Test
    public void returnTbtReassertRequiresTheExactCurrentHudRoute() {
        assertTrue(NavHudLiveSender.shouldReassertTbtAfterDashboardReturnForTest(
                true, true, false, true, true,
                "com.waze", "com.waze", 7L, 7L));
        assertFalse(NavHudLiveSender.shouldReassertTbtAfterDashboardReturnForTest(
                false, true, false, true, true,
                "com.waze", "com.waze", 7L, 7L));
        assertFalse(NavHudLiveSender.shouldReassertTbtAfterDashboardReturnForTest(
                true, true, true, true, true,
                "com.waze", "com.waze", 7L, 7L));
        assertFalse(NavHudLiveSender.shouldReassertTbtAfterDashboardReturnForTest(
                true, true, false, false, true,
                "com.waze", "com.waze", 7L, 7L));
        assertFalse(NavHudLiveSender.shouldReassertTbtAfterDashboardReturnForTest(
                true, true, false, true, true,
                "com.waze", "com.waze", 7L, 8L));
        assertFalse(NavHudLiveSender.shouldReassertTbtAfterDashboardReturnForTest(
                true, true, false, true, true,
                "com.waze", GMapsDirectChannel.PACKAGE_NAME, 7L, 7L));
        assertTrue(VehicleTbtPublisher.shouldReassertDashboardForTest(
                true, true, "com.waze", 7L, "com.waze", 7L));
        assertFalse(VehicleTbtPublisher.shouldReassertDashboardForTest(
                true, false, "com.waze", 7L, "com.waze", 7L));
    }

    @Test
    public void autoContainerAllowlistAcceptsOnlyDashboardModesAndRelease() {
        assertTrue(LocalAdbBridge.isAllowedRuntimeShellCommandForTest("id"));
        String command = LocalAdbBridge.autoContainerCommandForTest("auto_container", 16);
        assertTrue(LocalAdbBridge.isAllowedRuntimeShellCommandForTest(command));
        assertTrue(LocalAdbBridge.isAllowedRuntimeShellCommandForTest(
                LocalAdbBridge.autoContainerCommandForTest("auto_container", 17)));
        assertTrue(LocalAdbBridge.isAllowedRuntimeShellCommandForTest(
                LocalAdbBridge.autoContainerCommandForTest("AutoContainer", 18)));
        assertFalse(LocalAdbBridge.isAllowedRuntimeShellCommandForTest(
                "service call auto_container 2 i32 1000 i32 35 s16 '\"\"'"));
        assertFalse(LocalAdbBridge.isAllowedRuntimeShellCommandForTest(
                command + "; id"));
        assertTrue(LocalAdbBridge.isSuccessfulAutoContainerResponse(
                0, "Result: Parcel(00000000 00000001)"));
        assertFalse(LocalAdbBridge.isSuccessfulAutoContainerResponse(
                0, "Exception: unknown service"));
    }

    @Test
    public void secureSettingWriterQuotesDollarInnerClassAndKeepsFixedKeys() {
        String command = NavPermissionGrantPlan.secureSettingPutCommandForTest(
                NavPermissionGrantPlan.ACCESSIBILITY_SERVICES,
                ":other/com.example.Outer$Inner:com.bydhud.app/com.bydhud.app.NavAccessibilityService");
        assertEquals(
                "settings put secure enabled_accessibility_services "
                        + "':other/com.example.Outer$Inner:com.bydhud.app/com.bydhud.app.NavAccessibilityService'",
                command);
    }

    @Test
    public void purePlanPreservesExistingInnerClassServices() {
        NavPermissionGrantPlan plan = NavPermissionGrantPlan.fromCurrentSettings(
                "com.bydhud.app",
                "com.example/com.example.Outer$Inner",
                "com.example/.Outer$Inner",
                false,
                true,
                false,
                false);
        assertTrue(plan.isValid());
        assertTrue(plan.accessibilityServicesValue.contains("com.example/.Outer$Inner"));
        assertTrue(plan.accessibilityServicesValue.contains("com.bydhud.app/com.bydhud.app.NavAccessibilityService"));
    }

    @Test
    public void dashboardMovePreflightsBeforeProjectionAndDropsLegacyTaskProtocol() throws Exception {
        java.nio.file.Path file = Paths.get(
                "app/src/main/java/com/bydhud/app/NavAppDisplayController.java");
        if (!Files.exists(file)) {
            file = Paths.get("src/main/java/com/bydhud/app/NavAppDisplayController.java");
        }
        String source = new String(Files.readAllBytes(file),
                StandardCharsets.UTF_8);
        assertTrue(source.indexOf("preflightAuthorizedAdb(packageName, reason)")
                < source.indexOf("ClusterProjectionService.startProjection"));
        assertFalse(source.contains("StockMapProtocol30011"));
        assertTrue(source.contains("dashboard_autocontainer_failed"));
        assertTrue(source.contains("sendAutoContainerIfRequested"));
        assertFalse(source.contains("AUTO_CONTAINER_OFF"));
        assertTrue(source.contains("onDashboardReturnConfirmed"));
        int returnRelease = source.indexOf("projectionReleased = waitForProjectionRelease(");
        int compositorRelease = source.indexOf(
                "releaseAutoContainerLeaseIfRequested(", returnRelease);
        int returnReassert = source.indexOf(
                "requestTbtAfterReturnIfRequested(packageName, projectionReleased, reason)");
        assertTrue(returnRelease >= 0 && compositorRelease > returnRelease);
        assertTrue(returnReassert > compositorRelease);
        assertTrue(source.contains("AUTO_CONTAINER_RELEASE = 18"));
        assertTrue(source.contains("KEY_AUTOCONTAINER_LEASE_GENERATION"));
        assertTrue(source.contains("dashboard_autocontainer_lease_transferred"));
        assertTrue(source.contains("dashboard_autocontainer_lease_retained"));
        assertTrue(source.contains("releaseAutoContainerLeaseAfterFailedSuccessor"));
        assertTrue(source.contains("pendingAutoContainerLeaseTransferGeneration"));
        assertTrue(source.contains("ClusterProjectionService.hasProjectionOwner()"));
        assertTrue(source.contains("dashboard_autocontainer_lease_acquire_skipped_existing="));
        assertFalse(source.contains("AUTO_CONTAINER_OFF"));
        assertTrue(source.contains("AUTO_CONTAINER_PARTIAL"));
        int senderStart = source.indexOf("private String sendAutoContainerIfRequested");
        int senderEnd = source.indexOf("private static String autoContainerStatus", senderStart);
        assertTrue(senderStart >= 0 && senderEnd > senderStart);
        String sender = source.substring(senderStart, senderEnd);
        assertFalse(sender.contains("returnToMain"));
        assertTrue(sender.indexOf("if (value == AUTO_CONTAINER_FULLSCREEN || value == AUTO_CONTAINER_PARTIAL)")
                < sender.indexOf("LocalAdbBridge.runAutoContainer(context, value)"));
        assertTrue(sender.contains("existing AutoContainer lease retained"));

        int failedBranch = source.indexOf(
                "if (!isConfirmedProjectedDashboardDisplay(packageName, confirmed))");
        int failedReturn = source.indexOf("ClusterProjectionService.returnToMain(", failedBranch);
        int failedRelease = source.indexOf(
                "releaseAutoContainerLeaseAfterFailedSuccessor(", failedReturn);
        int failedRemember = source.indexOf(
                "independent dashboard projection not confirmed", failedRelease);
        assertTrue(failedReturn >= 0 && failedRelease > failedReturn && failedRemember > failedRelease);
        int failedHelper = source.indexOf(
                "private void releaseAutoContainerLeaseAfterFailedSuccessor(", failedRelease);
        int noOwner = source.indexOf("waitForProjectionRelease(", failedHelper);
        int failedLeaseRelease = source.indexOf("releaseAutoContainerLease(", noOwner);
        assertTrue(failedHelper > failedRelease
                && noOwner > failedHelper
                && failedLeaseRelease > noOwner);

        int leaseReleaseStart = source.indexOf("private void releaseAutoContainerLease(");
        int leaseReleaseEnd = source.indexOf(
                "private void releaseAutoContainerLeaseAfterFailedSuccessor(", leaseReleaseStart);
        String leaseRelease = source.substring(leaseReleaseStart, leaseReleaseEnd);
        int release18 = leaseRelease.indexOf("AUTO_CONTAINER_RELEASE, true");
        int clearAfterRelease = leaseRelease.indexOf("clearAutoContainerLeaseIfExact(", release18);
        int retainAfterFailure = leaseRelease.indexOf(
                "dashboard_autocontainer_lease_retained", clearAfterRelease);
        assertTrue(release18 >= 0
                && clearAfterRelease > release18
                && retainAfterFailure > clearAfterRelease);
        assertEquals(clearAfterRelease, leaseRelease.lastIndexOf("clearAutoContainerLeaseIfExact("));

        int transferStart = source.indexOf("private void transferAutoContainerLeaseIfReplaced(");
        int transferEnd = source.indexOf("private void prepareAutoContainerLeaseTransfer(", transferStart);
        String transfer = source.substring(transferStart, transferEnd);
        assertTrue(transfer.contains("leaseGeneration != pendingAutoContainerLeaseTransferGeneration"));
        assertTrue(transfer.contains("putString(KEY_AUTOCONTAINER_LEASE_PACKAGE, packageName)"));
        assertTrue(transfer.contains("putLong(KEY_AUTOCONTAINER_LEASE_GENERATION, generation)"));
        assertTrue(transfer.contains("pendingAutoContainerLeaseTransferGeneration = 0L"));

        int reconcileStart = source.indexOf("private boolean reconcileConfirmedDashboardOwnership(");
        int reconcileEnd = source.indexOf("return true;", reconcileStart);
        assertTrue(source.indexOf("transferAutoContainerLeaseIfReplaced", reconcileStart) < reconcileEnd);
        assertEquals(-1, source.substring(reconcileStart, reconcileEnd)
                .indexOf("AUTO_CONTAINER_RELEASE"));
        int endMoveStart = source.indexOf("private void endMove(String packageName)");
        int endMoveEnd = source.indexOf("notifyStatusChanged();", endMoveStart);
        String endMove = source.substring(endMoveStart, endMoveEnd);
        assertTrue(endMove.contains("pendingAutoContainerLeaseTransferFrom = \"\";"));
        assertTrue(endMove.contains("pendingAutoContainerLeaseTransferGeneration = 0L;"));
    }
}
