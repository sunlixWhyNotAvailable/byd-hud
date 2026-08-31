package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/** Keeps the Android service contract visible to JVM tests without instantiating the service. */
public final class SteeringTransferServiceSourceContractTest {
    @Test
    public void accessibilityServiceRequestsKeyFilteringAndConsumesLearning() throws Exception {
        String service = source("NavAccessibilityService.java");
        String keyHandler = between(
                service,
                "public boolean onKeyEvent(KeyEvent event)",
                "private void beginKeyLearningInternal()");
        assertTrue(keyHandler.contains("return true;"));
        assertTrue(keyHandler.contains("isCachedTargetEligible(packageName)"));
        assertTrue(keyHandler.contains("hasFreshTaskEvidence"));
        assertTrue(keyHandler.contains("scheduleSteeringTaskCacheRefresh(\"mapped-key-stale\")"));
        assertTrue(!keyHandler.contains("forceScanIfIdle"));
        assertTrue(!keyHandler.contains("LocalAdbBridge"));
        assertTrue(!keyHandler.contains("checkDisplay("));
        String xml = xmlSource("nav_accessibility_service.xml");
        assertTrue(xml.contains("flagRequestFilterKeyEvents"));
        assertTrue(xml.contains("canRequestFilterKeyEvents=\"true\""));
        assertTrue(service.contains("scanner.forceFreshScanIfIdle()"));
        assertTrue(service.contains("expireSteeringKeyTail"));
        assertTrue(service.contains("service.invalidateSteeringTaskEvidence()"));
        assertTrue(service.contains("scheduleSteeringTaskCacheRefresh(\"service-interrupt\")"));
        assertTrue(service.contains("steeringTaskInvalidationEpoch"));
        assertTrue(service.contains("steeringTaskCacheRefreshPending"));
        assertTrue(service.contains("canPublishTaskEvidence"));
        assertTrue(service.contains("scheduleSteeringTaskCacheRefresh(\"pending-invalidation\")"));
        String activity = source("MainActivity.java");
        assertTrue(activity.contains("resumeSteeringRuntime(this, \"activity-resume\")"));
    }

    @Test
    public void controllerRechecksTaskAndUsesExistingMovePipeline() throws Exception {
        String controller = source("NavAppDisplayController.java");
        assertTrue(controller.contains("void requestSteeringToggle("));
        assertTrue(controller.contains("checkDisplay(normalized, \"steering-precheck\")"));
        assertTrue(controller.contains("moveIndependentDashboardApp("));
        assertTrue(controller.contains("steering_transfer_failed"));
        assertTrue(!controller.contains("startActivity("));
        assertTrue(controller.contains("catch (IOException | SecurityException e)"));
        assertTrue(controller.contains("catch (SecurityException e)"));
    }

    @Test
    public void selectedBusyPressIsConsumedBeforeCacheAdmissionAndWorkerCreation() throws Exception {
        String keyHandler = between(compact(source("NavAccessibilityService.java")),
                "public boolean onKeyEvent(KeyEvent event)", "private void beginKeyLearningInternal()");
        String busy = between(keyHandler,
                "if (!shutdown && controller.isMoveInProgressFor(packageName))", "long now =");
        assertTrue(busy.contains("mappedKeyActive = true;"));
        assertTrue(busy.contains("armSteeringKeyTailTimeout();"));
        assertTrue(busy.contains("return true;"));
        assertFalse(busy.contains("requestSteeringToggle("));
        assertFalse(busy.contains("scheduleSteeringTaskCacheRefresh("));
        assertTrue(keyHandler.indexOf("controller.isMoveInProgressFor(packageName)")
                < keyHandler.indexOf("SteeringTransferPolicy.hasFreshTaskEvidence("));
        assertTrue(keyHandler.indexOf("SteeringTransferPolicy.hasFreshTaskEvidence(")
                < keyHandler.indexOf("SteeringTransferPolicy.canAdmitMappedPress("));

        String steering = between(compact(source("NavAppDisplayController.java")),
                "void requestSteeringToggle(", "private void moveIndependentDashboardApp(");
        int reserve = steering.indexOf("if (!beginMove(normalized,");
        int worker = steering.indexOf("Thread worker = new Thread(");
        int precheck = steering.indexOf("checkDisplay(normalized, \"steering-precheck\")");
        int dispatch = steering.indexOf("moveIndependentDashboardAppBlocking(");
        assertTrue(reserve >= 0 && worker > reserve && precheck > worker && dispatch > precheck);
        assertTrue(steering.substring(reserve, worker).contains("return;"));
        assertTrue(steering.contains("if (!executingMove) endMove(normalized);"));
    }

    @Test
    public void onlyTheSelectedAppMoveBypassesWindowInvalidation() throws Exception {
        String service = compact(source("NavAccessibilityService.java"));
        String event = between(service,
                "public void onAccessibilityEvent(AccessibilityEvent event)",
                "String packageName = safe(event.getPackageName());");
        assertTrue(event.contains("event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED"));
        assertTrue(event.contains(
                "if (!NavAppDisplayController.get(this).isMoveInProgressFor( "
                        + "SteeringTransferPreferences.packageName(this))) { invalidateSteeringTaskEvidence(); } "
                        + "requestSteeringTaskCacheRefresh(this, \"window-state\");"));
        String selectedMove = between(compact(source("NavAppDisplayController.java")),
                "boolean isMoveInProgressFor(String packageName)", "String activeDashboardPackage()");
        assertTrue(selectedMove.contains(
                "return SteeringTransferPolicy.isSelectedMove(moveInProgress, movingPackage, normalized);"));
        String resume = between(service,
                "static void resumeSteeringRuntime(", "protected void onServiceConnected()");
        assertTrue(resume.contains("boolean wasSuspended = service.steeringSuspended;"));
        assertTrue(resume.contains(
                "if (wasSuspended || !NavAppDisplayController.get(context).isMoveInProgressFor( "
                        + "SteeringTransferPreferences.packageName(context))) { service.invalidateSteeringTaskEvidence(); }"));
    }

