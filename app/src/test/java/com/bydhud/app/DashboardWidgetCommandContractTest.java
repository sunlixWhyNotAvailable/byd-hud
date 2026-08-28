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
        assertEquals(18, NavAppDisplayController.widgetAutoContainerValueForTest(
                NavAppDisplayController.WIDGET_MODE_IPC_OFF));
        assertEquals(18, NavAppDisplayController.widgetAutoContainerValueForTest(
                NavAppDisplayController.WIDGET_MODE_TBT));
        assertEquals(17, NavAppDisplayController.widgetAutoContainerValueForTest(
                NavAppDisplayController.WIDGET_MODE_MINI));
        assertEquals(16, NavAppDisplayController.widgetAutoContainerValueForTest(
                NavAppDisplayController.WIDGET_MODE_FULL));
        assertFalse(NavAppDisplayController.widgetModeUsesTbtProtocolForTest(
                NavAppDisplayController.WIDGET_MODE_IPC_OFF));
        assertTrue(NavAppDisplayController.widgetModeUsesTbtProtocolForTest(
                NavAppDisplayController.WIDGET_MODE_TBT));
        assertFalse(NavAppDisplayController.widgetModeUsesTbtProtocolForTest(
                NavAppDisplayController.WIDGET_MODE_MINI));
        assertFalse(NavAppDisplayController.widgetModeUsesTbtProtocolForTest(
                NavAppDisplayController.WIDGET_MODE_FULL));
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
    public void sourceKeepsTbtProtocolOrderedAfterSuccessfulReleaseOnly() throws Exception {
        String source = source("NavAppDisplayController.java");
        int command = source.indexOf("sendWidgetAutoContainer(");
        int protocol = source.indexOf("StockMapProtocol30011.dispatch(", command);
        assertTrue(command >= 0 && protocol > command);
        String worker = between(source, "private void runWidgetMode(",
                "private String sendWidgetAutoContainer(");
        assertTrue(worker.contains("if (!commandFailure.isEmpty())"));
        assertTrue(worker.contains("TBT protocol failed after AutoContainer 18"));
        assertFalse(worker.contains("returnActiveDashboardToMain"));
        assertFalse(source.contains("AUTO_CONTAINER_OFF"));
    }

    @Test
    public void profilePathIsOwnerBoundAndDoesNotUseResizeRecoveryMove() throws Exception {
        String service = source("ClusterProjectionService.java");
        assertTrue(service.contains("applyDashboardProfileForWidget"));
        assertTrue(service.contains("expectedProjectionGeneration"));
        assertTrue(service.contains("BooleanSupplier stillCurrent"));
        assertTrue(service.contains("resizeActiveProjectionForWidget"));
        assertTrue(service.contains("identity_updated=true"));
        String resize = between(service, "private void resizeActiveProjection(",
                "private void recoverProjectionAfterResizeFailure(");
        assertFalse(resize.contains("widgetProfile"));
    }

    @Test
    public void shutdownCancellationAndDeferredReturnShareTheMoveGate() throws Exception {
        String source = source("NavAppDisplayController.java");
        assertTrue(source.contains("void cancelWidgetModeForShutdown()"));
        assertTrue(source.contains("widgetOperationCancelled = true"));
        assertTrue(source.contains("pendingShutdownReturnPackage"));
        assertTrue(source.contains("dashboard_return_main_queued"));
        assertTrue(source.contains("moveIndependentDashboardApp(\n                    deferredReturnPackage"));
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
        int postResultCancellation = command.indexOf("if (!isWidgetOperationCurrent(token))", sent);
        assertTrue(sent >= 0 && bookkeeping > sent && postResultCancellation > bookkeeping);
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
