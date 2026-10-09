package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class DenzaTransferDiagnosticsTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
    }

    @Test public void completionWaits1500msAfterTransferCompletion() throws Exception {
        RecordingRunner runner = new RecordingRunner();
        Harness harness = new Harness(runner);
        DenzaTransferDiagnostics.Request request = request("capture-a", "transfer-a");

        harness.controller.begin(request);
        harness.scheduler.advanceBy(4_000L);
        complete(harness.controller, request, "success", "");

        assertEquals("scheduled", request.status);
        assertEquals(1, harness.scheduler.activeTaskCount());
        assertEquals(1_500L, harness.scheduler.delays.get(0).longValue());
        harness.scheduler.advanceBy(1_499L);
        assertEquals(0, harness.executor.pendingCount());

        harness.scheduler.advanceBy(1L);
        assertEquals("collecting", request.status);
        assertEquals(1, harness.executor.pendingCount());
        assertEquals(25_000L, harness.scheduler.delays.get(1).longValue());
        assertEquals("success", lastEvent(harness, request).details.getString("transferOutcome"));
    }

    @Test public void latestPendingRequestSupersedesAndCancelsEarlierStart() throws Exception {
        Harness harness = new Harness(new RecordingRunner());
        DenzaTransferDiagnostics.Request earlier = request("capture-a", "transfer-old");
        DenzaTransferDiagnostics.Request latest = request("capture-b", "transfer-new");

        harness.controller.begin(earlier);
        complete(harness.controller, earlier, "failure", "move rejected");
        harness.controller.begin(latest);
        complete(harness.controller, latest, "success", "");

        assertEquals("superseded", earlier.status);
        assertEquals("move rejected", lastEvent(harness, earlier).details.getString("transferError"));
        assertEquals("transfer-new", lastEvent(harness, earlier).details.getString("supersededBy"));
        assertEquals(1, harness.scheduler.activeTaskCount());
        harness.scheduler.advanceBy(1_500L);
        assertEquals(1, harness.executor.pendingCount());
        assertEquals("collecting", latest.status);
    }

    @Test public void busyTransferCompletionsAreAllRetainedWithoutQueueing() throws Exception {
        Harness harness = new Harness(new RecordingRunner());
        DenzaTransferDiagnostics.Request active = request("capture-a", "active");
        DenzaTransferDiagnostics.Request busySuccess = request("capture-a", "busy-success");
        DenzaTransferDiagnostics.Request busyFailure = request("capture-a", "busy-failure");
        harness.controller.begin(active);
        complete(harness.controller, active, "success", "");
        harness.scheduler.advanceBy(1_500L);

        harness.controller.begin(busySuccess);
        complete(harness.controller, busySuccess, "success", "");
        harness.controller.begin(busyFailure);
        complete(harness.controller, busyFailure, "failure", "task not found");

        assertEquals("busy", busySuccess.status);
        assertEquals("busy", busyFailure.status);
        assertEquals("success", lastEvent(harness, busySuccess).details.getString("transferOutcome"));
        assertEquals("failure", lastEvent(harness, busyFailure).details.getString("transferOutcome"));
        assertEquals("task not found", lastEvent(harness, busyFailure).details.getString("transferError"));
        assertEquals(1, harness.executor.pendingCount());
        assertEquals(1, harness.scheduler.activeTaskCount());

        harness.executor.runNext();
        assertEquals(1, harness.runner.collectCount);
        assertEquals(0, harness.executor.pendingCount());
        assertEquals("busy", busySuccess.status);
        assertEquals("busy", busyFailure.status);
    }

    @Test public void newerBeginDuringCompletionCannotRepublishOlderRequestAsScheduled()
            throws Exception {
        Harness harness = new Harness(new RecordingRunner());
        DenzaTransferDiagnostics.Request older = request("capture-a", "older");
        DenzaTransferDiagnostics.Request newer = request("capture-b", "newer");
        harness.controller.begin(older);
        harness.scheduler.afterNextSchedule = () -> harness.controller.begin(newer);

        complete(harness.controller, older, "success", "");

        assertEquals("superseded", older.status);
        List<StatusEvent> events = eventsFor(harness, older);
        int supersededAt = lastIndexOf(events, "superseded");
        assertTrue("supersede event was published", supersededAt >= 0);
        for (int i = supersededAt + 1; i < events.size(); i++) {
            assertFalse("stale scheduled status followed supersede",
                    "scheduled".equals(events.get(i).status));
        }
        assertEquals("transfer_running", newer.status);
        assertEquals(0, harness.scheduler.activeTaskCount());
    }

    @Test public void stoppingOlderCaptureDoesNotCancelNewerPendingSnapshot() throws Exception {
        Harness harness = new Harness(new RecordingRunner());
        DenzaTransferDiagnostics.Request older = request("capture-old", "older");
        DenzaTransferDiagnostics.Request newer = request("capture-new", "newer");
        harness.controller.begin(older);
        complete(harness.controller, older, "success", "");
        harness.controller.begin(newer);
        harness.controller.cancelCapture("capture-old", "logcat_stop");
        complete(harness.controller, newer, "success", "");

        harness.scheduler.advanceBy(1_500L);

        assertEquals("superseded", older.status);
        assertEquals("collecting", newer.status);
        assertEquals(1, harness.executor.pendingCount());
        assertEquals(0, harness.runner.cancelCount);
    }

    @Test public void cancelAllCancelsPendingAndRunningButNotCompletedRunner() throws Exception {
        Harness pending = new Harness(new RecordingRunner());
        DenzaTransferDiagnostics.Request pendingRequest = request("pending", "pending-op");
        pending.controller.begin(pendingRequest);
        complete(pending.controller, pendingRequest, "success", "");
        pending.controller.cancelAll("shutdown");
        pending.scheduler.advanceBy(1_500L);
        assertEquals("cancelled", pendingRequest.status);
        assertEquals(0, pending.executor.pendingCount());

        RecordingRunner runner = new RecordingRunner();
        runner.cancelDetails = partialMetadata("cancelled", "window_windows", 73L);
        Harness active = new Harness(runner);
        DenzaTransferDiagnostics.Request runningRequest = request("running", "running-op");
        active.controller.begin(runningRequest);
        complete(active.controller, runningRequest, "success", "");
        active.scheduler.advanceBy(1_500L);
        active.controller.cancelAll("shutdown");
        assertEquals(1, runner.cancelCount);
        active.executor.runNext();
        assertEquals("cancelled", runningRequest.status);
        assertPartialOutput(lastEvent(active, runningRequest), 73L);

        DenzaTransferDiagnostics.Request completed = request("done", "done-op");
        active.controller.begin(completed);
        complete(active.controller, completed, "success", "");
        active.scheduler.advanceBy(1_500L);
        active.executor.runNext();
        assertEquals("complete", completed.status);
        active.controller.cancelAll("late_shutdown");
        assertEquals("completed runner was already terminal", 1, runner.cancelCount);
        assertEquals("complete", completed.status);
    }

    @Test public void collectionDeadlineIs25SecondsAndPreservesPartialMetadataWhileUnwinding()
            throws Exception {
        RecordingRunner runner = new RecordingRunner();
        runner.cancelDetails = partialMetadata("partial", "display", 8_192L);
        Harness harness = new Harness(runner);
        DenzaTransferDiagnostics.Request request = request("capture-a", "timeout-op");
        harness.controller.begin(request);
        complete(harness.controller, request, "success", "");
        harness.scheduler.advanceBy(1_500L);

        harness.scheduler.advanceBy(24_999L);
        assertEquals(0, runner.cancelCount);
        assertEquals("collecting", request.status);
        harness.scheduler.advanceBy(1L);

        assertEquals(1, runner.cancelCount);
        assertEquals("timeout", request.status);
        assertEquals("collection_deadline", lastEvent(harness, request).details.getString("timeout"));
        assertPartialOutput(lastEvent(harness, request), 8_192L);

        harness.executor.runNext();
        assertEquals("timeout", request.status);
        assertEquals("timeout", lastEvent(harness, request).status);
        assertPartialOutput(lastEvent(harness, request), 8_192L);
    }

    @Test public void captureStopPreservesPartialCommandBytesAfterRunnerUnwinds() throws Exception {
        RecordingRunner runner = new RecordingRunner();
        runner.cancelDetails = partialMetadata("cancelled", "surface_flinger", 4_097L);
        Harness harness = new Harness(runner);
        DenzaTransferDiagnostics.Request request = request("capture-a", "cancel-op");
        harness.controller.begin(request);
        complete(harness.controller, request, "success", "");
        harness.scheduler.advanceBy(1_500L);

        harness.controller.cancelCapture("capture-a", "logcat_stop");
        assertEquals("cancelled", request.status);
        assertPartialOutput(lastEvent(harness, request), 4_097L);

        harness.executor.runNext();
        assertEquals("cancelled", request.status);
        assertEquals("cancelled", lastEvent(harness, request).status);
        assertPartialOutput(lastEvent(harness, request), 4_097L);
    }

    @Test public void postStateDriftUsesCachedAppAndProjectionValues() throws Exception {
        NavAppDisplayState before = new NavAppDisplayState(
                "com.example.cached", 17, 8, true, "cached_visible");
        JSONObject beforeApp = appJson(before);
        JSONObject afterProjection = cachedProjection("com.example.cached", 6, 2, 4, 8);
        JSONObject beforeProjection = new JSONObject(afterProjection.toString());

        JSONObject unchangedDrift = DenzaTransferDiagnostics.AdbSnapshotRunner.stateDrift(
                beforeApp, beforeProjection, before, afterProjection);
        assertFalse(unchangedDrift.getBoolean("taskChanged"));
        assertFalse(unchangedDrift.getBoolean("displayChanged"));
        assertFalse(unchangedDrift.getBoolean("visibilityChanged"));
        assertFalse(unchangedDrift.getBoolean("ownerChanged"));
        assertFalse(unchangedDrift.getBoolean("generationChanged"));
        assertFalse(unchangedDrift.getBoolean("modeChanged"));
        assertFalse(unchangedDrift.getBoolean("overlayHostDisplayIdChanged"));
        assertFalse(unchangedDrift.getBoolean("virtualDisplayIdChanged"));

        NavAppDisplayState after = new NavAppDisplayState(
                "com.example.cached", 18, 9, false, "cached_hidden");
        JSONObject changedProjection = cachedProjection("other.owner", 7, 3, 5, 9);
        JSONObject changedDrift = DenzaTransferDiagnostics.AdbSnapshotRunner.stateDrift(
                beforeApp, beforeProjection, after, changedProjection);
        assertTrue(changedDrift.getBoolean("taskChanged"));
        assertTrue(changedDrift.getBoolean("displayChanged"));
        assertTrue(changedDrift.getBoolean("visibilityChanged"));
        assertTrue(changedDrift.getBoolean("ownerChanged"));
        assertTrue(changedDrift.getBoolean("generationChanged"));
        assertTrue(changedDrift.getBoolean("modeChanged"));
        assertTrue(changedDrift.getBoolean("overlayHostDisplayIdChanged"));
        assertTrue(changedDrift.getBoolean("virtualDisplayIdChanged"));
        assertTrue(changedDrift.getBoolean("displayIdsComparable"));
    }

    @Test public void beginTransferStoresProvidedCachedPreStateAndCompletionMetadata()
            throws Exception {
        File captureDirectory = temporaryFolder.newFolder("diagnostic-capture");
        NavAppDisplayState before = new NavAppDisplayState(
                "com.example.navigation", 41, 8, true, "cached_visible");
        JSONObject projection = new JSONObject()
                .put("status", "cached")
                .put("owner", "com.example.navigation")
                .put("generation", 6)
                .put("width", 800)
                .put("height", 480);
        DenzaTransferDiagnostics.CaptureTarget target =
                new DenzaTransferDiagnostics.CaptureTarget(
                        "20261009", "diagnostic-test-capture", captureDirectory);

        DenzaTransferDiagnostics.Request request = DenzaTransferDiagnostics.beginTransfer(
                context, target, "com.example.navigation", true,
                HudPrefs.DASHBOARD_MODE_FULL, 3, "diagnostic_test", before, projection);

        assertNotNull(request);
        JSONObject base = new JSONObject(request.baseJson);
        JSONObject preTransfer = base.getJSONObject("preTransfer");
        JSONObject preApp = preTransfer.getJSONObject("app");
        assertEquals(41, preApp.getInt("taskId"));
        assertEquals(8, preApp.getInt("displayId"));
        assertTrue(preApp.getBoolean("visible"));
        assertEquals("cached_visible", preApp.getString("status"));
        assertEquals(6, preTransfer.getJSONObject("projection").getInt("generation"));
        assertEquals("com.example.navigation",
                preTransfer.getJSONObject("projection").getString("owner"));

        DenzaTransferDiagnostics.completeTransfer(request, "synthetic transfer failure");
        JSONObject transfer = request.transferDetails();
        assertEquals("failure", transfer.getString("transferOutcome"));
        assertEquals("synthetic transfer failure", transfer.getString("transferError"));
        DenzaTransferDiagnostics.cancelCapture(target.captureId, "test_cleanup");
        assertEquals("cancelled", request.status);
        assertTrue(WazeCaptureDebugWriter.get().awaitCheckpoint(5_000L));
    }

    @Test public void recordingOffHasNoTargetAndTransferHookGatesDiagnosticRegistration()
            throws Exception {
        ShellRuntimeSession.prefs(context).edit().clear().commit();
        HudPrefs.setUserShutdownActive(context, false);
        assertFalse(LogcatRecorder.isRecording());
        assertNull(LogcatRecorder.diagnosticTarget(context));
        assertFalse(DenzaTransferDiagnostics.hasCaptureTarget(null));
        assertNull(DenzaTransferDiagnostics.beginTransfer(context, null, "com.example.navigation",
                true, HudPrefs.DASHBOARD_MODE_FULL, 3, "recording_off", null, null));

        String controllerSource = readRepositorySource(
                "app/src/main/java/com/bydhud/app/NavAppDisplayController.java");
        String transferHook = section(controllerSource,
                "private void moveIndependentDashboardAppBlocking(",
                "private String completionErrorForState(");
        int targetCheck = transferHook.indexOf("if (DenzaTransferDiagnostics.hasCaptureTarget(target))");
        int registration = transferHook.indexOf("DenzaTransferDiagnostics.beginTransfer(");
        assertTrue(targetCheck >= 0);
        assertTrue(registration > targetCheck);

        String recorderSource = readRepositorySource(
                "app/src/main/java/com/bydhud/app/LogcatRecorder.java");
        String targetMethod = section(recorderSource,
                "static DenzaTransferDiagnostics.CaptureTarget diagnosticTarget(",
                "static CaptureLease acquireForShanghai(");
        assertFalse(targetMethod.contains("LocalAdbBridge"));
        assertFalse(targetMethod.contains("runCommand("));
    }

    private static void complete(DenzaTransferDiagnostics.Controller controller,
            DenzaTransferDiagnostics.Request request, String outcome, String error)
            throws Exception {
        JSONObject details = new JSONObject()
                .put("transferCompletedAtElapsedMs", 1_234L)
                .put("transferOutcome", outcome)
                .put("transferError", error);
        request.setTransferDetails(details);
        controller.complete(request, details);
    }

    private static DenzaTransferDiagnostics.Request request(String captureId, String operationId) {
        return requestWithBase(captureId, operationId, "{}");
    }

    private static DenzaTransferDiagnostics.Request requestWithBase(
            String captureId, String operationId, String baseJson) {
        return new DenzaTransferDiagnostics.Request(
                null, captureId, operationId, "com.example.cached", null, baseJson);
    }

    private static JSONObject partialMetadata(String outcome, String command, long outputBytes)
            throws Exception {
        return new JSONObject()
                .put("snapshotOutcome", outcome)
                .put("commands", new JSONArray().put(new JSONObject()
                        .put("command", command)
                        .put("status", "timeout")
                        .put("outputBytes", outputBytes)));
    }

    private static void assertPartialOutput(StatusEvent event, long expectedBytes)
            throws Exception {
        JSONObject command = event.details.getJSONArray("commands").getJSONObject(0);
        assertEquals(expectedBytes, command.getLong("outputBytes"));
        assertEquals("timeout", command.getString("status"));
    }

    private static StatusEvent lastEvent(Harness harness,
            DenzaTransferDiagnostics.Request request) {
        List<StatusEvent> events = eventsFor(harness, request);
        assertFalse("request has at least one status event", events.isEmpty());
        return events.get(events.size() - 1);
    }

    private static List<StatusEvent> eventsFor(Harness harness,
            DenzaTransferDiagnostics.Request request) {
        List<StatusEvent> events = new ArrayList<>();
        for (StatusEvent event : harness.events) {
            if (event.request == request) events.add(event);
        }
        return events;
    }

    private static int lastIndexOf(List<StatusEvent> events, String status) {
        for (int i = events.size() - 1; i >= 0; i--) {
            if (status.equals(events.get(i).status)) return i;
        }
        return -1;
    }

    private static JSONObject appJson(NavAppDisplayState state) throws Exception {
        return new JSONObject()
                .put("package", state.packageName)
                .put("taskId", state.taskId)
                .put("displayId", state.displayId)
                .put("visible", state.visible)
                .put("status", state.status);
    }

    private static JSONObject cachedProjection(String owner, int generation, int mode,
            int overlayHostDisplayId, int virtualDisplayId) throws Exception {
        return new JSONObject()
                .put("status", "cached")
                .put("owner", owner)
                .put("generation", generation)
                .put("mode", mode)
                .put("displayIdsKnown", true)
                .put("overlayHostDisplayId", overlayHostDisplayId)
                .put("virtualDisplayId", virtualDisplayId);
    }

    private static String section(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start < 0 ? 0 : start + startMarker.length());
        assertTrue("source contains " + startMarker, start >= 0);
        assertTrue("source contains " + endMarker, end > start);
        return source.substring(start, end);
    }

    private static String readRepositorySource(String relative) throws IOException {
        Path directory = Paths.get("").toAbsolutePath();
        for (int i = 0; directory != null && i < 8; i++, directory = directory.getParent()) {
            Path candidate = directory.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("Source file not found from test working directory: " + relative);
    }

    private static final class Harness {
        final ManualScheduler scheduler = new ManualScheduler();
        final ManualExecutor executor = new ManualExecutor();
        final RecordingRunner runner;
        final List<StatusEvent> events = new ArrayList<>();
        final DenzaTransferDiagnostics.Controller controller;

        Harness(RecordingRunner runner) {
            this.runner = runner;
            controller = new DenzaTransferDiagnostics.Controller(
                    scheduler,
                    executor,
                    (request, status, details) -> events.add(new StatusEvent(
                            request, status, copy(details))),
                    runner);
        }
    }

    private static final class StatusEvent {
        final DenzaTransferDiagnostics.Request request;
        final String status;
        final JSONObject details;

        StatusEvent(DenzaTransferDiagnostics.Request request, String status, JSONObject details) {
            this.request = request;
            this.status = status;
            this.details = details;
        }
    }

    private static final class ManualScheduler implements DenzaTransferDiagnostics.Scheduler {
        final List<ScheduledWork> work = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();
        long nowMs;
        Runnable afterNextSchedule;

        @Override public DenzaTransferDiagnostics.Cancellable schedule(Runnable runnable,
                long delayMs) {
            ScheduledWork scheduled = new ScheduledWork(nowMs + delayMs, runnable);
            work.add(scheduled);
            delays.add(delayMs);
            Runnable hook = afterNextSchedule;
            afterNextSchedule = null;
            if (hook != null) hook.run();
            return () -> scheduled.cancelled = true;
        }

        void advanceBy(long elapsedMs) {
            long target = nowMs + elapsedMs;
            while (true) {
                ScheduledWork next = work.stream()
                        .filter(item -> !item.cancelled && !item.ran && item.atMs <= target)
                        .min(Comparator.comparingLong(item -> item.atMs))
                        .orElse(null);
                if (next == null) break;
                next.ran = true;
                nowMs = next.atMs;
                next.work.run();
            }
            nowMs = target;
        }

        int activeTaskCount() {
            int count = 0;
            for (ScheduledWork item : work) {
                if (!item.cancelled && !item.ran) count++;
            }
            return count;
        }
    }

    private static final class ScheduledWork {
        final long atMs;
        final Runnable work;
        boolean cancelled;
        boolean ran;

        ScheduledWork(long atMs, Runnable work) {
            this.atMs = atMs;
            this.work = work;
        }
    }

    private static final class ManualExecutor implements DenzaTransferDiagnostics.TaskExecutor {
        final List<Runnable> work = new ArrayList<>();

        @Override public void execute(Runnable runnable) { work.add(runnable); }

        int pendingCount() { return work.size(); }

        void runNext() {
            assertFalse("queued collector work exists", work.isEmpty());
            work.remove(0).run();
        }
    }

    private static final class RecordingRunner implements DenzaTransferDiagnostics.SnapshotRunner {
        int collectCount;
        int cancelCount;
        JSONObject cancelDetails = new JSONObject();
        String collectStatus = "complete";
        JSONObject collectDetails = new JSONObject();

        @Override public void collect(DenzaTransferDiagnostics.Request request,
                DenzaTransferDiagnostics.UpdateSink updates) {
            collectCount++;
            updates.update(new DenzaTransferDiagnostics.Update(
                    collectStatus, copy(collectDetails)));
        }

        @Override public JSONObject cancel(DenzaTransferDiagnostics.Request request) {
            cancelCount++;
            return copy(cancelDetails);
        }
    }

    private static JSONObject copy(JSONObject source) {
        if (source == null) return new JSONObject();
        try {
            return new JSONObject(source.toString());
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }
}
