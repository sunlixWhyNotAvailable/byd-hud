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
    public void autoContainerPolicyOnlySelectsExplicitTransitions() {
        assertEquals(16, NavAppDisplayController.autoContainerValueForTest(true, true, true));
        assertEquals(0, NavAppDisplayController.autoContainerValueForTest(true, false, true));
        assertEquals(0, NavAppDisplayController.autoContainerValueForTest(true, true, false));
        assertEquals(0, NavAppDisplayController.autoContainerValueForTest(false, false, true));
        assertEquals(0, NavAppDisplayController.autoContainerValueForTest(false, false, false));
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
    public void autoContainerAllowlistRejectsMiniAndInjection() {
        assertTrue(LocalAdbBridge.isAllowedRuntimeShellCommandForTest("id"));
        String command = LocalAdbBridge.autoContainerCommandForTest("auto_container", 16);
        assertTrue(LocalAdbBridge.isAllowedRuntimeShellCommandForTest(command));
        assertTrue(LocalAdbBridge.isAllowedRuntimeShellCommandForTest(
                LocalAdbBridge.autoContainerCommandForTest("AutoContainer", 18)));
        assertFalse(LocalAdbBridge.isAllowedRuntimeShellCommandForTest(
                "service call auto_container 2 i32 1000 i32 17 s16 '\"\"'"));
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
        assertFalse(source.contains("AUTO_CONTAINER_OFF"));
        assertFalse(source.contains("AUTO_CONTAINER_PARTIAL"));
        int senderStart = source.indexOf("private String sendAutoContainerIfRequested");
        int senderEnd = source.indexOf("private static String autoContainerStatus", senderStart);
        assertTrue(senderStart >= 0 && senderEnd > senderStart);
        assertFalse(source.substring(senderStart, senderEnd).contains("returnToMain"));
    }
}
