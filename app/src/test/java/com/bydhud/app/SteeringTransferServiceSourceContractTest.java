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
        String learning = between(key, "if (keyLearning) {", "int keyCode = event.getKeyCode();");
        assertTrue(learning.contains("SteeringTransferPolicy.isFirstDown("));
        assertTrue(learning.contains("suppressKeyCode = code;"));
        assertTrue(learning.contains("SteeringTransferPreferences.setKeyCode(this, code);"));
        assertTrue(learning.contains("return true;"));
        assertFalse(learning.contains("requestSteeringToggle("));
        String xml = file("src/main/res/xml/nav_accessibility_service.xml");
        assertTrue(xml.contains("flagRequestFilterKeyEvents"));
        assertTrue(xml.contains("canRequestFilterKeyEvents=\"true\""));
    }

    @Test
    public void everyMappedEventIncludingOrphanTailsIsConsumedWithoutTaskAdmission() throws Exception {
        String key = keyHandler();
        assertTrue(key.contains("if (event == null) return false;"));
        assertTrue(key.contains(
                "if (!SteeringTransferPolicy.isMappedKey(keyCode, configured)) { return false; }"));
        String mapped = key.substring(key.indexOf("if (event.getAction() == KeyEvent.ACTION_DOWN)",
                key.indexOf("int configured =")));
        assertFalse(mapped.contains("return false;"));
        assertTrue(mapped.endsWith("return true; } "));
        assertTrue(mapped.contains("SteeringTransferPolicy.shouldStartTransfer("));
        assertTrue(mapped.contains("if (startTransfer) { NavAppDisplayController.get(this).requestSteeringToggle("));
        assertTrue(mapped.contains("else if (event.getAction() == KeyEvent.ACTION_UP)"));
        assertFalse(key.contains("LocalAdbBridge"));
        assertFalse(key.contains("checkDisplay("));
        assertFalse(key.contains("Thread"));
        assertFalse(key.contains("sleep("));
        assertFalse(key.contains("steering_key passed"));
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
        String suppressed = between(key, "if (suppressed >= 0 && keyCode == suppressed)",
                "final long runtimeGeneration");
        assertTrue(suppressed.contains("suppressKeyCode = SteeringTransferPreferences.NO_KEY_CODE;"));
        assertTrue(suppressed.contains("return true;"));
        assertFalse(suppressed.contains("requestSteeringToggle"));
        assertTrue(service.contains("STEERING_KEY_TAIL_TIMEOUT_MS = 3_000L"));
        String expiry = between(service, "private void expireSteeringKeyTail(",
                "private void postCaptureActiveWindow(");
        assertTrue(expiry.contains("if (generation != steeringKeyTailGeneration) return;"));
        assertTrue(expiry.contains("suppressKeyCode = SteeringTransferPreferences.NO_KEY_CODE;"));
        assertTrue(expiry.contains("mappedKeyActive = false;"));
        assertTrue(key.contains("mappedKeyActive = true; } armSteeringKeyTailTimeout();"));
        assertTrue(key.contains("mappedKeyActive = false; } cancelSteeringKeyTailTimeoutIfIdle();"));
    }

    @Test
    public void sharedGateIsReservedBeforeWorkerAndBusyNeverQueuesOrRetries() throws Exception {
        String controller = source("NavAppDisplayController.java");
        String steering = steeringWorker();
        int reserve = steering.indexOf("if (!beginMove(normalized,");
        int worker = steering.indexOf("Thread worker = new Thread(");
        int precheck = steering.indexOf("checkDisplay(normalized, \"steering-precheck\")");
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
        assertTrue(ui.contains("reason, completion, null)"));
        String widget = between(controller, "void requestWidgetMode(", "void cancelWidgetModeForShutdown()");
        assertTrue(widget.indexOf("if (!beginMove(\"\",") < widget.indexOf("Thread worker ="));
        String begin = between(controller, "private boolean beginMove(", "boolean reserveMove(");
        assertTrue(begin.contains("if (!reserveMove()) return false;"));
    }

    @Test
    public void everyFreshPrecheckRevalidatesBindingAndLifecycleBeforeMutation() throws Exception {
        String service = source("NavAccessibilityService.java");
        String key = keyHandler();
        assertTrue(key.indexOf("SteeringTransferPreferences.revision(this)")
                < key.indexOf("SteeringTransferPreferences.keyCode(this)"));
        assertTrue(key.contains("() -> isSteeringRequestCurrent(runtimeGeneration, bindingRevision)"));
        assertTrue(service.contains("bindingRevision, SteeringTransferPreferences.revision(this)"));
        assertTrue(service.contains("runtimeGeneration, steeringRuntimeGeneration"));
        String steering = steeringWorker();
        assertTrue(steering.contains("checkDisplay(normalized, \"steering-precheck\"); "
                + "if (!requestCurrent.getAsBoolean()) return;"));
        assertTrue(steering.contains("observedDisplay(normalized, current)"));
        assertTrue(steering.contains("SteeringTransferPolicy.canToggleTask(current, observed)"));
        assertTrue(steering.contains("}, requestCurrent);"));
        String controller = source("NavAppDisplayController.java");
        String move = between(controller, "private void moveIndependentDashboardAppBlocking(",
                "private String completionErrorForState(");
        int query = move.indexOf("NavAppDisplayState current = checkDisplay(");
        int guard = move.indexOf("requestCurrent != null && (!requestCurrent.getAsBoolean()");
        int dispatch = move.indexOf("ClusterProjectionService.returnToMain(");
        assertTrue(query >= 0 && guard > query && dispatch > guard);
        assertTrue(move.contains("current, observedDisplay(packageName, current))"));
        assertTrue(move.contains("returnPreviousDashboardApp(packageName, reason, requestCurrent)"));
        String replacement = between(controller, "private synchronized NavAppDisplayState moveTaskToDisplayBlocking(",
                "LocalAdbBridge.ShellResult move = runCommand(");
        assertTrue(replacement.contains("checkDisplay(normalized, reason); "
                + "if (requestCurrent != null && !requestCurrent.getAsBoolean())"));
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
        assertTrue(between(service, "static void resumeSteeringRuntime(", "protected void onServiceConnected()")
                .contains("service.steeringSuspended = false;"));
        assertTrue(source("MainActivity.java").contains("resumeSteeringRuntime(this, \"activity-resume\")"));
    }

    @Test
    public void noTaskErrorsAndTimeoutsReleaseGateWithoutLaunchingOrReplayingKeys() throws Exception {
        String steering = steeringWorker();
        assertTrue(steering.contains("if (normalized.isEmpty()) { reportSteeringFailure("));
        assertTrue(steering.contains("if (!SteeringTransferPolicy.canToggleTask(current, observed)) { "
                + "reportSteeringFailure(normalized, \"task/display state unknown\"); return; }"));
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
        assertTrue(preferences.contains("editor.putLong(KEY_REVISION, revision(context) + 1L).apply();"));
        assertTrue(preferences.contains("MainActivity.publishSharedUiStateChange();"));
        assertFalse(between(preferences, "static void setPackageName(", "static void setProfile(")
                .contains("KEY_CODE"));
        assertTrue(between(preferences, "static void reset(", "private static void put(")
                .contains("putInt(KEY_CODE, NO_KEY_CODE)"));
    }

    private static String keyHandler() throws IOException {
        return between(source("NavAccessibilityService.java"),
                "public boolean onKeyEvent(KeyEvent event)", "private void beginKeyLearningInternal()").trim() + " ";
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
