package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/** Checks Android wiring alongside the executable policy/gate tests, not vehicle delivery. */
public final class SteeringTransferServiceSourceContractTest {
    @Test
    public void accessibilityFiltersKeysAndLearningCapturesWithoutDispatchingAToggle() throws Exception {
        String key = keyHandler();
        String learning = between(key, "if (keyLearning) {", "int suppressed = suppressKeyCode;");
        assertTrue(learning.contains("SteeringTransferPolicy.isFirstDown("));
        assertTrue(learning.contains("capturedKeyCode = canonicalKeyCode;"));
        assertTrue(learning.contains("suppressKeyCode = canonicalKeyCode;"));
        assertTrue(learning.contains("return true;"));
        assertFalse(learning.contains("saveProfile("));
        assertFalse(learning.contains("requestSteeringTransfer("));
        String xml = file("src/main/res/xml/nav_accessibility_service.xml");
        assertTrue(xml.contains("flagRequestFilterKeyEvents"));
        assertTrue(xml.contains("canRequestFilterKeyEvents=\"true\""));
    }

    @Test
    public void everyMappedEventIncludingOrphanTailsIsConsumedWithoutTaskAdmission() throws Exception {
        String key = keyHandler();
        assertTrue(key.contains("if (event == null) return false;"));
        assertTrue(key.contains("steeringGestures.onKeyWithResult( keyCode, event.getAction(), event.getRepeatCount(),"));
        assertTrue(key.contains("consumed = result.consumed;"));
        assertTrue(key.contains("event.isCanceled(), event.getEventTime(), SystemClock.uptimeMillis(),"));
        assertTrue(key.contains("blocked, this::dispatchSteeringMatch)"));
        assertTrue(key.contains("if (blocked) steeringGestures.cancel();"));
        assertTrue(key.contains("scheduleSteeringDeadlineLocked();"));
        assertTrue(key.contains("return consumed;"));
        assertFalse(key.contains("LocalAdbBridge"));
        assertFalse(key.contains("checkDisplay("));
        assertFalse(key.contains("Thread"));
        assertFalse(key.contains("sleep("));
        assertFalse(key.contains("steering_key passed"));
        String dispatch = between(source("NavAccessibilityService.java"),
                "private void dispatchSteeringMatch(", "private void onSteeringDeadline()");
        assertTrue(dispatch.contains("String origin"));
        assertTrue(dispatch.contains("+ \" origin=\" + origin"));
    }

    @Test
    public void shutdownCancelsLearningWithoutPassingAMappedKeyThrough() throws Exception {
        String shutdown = between(keyHandler(),
                "if (keyLearning && HudPrefs.isUserShutdownActive(this))", "if (keyLearning) {");
        assertTrue(shutdown.contains("cancelKeyLearningTransient();"));
        assertFalse(shutdown.contains("return"));
        String service = source("NavAccessibilityService.java");
        String current = between(service,
                "private boolean isSteeringRequestCurrent(", "static void resumeSteeringRuntime(");
        assertTrue(current.contains("activeService == this && !steeringSuspended"));
        assertTrue(current.contains("HudPrefs.isUserShutdownActive(this)"));
    }

