package com.bydhud.app;

import android.content.Context;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Collects one bounded-time read-only Denza snapshot for an active Logcat capture. */
final class DenzaTransferDiagnostics {
    static final long POST_TRANSFER_DELAY_MS = 1_500L;
    static final long COLLECTION_TIMEOUT_MS = 25_000L;
    private static final String TAG = "BydHudDenzaDiagnostics";
    private static final String DIRECTORY = "diagnostics/denza-transfer";
    private static final ScheduledExecutorService TIMER =
            Executors.newSingleThreadScheduledExecutor(runnable -> daemon(runnable, "BydHudDenzaTimer"));
    private static final ExecutorService COLLECTOR =
            Executors.newSingleThreadExecutor(runnable -> daemon(runnable, "BydHudDenzaCollector"));
    private static final Controller PROCESS = new Controller(
            (work, delayMs) -> {
                ScheduledFuture<?> future = TIMER.schedule(work, delayMs, TimeUnit.MILLISECONDS);
                return () -> future.cancel(false);
            },
            COLLECTOR::execute,
            DenzaTransferDiagnostics::enqueueStatus,
            new AdbSnapshotRunner());

    private DenzaTransferDiagnostics() { }

    static boolean hasCaptureTarget(CaptureTarget target) {
        return target != null && target.directory != null && !target.captureId.isEmpty();
    }

    static final class CaptureTarget {
        final String day;
        final String captureId;
        final File directory;

        CaptureTarget(String day, String captureId, File directory) {
            this.day = day == null ? "" : day;
            this.captureId = captureId == null ? "" : captureId;
            this.directory = directory;
        }
    }

    static final class Request {
        final Context context;
        final String captureId;
        final String operationId;
        final String packageName;
        final File directory;
        final String baseJson;
        final AtomicBoolean cancelled = new AtomicBoolean();
        private final JSONObject lastDetails = new JSONObject();
        private volatile String transferDetailsJson = "{}";
        volatile String status = "new";

        Request(Context context, String captureId, String operationId, String packageName,
                File directory, String baseJson) {
            this.context = context;
            this.captureId = captureId;
            this.operationId = operationId;
            this.packageName = packageName;
            this.directory = directory;
            this.baseJson = baseJson;
        }

        JSONObject transferDetails() {
            try {
                return new JSONObject(transferDetailsJson);
            } catch (JSONException ignored) {
                return new JSONObject();
            }
        }

        void setTransferDetails(JSONObject details) {
            transferDetailsJson = details == null ? "{}" : details.toString();
            mergeDetails(details);
        }

        synchronized JSONObject mergeDetails(JSONObject details) {
            try {
                DenzaTransferDiagnostics.merge(lastDetails, details);
                return new JSONObject(lastDetails.toString());
            } catch (JSONException ignored) {
                return new JSONObject();
            }
        }
    }

