package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public final class DashboardWidgetCommandContractTest {
    @Test
    public void widgetModesKeepTheApprovedFourSequences() {
        assertEquals(0, NavAppDisplayController.widgetAutoContainerValueForTest(
                NavAppDisplayController.WIDGET_MODE_IPC_OFF));
        assertEquals(0, NavAppDisplayController.widgetAutoContainerValueForTest(
                NavAppDisplayController.WIDGET_MODE_TBT));
        assertEquals(17, NavAppDisplayController.widgetAutoContainerValueForTest(
                NavAppDisplayController.WIDGET_MODE_MINI));
        assertEquals(0, NavAppDisplayController.widgetAutoContainerValueForTest(
                NavAppDisplayController.WIDGET_MODE_FULL));
        assertFalse(NavAppDisplayController.widgetModeUsesTbtProtocolForTest(
                NavAppDisplayController.WIDGET_MODE_IPC_OFF));
        assertTrue(NavAppDisplayController.widgetModeUsesTbtProtocolForTest(
                NavAppDisplayController.WIDGET_MODE_TBT));
        assertFalse(NavAppDisplayController.widgetModeUsesTbtProtocolForTest(
                NavAppDisplayController.WIDGET_MODE_MINI));
        assertFalse(NavAppDisplayController.widgetModeUsesTbtProtocolForTest(
                NavAppDisplayController.WIDGET_MODE_FULL));
        assertFalse(NavAppDisplayController.widgetModeUsesAutoContainerForTest(
                NavAppDisplayController.WIDGET_MODE_IPC_OFF, false));
        assertFalse(NavAppDisplayController.widgetModeUsesAutoContainerForTest(
                NavAppDisplayController.WIDGET_MODE_TBT, false));
        assertTrue(NavAppDisplayController.widgetModeUsesAutoContainerForTest(
                NavAppDisplayController.WIDGET_MODE_TBT, true));
        assertTrue(NavAppDisplayController.widgetModeUsesAutoContainerForTest(
                NavAppDisplayController.WIDGET_MODE_MINI, false));
        assertFalse(NavAppDisplayController.widgetModeUsesAutoContainerForTest(
                NavAppDisplayController.WIDGET_MODE_FULL, false));
        assertTrue(NavAppDisplayController.widgetTbtNeedsAutoContainerReleaseForTest(16, false));
        assertTrue(NavAppDisplayController.widgetTbtNeedsAutoContainerReleaseForTest(17, false));
        assertTrue(NavAppDisplayController.widgetTbtNeedsAutoContainerReleaseForTest(0, true));
        assertFalse(NavAppDisplayController.widgetTbtNeedsAutoContainerReleaseForTest(0, false));
        assertFalse(NavAppDisplayController.widgetTbtNeedsAutoContainerReleaseForTest(18, false));
    }

    @Test
    public void widgetResizeCommitSurvivesTransientGeometryInvalidation() {
        // Geometry is deliberately false while the ready surface is being resized;
        // owner and cancellation fences still allow the final commit.
        assertTrue(ClusterProjectionService.widgetResizeCommitAllowedForTest(
                true, true));
        assertFalse(ClusterProjectionService.widgetResizeCommitAllowedForTest(
                false, true));
        assertFalse(ClusterProjectionService.widgetResizeCommitAllowedForTest(
                true, false));
    }

    @Test
    public void sourceKeepsTbtEdgeOrderedAfterOptionalSuccessfulRelease() throws Exception {
        String source = source("NavAppDisplayController.java");
        String worker = between(source, "private void runWidgetMode(",
                "private String sendWidgetTbtProtocolEdge(");
        int ownership = worker.indexOf("int ownership = autoContainerOwnership()");
        int release = worker.indexOf("releasePersistedAutoContainerOwnership(", ownership);
        int edge = worker.indexOf("sendWidgetTbtProtocolEdge(token)", release);
        int full = worker.indexOf("DashboardLayoutPolicy.PROTOCOL_FULL", release);
        assertTrue(ownership >= 0 && release > ownership && edge > release && full > release);
        assertTrue(worker.contains(
                "DashboardLayoutPolicy.shouldReleaseBeforeWidget(mode, ownership)"));
        assertTrue(worker.contains("ownership == DashboardLayoutPolicy.OWNERSHIP_NONE"));
        assertFalse(worker.contains("returnActiveDashboardToMain"));
        assertFalse(source.contains("AUTO_CONTAINER_OFF"));

        String tbtEdge = between(source, "private String sendWidgetTbtProtocolEdge(",
                "private String sendWidgetProtocolOperation(");
        int typeOne = tbtEdge.indexOf("DashboardLayoutPolicy.PROTOCOL_NATIVE");
        int typeOneGate = tbtEdge.indexOf("typeOneFailure.isEmpty()", typeOne);
        int typeTwo = tbtEdge.indexOf("DashboardLayoutPolicy.PROTOCOL_TBT", typeOneGate);
        assertTrue(typeOne >= 0 && typeOneGate > typeOne && typeTwo > typeOneGate);
        assertFalse(tbtEdge.contains("sleep"));
        assertFalse(tbtEdge.contains("delay"));

        String widgetCommand = between(source, "private String sendWidgetAutoContainer(",
                "private boolean isWidgetOperationCurrent(");
        int sent = widgetCommand.indexOf("LocalAdbBridge.runAutoContainer(");
        int accepted = widgetCommand.indexOf("if (!result.success())", sent);
        int record = widgetCommand.indexOf("recordWidgetAutoContainerValue(", accepted);
        int bookkeeping = widgetCommand.indexOf("acquireAutoContainerLeaseIfSucceeded(", record);
        assertTrue(sent >= 0 && accepted > sent && record > accepted && bookkeeping > record);
        assertFalse(widgetCommand.substring(record).contains("widgetCancellationReason()"));
        assertTrue(source.contains(".remove(KEY_WIDGET_AUTOCONTAINER_VALUE)"));
    }

    @Test
    public void profilePathIsOwnerBoundAndRecoversOnlyAfterRollbackFailure() throws Exception {
        String service = source("ClusterProjectionService.java");
        assertTrue(service.contains("applyDashboardProfileForWidget"));
        assertTrue(service.contains("expectedProjectionGeneration"));
        assertTrue(service.contains("BooleanSupplier stillCurrent"));
        assertTrue(service.contains("resizeActiveProjectionForWidget"));
        assertTrue(service.contains("identity_updated=true"));
        String resize = between(service, "private boolean resizeActiveProjection(",
                "private void recoverProjectionAfterResizeFailure(");
        assertFalse(resize.contains("widgetProfile"));
        String widgetResize = between(service, "private String resizeActiveProjectionForWidget(",
                "private boolean isCurrentWidgetRequestLocked(");
        int unpublished = widgetResize.indexOf("projectionPlacementReady = false;");
        int resized = widgetResize.indexOf("view.getHolder().setFixedSize(");
        int restored = widgetResize.indexOf(
                "projectionPlacementReady = oldPlacementReady;", resized);
        int rollbackFailure = widgetResize.indexOf("profile_resize_rollback_failed widget=true");
        int recovery = widgetResize.indexOf(
                "recoverProjectionAfterResizeFailure(", rollbackFailure);
        assertTrue(unpublished >= 0 && resized > unpublished && restored > resized);
        assertTrue(rollbackFailure >= 0 && recovery > rollbackFailure);
    }

    @Test
    public void shutdownCancellationAndDeferredReturnShareTheMoveGate() throws Exception {
        String source = source("NavAppDisplayController.java");
        assertTrue(source.contains("void cancelWidgetModeForShutdown()"));
        assertTrue(source.contains("widgetOperationCancelled = true"));
        assertTrue(source.contains("pendingShutdownReturnPackage"));
        assertTrue(source.contains("dashboard_return_main_queued"));
        assertTrue(source.contains("returnActiveDashboardToMain(deferredReturnReason);"));
        String shutdownReturn = between(source, "void returnActiveDashboardToMain(",
                "private static boolean isShutdownReturnReason(");
        assertFalse(shutdownReturn.contains("isMoveInProgress()"));
        int locked = shutdownReturn.indexOf("synchronized (lock)");
        int gateCheck = shutdownReturn.indexOf("if (moveInProgress)", locked);
        int pending = shutdownReturn.indexOf("pendingShutdownReturnPackage = active", gateCheck);
        assertTrue(locked >= 0 && gateCheck > locked && pending > gateCheck);

        String command = between(source, "private String sendWidgetAutoContainer(",
                "private boolean isWidgetOperationCurrent(");
        int sent = command.indexOf("LocalAdbBridge.runAutoContainer(");
        int bookkeeping = command.indexOf("acquireAutoContainerLeaseIfSucceeded(", sent);
        assertTrue(sent >= 0 && bookkeeping > sent);
        assertFalse(command.substring(sent).contains("widgetCancellationReason()"));
        assertTrue(command.contains("projectionGenerationForPackage(normalizedOwner) == leaseGeneration"));
    }

    private static String source(String fileName) throws Exception {
        Path path = Paths.get("app/src/main/java/com/bydhud/app/" + fileName);
        if (!Files.exists(path)) {
            path = Paths.get("src/main/java/com/bydhud/app/" + fileName);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue("missing start marker " + start, from >= 0);
        assertTrue("missing end marker " + end, to > from);
        return source.substring(from, to);
    }
}