    @Test
    public void learningAndMappedTailsKeepBoundedLostUpRecovery() throws Exception {
        String service = source("NavAccessibilityService.java");
        String key = keyHandler();
        String suppressed = between(key,
                "if (suppressed >= 0 && SteeringTransferPolicy.isMappedKey(keyCode, suppressed))",
                "final boolean consumed");
        assertTrue(suppressed.contains("suppressKeyCode = SteeringTransferPreferences.NO_KEY_CODE;"));
        assertTrue(suppressed.contains("return true;"));
        assertFalse(suppressed.contains("requestSteeringToggle"));
        assertTrue(service.contains("STEERING_KEY_TAIL_TIMEOUT_MS = 3_000L"));
        String expiry = between(service, "private void expireSteeringKeyTail(",
                "private void postCaptureActiveWindow(");
        assertTrue(expiry.contains("if (generation != steeringKeyTailGeneration) return;"));
        assertTrue(expiry.contains("suppressKeyCode = SteeringTransferPreferences.NO_KEY_CODE;"));
        assertTrue(expiry.contains("clearGestureLocked();"));
        assertTrue(key.contains("armSteeringKeyTailTimeout();"));
        assertTrue(key.contains("cancelSteeringKeyTailTimeoutIfIdle();"));
        assertTrue(service.contains("new Handler(Looper.getMainLooper())"));
        assertTrue(service.contains("ViewConfiguration.getLongPressTimeout()"));
        assertTrue(service.contains("ViewConfiguration.getMultiPressTimeout()"));
        assertTrue(service.contains("ViewConfiguration.getDoubleTapTimeout()"));
        assertTrue(service.contains("steeringHandler.removeCallbacks(steeringDeadline);"));
        assertTrue(service.contains("steeringGestures.cancel();"));
    }

    @Test
    public void sharedGateIsReservedBeforeWorkerAndBusyNeverQueuesOrRetries() throws Exception {
        String controller = source("NavAppDisplayController.java");
        String steering = steeringWorker();
        int reserve = steering.indexOf("if (!beginMove(normalized,");
        int worker = steering.indexOf("Thread worker = new Thread(");
        int precheck = steering.indexOf(
                "checkDisplay(normalized, source + \"-toggle-precheck\")");
        int dispatch = steering.indexOf("moveIndependentDashboardAppBlocking(");
        assertTrue(reserve >= 0 && worker > reserve && precheck > worker && dispatch > precheck);
        assertTrue(steering.substring(reserve, worker).contains("return;"));
        assertFalse(steering.contains("postDelayed"));
        assertFalse(steering.contains("while ("));
        assertTrue(steering.contains("if (!executingMove) endMove(normalized);"));
        assertTrue(steering.contains("catch (RuntimeException error) { endMove(normalized);"));
        String ui = between(controller, "private void moveIndependentDashboardApp(",
                "void requestWidgetMode(");
        assertTrue(ui.indexOf("if (!beginMove(normalized,") < ui.indexOf("Thread worker ="));
        assertTrue(ui.contains("reason, completion, null, shutdownToken, null)"));
        String widget = between(controller, "void requestWidgetMode(", "void cancelWidgetModeForShutdown()");
        assertTrue(widget.indexOf("if (!beginMove(\"\",") < widget.indexOf("Thread worker ="));
        String begin = between(controller, "private boolean beginMove(", "boolean reserveMove(");
        assertTrue(begin.contains("if (!reserveMove(shutdownReason)) return false;"));
    }