    static Request beginTransfer(Context context, CaptureTarget target, String packageName,
            boolean toDashboard, int dashboardMode, int formatMethod, String reason,
            NavAppDisplayState beforeState, JSONObject projectionBefore) {
        if (context == null || !hasCaptureTarget(target)) return null;
        try {
            Context app = context.getApplicationContext();
            String operationId = UUID.randomUUID().toString();
            String normalizedPackage = safe(packageName);
            NavAppDisplayState state = beforeState == null
                    ? new NavAppDisplayState(normalizedPackage, -1,
                            NavAppDisplayState.DISPLAY_UNKNOWN, false, "unknown")
                    : beforeState;
            JSONObject before = new JSONObject()
                    .put("app", appState(normalizedPackage, state))
                    .put("projection", projectionBefore == null
                            ? new JSONObject().put("status", "unavailable")
                            : new JSONObject(projectionBefore.toString()))
                    .put("dashboardHost", DashboardProjectionPolicy.hostSnapshot());
            JSONObject base = new JSONObject()
                    .put("schemaVersion", 1)
                    .put("snapshotAtomic", false)
                    .put("snapshotCommandOrder", "surfaceflinger,display,activity activities,window windows")
                    .put("captureId", target.captureId)
                    .put("captureDay", target.day)
                    .put("operationId", operationId)
                    .put("recordingDirectory", target.directory.getAbsolutePath())
                    .put("package", normalizedPackage)
                    .put("reason", safe(reason))
                    .put("requestedAtWallMs", System.currentTimeMillis())
                    .put("requestedAtElapsedMs", SystemClock.elapsedRealtime())
                    .put("direction", toDashboard ? "to_dashboard" : "to_main")
                    .put("targetDisplay", toDashboard ? "projection" : "main")
                    .put("targetDisplayId", toDashboard ? JSONObject.NULL : 0)
                    .put("dashboardMode", dashboardMode)
                    .put("formatMethod", formatMethod)
                    .put("preTransfer", before);
            File diagnosticsRoot = new File(target.directory, DIRECTORY);
            File operationDirectory = new File(diagnosticsRoot, operationId);
            Request request = new Request(app, target.captureId, operationId,
                    normalizedPackage, operationDirectory, base.toString());
            PROCESS.begin(request);
            return request;
        } catch (Exception error) {
            Log.w(TAG, "Unable to register transfer snapshot", error);
            return null;
        }
    }

    static void completeTransfer(Request request, String error) {
        if (request == null) return;
        JSONObject details = new JSONObject();
        try {
            details.put("transferCompletedAtElapsedMs", SystemClock.elapsedRealtime())
                    .put("transferOutcome", error == null || error.isEmpty() ? "success" : "failure")
                    .put("transferError", safe(error));
        } catch (JSONException ignored) { }
        request.setTransferDetails(details);
        PROCESS.complete(request, details);
    }

    static void cancelCapture(String captureId, String reason) {
        PROCESS.cancelCapture(safe(captureId), safe(reason));
    }

    static void cancelAll(String reason) {
        PROCESS.cancelAll(safe(reason));
    }

    private static void enqueueStatus(Request request, String status, JSONObject details) {
        if (!WazeCaptureDebugWriter.get().settingsTask(
                () -> writeStatus(request, status, details))) {
            Log.w(TAG, "Unable to queue transfer status " + request.operationId);
        }
    }

    private static void writeStatus(Request request, String status, JSONObject details) {
        AtomicFile file = new AtomicFile(new File(request.directory, "status.json"));
        try {
            JSONObject record = new JSONObject(request.baseJson)
                    .put("status", status)
                    .put("updatedAtElapsedMs", SystemClock.elapsedRealtime());
            JSONObject transfer = request.transferDetails();
            merge(record, transfer);
            merge(record, details);
            byte[] bytes = record.toString(2).getBytes(StandardCharsets.UTF_8);
            NavigationLogStorage.withReadLock(() -> {
                if (!request.directory.isDirectory() && !request.directory.mkdirs()) {
                    throw new StatusWriteException(new IOException(
                            "Unable to create " + request.directory));
                }
                FileOutputStream output = null;
                try {
                    output = file.startWrite();
                    output.write(bytes);
                    file.finishWrite(output);
                } catch (IOException error) {
                    if (output != null) file.failWrite(output);
                    throw new StatusWriteException(error);
                }
            });
        } catch (Exception error) {
            Log.w(TAG, "Unable to save transfer status " + request.operationId, error);
            WazeCaptureDebugWriter.recordWriteFailure(
                    "Denza transfer status " + request.operationId + ": " + errorDetail(error));
        }
    }

