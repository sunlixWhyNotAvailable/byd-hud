package com.bydhud.app;

// Captures bounded full-system diagnostics through the already-authorized local ADB bridge.

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class LogcatRecorder {
    static final String STATUS_WAITING = "Очікування запису";
    static final String STATUS_RECORDING = "Йде запис логу";
    static final String STATUS_SAVING = "Збереження логу";
    static final String STATUS_SAVED = "Лог збережено";

    static final String PHASE_IDLE = "idle";
    static final String PHASE_INTERACTION_1 = "interaction-1";
    static final String PHASE_INTERACTION_2 = "interaction-2";

    private static final String TAG = "BydHudLogcat";
    private static final long POLL_INTERVAL_MS = 2_000L;
    private static final long SEGMENT_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_SEGMENTS = 4;
    private static final int MAX_UID_POLL_BYTES = 4 * 1024 * 1024;
    private static final int MAX_BOUNDARY_LINES = 4_096;
    private static final SimpleDateFormat FILE_FORMAT =
            new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US);
    private static final SimpleDateFormat LINE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US);
    private static final SimpleDateFormat LOGCAT_CURSOR_FORMAT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private static final ScheduledExecutorService WORKER =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "BydHudSystemRecorder");
                thread.setDaemon(true);
                return thread;
            });
    private static volatile Handler mainHandler;

    private static volatile Session activeSession;
    private static volatile Session finalizingSession;
    private static volatile String activeStartDay = "";
    private static File lastSavedFile;
    private static String lastStatus = STATUS_WAITING;
    private static String lastDetail = "";

    private LogcatRecorder() {
    }

    static synchronized boolean isRecording() {
        return activeSession != null;
    }

    static synchronized boolean isPhaseActive() {
        return activeSession != null && activeSession.activePhase != null;
    }

    static synchronized String activeStartDay() {
        return activeSession != null || finalizingSession != null ? activeStartDay : "";
    }

    static synchronized boolean hasSessionForDay(String day) {
        String safeDay = day == null ? "" : day.trim();
        return !safeDay.isEmpty() && safeDay.equals(activeStartDay())
                && (activeSession != null || finalizingSession != null);
    }

    static String retentionActiveStartDay() {
        return activeStartDay;
    }

    static synchronized String statusText() {
        StringBuilder text = new StringBuilder(lastStatus);
        Session session = activeSession != null ? activeSession : finalizingSession;
        File file = session == null ? lastSavedFile : session.manifestFile;
        if (file != null) text.append('\n').append(file.getAbsolutePath());
        if (session != null && session.activePhase != null) {
            text.append('\n').append("phase=").append(session.activePhase.id);
        }
        if (!lastDetail.isEmpty()) text.append('\n').append(lastDetail);
        return text.toString();
    }

    static Result start(Context context) {
        Context appContext = context.getApplicationContext();
        Session session;
        synchronized (LogcatRecorder.class) {
            if (activeSession != null || finalizingSession != null) {
                Session current = activeSession != null ? activeSession : finalizingSession;
                if (finalizingSession != null) {
                    lastStatus = STATUS_SAVING;
                    return Result.pending(current.manifestFile, "finalization in progress");
                }
                lastStatus = STATUS_RECORDING;
                return Result.recording(current.manifestFile, "already recording");
            }
            String day = NavCaptureStore.todayDir();
            String captureId = timestampForFile();
            File directory = new File(NavigationLogStorage.logcatDir(appContext, day),
                    "system_" + captureId);
            session = new Session(appContext, day, captureId, directory);
            activeSession = session;
            activeStartDay = day;
            lastSavedFile = null;
            lastStatus = STATUS_RECORDING;
            lastDetail = "starting full-system capture";
        }
        Future<?> future = WORKER.submit(() -> runBegin(session));
        Throwable failure = await(future, 90_000L);
        if (failure instanceof TimeoutException) {
            return Result.pending(session.manifestFile, "start still running");
        }
        if (failure != null) {
            return Result.failed(session.manifestFile,
                    "start failed: " + failure.getClass().getSimpleName()
                            + ": " + safe(failure.getMessage()));
        }
        synchronized (LogcatRecorder.class) {
            if (session.failed) {
                return Result.failed(session.manifestFile, session.failureDetail);
            }
            if (session.finalized) {
                return Result.saved(session.manifestFile, "already stopped");
            }
            if (session.stopRequested || activeSession != session) {
                return Result.pending(session.manifestFile, "stop requested");
            }
        }
        return Result.recording(session.manifestFile, session.mode);
    }

    static Result restartAfterRebase(Context context) {
        return start(context);
    }

    static Result stop(Context context) {
        Session session;
        Future<?> future;
        synchronized (LogcatRecorder.class) {
            session = activeSession;
            if (session == null) {
                if (finalizingSession != null) {
                    return Result.pending(finalizingSession.manifestFile,
                            "finalization in progress");
                }
                lastStatus = lastSavedFile == null ? STATUS_WAITING : STATUS_SAVED;
                return Result.saved(lastSavedFile,
                        lastSavedFile == null ? "not recording" : "already stopped");
            }
            activeSession = null;
            finalizingSession = session;
            session.stopRequested = true;
            lastStatus = STATUS_SAVING;
            lastDetail = "finalizing system capture";
            if (session.pollFuture != null) session.pollFuture.cancel(false);
            if (session.phaseFuture != null) session.phaseFuture.cancel(false);
            future = ensureFinishLocked(session);
        }
        Throwable failure = await(future, 90_000L);
        if (failure instanceof TimeoutException) {
            return Result.pending(session.manifestFile, "finalization still running");
        }
        if (failure != null) {
            return Result.failed(session.manifestFile,
                    "stop failed: " + failure.getClass().getSimpleName()
                            + ": " + safe(failure.getMessage()));
        }
        synchronized (LogcatRecorder.class) {
            if (session.failed) {
                return Result.failed(session.manifestFile, session.failureDetail);
            }
            return Result.saved(lastSavedFile, lastDetail);
        }
    }

    static void stopAsync(Context context, Runnable completion) {
        Session session;
        synchronized (LogcatRecorder.class) {
            session = activeSession;
            if (session == null) {
                session = finalizingSession;
                if (session == null) {
                    postCompletion(completion);
                    return;
                }
                addCompletionLocked(session, completion);
                ensureFinishLocked(session);
                return;
            }
            activeSession = null;
            finalizingSession = session;
            session.stopRequested = true;
            lastStatus = STATUS_SAVING;
            lastDetail = "finalizing system capture";
            if (session.pollFuture != null) session.pollFuture.cancel(false);
            if (session.phaseFuture != null) session.phaseFuture.cancel(false);
            addCompletionLocked(session, completion);
            ensureFinishLocked(session);
        }
    }

    static Result startPresetPhase(Context context, String phaseId) {
        Session session;
        long durationMs = presetDurationMs(phaseId);
        synchronized (LogcatRecorder.class) {
            session = activeSession;
            if (session == null) return Result.failed(lastSavedFile, "recorder is not running");
            if (durationMs <= 0L) return Result.failed(session.manifestFile, "unknown phase");
            if (session.activePhase != null) {
                return Result.failed(session.manifestFile,
                        "phase already active: " + session.activePhase.id);
            }
        }
        Throwable failure = await(WORKER.submit(
                () -> beginPhase(session, phaseId, durationMs)), 30_000L);
        if (failure != null) {
            return Result.failed(session.manifestFile,
                    "phase failed: " + failure.getClass().getSimpleName());
        }
        return Result.recording(session.manifestFile,
                "phase=" + phaseId + " durationMs=" + durationMs);
    }

    static long presetDurationMs(String phaseId) {
        if (PHASE_IDLE.equals(phaseId)) return 10_000L;
        if (PHASE_INTERACTION_1.equals(phaseId)
                || PHASE_INTERACTION_2.equals(phaseId)) return 24_000L;
        return -1L;
    }

    static String fullLogcatCommandForTest(long cursorMs) {
        return fullLogcatCommand(cursorMs);
    }

    private static void runBegin(Session session) {
        try {
            begin(session);
        } catch (Exception error) {
            fail(session, "start failed: " + error.getClass().getSimpleName()
                    + ": " + safe(error.getMessage()));
        }
    }

    private static Future<?> ensureFinishLocked(Session session) {
        Future<?> future = session.finishFuture;
        if (future == null) {
            future = WORKER.submit(() -> runFinish(session));
            session.finishFuture = future;
        }
        return future;
    }

    private static void runFinish(Session session) {
        try {
            finish(session);
        } catch (Exception error) {
            fail(session, "stop failed: " + error.getClass().getSimpleName()
                    + ": " + safe(error.getMessage()));
        }
    }

    private static void addCompletionLocked(Session session, Runnable completion) {
        if (completion != null) session.completions.add(completion);
    }

    private static void postCompletion(Runnable completion) {
        if (completion == null) return;
        Handler handler = mainHandler();
        if (handler == null) return;
        handler.post(() -> {
            try {
                completion.run();
            } catch (RuntimeException error) {
                Log.w(TAG, "recorder completion failed", error);
            }
        });
    }

    private static Handler mainHandler() {
        Handler cached = mainHandler;
        if (cached != null) return cached;
        try {
            Looper looper = Looper.getMainLooper();
            if (looper == null) return null;
            cached = new Handler(looper);
            mainHandler = cached;
            return cached;
        } catch (RuntimeException error) {
            Log.w(TAG, "main handler unavailable", error);
            return null;
        }
    }

    private static List<Runnable> takeCompletionsLocked(Session session) {
        List<Runnable> completions = new ArrayList<>(session.completions);
        session.completions.clear();
        return completions;
    }

    private static void postCompletions(List<Runnable> completions) {
        for (Runnable completion : completions) postCompletion(completion);
    }

    private static void publishUiState() {
        try {
            MainActivity.publishSharedUiStateChange();
        } catch (RuntimeException error) {
            Log.w(TAG, "shared UI state publish failed", error);
        }
    }

    private static void begin(Session session) {
        try {
            if (!session.directory.exists() && !session.directory.mkdirs()) {
                throw new IOException("Unable to create " + session.directory);
            }
            session.startedWallMs = System.currentTimeMillis();
            session.startedElapsedMs = SystemClock.elapsedRealtime();
            session.lastPollWallMs = session.startedWallMs - 1_000L;
            session.manifest.put("captureId", session.captureId);
            session.manifest.put("status", "recording");
            session.manifest.put("startedAt", timestampForLine(session.startedWallMs));
            session.manifest.put("startedElapsedMs", session.startedElapsedMs);
            session.manifest.put("package", session.context.getPackageName());
            session.manifest.put("versionName", BuildConfig.VERSION_NAME);
            session.manifest.put("versionCode", BuildConfig.VERSION_CODE);
            session.manifest.put("bufferClear", false);
            session.manifest.put("pollIntervalMs", POLL_INTERVAL_MS);
            session.manifest.put("segmentBytes", SEGMENT_BYTES);
            session.manifest.put("maxSegments", MAX_SEGMENTS);
            session.manifest.put("logcatCommand",
                    "logcat -b all -v threadtime -T <cursor> -d");
            session.manifest.put("fallbackCommand",
                    "logcat -v threadtime -T <cursor> -d (app-visible buffers)");
            session.manifest.put("app", appIdentity(session.context));
            session.manifest.put("runtime", runtimeIdentity(session.context));
            String adbProbe;
            try {
                LocalAdbBridge.ShellResult access = LocalAdbBridge.runDiagnosticShellCommand(
                        session.context, "logcat -g -b all");
                session.mode = access.success() ? "full_system_adb" : "app_uid_fallback";
                session.fallbackReason = access.success() ? "" : access.shortDetail();
                adbProbe = access.shortDetail();
            } catch (Exception error) {
                session.mode = "app_uid_fallback";
                session.fallbackReason = "ADB unavailable: "
                        + error.getClass().getSimpleName() + ": " + safe(error.getMessage());
                adbProbe = session.fallbackReason;
            }
            session.manifest.put("mode", session.mode);
            session.manifest.put("buffers",
                    "full_system_adb".equals(session.mode) ? "all" : "app-visible");
            session.manifest.put("adbProbe", adbProbe);
            writeLog(session, "=== BYD HUD system capture " + session.captureId + " ===\n"
                    + "mode=" + session.mode + " bufferClear=false\n");
            captureSnapshot(session, "before", fullSnapshotCommands());
            poll(session);
            writeManifest(session);
            session.pollFuture = WORKER.scheduleWithFixedDelay(
                    () -> pollSafely(session), POLL_INTERVAL_MS, POLL_INTERVAL_MS,
                    TimeUnit.MILLISECONDS);
            if (session.stopRequested) {
                session.pollFuture.cancel(false);
            }
            updateDetail(session, "mode=" + session.mode + " capture=" + session.captureId);
            AppEventLogger.event(session.context,
                    "system_recorder_start id=" + session.captureId + " mode=" + session.mode);
            publishUiState();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static void finish(Session session) {
        try {
            synchronized (LogcatRecorder.class) {
                if (session.finalized || session.failed) return;
            }
            if (session.activePhase != null) finishPhase(session, "recorder-stop");
            poll(session);
            captureSnapshot(session, "after", fullSnapshotCommands());
            finalizeSegment(session);
            long endedWallMs = System.currentTimeMillis();
            session.manifest.put("status", "saved");
            session.manifest.put("endedAt", timestampForLine(endedWallMs));
            session.manifest.put("endedElapsedMs", SystemClock.elapsedRealtime());
            session.manifest.put("durationMs", Math.max(0L,
                    SystemClock.elapsedRealtime() - session.startedElapsedMs));
            session.manifest.put("mode", session.mode);
            session.manifest.put("fallbackReason", session.fallbackReason);
            session.manifest.put("segments", new JSONArray(session.segmentNames));
            session.manifest.put("bytes", session.totalBytes);
            session.manifest.put("droppedBytes", session.droppedBytes);
            session.manifest.put("truncated", session.truncated);
            session.manifest.put("phases", session.phases);
            session.manifest.put("runtimeEnd", runtimeIdentity(session.context));
            writeManifest(session);
            List<Runnable> completions;
            synchronized (LogcatRecorder.class) {
                session.finalized = true;
                if (activeSession == session) activeSession = null;
                if (finalizingSession == session) finalizingSession = null;
                activeStartDay = "";
                lastSavedFile = session.manifestFile;
                lastStatus = STATUS_SAVED;
                lastDetail = "mode=" + session.mode + " bytes=" + session.totalBytes
                        + (session.truncated ? " truncated" : "");
                completions = takeCompletionsLocked(session);
            }
            publishUiState();
            postCompletions(completions);
            AppEventLogger.event(session.context,
                    "system_recorder_saved id=" + session.captureId
                            + " bytes=" + session.totalBytes
                            + " mode=" + session.mode
                            + " truncated=" + session.truncated);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static void pollSafely(Session session) {
        if (!isCurrent(session)) return;
        try {
            poll(session);
            writeManifest(session);
        } catch (Exception error) {
            session.pollErrors++;
            session.lastPollError = error.getClass().getSimpleName() + ": "
                    + safe(error.getMessage());
            try {
                session.manifest.put("pollErrors", session.pollErrors);
                session.manifest.put("lastPollError", session.lastPollError);
                writeManifest(session);
            } catch (Exception ignored) {
                Log.w(TAG, "manifest update failed", ignored);
            }
        }
    }

    private static void poll(Session session) throws IOException {
        long requestedAtMs = System.currentTimeMillis();
        long cursorMs = Math.max(0L, session.lastPollWallMs - 1_000L);
        String output;
        if ("full_system_adb".equals(session.mode)) {
            try {
                LocalAdbBridge.ShellResult result = LocalAdbBridge.runDiagnosticShellCommand(
                        session.context, fullLogcatCommand(cursorMs));
                if (result.success()) {
                    output = result.output;
                    if (result.truncated) {
                        session.truncated = true;
                        session.droppedBytes += result.droppedBytes;
                    }
                } else {
                    session.mode = "app_uid_fallback";
                    session.fallbackReason = "ADB lost: " + result.shortDetail();
                    output = appUidLogcat(session, cursorMs);
                }
            } catch (IOException error) {
                session.mode = "app_uid_fallback";
                session.fallbackReason = "ADB lost: " + safe(error.getMessage());
                output = appUidLogcat(session, cursorMs);
            }
        } else {
            output = appUidLogcat(session, cursorMs);
        }
        writeDeduplicated(session, boundedPollOutput(session, output));
        session.lastPollWallMs = requestedAtMs;
        session.pollCount++;
        try {
            session.manifest.put("mode", session.mode);
            session.manifest.put("fallbackReason", session.fallbackReason);
            session.manifest.put("pollCount", session.pollCount);
        } catch (Exception ignored) {
            Log.w(TAG, "poll manifest update failed", ignored);
        }
    }

    private static String appUidLogcat(Session session, long cursorMs) throws IOException {
        File output = new File(session.context.getCacheDir(),
                "bydhud-logcat-" + session.captureId + ".tmp");
        Process process = new ProcessBuilder(
                "logcat", "-v", "threadtime", "-T", cursor(cursorMs), "-d")
                .redirectErrorStream(true)
                .redirectOutput(output)
                .start();
        try {
            if (!process.waitFor(8L, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("app logcat poll timeout");
            }
            long available = output.length();
            if (available > MAX_UID_POLL_BYTES) {
                session.truncated = true;
                session.droppedBytes += available - MAX_UID_POLL_BYTES;
            }
            return readBounded(output, MAX_UID_POLL_BYTES);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("app logcat poll interrupted", error);
        } finally {
            if (output.exists() && !output.delete()) output.deleteOnExit();
        }
    }

    private static void writeDeduplicated(Session session, String output) throws IOException {
        if (output == null || output.isEmpty()) {
            return;
        }
        String[] lines = output.split("\\r?\\n");
        StringBuilder accepted = new StringBuilder(output.length());
        for (String line : lines) {
            if (line.isEmpty() || session.previousPollLines.contains(line)) continue;
            accepted.append(line).append('\n');
        }
        LinkedHashSet<String> boundary = new LinkedHashSet<>();
        int start = Math.max(0, lines.length - MAX_BOUNDARY_LINES);
        for (int i = start; i < lines.length; i++) {
            if (!lines[i].isEmpty()) boundary.add(lines[i]);
        }
        session.previousPollLines = boundary;
        if (accepted.length() > 0) writeLog(session, accepted.toString());
    }

    private static String boundedPollOutput(Session session, String output) {
        if (output == null || output.isEmpty()) return "";
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_UID_POLL_BYTES) return output;
        session.truncated = true;
        session.droppedBytes += bytes.length - MAX_UID_POLL_BYTES;
        return new String(bytes, 0, MAX_UID_POLL_BYTES, StandardCharsets.UTF_8);
    }

    private static void beginPhase(Session session, String phaseId, long durationMs) {
        if (!isCurrent(session) || session.activePhase != null) return;
        try {
            Phase phase = new Phase(phaseId, durationMs);
            phase.startedWallMs = System.currentTimeMillis();
            phase.startedElapsedMs = SystemClock.elapsedRealtime();
            session.activePhase = phase;
            AppEventLogger.event(session.context,
                    "system_recorder_phase_start id=" + session.captureId
                            + " phase=" + phase.id + " durationMs=" + durationMs);
            writeLog(session, "\n=== PHASE START " + phase.id + " "
                    + timestampForLine(phase.startedWallMs) + " ===\n");
            captureSnapshot(session, "phase-" + phase.id + "-before",
                    phaseBeforeCommands());
            updateDetail(session, "phase=" + phase.id + " durationMs=" + durationMs);
            writeManifest(session);
            session.phaseFuture = WORKER.schedule(
                    () -> finishPhaseSafely(session), durationMs, TimeUnit.MILLISECONDS);
            publishUiState();
        } catch (Exception error) {
            session.activePhase = null;
            session.phaseFuture = null;
            publishUiState();
            throw new IllegalStateException(error);
        }
    }

    private static void finishPhaseSafely(Session session) {
        try {
            finishPhase(session, "complete");
        } catch (Exception error) {
            Log.e(TAG, "phase finish failed", error);
        }
    }

    private static void finishPhase(Session session, String outcome) throws Exception {
        Phase phase = session.activePhase;
        if (phase == null) return;
        Exception failure = null;
        phase.endedWallMs = System.currentTimeMillis();
        phase.endedElapsedMs = SystemClock.elapsedRealtime();
        try {
            captureSnapshot(session, "phase-" + phase.id + "-after", phaseAfterCommands());
            JSONObject record = new JSONObject();
            record.put("id", phase.id);
            record.put("plannedDurationMs", phase.durationMs);
            record.put("actualDurationMs", Math.max(0L,
                    phase.endedElapsedMs - phase.startedElapsedMs));
            record.put("startedAt", timestampForLine(phase.startedWallMs));
            record.put("endedAt", timestampForLine(phase.endedWallMs));
            record.put("outcome", outcome);
            session.phases.put(record);
            writeLog(session, "=== PHASE END " + phase.id + " outcome=" + outcome + " "
                    + timestampForLine(phase.endedWallMs) + " ===\n");
            AppEventLogger.event(session.context,
                    "system_recorder_phase_end id=" + session.captureId
                            + " phase=" + phase.id + " outcome=" + outcome
                            + " durationMs=" + (phase.endedElapsedMs - phase.startedElapsedMs));
        } catch (Exception error) {
            failure = error;
        } finally {
            session.activePhase = null;
            session.phaseFuture = null;
            try {
                writeManifest(session);
            } catch (Exception error) {
                if (failure == null) failure = error;
            }
            updateDetail(session, (failure == null ? "phase saved=" : "phase failed=")
                    + phase.id);
            publishUiState();
        }
        if (failure != null) throw failure;
    }

    private static void captureSnapshot(Session session, String label, List<String> commands)
            throws IOException {
        File file = new File(session.directory, label + ".txt");
        StringBuilder output = new StringBuilder();
        output.append("captureId=").append(session.captureId)
                .append(" label=").append(label)
                .append(" at=").append(timestampForLine(System.currentTimeMillis()))
                .append('\n');
        if (!"full_system_adb".equals(session.mode)) {
            output.append("unavailable: full-system ADB unavailable; mode=")
                    .append(session.mode).append(" reason=")
                    .append(session.fallbackReason).append('\n');
        } else {
            for (String command : commands) {
                output.append("\n### ").append(command).append('\n');
                try {
                    LocalAdbBridge.ShellResult result =
                            LocalAdbBridge.runDiagnosticShellCommand(session.context, command);
                    output.append("exit=").append(result.exitCode).append('\n')
                            .append(result.output).append('\n');
                    if (result.truncated) {
                        session.truncated = true;
                        session.droppedBytes += result.droppedBytes;
                        output.append("truncated=true droppedBytes=")
                                .append(result.droppedBytes).append('\n');
                    }
                } catch (Exception error) {
                    output.append("unavailable: ").append(error.getClass().getSimpleName())
                            .append(": ").append(safe(error.getMessage())).append('\n');
                }
            }
        }
        writeFile(file, output.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> fullSnapshotCommands() {
        return Arrays.asList(
                "dumpsys accessibility",
                "dumpsys activity activities",
                "dumpsys window windows",
                "dumpsys package com.bydhud.app",
                "dumpsys cpuinfo",
                "dumpsys thermalservice",
                "dumpsys meminfo com.bydhud.app",
                "cat /proc/loadavg",
                "cat /proc/" + android.os.Process.myPid() + "/stat");
    }

    private static List<String> phaseBeforeCommands() {
        return Arrays.asList(
                "dumpsys gfxinfo com.bydhud.app reset",
                "dumpsys cpuinfo",
                "dumpsys thermalservice",
                "dumpsys meminfo com.bydhud.app",
                "cat /proc/loadavg");
    }

    private static List<String> phaseAfterCommands() {
        return Arrays.asList(
                "dumpsys gfxinfo com.bydhud.app framestats",
                "dumpsys cpuinfo",
                "dumpsys thermalservice",
                "dumpsys meminfo com.bydhud.app",
                "cat /proc/loadavg");
    }

    private static JSONObject appIdentity(Context context) throws Exception {
        JSONObject app = new JSONObject();
        PackageManager manager = context.getPackageManager();
        PackageInfo info;
        if (Build.VERSION.SDK_INT >= 28) {
            info = manager.getPackageInfo(context.getPackageName(),
                    PackageManager.GET_SIGNING_CERTIFICATES);
        } else {
            info = manager.getPackageInfo(context.getPackageName(),
                    PackageManager.GET_SIGNATURES);
        }
        ApplicationInfo application = context.getApplicationInfo();
        app.put("pid", android.os.Process.myPid());
        app.put("uid", android.os.Process.myUid());
        app.put("sourceDir", application.sourceDir);
        app.put("apkSha256", sha256(new File(application.sourceDir)));
        app.put("signerSha256", signerSha256(info));
        app.put("debuggable", (application.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        app.put("buildFingerprint", Build.FINGERPRINT);
        app.put("api", Build.VERSION.SDK_INT);
        app.put("abis", new JSONArray(Arrays.asList(Build.SUPPORTED_ABIS)));
        return app;
    }

    private static JSONObject runtimeIdentity(Context context) throws Exception {
        JSONObject runtime = new JSONObject();
        runtime.put("permissionStatus", NavRuntimePermissionStatus.check(context).summary());
        runtime.put("accessibilityConnected",
                NavAccessibilityService.isConnectedForRuntimeCheck());
        runtime.put("accessibilityDetail",
                NavAccessibilityService.runtimeDetailForRuntimeCheck());
        runtime.put("hudDelivery", HudDeliveryStatus.uiStatus());
        runtime.put("wazeIngress", NavCaptureIngressPolicy.mode("com.waze").name());
        runtime.put("gmapsIngress",
                NavCaptureIngressPolicy.mode(GMapsDirectChannel.PACKAGE_NAME).name());
        runtime.put("wazeHudEnabled",
                NavCapturePrefs.isHudEnabled(context, "com.waze"));
        runtime.put("gmapsHudEnabled",
                NavCapturePrefs.isHudEnabled(context, GMapsDirectChannel.PACKAGE_NAME));
        return runtime;
    }

    private static String signerSha256(PackageInfo info) throws Exception {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            signatures = info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
        } else {
            signatures = info.signatures;
        }
        if (signatures == null || signatures.length == 0) return "unavailable";
        return hex(MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray()));
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        try (FileInputStream input = new FileInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) value.append(String.format(Locale.US, "%02X", item));
        return value.toString();
    }

    private static void writeLog(Session session, String text) throws IOException {
        NavigationLogStorage.lockTopologyRead();
        try {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            int offset = 0;
            while (offset < bytes.length) {
                if (session.segmentIndex >= MAX_SEGMENTS) {
                    session.truncated = true;
                    session.droppedBytes += bytes.length - offset;
                    return;
                }
                if (session.segmentOut == null) openSegment(session);
                int writable = (int) Math.min(bytes.length - offset,
                        SEGMENT_BYTES - session.segmentBytes);
                session.segmentOut.write(bytes, offset, writable);
                session.segmentOut.flush();
                session.segmentBytes += writable;
                session.totalBytes += writable;
                offset += writable;
                if (session.segmentBytes >= SEGMENT_BYTES) finalizeSegment(session);
            }
        } finally {
            NavigationLogStorage.unlockTopologyRead();
        }
    }

    private static void openSegment(Session session) throws IOException {
        String base = String.format(Locale.US, "logcat-%03d", session.segmentIndex);
        session.segmentPart = new File(session.directory, base + ".log.part");
        session.segmentFinal = new File(session.directory, base + ".log");
        session.segmentOut = new FileOutputStream(session.segmentPart, false);
        session.segmentBytes = 0L;
        session.segmentNames.add(session.segmentFinal.getName());
    }

    private static void finalizeSegment(Session session) throws IOException {
        NavigationLogStorage.lockTopologyRead();
        try {
            if (session.segmentOut == null) return;
            session.segmentOut.flush();
            session.segmentOut.close();
            session.segmentOut = null;
            if (session.segmentFinal.exists() && !session.segmentFinal.delete()) {
                throw new IOException("Unable to replace " + session.segmentFinal);
            }
            if (!session.segmentPart.renameTo(session.segmentFinal)) {
                throw new IOException("Unable to finalize " + session.segmentPart);
            }
            session.segmentPart = null;
            session.segmentFinal = null;
            session.segmentIndex++;
            session.segmentBytes = 0L;
        } finally {
            NavigationLogStorage.unlockTopologyRead();
        }
    }

    private static void writeManifest(Session session) throws IOException {
        try {
            session.manifest.put("mode", session.mode);
            session.manifest.put("fallbackReason", session.fallbackReason);
            session.manifest.put("segments", new JSONArray(session.segmentNames));
            session.manifest.put("bytes", session.totalBytes);
            session.manifest.put("droppedBytes", session.droppedBytes);
            session.manifest.put("truncated", session.truncated);
            session.manifest.put("phases", session.phases);
            File temporary = new File(session.directory, "manifest.json.tmp");
            writeFile(temporary,
                    session.manifest.toString(2).getBytes(StandardCharsets.UTF_8));
            if (session.manifestFile.exists() && !session.manifestFile.delete()) {
                throw new IOException("Unable to replace manifest");
            }
            if (!temporary.renameTo(session.manifestFile)) {
                throw new IOException("Unable to finalize manifest");
            }
        } catch (Exception error) {
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Manifest write failed", error);
        }
    }

    private static void writeFile(File file, byte[] bytes) throws IOException {
        try {
            NavigationLogStorage.withReadLock(() -> {
                try (FileOutputStream output = new FileOutputStream(file, false)) {
                    output.write(bytes);
                    output.flush();
                } catch (IOException error) {
                    throw new FileWriteException(error);
                }
            });
        } catch (FileWriteException error) {
            throw (IOException) error.getCause();
        }
    }

    private static String readBounded(File file, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[32 * 1024];
        try (FileInputStream input = new FileInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) >= 0 && output.size() < maxBytes) {
                if (read <= 0) continue;
                output.write(buffer, 0, Math.min(read, maxBytes - output.size()));
            }
        }
        return output.toString("UTF-8");
    }

    private static boolean isCurrent(Session session) {
        return activeSession == session;
    }

    private static void updateDetail(Session session, String detail) {
        synchronized (LogcatRecorder.class) {
            if (activeSession == session) lastDetail = detail;
        }
    }

    private static void fail(Session session, String detail) {
        synchronized (LogcatRecorder.class) {
            if (session.finalized) return;
            session.finalized = true;
        }
        Log.e(TAG, detail);
        try {
            finalizeSegment(session);
            session.manifest.put("status", "failed");
            session.manifest.put("failure", detail);
            writeManifest(session);
        } catch (Exception ignored) {
            Log.e(TAG, "Unable to finalize failed capture", ignored);
        }
        List<Runnable> completions;
        synchronized (LogcatRecorder.class) {
            if (activeSession == session) activeSession = null;
            if (finalizingSession == session) finalizingSession = null;
            activeStartDay = "";
            lastSavedFile = session.manifestFile;
            lastStatus = STATUS_WAITING;
            lastDetail = detail;
            session.failed = true;
            session.failureDetail = detail;
            completions = takeCompletionsLocked(session);
        }
        publishUiState();
        postCompletions(completions);
        AppEventLogger.event(session.context,
                "system_recorder_failed id=" + session.captureId + " error=" + detail);
    }

    private static Throwable await(Future<?> future, long timeoutMs) {
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return null;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return error;
        } catch (ExecutionException error) {
            return error.getCause() == null ? error : error.getCause();
        } catch (TimeoutException error) {
            return error;
        }
    }

    private static String fullLogcatCommand(long cursorMs) {
        return "logcat -b all -v threadtime -T '" + cursor(cursorMs) + "' -d";
    }

    private static String cursor(long millis) {
        synchronized (LOGCAT_CURSOR_FORMAT) {
            return LOGCAT_CURSOR_FORMAT.format(new Date(millis));
        }
    }

    private static String timestampForFile() {
        synchronized (FILE_FORMAT) {
            return FILE_FORMAT.format(new Date());
        }
    }

    private static String timestampForLine(long millis) {
        synchronized (LINE_FORMAT) {
            LINE_FORMAT.setTimeZone(TimeZone.getDefault());
            return LINE_FORMAT.format(new Date(millis));
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    static final class Result {
        final boolean ok;
        final boolean recording;
        final File file;
        final String detail;

        private Result(boolean ok, boolean recording, File file, String detail) {
            this.ok = ok;
            this.recording = recording;
            this.file = file;
            this.detail = detail == null ? "" : detail;
        }

        static Result recording(File file, String detail) {
            return new Result(true, true, file, detail);
        }

        static Result saved(File file, String detail) {
            return new Result(true, false, file, detail);
        }

        static Result failed(File file, String detail) {
            return new Result(false, false, file, detail);
        }

        static Result pending(File file, String detail) {
            return new Result(false, false, file, detail);
        }
    }

    private static final class Session {
        final Context context;
        final String day;
        final String captureId;
        final File directory;
        final File manifestFile;
        final JSONObject manifest = new JSONObject();
        final JSONArray phases = new JSONArray();
        final List<String> segmentNames = new ArrayList<>();
        volatile Phase activePhase;
        volatile ScheduledFuture<?> pollFuture;
        volatile ScheduledFuture<?> phaseFuture;
        volatile Future<?> finishFuture;
        volatile boolean stopRequested;
        volatile boolean finalized;
        volatile boolean failed;
        String failureDetail = "";
        final List<Runnable> completions = new ArrayList<>();
        Set<String> previousPollLines = new LinkedHashSet<>();
        FileOutputStream segmentOut;
        File segmentPart;
        File segmentFinal;
        int segmentIndex;
        long segmentBytes;
        long totalBytes;
        long droppedBytes;
        boolean truncated;
        long startedWallMs;
        long startedElapsedMs;
        long lastPollWallMs;
        int pollCount;
        int pollErrors;
        String lastPollError = "";
        String mode = "initializing";
        String fallbackReason = "";

        Session(Context context, String day, String captureId, File directory) {
            this.context = context;
            this.day = day;
            this.captureId = captureId;
            this.directory = directory;
            this.manifestFile = new File(directory, "manifest.json");
        }
    }

    private static final class Phase {
        final String id;
        final long durationMs;
        long startedWallMs;
        long startedElapsedMs;
        long endedWallMs;
        long endedElapsedMs;

        Phase(String id, long durationMs) {
            this.id = id;
            this.durationMs = durationMs;
        }
    }

    private static final class FileWriteException extends RuntimeException {
        FileWriteException(IOException cause) {
            super(cause);
        }
    }
}