    @Test
    public void everyFreshPrecheckRevalidatesBindingAndLifecycleBeforeMutation() throws Exception {
        String service = source("NavAccessibilityService.java");
        String key = keyHandler();
        assertTrue(key.contains("refreshSteeringProfilesLocked();"));
        String refresh = between(service, "private void refreshSteeringProfilesLocked()",
                "private void dispatchSteeringMatch(");
        assertTrue(refresh.contains("synchronized (SteeringTransferPreferences.class)"));
        assertTrue(refresh.contains("SteeringTransferPreferences.profiles(this)"));
        assertTrue(refresh.contains("SteeringTransferPreferences.revision(this)"));
        assertTrue(refresh.contains("steeringGestures.configure("));
        String deadlines = between(service, "private void onSteeringDeadline()",
                "private void scheduleSteeringDeadlineLocked()");
        assertTrue(deadlines.indexOf("refreshSteeringProfilesLocked();")
                < deadlines.indexOf("steeringGestures.advance("));
        assertTrue(service.contains("() -> isSteeringRequestCurrent(runtimeGeneration, bindingRevision)"));
        assertTrue(service.contains("bindingRevision, SteeringTransferPreferences.revision(this)"));
        assertTrue(service.contains("runtimeGeneration, steeringRuntimeGeneration"));
        String steering = steeringWorker();
        assertTrue(steering.contains("checkDisplay(normalized, source + \"-toggle-precheck\"); "
                + "if (!requestCurrent.getAsBoolean()) return;"));
        assertTrue(steering.contains("observedDisplay(normalized, current)"));
        assertTrue(steering.contains("SteeringTransferPolicy.canToggleTask(current, observed)"));
        assertTrue(steering.contains("}, requestCurrent, 0L, current);"));
        String controller = source("NavAppDisplayController.java");
        String move = between(controller, "private void moveIndependentDashboardAppBlocking(",
                "private String completionErrorForState(");
        int query = move.indexOf("NavAppDisplayState current = admittedState == null");
        int guard = move.indexOf("requestCurrent != null && !requestCurrent.getAsBoolean()");
        int knownDisplay = move.indexOf("if (!SteeringTransferPolicy.canToggleTask(");
        int dispatch = move.indexOf("ClusterProjectionService.returnToMain(");
        assertTrue(query >= 0 && guard > query && knownDisplay > guard && dispatch > knownDisplay);
        assertTrue(move.contains("current, observedDisplay(packageName, current))"));
        assertTrue(move.contains(
                "returnPreviousDashboardApp( packageName, layoutCommand, reason, requestCurrent)"));
        String replacement = between(controller, "synchronized NavAppDisplayState moveTaskToDisplayBlocking(",
                "private boolean ensureWazeSurfaceOnDisplay(");
        assertTrue(replacement.contains("checkDisplay(normalized, reason) : admittedState; "
                + "if (requestCurrent != null && !requestCurrent.getAsBoolean())"));
        assertTrue(replacement.contains("ClusterProjectionService.prepareOutputForTaskMove("));
    }

    @Test
    public void lifecycleBoundariesCancelInflightRequestsAndResumeDoesNotRescan() throws Exception {
        String service = source("NavAccessibilityService.java");
        assertTrue(between(service, "static void suspendForUserShutdown(", "static boolean beginKeyLearning(")
                .contains("service.clearSteeringTransientState();"));
        assertTrue(between(service, "public void onDestroy()", "public void onInterrupt()")
                .contains("clearSteeringTransientState();"));
        assertTrue(between(service, "public void onInterrupt()", "public boolean onKeyEvent(")
                .contains("clearSteeringTransientState();"));
        assertTrue(between(service, "private void clearSteeringTransientState()", "private void armSteeringKeyTailTimeout()")
                .contains("steeringRuntimeGeneration++;"));
        assertTrue(between(service, "protected void onServiceConnected()", "public void onAccessibilityEvent(")
                .contains("steeringRuntimeGeneration++;"));
        assertTrue(between(service, "private void beginKeyLearningInternal()", "private void cancelKeyLearningTransient()")
                .contains("steeringRuntimeGeneration++;"));
        assertTrue(between(service, "static void resumeSteeringRuntime(", "protected void onServiceConnected()")
                .contains("service.steeringSuspended = false;"));
        assertTrue(source("MainActivity.java").contains("resumeSteeringRuntime(this, \"activity-resume\")"));
    }