    @Test
    public void scanActionOwnsPublicationClearingAndAcceptedEvidenceAge() throws Exception {
        String refresh = between(compact(source("NavAccessibilityService.java")),
                "private void refreshSteeringTaskCache()", "private void schedulePeriodicSteeringTaskCacheRefresh(");
        assertTrue(refresh.contains(
                "scanBusy, authoritative, scanEpoch, steeringTaskInvalidationEpoch, selectedMove);"));
        assertTrue(refresh.contains(
                "if (action == SteeringTransferPolicy.TaskScanAction.PUBLISH) { "
                        + "steeringTaskSnapshot = scanned; steeringTaskEvidenceElapsedMs = now; refreshed = true; "
                        + "} else if (action == SteeringTransferPolicy.TaskScanAction.CLEAR) { "
                        + "clearSteeringTaskEvidenceLocked(); }"));
        assertTrue(refresh.indexOf("steeringTaskEvidenceElapsedMs =")
                == refresh.lastIndexOf("steeringTaskEvidenceElapsedMs ="));
        String failure = between(refresh, "catch (RuntimeException error)", "finally {");
        assertTrue(failure.contains(
                "HudPrefs.isUserShutdownActive(this), false, false, "
                        + "scanEpoch, steeringTaskInvalidationEpoch, selectedMove) "
                        + "== SteeringTransferPolicy.TaskScanAction.CLEAR) { clearSteeringTaskEvidenceLocked(); }"));
    }

    @Test
    public void commonMoveCompletionPublishesOnlyConfirmedTaskBeforeIdle() throws Exception {
        String controller = compact(source("NavAppDisplayController.java"));
        String begin = between(controller,
                "private boolean beginMove(", "private void persistDashboardProjection(");
        assertTrue(begin.contains(
                "steeringTaskMoveCompletion = NavAccessibilityService.beginSteeringTaskMove( context, packageName);"));
        String move = between(controller,
                "private void moveIndependentDashboardAppBlocking(", "private String completionErrorForState(");
        assertTrue(move.contains("NavAppDisplayState confirmedTask = null;"));
        assertTrue(move.contains("if (onMain) confirmedTask = confirmed;"));
        assertTrue(move.contains(
                "boolean alreadyProjected = isConfirmedProjectedDashboardDisplay(packageName, current);"));
        assertTrue(between(move, "if (alreadyProjected)", "\"independent dashboard projection retained\"")
                .contains("confirmedTask = current;"));
        String projection = between(move,
                "NavAppDisplayState confirmed = waitForProjectedDashboardDisplay(", "} catch (SecurityException e)");
        String rejected = between(projection,
                "if (!isConfirmedProjectedDashboardDisplay(packageName, confirmed))", "reconcileConfirmedDashboardOwnership(");
        assertTrue(rejected.contains("return;"));
        assertFalse(rejected.contains("confirmedTask ="));
        assertTrue(projection.contains("confirmedTask = confirmed;"));
        assertTrue(move.contains("endMove(packageName, confirmedTask);"));
        assertFalse(move.contains("confirmedTask = new NavAppDisplayState"));

        String end = between(controller,
                "private void endMove(String packageName, NavAppDisplayState confirmedTask)",
                "private long widgetProjectionGenerationForPackage(");
        int publish = end.indexOf("publishTask.accept(confirmedTask);");
        int idle = end.indexOf("moveInProgress = false;");
        assertTrue(publish >= 0 && idle > publish);
        String completion = between(compact(source("NavAccessibilityService.java")),
                "static Consumer<NavAppDisplayState> beginSteeringTaskMove(", "static void resumeSteeringRuntime(");
        assertTrue(completion.contains("SteeringTransferPolicy.canPublishTaskEvidence("));
        assertTrue(completion.contains("bindingRevision != SteeringTransferPreferences.revision(context)"));
        assertTrue(completion.contains(
                "packageName.equals(confirmed.packageName) ? SteeringTransferPolicy.confirmedTaskSnapshot( "
                        + "confirmed, System.currentTimeMillis()) : null;"));
        assertTrue(completion.contains(
                "steeringTaskEvidenceElapsedMs = service.steeringTaskSnapshot == null ? 0L : SystemClock.elapsedRealtime();"));
    }

    private static String source(String name) throws IOException {
        return new String(Files.readAllBytes(Path.of("src/main/java/com/bydhud/app", name)),
                StandardCharsets.UTF_8);
    }

    private static String xmlSource(String name) throws IOException {
        return new String(Files.readAllBytes(Path.of("src/main/res/xml", name)),
                StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to <= from) throw new AssertionError("missing source section");
        return source.substring(from, to);
    }

    private static String compact(String source) {
        return source.replaceAll("\\s+", " ");
    }
}