    private static void merge(JSONObject target, JSONObject values) throws JSONException {
        if (values == null) return;
        java.util.Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            target.put(key, values.get(key));
        }
    }

    static JSONObject summarizeForExport(List<JSONObject> records) {
        JSONObject summary = new JSONObject();
        JSONObject counts = new JSONObject();
        int total = 0;
        boolean inProgress = false;
        boolean nonComplete = false;
        try {
            if (records != null) {
                for (JSONObject record : records) {
                    if (record == null) continue;
                    String status = record.optString("status", "unknown");
                    total++;
                    counts.put(status, counts.optInt(status) + 1);
                    if ("transfer_running".equals(status) || "scheduled".equals(status)
                            || "collecting".equals(status)) inProgress = true;
                    if (!"complete".equals(status)) nonComplete = true;
                }
            }
            summary.put("operationCount", total)
                    .put("statusCounts", counts)
                    .put("outcome", total == 0 ? "none"
                            : inProgress ? "IN_PROGRESS"
                            : nonComplete ? "PARTIAL" : "COMPLETE")
                    .put("separateFromLogcatCompleteness", true);
        } catch (JSONException ignored) { }
        return summary;
    }

    private static JSONObject appState(String packageName, NavAppDisplayState state)
            throws JSONException {
        return new JSONObject()
                .put("package", packageName)
                .put("taskId", state.taskId)
                .put("displayId", state.displayId)
                .put("visible", state.visible)
                .put("status", safe(state.status));
    }

    private static Thread daemon(Runnable work, String name) {
        Thread thread = new Thread(work, name);
        thread.setDaemon(true);
        return thread;
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    static final class Controller {
        private final Object lock = new Object();
        private final Scheduler scheduler;
        private final TaskExecutor collectorExecutor;
        private final StatusSink statusSink;
        private final SnapshotRunner runner;
        private Request pending;
        private Request running;
        private Cancellable startTask;
        private Cancellable deadlineTask;

        Controller(Scheduler scheduler, TaskExecutor collectorExecutor,
                StatusSink statusSink, SnapshotRunner runner) {
            this.scheduler = scheduler;
            this.collectorExecutor = collectorExecutor;
            this.statusSink = statusSink;
            this.runner = runner;
        }

        void begin(Request request) {
            Request superseded = null;
            boolean busy;
            synchronized (lock) {
                busy = running != null;
                if (!busy) {
                    superseded = pending;
                    if (startTask != null) startTask.cancel();
                    pending = request;
                    startTask = null;
                    request.status = "transfer_running";
                    if (superseded != null) superseded.status = "superseded";
                } else {
                    request.status = "busy";
                }
            }
            if (superseded != null) {
                persist(superseded, "superseded", json("supersededBy", request.operationId));
            }
            persist(request, busy ? "busy" : "transfer_running",
                    busy ? json("diagnosticReason", "collector_running") : new JSONObject());
        }

        void complete(Request request, JSONObject transferDetails) {
            String terminalStatus = "";
            JSONObject terminalDetails = transferDetails;
            synchronized (lock) {
                if (pending == request && !request.cancelled.get()) {
                    try {
                        Cancellable scheduled = scheduler.schedule(
                                () -> start(request), POST_TRANSFER_DELAY_MS);
                        if (pending == request && !request.cancelled.get()) {
                            startTask = scheduled;
                            request.status = "scheduled";
                            terminalStatus = "scheduled";
                        } else {
                            scheduled.cancel();
                            terminalStatus = request.status;
                        }
                    } catch (RuntimeException error) {
                        if (pending == request) pending = null;
                        if (!isTerminal(request.status)) {
                            request.status = "error";
                            terminalDetails = json("error", errorDetail(error));
                        }
                        terminalStatus = request.status;
                    }
                } else if ("busy".equals(request.status)
                        || "superseded".equals(request.status)
                        || "cancelled".equals(request.status)
                        || "timeout".equals(request.status)) {
                    terminalStatus = request.status;
                }
            }
            if (!terminalStatus.isEmpty()) persist(request, terminalStatus, terminalDetails);
        }

        private void start(Request request) {
            synchronized (lock) {
                if (pending != request || request.cancelled.get()) return;
                pending = null;
                startTask = null;
                running = request;
                request.status = "collecting";
                try {
                    deadlineTask = scheduler.schedule(
                            () -> deadline(request), COLLECTION_TIMEOUT_MS);
                } catch (RuntimeException error) {
                    running = null;
                    request.status = "error";
                    persist(request, "error", json("error", errorDetail(error)));
                    return;
                }
            }
            persist(request, "collecting", json(
                    "snapshotStartedAtElapsedMs", SystemClock.elapsedRealtime()));
            try {
                collectorExecutor.execute(() -> {
                    try {
                        runner.collect(request, update -> {
                            String updateStatus;
                            synchronized (lock) {
                                if (running != request) return;
                                if (request.cancelled.get()) {
                                    if (!"cancelled".equals(request.status)
                                            && !"timeout".equals(request.status)) return;
                                } else if (isTerminal(update.status)) {
                                    request.status = update.status;
                                }
                                updateStatus = request.status;
                            }
                            persist(request, updateStatus, update.details);
                        });
                    } catch (Exception error) {
                        synchronized (lock) {
                            if (running != request || request.cancelled.get()) return;
                            request.status = "error";
                        }
                        persist(request, "error", json("error", errorDetail(error)));
                    } finally {
                        synchronized (lock) {
                            if (running == request) {
                                running = null;
                                if (deadlineTask != null) deadlineTask.cancel();
                                deadlineTask = null;
                            }
                        }
                    }
                });
            } catch (RuntimeException error) {
                synchronized (lock) {
                    if (running == request) {
                        running = null;
                        request.status = "error";
                    }
                    if (deadlineTask != null) deadlineTask.cancel();
                    deadlineTask = null;
                }
                persist(request, "error", json("error", errorDetail(error)));
            }
        }

        private void deadline(Request request) {
            synchronized (lock) {
                if (running != request || isTerminal(request.status)
                        || !request.cancelled.compareAndSet(false, true)) return;
                request.status = "timeout";
            }
            JSONObject partial = runner.cancel(request);
            if (partial == null) partial = new JSONObject();
            try { partial.put("timeout", "collection_deadline")
                    .put("timedOutAtElapsedMs", SystemClock.elapsedRealtime()); }
            catch (JSONException ignored) { }
            persist(request, "timeout", partial);
        }

        void cancelCapture(String captureId, String reason) {
            cancel(captureId, reason, false);
        }

        void cancelAll(String reason) {
            cancel("", reason, true);
        }

        private void cancel(String captureId, String reason, boolean all) {
            Request cancelPending = null;
            Request cancelRunning = null;
            synchronized (lock) {
                if (pending != null && (all || pending.captureId.equals(captureId))) {
                    cancelPending = pending;
                    pending = null;
                    if (startTask != null) startTask.cancel();
                    startTask = null;
                    cancelPending.cancelled.set(true);
                    cancelPending.status = "cancelled";
                }
                if (running != null && (all || running.captureId.equals(captureId))
                        && !isTerminal(running.status)) {
                    cancelRunning = running;
                    cancelRunning.cancelled.set(true);
                    cancelRunning.status = "cancelled";
                }
            }
            if (cancelPending != null) {
                persist(cancelPending, "cancelled", json("cancelReason", reason));
            }
            if (cancelRunning != null) {
                JSONObject partial = runner.cancel(cancelRunning);
                if (partial == null) partial = new JSONObject();
                try { partial.put("cancelReason", reason); } catch (JSONException ignored) { }
                persist(cancelRunning, "cancelled", partial);
            }
        }

        private void persist(Request request, String status, JSONObject details) {
            synchronized (lock) {
                String currentStatus = request.status;
                statusSink.write(request, currentStatus, request.mergeDetails(details));
            }
        }

        private static boolean isTerminal(String status) {
            return "complete".equals(status) || "partial".equals(status)
                    || "error".equals(status) || "timeout".equals(status)
                    || "cancelled".equals(status) || "busy".equals(status)
                    || "superseded".equals(status);
        }
    }

    interface Scheduler {
        Cancellable schedule(Runnable work, long delayMs);
    }

    interface Cancellable {
        void cancel();
    }

    interface TaskExecutor {
        void execute(Runnable work);
    }

    interface StatusSink {
        void write(Request request, String status, JSONObject details);
    }

    interface SnapshotRunner {
        void collect(Request request, UpdateSink updates) throws Exception;
        JSONObject cancel(Request request);
    }

    interface UpdateSink {
        void update(Update update);
    }

    static final class Update {
        final String status;
        final JSONObject details;

        Update(String status, JSONObject details) {
            this.status = status;
            this.details = details;
        }
    }

    private static JSONObject json(String key, Object value) {
        JSONObject result = new JSONObject();
        try { result.put(key, value); } catch (JSONException ignored) { }
        return result;
    }

    private static JSONObject json(String key, Object value, String key2, Object value2) {
        JSONObject result = json(key, value);
        try { result.put(key2, value2); } catch (JSONException ignored) { }
        return result;
    }

    private static String errorDetail(Throwable error) {
        String message = safe(error == null ? "" : error.getMessage());
        return error == null ? "unknown" : error.getClass().getSimpleName()
                + (message.isEmpty() ? "" : ": " + message);
    }

    static final class AdbSnapshotRunner implements SnapshotRunner {
        private static final List<Command> COMMANDS = Arrays.asList(
                new Command(LocalAdbBridge.DiagnosticSnapshotCommand.SURFACE_FLINGER,
                        "surfaceflinger.txt"),
                new Command(LocalAdbBridge.DiagnosticSnapshotCommand.DISPLAY, "display.txt"),
                new Command(LocalAdbBridge.DiagnosticSnapshotCommand.ACTIVITY_ACTIVITIES,
                        "activity-activities.txt"),
                new Command(LocalAdbBridge.DiagnosticSnapshotCommand.WINDOW_WINDOWS,
                        "window-windows.txt"));
        private final Object activeLock = new Object();
        private Request activeRequest;
        private LocalAdbBridge.DiagnosticSnapshotSession activeSession;

        @Override public void collect(Request request, UpdateSink updates) {
            long startedAt = SystemClock.elapsedRealtime();
            JSONObject details = request.transferDetails();
            JSONArray commands = new JSONArray();
            LocalAdbBridge.DiagnosticSnapshotSession session = null;
            String finalStatus = "complete";
            String failure = "";
            int nextCommand = 0;
            try {
                session = LocalAdbBridge.createDiagnosticSnapshotSession(request.context);
                synchronized (activeLock) {
                    activeRequest = request;
                    activeSession = session;
                }
                if (request.cancelled.get()) throw new SnapshotCancelledException();
                long remaining = remainingMs(startedAt);
                if (remaining <= 0L) throw new SnapshotDeadlineException();
                session.open(remaining);
                for (; nextCommand < COMMANDS.size(); nextCommand++) {
                    if (request.cancelled.get()) throw new SnapshotCancelledException();
                    remaining = remainingMs(startedAt);
                    if (remaining <= 0L) throw new SnapshotDeadlineException();
                    Command command = COMMANDS.get(nextCommand);
                    long commandStarted = SystemClock.elapsedRealtime();
                    CommandOutput output = null;
                    boolean commandRecorded = false;
                    JSONObject result = new JSONObject();
                    try {
                        output = openCommandOutput(new File(request.directory, command.fileName));
                        result.put("command", command.command.name().toLowerCase(Locale.ROOT))
                                .put("file", command.fileName)
                                .put("status", "running")
                                .put("startedAtElapsedMs", commandStarted);
                        synchronized (activeLock) { commands.put(result); }
                        commandRecorded = true;
                        emit(updates, "collecting", details, commands, command.command.name());
                        int exitCode = session.stream(command.command, output);
                        synchronized (activeLock) {
                            result.put("exitCode", exitCode)
                                    .put("status", exitCode == 0 ? "complete" : "error")
                                    .put("outputBytes", output.bytes())
                                    .put("elapsedMs", SystemClock.elapsedRealtime() - commandStarted);
                        }
                        if (exitCode != 0) {
                            finalStatus = "partial";
                            result.put("error", "command exited with " + exitCode);
                        }
                    } catch (LocalAdbBridge.DiagnosticSnapshotTimeoutException error) {
                        finalStatus = "partial";
                        synchronized (activeLock) {
                            result = finishCommandResult(result, command, output, commandStarted,
                                    "timeout", errorDetail(error));
                            if (!commandRecorded) commands.put(result);
                        }
                        failure = "command_timeout";
                        session.close();
                        nextCommand++;
                        break;
                    } catch (SnapshotCancelledException error) {
                        synchronized (activeLock) {
                            String terminal = "timeout".equals(request.status)
                                    ? "timeout" : "cancelled";
                            result = finishCommandResult(result, command, output, commandStarted,
                                    terminal, terminal.equals("timeout")
                                            ? "collection_deadline" : "cancelled");
                            if (!commandRecorded) commands.put(result);
                        }
                        nextCommand++;
                        throw error;
                    } catch (Exception error) {
                        if (request.cancelled.get()) {
                            synchronized (activeLock) {
                                String terminal = "timeout".equals(request.status)
                                        ? "timeout" : "cancelled";
                                result = finishCommandResult(result, command, output,
                                        commandStarted, terminal, terminal.equals("timeout")
                                                ? "collection_deadline" : "cancelled");
                                if (!commandRecorded) commands.put(result);
                            }
                            nextCommand++;
                            throw new SnapshotCancelledException();
                        }
                        finalStatus = "partial";
                        synchronized (activeLock) {
                            result = finishCommandResult(result, command, output, commandStarted,
                                    "error", errorDetail(error));
                            if (!commandRecorded) commands.put(result);
                        }
                        failure = errorDetail(error);
                        session.close();
                        nextCommand++;
                        break;
                    } finally {
                        try {
                            if (output != null) output.close();
                        } finally {
                            // The socket may have been closed concurrently by Stop/deadline.
                            // Keep the final command byte count in the worker's terminal update.
                        }
                    }
                    emit(updates, "collecting", details, commands, "");
                }
            } catch (SnapshotCancelledException ignored) {
                finalStatus = "cancelled";
                failure = "cancelled";
            } catch (SnapshotDeadlineException error) {
                finalStatus = "timeout";
                failure = "collection_deadline";
            } catch (Exception error) {
                if (request.cancelled.get()) {
                    finalStatus = request.status;
                    failure = "timeout".equals(request.status)
                            ? "collection_deadline" : "cancelled";
                } else {
                    finalStatus = isTimeout(error) ? "timeout" : "error";
                    failure = errorDetail(error);
                }
            } finally {
                if (session != null) session.close();
                synchronized (activeLock) {
                    if (activeRequest == request) {
                        activeSession = null;
                        activeRequest = null;
                    }
                }
            }
            while (nextCommand < COMMANDS.size()) {
                Command skipped = COMMANDS.get(nextCommand++);
                commands.put(commandResult(skipped, "not_started", failure.isEmpty()
                        ? finalStatus : failure));
            }
            try {
                if (request.cancelled.get()) {
                    finalStatus = request.status;
                    failure = "timeout".equals(finalStatus)
                            ? "collection_deadline" : "cancelled";
                }
                details.put("snapshotStartedAtElapsedMs", startedAt)
                        .put("snapshotFinishedAtElapsedMs", SystemClock.elapsedRealtime())
                        .put("snapshotElapsedMs", SystemClock.elapsedRealtime() - startedAt)
                        .put("snapshotOutcome", finalStatus)
                        .put("commands", commands);
                if (!failure.isEmpty()) details.put("failure", failure);
                addPostState(request, details);
            } catch (JSONException error) {
                finalStatus = "partial";
                try { details.put("metadataError", errorDetail(error)); }
                catch (JSONException ignored) { }
            }
            updates.update(new Update(finalStatus, details));
        }

        @Override public JSONObject cancel(Request request) {
            LocalAdbBridge.DiagnosticSnapshotSession session = null;
            synchronized (activeLock) {
                if (activeRequest == request) session = activeSession;
            }
            if (session != null) session.close();
            return json("cancelRequestedAtElapsedMs", SystemClock.elapsedRealtime());
        }

        private static void addPostState(Request request, JSONObject details)
                throws JSONException {
            NavAppDisplayState state = NavAppDisplayController.get(request.context)
                    .lastState(request.packageName);
            JSONObject projection = ClusterProjectionService.cachedDiagnosticSnapshot();
            JSONObject post = new JSONObject()
                    .put("capturedAtElapsedMs", SystemClock.elapsedRealtime())
                    .put("app", appState(request.packageName, state))
                    .put("projection", projection)
                    .put("dashboardHost", DashboardProjectionPolicy.hostSnapshot());
            JSONObject base = new JSONObject(request.baseJson);
            JSONObject before = base.optJSONObject("preTransfer");
            JSONObject beforeApp = before == null ? null : before.optJSONObject("app");
            JSONObject beforeProjection = before == null
                    ? null : before.optJSONObject("projection");
            details.put("cachedPostState", post)
                    .put("stateDrift", stateDrift(beforeApp, beforeProjection,
                            state, projection));
        }

        static JSONObject stateDrift(JSONObject beforeApp, JSONObject beforeProjection,
                NavAppDisplayState afterApp, JSONObject afterProjection) throws JSONException {
            NavAppDisplayState state = afterApp == null
                    ? new NavAppDisplayState("", -1, NavAppDisplayState.DISPLAY_UNKNOWN,
                            false, "unknown")
                    : afterApp;
            JSONObject projection = afterProjection == null
                    ? new JSONObject().put("status", "unavailable") : afterProjection;
            boolean projectionComparable = beforeProjection != null
                    && "cached".equals(beforeProjection.optString("status"))
                    && "cached".equals(projection.optString("status"));
            boolean displayIdsComparable = projectionComparable
                    && beforeProjection.optBoolean("displayIdsKnown")
                    && projection.optBoolean("displayIdsKnown");
            JSONObject drift = new JSONObject()
                    .put("appStateComparable", beforeApp != null)
                    .put("taskChanged", beforeApp != null
                            && beforeApp.optInt("taskId", -1) != state.taskId)
                    .put("displayChanged", beforeApp != null
                            && beforeApp.optInt("displayId", NavAppDisplayState.DISPLAY_UNKNOWN)
                            != state.displayId)
                    .put("visibilityChanged", beforeApp != null
                            && beforeApp.optBoolean("visible") != state.visible)
                    .put("projectionStateComparable", projectionComparable)
                    .put("projectionStatusChanged", beforeProjection != null
                            && !beforeProjection.optString("status", "")
                                    .equals(projection.optString("status", "")))
                    .put("ownerChanged", projectionComparable
                            && !beforeProjection.optString("owner", "").equals(
                                    projection.optString("owner", "")))
                    .put("generationChanged", projectionComparable
                            && beforeProjection.optLong("generation", -1L)
                            != projection.optLong("generation", -1L))
                    .put("modeChanged", projectionComparable
                            && beforeProjection.optInt("mode", -1)
                            != projection.optInt("mode", -1))
                    .put("displayIdsKnownChanged", projectionComparable
                            && beforeProjection.optBoolean("displayIdsKnown")
                                    != projection.optBoolean("displayIdsKnown"))
                    .put("displayIdsComparable", displayIdsComparable)
                    .put("overlayHostDisplayIdChanged", displayIdsComparable
                            && beforeProjection.optInt("overlayHostDisplayId", -1)
                            != projection.optInt("overlayHostDisplayId", -1))
                    .put("virtualDisplayIdChanged", displayIdsComparable
                            && beforeProjection.optInt("virtualDisplayId", -1)
                            != projection.optInt("virtualDisplayId", -1));
            return drift;
        }

        private static void emit(UpdateSink updates, String status, JSONObject base,
                JSONArray commands, String currentCommand) {
            try {
                JSONObject details = new JSONObject(base.toString())
                        .put("commands", new JSONArray(commands.toString()));
                if (!currentCommand.isEmpty()) details.put("currentCommand", currentCommand);
                updates.update(new Update(status, details));
            } catch (JSONException ignored) { }
        }

        private static JSONObject finishCommandResult(JSONObject result, Command command,
                CommandOutput output, long commandStarted, String status, String error) {
            try {
                if (!result.has("command")) {
                    result.put("command", command.command.name().toLowerCase(Locale.ROOT))
                            .put("file", command.fileName)
                            .put("startedAtElapsedMs", commandStarted);
                }
                result.put("status", status)
                        .put("outputBytes", output == null ? 0L : output.bytes())
                        .put("elapsedMs", SystemClock.elapsedRealtime() - commandStarted)
                        .put("error", error);
            } catch (JSONException ignored) { }
            return result;
        }

        private static JSONObject commandResult(Command command, String status, String reason) {
            JSONObject result = new JSONObject();
            try {
                result.put("command", command.command.name().toLowerCase(Locale.ROOT))
                        .put("file", command.fileName)
                        .put("status", status)
                        .put("reason", reason);
            } catch (JSONException ignored) { }
            return result;
        }

        private static long remainingMs(long startedAt) {
            return COLLECTION_TIMEOUT_MS - (SystemClock.elapsedRealtime() - startedAt);
        }

        private static boolean isTimeout(Throwable error) {
            Throwable current = error;
            while (current != null) {
                if (current instanceof SocketTimeoutException
                        || current instanceof LocalAdbBridge.DiagnosticSnapshotTimeoutException) {
                    return true;
                }
                current = current.getCause();
            }
            return false;
        }
    }

    private static final class Command {
        final LocalAdbBridge.DiagnosticSnapshotCommand command;
        final String fileName;

        Command(LocalAdbBridge.DiagnosticSnapshotCommand command, String fileName) {
            this.command = command;
            this.fileName = fileName;
        }
    }

    private static final class CommandOutput extends OutputStream {
        private final FileOutputStream output;
        private long bytes;
        private boolean closed;

        CommandOutput(FileOutputStream output) {
            this.output = output;
        }

        @Override public void write(int value) throws IOException {
            withStorageReadLock(() -> output.write(value));
            bytes++;
        }

        @Override public void write(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return;
            withStorageReadLock(() -> output.write(buffer, offset, length));
            bytes += length;
        }

        @Override public void flush() throws IOException {
            withStorageReadLock(output::flush);
        }

        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            withStorageReadLock(output::close);
        }

        long bytes() { return bytes; }
    }

    private static CommandOutput openCommandOutput(File file) throws IOException {
        try {
            FileOutputStream output = NavigationLogStorage.withReadLock(() -> {
                File parent = file.getParentFile();
                if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
                    throw new StatusWriteException(new IOException("Unable to create " + parent));
                }
                try {
                    return new FileOutputStream(file, false);
                } catch (IOException error) {
                    throw new StatusWriteException(error);
                }
            });
            return new CommandOutput(output);
        } catch (StatusWriteException error) {
            throw error.io;
        }
    }

    private static void withStorageReadLock(IoAction action) throws IOException {
        try {
            NavigationLogStorage.withReadLock(() -> {
                try {
                    action.run();
                } catch (IOException error) {
                    throw new StatusWriteException(error);
                }
            });
        } catch (StatusWriteException error) {
            throw error.io;
        }
    }

    private interface IoAction { void run() throws IOException; }

    private static final class StatusWriteException extends RuntimeException {
        final IOException io;
        StatusWriteException(IOException io) { super(io); this.io = io; }
    }

    private static final class SnapshotCancelledException extends IOException { }
    private static final class SnapshotDeadlineException extends IOException { }
}