    @Test
    public void noTaskErrorsAndTimeoutsReleaseGateWithoutLaunchingOrReplayingKeys() throws Exception {
        String steering = steeringWorker();
        assertTrue(steering.contains("if (normalized.isEmpty()) { reportSteeringFailure("));
        String rejected = between(steering,
                "if (!SteeringTransferPolicy.canToggleTask(current, observed)) {",
                "boolean toDashboard =");
        assertTrue(rejected.contains("steering_transfer_rejected observed="));
        assertTrue(rejected.contains("reportSteeringFailure(normalized, \"task/display state unknown\"); return; }"));
        assertTrue(steering.contains("catch (RuntimeException error)"));
        assertTrue(steering.contains("finally { if (!executingMove) endMove(normalized); }"));
        String controller = source("NavAppDisplayController.java");
        String check = between(controller, "NavAppDisplayState checkDisplay(", "void moveToDashboard(");
        assertTrue(check.contains("if (!result.success()) { return remember(new NavAppDisplayState("));
        assertTrue(check.contains("catch (IOException | SecurityException e)"));
        assertFalse(controller.contains("startActivity("));
        assertFalse(controller.contains("am start"));
        assertFalse(keyHandler().contains("dispatchKeyEvent"));
        assertFalse(keyHandler().contains("sendKey"));
    }

    @Test
    public void steeringCacheIsGoneButUiStateAndProjectionOwnershipRemain() throws Exception {
        String service = source("NavAccessibilityService.java");
        String controller = source("NavAppDisplayController.java");
        String preferences = source("SteeringTransferPreferences.java");
        for (String removed : new String[] {"steeringTask", "SteeringTask", "STEERING_TASK_CACHE",
                "isCachedTargetEligible", "beginSteeringTaskMove", "hasFreshTaskEvidence"}) {
            assertFalse(service.contains(removed));
            assertFalse(controller.contains(removed));
            assertFalse(preferences.contains(removed));
        }
        assertFalse(service.contains("NavAppTaskScanner"));
        assertTrue(controller.contains("states.put(safeState.packageName, safeState)"));
        assertTrue(controller.contains("ClusterProjectionService.isProjectedPackageCurrent(current)"));
        assertTrue(controller.contains("reconcileConfirmedDashboardOwnership("));
        String end = between(controller, "private void endMove(String packageName)",
                "private long widgetProjectionGenerationForPackage(");
        assertTrue(end.contains("moveInProgress = false;"));
        assertTrue(end.contains("notifyStatusChanged();"));
        assertTrue(preferences.contains("putLong(KEY_REVISION, preferences.getLong(KEY_REVISION, 0L) + 1L)"));
        assertTrue(preferences.contains("MainActivity.publishSharedUiStateChange();"));
        assertTrue(preferences.contains("KEY_PROFILES = \"profiles_v2\""));
        assertTrue(preferences.contains("static synchronized boolean saveProfile("));
        assertTrue(preferences.contains("static synchronized boolean deleteProfile("));
        assertFalse(preferences.contains("static void setKeyCode("));
    }

    @Test
    public void diagnosticExportDoesNotCommitLegacyMigration() throws Exception {
        String preferences = source("SteeringTransferPreferences.java");
        String diagnostic = between(preferences,
                "static synchronized List<SteeringTransferProfile> diagnosticProfiles(",
                "static synchronized boolean saveProfile(");
        assertTrue(diagnostic.contains("migrateLegacy("));
        assertFalse(diagnostic.contains("ensureMigrated("));
        assertFalse(diagnostic.contains(".edit()"));
        assertTrue(source("VehicleConfigurationDiagnostics.java")
                .contains("SteeringTransferPreferences.diagnosticProfiles(context)"));
    }

    private static String keyHandler() throws IOException {
        return between(source("NavAccessibilityService.java"),
                "public boolean onKeyEvent(KeyEvent event)", "private void logSteeringKeyIngress(").trim() + " ";
    }

    private static String steeringWorker() throws IOException {
        return between(source("NavAppDisplayController.java"),
                "void requestSteeringToggle(", "private void moveIndependentDashboardApp(");
    }

    private static String source(String name) throws IOException {
        return file("src/main/java/com/bydhud/app/" + name).replaceAll("\\s+", " ");
    }

    private static String file(String relativePath) throws IOException {
        Path path = Path.of(relativePath);
        if (!Files.exists(path)) path = Path.of("app", relativePath);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to <= from) throw new AssertionError("missing source section: " + start);
        return source.substring(from, to);
    }
}
