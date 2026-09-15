package com.bydhud.app;

// Captures full-system diagnostics through one bounded-memory continuous Logcat stream.

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

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class LogcatRecorder {
    static final String STATUS_WAITING = "Очікування запису";
    static final String STATUS_RECORDING = "Йде запис логу";
    static final String STATUS_SAVING = "Збереження логу";
    static final String STATUS_SAVED = "Лог збережено";

    private static final String TAG = "BydHudLogcat";
    private static final int STREAM_CHUNK_BYTES = 32 * 1024;
    private static final long STREAM_STOP_TIMEOUT_MS = 10_000L;
    private static final SimpleDateFormat FILE_FORMAT =
            new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US);
    private static final SimpleDateFormat LINE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US);
    private static final SimpleDateFormat LOGCAT_CURSOR_FORMAT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private static final ExecutorService WORKER =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "BydHudSystemRecorder");
                thread.setDaemon(true);
                return thread;
            });
    private static final ExecutorService STREAM_READER =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "BydHudLogcatStream");
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
            session.streamControl.stop();
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
            session.streamControl.stop();
            addCompletionLocked(session, completion);
            ensureFinishLocked(session);
        }
    }

    static String fullLogcatCommandForTest(long cursorMs) {
        return "logcat -b all -v threadtime -T '" + cursor(cursorMs) + "'";
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
        CaptureSource source = null;
        try {
            if (!session.directory.exists() && !session.directory.mkdirs()) {
                throw new IOException("Unable to create " + session.directory);
            }
            session.startedWallMs = System.currentTimeMillis();
            session.startedElapsedMs = SystemClock.elapsedRealtime();
            session.manifest.put("captureId", session.captureId);
            session.manifest.put("status", "recording");
            session.manifest.put("startedAt", timestampForLine(session.startedWallMs));
            session.manifest.put("startedElapsedMs", session.startedElapsedMs);
            session.manifest.put("package", session.context.getPackageName());
            session.manifest.put("versionName", BuildConfig.VERSION_NAME);
            session.manifest.put("versionCode", BuildConfig.VERSION_CODE);
            session.manifest.put("bufferClear", false);
            session.manifest.put("intakeMethod", "continuous_stream");
            session.manifest.put("logcatCommand",
                    "logcat -b all -v threadtime -T <start-cursor>");
            session.manifest.put("fallbackCommand",
                    "logcat -v threadtime -T <cursor> (continuous app-visible buffers)");
            session.manifest.put("app", appIdentity(session.context));
            session.manifest.put("runtime", runtimeIdentity(session.context));
            try {
                source = new AdbLogcatSource(LocalAdbBridge.openLogcatStream(
                        session.context, cursor(session.startedWallMs - 1_000L)));
                session.mode = "full_system_adb";
            } catch (Exception error) {
                session.mode = "app_uid_fallback";
                session.fallbackReason = "ADB unavailable: " + errorDetail(error);
                session.reducedCoverage = true;
                source = null;
            }
            session.manifest.put("mode", session.mode);
            session.manifest.put("buffers",
                    "full_system_adb".equals(session.mode) ? "all" : "app-visible");
            if (source != null && !session.streamControl.install(source)) {
                throw new IOException("capture stopped before Logcat stream start");
            }
            writeLog(session, "=== BYD HUD system capture " + session.captureId + " ===\n"
                    + "mode=" + session.mode + " bufferClear=false\n");
            if (source == null) {
                source = openAppUidLogcat(session, session.startedWallMs - 1_000L);
                if (!session.streamControl.install(source)) {
                    throw new IOException("capture stopped before Logcat stream start");
                }
            }
            writeManifest(session);
            CaptureSource initialSource = source;
            FutureTask<Void> reader = new FutureTask<>(
                    () -> runStream(session, initialSource), null);
            session.readerFuture = reader;
            startReaderBeforeSnapshot(STREAM_READER, reader,
                    () -> captureSnapshot(session, "before", fullSnapshotCommands(true)));
            updateDetail(session, "mode=" + session.mode + " capture=" + session.captureId);
            if (!session.stopRequested) {
                AppEventLogger.event(session.context,
                        "system_recorder_start id=" + session.captureId
                                + " mode=" + session.mode);
            }
            publishUiState();
        } catch (Exception error) {
            session.streamControl.stop();
            closeSource(source);
            throw new IllegalStateException(error);
        }
    }

    interface IoAction {
        void run() throws IOException;
    }

    static void startReaderBeforeSnapshot(
            ExecutorService executor, FutureTask<?> reader, IoAction snapshot)
            throws IOException {
        try {
            executor.execute(reader);
        } catch (RuntimeException error) {
            reader.cancel(false);
            throw error;
        }
        snapshot.run();
    }

    private static void finish(Session session) {
        try {
            synchronized (LogcatRecorder.class) {
                if (session.finalized || session.failed) return;
            }
            Throwable readerFailure = await(session.readerFuture, STREAM_STOP_TIMEOUT_MS);
            if (readerFailure != null) {
                throw new IOException(readerFailure instanceof TimeoutException
                        ? "Logcat reader did not stop after its stream was closed"
                        : "Logcat reader failed: " + safe(readerFailure.getMessage()), readerFailure);
            }
            if (session.readerFailure != null) {
                throw new IOException("Logcat reader failed: " + session.readerError,
                        session.readerFailure);
            }
            captureSnapshot(session, "after", fullSnapshotCommands(false));
            finalizeLog(session);
            long endedWallMs = System.currentTimeMillis();
            session.manifest.put("status", "saved");
            session.manifest.put("endedAt", timestampForLine(endedWallMs));
            session.manifest.put("endedElapsedMs", SystemClock.elapsedRealtime());
            session.manifest.put("durationMs", Math.max(0L,
                    SystemClock.elapsedRealtime() - session.startedElapsedMs));
            session.manifest.put("runtimeEnd", runtimeIdentity(session.context));
            session.manifest.put("streamComplete", !session.knownLoss);
            writeManifest(session);
            long savedBytes = session.logFile.bytes();
            List<Runnable> completions;
            synchronized (LogcatRecorder.class) {
                session.finalized = true;
                if (activeSession == session) activeSession = null;
                if (finalizingSession == session) finalizingSession = null;
                activeStartDay = "";
                lastSavedFile = session.manifestFile;
                lastStatus = STATUS_SAVED;
                lastDetail = "mode=" + session.mode + " bytes=" + savedBytes
                        + (session.reducedCoverage ? " reduced-coverage" : "");
                completions = takeCompletionsLocked(session);
            }
            publishUiState();
            postCompletions(completions);
            AppEventLogger.event(session.context,
                    "system_recorder_saved id=" + session.captureId
                            + " bytes=" + savedBytes
                            + " mode=" + session.mode
                            + " reducedCoverage=" + session.reducedCoverage
                            + " knownLoss=" + session.knownLoss);
        } catch (Exception error) {
            session.streamControl.stop();
            throw new IllegalStateException(error);
        }
    }

    private static void runStream(Session session, CaptureSource initialSource) {
        CaptureOutput capture = new CaptureOutput(session);
        CaptureSource source = initialSource;
        try {
            try {
                source.readTo(capture);
                if (session.stopRequested) {
                    recordPartial(session, capture.persistPartial(), "stop");
                    return;
                }
                if (!(source instanceof AdbLogcatSource)) {
                    throw new IOException("app-visible Logcat stream ended");
                }
                transitionToFallback(session, source, capture, "ADB stream ended");
            } catch (CaptureWriteException error) {
                throw error;
            } catch (FallbackStreamException error) {
                throw error;
            } catch (IOException error) {
                if (session.stopRequested) {
                    recordPartial(session, capture.persistPartial(), "stop");
                    return;
                }
                if (!(source instanceof AdbLogcatSource)) throw error;
                transitionToFallback(session, source, capture,
                        "ADB stream failed: " + errorDetail(error));
            }
        } catch (Exception error) {
            try {
                recordPartial(session, capture.persistPartial(), "stream_failure");
            } catch (IOException partialError) {
                error.addSuppressed(partialError);
            }
            requestReaderFailure(session, error);
        } finally {
            session.streamControl.clearAndClose(source);
        }
    }

    private static void transitionToFallback(Session session, CaptureSource adbSource,
            CaptureOutput capture, String reason) throws IOException {
        session.streamControl.clearAndClose(adbSource);
        if (session.stopRequested) return;
        byte[] pendingPrefix = capture.takePartial();
        LogcatStreamBoundary.Boundary boundary = session.boundary.snapshot();
        session.mode = "app_uid_fallback";
        session.fallbackReason = reason;
        session.reducedCoverage = true;
        session.knownLoss = true;
        recordInterruption(session, reason,
                boundary.reliable ? "timestamp_occurrence_reconcile" : "reconcile_unavailable");
        CaptureSource fallback;
        try {
            fallback = openAppUidLogcat(session,
                    boundary.timestamp.isEmpty() ? System.currentTimeMillis() : -1L,
                    boundary.timestamp);
        } catch (IOException error) {
            capture.restorePartial(pendingPrefix);
            throw error;
        }
        if (!session.streamControl.install(fallback)) {
            capture.restorePartial(pendingPrefix);
            recordPartial(session, capture.persistPartial(), "stop_during_fallback_open");
            return;
        }
        LogcatStreamBoundary.Reconciler reconciler = null;
        try {
            OutputStream output = capture;
            if (boundary.reliable) {
                output = reconciler = new LogcatStreamBoundary.Reconciler(
                        boundary, capture, pendingPrefix);
            } else if (pendingPrefix.length > 0) {
                output = reconciler = new LogcatStreamBoundary.Reconciler(
                        boundary, capture, pendingPrefix);
            }
            writeManifest(session);
            AppEventLogger.event(session.context,
                    "system_recorder_fallback id=" + session.captureId
                            + " reason=" + safe(reason)
                            + " reconcile=" + (boundary.reliable ? "occurrence" : "unavailable"));
            try {
                fallback.readTo(output);
            } catch (CaptureWriteException error) {
                throw error;
            } catch (IOException error) {
                if (session.stopRequested) return;
                throw new FallbackStreamException(
                        "app-visible Logcat stream failed: " + errorDetail(error), error);
            }
            if (session.stopRequested) return;
            if (reconciler != null && reconciler.overflowed()) {
                recordInterruption(session, "fallback boundary line exceeded limit",
                        "reconcile_overflow");
            }
            throw new FallbackStreamException("app-visible Logcat stream ended", null);
        } finally {
            finishFallbackStream(session, capture, reconciler, fallback);
        }
    }

    // Finalize both fallback paths through the same tail writer and unconditional close.
    static void finishFallbackStream(Session session, CaptureOutput capture,
            LogcatStreamBoundary.Reconciler reconciler, CaptureSource fallback) throws IOException {
        try {
            LogcatStreamBoundary.Finish result = reconciler == null ? null : reconciler.finish();
            recordPartial(session, capture.persistPartial(),
                    result != null && result.pendingMismatch
                            ? "transition_prefix_mismatch" : "transition_stop");
            if (result != null) {
                if (result.pendingMismatch) {
                    recordInterruption(session,
                            "ADB partial record did not match fallback boundary",
                            "both_valid_prefixes_persisted");
                }
                if (result.discardedBytes > 0) {
                    session.knownLoss = true;
                    recordInterruption(session,
                            "discarded incomplete UTF-8 suffix bytes=" + result.discardedBytes,
                            "valid_prefix_persisted");
                }
            }
        } finally {
            session.streamControl.clearAndClose(fallback);
        }
    }

    private static CaptureSource openAppUidLogcat(Session session, long cursorMs)
            throws IOException {
        return openAppUidLogcat(session, cursorMs, "");
    }

    private static CaptureSource openAppUidLogcat(
            Session session, long cursorMs, String exactCursor) throws IOException {
        String value = exactCursor == null || exactCursor.isEmpty()
                ? cursor(Math.max(0L, cursorMs)) : exactCursor;
        Process process = new ProcessBuilder(
                "logcat", "-v", "threadtime", "-T", value)
                .redirectErrorStream(true)
                .start();
        return new ProcessLogcatSource(process);
    }

    private static void requestReaderFailure(Session session, Exception error) {
        session.readerFailure = error;
        session.readerError = errorDetail(error);
        recordInterruption(session, session.readerError, "stream_failed");
        session.streamControl.stop();
        synchronized (LogcatRecorder.class) {
            if (session.finalized || session.failed) return;
            if (activeSession == session) activeSession = null;
            finalizingSession = session;
            session.stopRequested = true;
            lastStatus = STATUS_SAVING;
            lastDetail = "Logcat stream failed; finalizing evidence";
            ensureFinishLocked(session);
        }
        publishUiState();
    }

    private static void recordInterruption(Session session, String reason, String recovery) {
        synchronized (session.interruptions) {
            JSONObject item = new JSONObject();
            try {
                item.put("at", timestampForLine(System.currentTimeMillis()));
                item.put("reason", safe(reason));
                item.put("recovery", recovery);
                item.put("knownLoss", session.knownLoss);
            } catch (Exception ignored) {
                Log.w(TAG, "interruption metadata failed", ignored);
            }
            session.interruptions.add(item);
        }
    }

    private static void recordPartial(Session session, PartialWrite result, String reason) {
        if (result == null || result.inputBytes <= 0) return;
        if (result.discardedBytes > 0) session.knownLoss = true;
        recordInterruption(session,
                "partial Logcat record bytes=" + result.inputBytes
                        + " persisted=" + result.persistedBytes
                        + " discardedUtf8Tail=" + result.discardedBytes,
                reason);
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
                        session.diagnosticDroppedBytes += result.droppedBytes;
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

    private static List<String> fullSnapshotCommands(boolean before) {
        return Arrays.asList(
                before
                        ? "dumpsys gfxinfo com.bydhud.app reset"
                        : "dumpsys gfxinfo com.bydhud.app framestats",
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
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        appendLog(session, bytes, 0, bytes.length);
    }

    private static void appendLog(Session session, byte[] bytes, int offset, int length)
            throws IOException {
        NavigationLogStorage.lockTopologyRead();
        try {
            session.logFile.append(bytes, offset, length);
        } finally {
            NavigationLogStorage.unlockTopologyRead();
        }
    }

    private static void finalizeLog(Session session) throws IOException {
        NavigationLogStorage.lockTopologyRead();
        try {
            session.logFile.finish();
        } finally {
            NavigationLogStorage.unlockTopologyRead();
        }
    }

    private static void writeManifest(Session session) throws IOException {
        try {
            session.manifest.put("mode", session.mode);
            session.manifest.put("buffers",
                    "full_system_adb".equals(session.mode) ? "all" : "app-visible");
            session.manifest.put("fallbackReason", session.fallbackReason);
            session.manifest.put("reducedCoverage", session.reducedCoverage);
            session.manifest.put("knownLoss", session.knownLoss);
            session.manifest.put("readerError",
                    session.readerError.isEmpty() ? JSONObject.NULL : session.readerError);
            JSONArray interruptions = new JSONArray();
            synchronized (session.interruptions) {
                for (JSONObject item : session.interruptions) interruptions.put(item);
            }
            session.manifest.put("interruptions", interruptions);
            JSONArray segments = new JSONArray();
            if (session.logFile.file().isFile()) {
                segments.put(session.logFile.file().getName());
            }
            session.manifest.put("segments", segments);
            session.manifest.put("bytes", session.logFile.bytes());
            session.manifest.put("diagnosticDroppedBytes", session.diagnosticDroppedBytes);
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

    private static void updateDetail(Session session, String detail) {
        synchronized (LogcatRecorder.class) {
            if (activeSession == session) lastDetail = detail;
        }
    }

    private static void fail(Session session, String detail) {
        synchronized (LogcatRecorder.class) {
            if (session.finalized) return;
            session.finalized = true;
            session.stopRequested = true;
        }
        session.streamControl.stop();
        Throwable readerStop = await(session.readerFuture, STREAM_STOP_TIMEOUT_MS);
        if (readerStop instanceof TimeoutException) {
            detail += "; Logcat reader did not stop after stream close";
        }
        Log.e(TAG, detail);
        try {
            finalizeLog(session);
        } catch (Exception error) {
            detail += "; log finalization failed: " + error.getClass().getSimpleName()
                    + ": " + safe(error.getMessage());
            Log.e(TAG, "Unable to finalize capture log", error);
        }
        try {
            session.manifest.put("status", "failed");
            session.manifest.put("failure", detail);
            session.manifest.put("streamComplete", false);
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
        if (future == null) return null;
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

    private static String errorDetail(Throwable error) {
        StringBuilder detail = new StringBuilder();
        Throwable current = error;
        for (int depth = 0; current != null && depth < 4; depth++) {
            if (depth > 0) detail.append(" <- ");
            detail.append(current.getClass().getSimpleName());
            String message = safe(current.getMessage());
            if (!message.isEmpty()) detail.append(": ").append(message);
            current = current.getCause();
        }
        return detail.toString();
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

    static final class Session {
        final Context context;
        final String day;
        final String captureId;
        final File directory;
        final File manifestFile;
        final JSONObject manifest = new JSONObject();
        final LogcatCaptureFile logFile;
        final LogcatStreamBoundary boundary = new LogcatStreamBoundary();
        final StreamControl streamControl = new StreamControl();
        final List<JSONObject> interruptions = new ArrayList<>();
        volatile Future<?> readerFuture;
        volatile Future<?> finishFuture;
        volatile boolean stopRequested;
        volatile boolean finalized;
        volatile boolean failed;
        volatile Throwable readerFailure;
        String failureDetail = "";
        final List<Runnable> completions = new ArrayList<>();
        volatile long diagnosticDroppedBytes;
        long startedWallMs;
        long startedElapsedMs;
        volatile boolean reducedCoverage;
        volatile boolean knownLoss;
        volatile String readerError = "";
        volatile String mode = "initializing";
        volatile String fallbackReason = "";

        Session(Context context, String day, String captureId, File directory) {
            this.context = context;
            this.day = day;
            this.captureId = captureId;
            this.directory = directory;
            this.manifestFile = new File(directory, "manifest.json");
            this.logFile = new LogcatCaptureFile(directory);
        }
    }

    interface CaptureSource extends Closeable {
        void readTo(OutputStream output) throws IOException;
    }

    private static final class AdbLogcatSource implements CaptureSource {
        private final LocalAdbBridge.LogcatStreamSession session;

        AdbLogcatSource(LocalAdbBridge.LogcatStreamSession session) {
            this.session = session;
        }

        @Override public void readTo(OutputStream output) throws IOException {
            session.readTo(output);
        }

        @Override public void close() {
            session.close();
        }
    }

    private static final class ProcessLogcatSource implements CaptureSource {
        private final Process process;
        private final InputStream input;

        ProcessLogcatSource(Process process) {
            this.process = process;
            input = process.getInputStream();
        }

        @Override public void readTo(OutputStream output) throws IOException {
            byte[] buffer = new byte[STREAM_CHUNK_BYTES];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) output.write(buffer, 0, read);
            }
            int exitCode;
            try {
                exitCode = process.exitValue();
            } catch (IllegalThreadStateException error) {
                throw new IOException("app-visible Logcat stream closed without process exit", error);
            }
            if (exitCode != 0) {
                throw new IOException("app-visible Logcat exited with " + exitCode);
            }
        }

        @Override public void close() {
            try {
                input.close();
            } catch (IOException ignored) {
                // Closing the process is the Stop signal; a reader-side error owns reporting.
            }
            process.destroy();
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    static final class CaptureOutput extends OutputStream {
        private static final int MAX_RECORD_BYTES = 64 * 1024;
        private final Session session;
        private final byte[] record = new byte[MAX_RECORD_BYTES + 1];
        private int recordBytes;

        CaptureOutput(Session session) {
            this.session = session;
        }

        @Override public void write(int value) throws IOException {
            appendByte((byte) value);
        }

        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            int end = offset + length;
            for (int index = offset; index < end; index++) {
                appendByte(bytes[index]);
            }
        }

        private void appendByte(byte value) throws IOException {
            if (recordBytes >= MAX_RECORD_BYTES) {
                throw new CaptureWriteException("Logcat record exceeds bounded intake", null);
            }
            record[recordBytes++] = value;
            if (value != '\n') return;
            try {
                appendLog(session, record, 0, recordBytes);
                session.boundary.observe(record, 0, recordBytes);
                recordBytes = 0;
            } catch (IOException error) {
                throw new CaptureWriteException("capture file write failed", error);
            }
        }

        byte[] takePartial() {
            byte[] partial = Arrays.copyOf(record, recordBytes);
            recordBytes = 0;
            return partial;
        }

        void restorePartial(byte[] partial) throws IOException {
            if (partial == null || partial.length == 0) return;
            if (recordBytes != 0 || partial.length > MAX_RECORD_BYTES) {
                throw new IOException("Unable to restore bounded Logcat partial record");
            }
            System.arraycopy(partial, 0, record, 0, partial.length);
            recordBytes = partial.length;
        }

        PartialWrite persistPartial() throws IOException {
            byte[] partial = takePartial();
            int complete = LogcatStreamBoundary.completeUtf8PrefixLength(
                    partial, partial.length);
            if (complete > 0) {
                try {
                    appendLog(session, partial, 0, complete);
                } catch (IOException error) {
                    throw new CaptureWriteException("capture partial write failed", error);
                }
            }
            return new PartialWrite(partial.length, complete, partial.length - complete);
        }
    }

    private static final class PartialWrite {
        final int inputBytes;
        final int persistedBytes;
        final int discardedBytes;

        PartialWrite(int inputBytes, int persistedBytes, int discardedBytes) {
            this.inputBytes = inputBytes;
            this.persistedBytes = persistedBytes;
            this.discardedBytes = discardedBytes;
        }
    }

    static final class StreamControl {
        private CaptureSource source;
        private boolean stopped;

        synchronized boolean install(CaptureSource next) {
            if (stopped) {
                close(next);
                return false;
            }
            source = next;
            return true;
        }

        synchronized void clearAndClose(CaptureSource expected) {
            if (source == expected) source = null;
            close(expected);
        }

        synchronized void stop() {
            stopped = true;
            CaptureSource current = source;
            source = null;
            close(current);
        }

        private static void close(Closeable value) {
            if (value == null) return;
            try {
                value.close();
            } catch (IOException ignored) {
                // Reader/finalizer reports the meaningful terminal result.
            }
        }
    }

    private static void closeSource(Closeable source) {
        if (source == null) return;
        try {
            source.close();
        } catch (IOException ignored) {
            // The owning start/finalize error is reported with the capture result.
        }
    }

    private static final class CaptureWriteException extends IOException {
        CaptureWriteException(String message, IOException cause) {
            super(message, cause);
        }
    }

    private static final class FallbackStreamException extends IOException {
        FallbackStreamException(String message, IOException cause) {
            super(message, cause);
        }
    }

    private static final class FileWriteException extends RuntimeException {
        FileWriteException(IOException cause) {
            super(cause);
        }
    }
}
