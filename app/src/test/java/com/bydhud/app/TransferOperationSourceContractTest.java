package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/** Guards the one-query/one-confirm transfer path without pretending to exercise a vehicle. */
public final class TransferOperationSourceContractTest {
    @Test
    public void admittedTaskFlowsThroughProjectionWithoutAnotherPreflightQuery() throws Exception {
        String controller = source("NavAppDisplayController.java");
        String service = source("ClusterProjectionService.java");
        assertFalse(controller.contains("preflightAuthorizedAdb("));
        assertFalse(controller.contains("\"id\""));
        assertTrue(controller.contains("0L, current)"));
        assertTrue(controller.contains("dashboardMode, safe(reason), current, () -> projectionOpen.get()"));
        assertTrue(controller.contains("shutdownToken, current, () -> returnOpen.get()"));
        assertTrue(service.contains("putTaskState(intent, taskState)"));
        assertTrue(service.contains("requestCurrent, taskState)"));
        assertTrue(service.contains("moveOwnerToken).isEmpty() && transferCurrent(transferToken), taskState)"));
        String transfer = between(controller, "private void moveIndependentDashboardAppBlocking(",
                "private String completionErrorForState(");
        assertTrue(transfer.contains("!isConfirmedProjectedDashboardDisplay(packageName, current)"));
        assertTrue(transfer.contains("foreign-display return-main"));
        assertFalse(between(transfer, "foreign-display return-main", "return;")
                .contains("clearDashboardProjection("));
    }

    @Test
    public void successfulProjectionWaitHasNoQueryOrFallbackMover() throws Exception {
        String controller = source("NavAppDisplayController.java");
        String wait = between(controller, "private static NavAppDisplayState awaitTransferCompletion(",
                "private boolean isConfirmedProjectedDashboardDisplay(");
        assertTrue(wait.contains("completed.await("));
        assertFalse(wait.contains("checkDisplay("));
        assertFalse(wait.contains("moveTaskToDisplayBlocking("));
        assertTrue(controller.contains("returnCompleted, returnResult, DISPLAY_CONFIRM_TIMEOUT_MS"));
        assertTrue(controller.contains(
                "projectionCompleted, projectionResult, PROJECTED_DISPLAY_CONFIRM_TIMEOUT_MS"));
    }

    @Test
    public void uiAndSteeringResolveToggleDirectionFromTheSameFreshWorkerQuery() throws Exception {
        String controller = source("NavAppDisplayController.java");
        String ui = between(controller, "void requestUiToggle(", "void requestSteeringToggle(");
        String steering = between(controller,
                "void requestSteeringToggle(", "private void requestFreshToggle(");
        String shared = between(controller,
                "private void requestFreshToggle(", "private void moveIndependentDashboardApp(");
        assertTrue(ui.contains("requestFreshToggle("));
        assertTrue(steering.contains("requestFreshToggle("));
        assertTrue(shared.contains("NavAppDisplayState current = checkDisplay("));
        assertTrue(shared.contains("SteeringTransferPolicy.canToggleTask(current, observed)"));
        assertTrue(shared.contains("SteeringTransferPolicy.toggleMovesToDashboard(observed)"));
        assertTrue(shared.contains("requestCurrent, 0L, current"));

        String activity = source("MainActivity.java");
        assertTrue(activity.contains("controller.requestUiToggle(normalized,"
                + " \"ui-independent-dashboard-explicit\")"));
        assertTrue(activity.contains("composeToggleDashboard(String packageName)"));
        String compose = source("BydHudRuntimeCompose.kt");
        assertTrue(compose.contains("activity.composeToggleDashboard(row.packageName)"));
    }

    @Test
    public void companionReusesConfirmedMoveWhileWaitingForSurfaceReadiness() throws Exception {
        String sender = source("NavHudLiveSender.java");
        assertTrue(sender.contains("taskId == confirmedMovedTask && targetDisplay == confirmedMovedTarget"));
        assertTrue(sender.contains("confirmedMovedTask = taskId"));
        assertTrue(sender.contains("start.routeGeneration == wazeRouteGeneration"));
        assertTrue(sender.contains("activityInstanceId == WazeSurfaceActivity.activeInstanceId()"));
        assertTrue(sender.contains("directSessionGeneration == wazeDirectChannel.sessionGeneration()"));
    }

    private static String source(String name) throws Exception {
        Path path = Path.of("src/main/java/com/bydhud/app/" + name);
        if (!Files.exists(path)) path = Path.of("app").resolve(path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to <= from) throw new AssertionError("missing source section");
        return source.substring(from, to);
    }
}
