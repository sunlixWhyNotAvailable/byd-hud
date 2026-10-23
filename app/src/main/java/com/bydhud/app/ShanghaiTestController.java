package com.bydhud.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** One serialized, bounded session; Activity/Compose only request actions and render snapshots. */
final class ShanghaiTestController {
    private static final long START_TIMEOUT_MS = 45_000L;
    private static final long CAPTURE_TAIL_MS = 5_000L;
    private static volatile ShanghaiTestState cached = new ShanghaiTestState();
    private static ShanghaiTestController instance;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "BydHudShanghai");
        thread.setDaemon(true);
        return thread;
    });
    private ShanghaiMockGps gps;
    private volatile Run current;
    private boolean recoveryQueued;
    private final List<Runnable> stopCompletions = new ArrayList<>();
    private final List<Runnable> recoveryCompletions = new ArrayList<>();

    private ShanghaiTestController(Context context) { this.context = context.getApplicationContext(); }

    static synchronized ShanghaiTestController get(Context context) {
        if (instance == null) instance = new ShanghaiTestController(context);
        return instance;
    }

    static ShanghaiTestState snapshot() { return cached; }

    static boolean protectsStorageDay(String day) {
        return cached.protectsDay(day) || ShanghaiDiagnostics.isDayWriting(day);
    }

    private ShanghaiMockGps gps() {
        if (gps == null) gps = new ShanghaiMockGps(context);
        return gps;
    }

    synchronized void start() {
        if (current != null || recoveryQueued || cached.isBusy()) return;
        if (ShanghaiDiagnostics.hasUnfinishedCapture()) {
            cached = new ShanghaiTestState(ShanghaiTestState.Phase.ERROR, 0, "",
                    localized("Попередній запис ще завершується. Повторіть запуск пізніше.",
                            "The previous capture is still finishing. Retry later.",
                            "Предыдущая запись ещё завершается. Повторите запуск позже."),
                    ShanghaiTestState.CaptureStatus.FAILED, false, "", "");
            notifyUi();
            return;
        }
        Run run = new Run(false);
        current = run;
        publish(run, ShanghaiTestState.Phase.STARTING, "");
        launch(run);
    }

    synchronized void resetAnyMockGps() {
        if (current != null) {
            if (current.finishing) return;
            current.resetAny = true;
            stop("manual-reset", null);
            return;
        }
        if (recoveryQueued) return;
        Run run = new Run(true);
        current = run;
        publish(run, ShanghaiTestState.Phase.RECOVERING, "");
        launch(run);
    }

    private void launch(Run run) {
        try {
            ShanghaiTestService.launch(context, run.id, run.resetOnly);
            worker.schedule(() -> {
                if (current == run && !run.serviceReady) fail(run, "SERVICE_START", "Foreground service did not start");
            }, 10, TimeUnit.SECONDS);
        } catch (RuntimeException error) {
            worker.execute(() -> fail(run, describe(error)));
        }
    }

    void onServiceReady(String id, boolean reset) {
        worker.execute(() -> {
            Run run = current;
            if (run == null) {
                synchronized (ShanghaiTestController.this) {
                    if (current == null) ShanghaiTestService.finish(context);
                }
                return;
            }
            if (!run.id.equals(id) || run.resetOnly != reset || run.serviceReady) return;
            run.serviceReady = true;
            if (run.cancelled) { finish(run); return; }
            if (run.resetOnly) {
                try {
                    run.job = ShellWorkClient.prepare(context, "shanghai", run.id,
                            new JSONObject().put("action", "reset"));
                    ShellWorkClient.launch(context, run.job);
                    run.jobObservedMs = SystemClock.elapsedRealtime();
                } catch (Exception error) { run.failure = describe(error); }
                finish(run); return;
            }
            if (run.job != null) { attachShellRun(run); return; }
            prepare(run);
        });
    }

    synchronized void onServiceDestroyed(String sessionId) {
        // The route is owned by shell. Destruction of its foreground observer is not Stop.
    }

    synchronized void stop(String reason, Runnable completion) {
        if (completion != null) stopCompletions.add(completion);
        Run run = current;
        if (run == null) {
            worker.execute(this::completeStopCallbacks);
            return;
        }
        run.cancelled = true;
        if (completion != null) run.restoreOutput = false;
        run.stopReason = reason == null ? "stop" : reason;
        worker.execute(() -> finish(run));
    }

    synchronized void recoverOwned(String reason) { recoverOwned(reason, null); }

    synchronized void recoverOwned(String reason, Runnable idleCompletion) {
        if (idleCompletion != null) recoveryCompletions.add(idleCompletion);
        if (current != null || recoveryQueued) return;
        String saved = ShellRuntimeSession.prefs(context).getString("shanghai_work", "");
        if (!saved.isEmpty() && ShellRuntimeSession.mayRestore(context)) {
            try {
                JSONObject value = new JSONObject(saved);
                Run run = new Run(value.optBoolean("reset"), value.getString("id"));
                String jobPath = value.optString("job");
                if (!jobPath.isEmpty()) {
                    run.job = ShellWorkFiles.inside(ShellWorkFiles.root(context), jobPath);
                    JSONObject expected = ShellWorkClient.expected(context, run.job);
                    if (!"shanghai".equals(expected.optString("type"))
                            || !run.id.equals(expected.optString("id")))
                        throw new IOException("Saved Shanghai operation identity changed");
                    JSONObject state = ShellWorkClient.state(context, run.job);
                    if (ShellWorkClient.terminal(state.optString("state"))
                            && (!"SUCCEEDED".equals(state.optString("state"))
                            || state.optJSONObject("result") == null
                            || state.getJSONObject("result").optBoolean("cleanupPending")
                            || !state.getJSONObject("result").optBoolean("ok", true)))
                        throw new IOException("Previous shell cleanup is pending");
                }
                run.recovered = true;
                run.directory = new File(value.getString("directory"));
                run.day = value.getString("day"); run.startedWallMs = value.optLong("started");
                run.originalLogcatOwned = value.optBoolean("logcatOwned");
                run.outputSuspended = run.job != null; run.instrumentSuspendAttempted = run.job != null;
                current = run;
                if (run.job != null) ShanghaiOutputGate.suspend();
                publish(run, ShanghaiTestState.Phase.RECOVERING, "");
                if (run.job != null && ShellWorkClient.terminal(
                        ShellWorkClient.state(context, run.job).optString("state"))) {
                    worker.execute(() -> finish(run));
                } else launch(run);
                return;
            } catch (Exception error) { AppEventLogger.event(context, "shanghai_reattach_failed " + describe(error)); }
        }
        if (saved.isEmpty() && !gps().hasPendingRecovery()) {
            cached = new ShanghaiTestState();
            main.post(this::completeRecoveryCallbacks);
            return;
        }
        recoveryQueued = true;
        worker.execute(() -> {
            try {
                if (!cancelSavedShellRun(saved)) {
                    cached = new ShanghaiTestState(ShanghaiTestState.Phase.ERROR, 0, "",
                            localized("Попередня shell-операція ще завершується.",
                                    "The previous shell operation is still finishing.",
                                    "Предыдущая shell-операция ещё завершается."),
                            ShanghaiTestState.CaptureStatus.FAILED, true, "", "");
                    return;
                }
                if (!saved.isEmpty()) {
                    File cleanup = ShellWorkClient.prepare(context, "shanghai",
                            "cleanup-" + UUID.randomUUID(), new JSONObject().put("action", "cleanup"));
                    JSONObject result = ShellWorkClient.await(context, cleanup, null);
                    if (result.optBoolean("cleanupPending")) {
                        cached = new ShanghaiTestState(ShanghaiTestState.Phase.ERROR, 0, "",
                                localized("Очищення GPS не завершене. Повторіть скидання.",
                                        "GPS cleanup is incomplete. Retry reset.",
                                        "Очистка GPS не завершена. Повторите сброс."),
                                ShanghaiTestState.CaptureStatus.NOT_STARTED, true, "", "");
                        AppEventLogger.event(context, "shell_gps_cleanup_pending "
                                + result.optString("cleanup"));
                        return;
                    }
                    ShellRuntimeSession.prefs(context).edit().remove("shanghai_work").commit();
                }
                if (!gps().hasPendingRecovery()) return;
                cached = new ShanghaiTestState(ShanghaiTestState.Phase.RECOVERING, 0,
                        gps().pendingSessionId(), "", ShanghaiTestState.CaptureStatus.NOT_STARTED, true, "", "");
                notifyUi();
                ShanghaiMockGps.Result result = gps().recoverOwned();
                boolean pending = gps().hasPendingRecovery();
                cached = new ShanghaiTestState(pending ? ShanghaiTestState.Phase.ERROR : ShanghaiTestState.Phase.IDLE,
                        0, "", pending ? localized("Не вдалося завершити очищення GPS. Повторіть скидання.",
                        "GPS cleanup is still pending. Retry reset.", "Очистка GPS не завершена. Повторите сброс.") : "",
                        ShanghaiTestState.CaptureStatus.NOT_STARTED, pending, "", "");
                AppEventLogger.event(context, "shanghai_recovery reason=" + reason + " result=" + result.code
                        + " detail=" + result.detail);
            } catch (Exception error) {
                cached = new ShanghaiTestState(ShanghaiTestState.Phase.ERROR, 0, "",
                        localized("Не вдалося перевірити стан GPS.", "Could not check GPS state.",
                                "Не удалось проверить состояние GPS."),
                        ShanghaiTestState.CaptureStatus.FAILED, true, "", "");
                AppEventLogger.event(context, "shanghai_recovery_error " + describe(error));
            } finally {
                if (!gps().hasPendingRecovery()) {
                    try { InstrumentProxyManager.get(context).resumeAfterShanghai(); }
                    catch (Exception error) {
                        AppEventLogger.event(context, "shanghai_recovery output_resume_failed " + describe(error));
                    }
                }
                synchronized (ShanghaiTestController.this) { recoveryQueued = false; }
                notifyUi();
                completeRecoveryCallbacks();
            }
        });
    }

    private boolean cancelSavedShellRun(String saved) {
        if (saved == null || saved.isEmpty()) return true;
        try {
            JSONObject value = new JSONObject(saved);
            String path = value.optString("job", "");
            if (path.isEmpty()) return true;
            File job = ShellWorkFiles.inside(ShellWorkFiles.root(context), path);
            JSONObject expected;
            try { expected = ShellWorkClient.expected(context, job); }
            catch (IOException untrustedLegacyJob) {
                AppEventLogger.event(context, "shanghai_legacy_job_not_replayed id="
                        + value.optString("id"));
                return true;
            }
            if (!"shanghai".equals(expected.optString("type"))
                    || !value.optString("id").equals(expected.optString("id"))) return false;
            JSONObject state = ShellWorkClient.state(context, job);
            if (ShellWorkClient.terminal(state.optString("state"))) return true;
            ShellWorkClient.cancel(context, job);
            long deadline = SystemClock.elapsedRealtime() + 10_000L;
            while (SystemClock.elapsedRealtime() < deadline) {
                if (ShellWorkClient.terminal(
                        ShellWorkClient.state(context, job).optString("state"))) return true;
                Thread.sleep(50L);
            }
            return false;
        } catch (Exception error) {
            AppEventLogger.event(context, "shanghai_saved_shell_recovery_unavailable " + describe(error));
            return false;
        }
    }

    private void prepare(Run run) {
        try {
            if (gps().hasPendingRecovery()) {
                ShanghaiMockGps.Result recovery = gps().recoverOwned();
                if (gps().hasPendingRecovery()) throw new IOException(recovery.detail);
            }
            if (run.cancelled) { finish(run); return; }
            ShellRuntimeSession.arm(context);
            InstrumentProxyManager.get(context).ensureStarted("shanghai-setup");
            run.route = ShanghaiRoute.load(context);
            if (run.day.isEmpty()) run.day = NavCaptureStore.todayDir();
            NavigationLogStorage.withReadLock(() -> {
                publish(run, ShanghaiTestState.Phase.STARTING, "");
                run.directory = new File(NavigationLogStorage.logcatDir(context, run.day), "shanghai_" + run.id);
                if (!run.directory.isDirectory() && !run.directory.mkdirs()) {
                    throw new IllegalStateException("Could not create Shanghai evidence directory");
                }
            });
            run.logcat = run.recovered ? LogcatRecorder.recoverForShanghai(context, run.originalLogcatOwned)
                    : LogcatRecorder.acquireForShanghai(context);
            File segment = run.recovered ? new File(run.directory, "reattach-" + System.currentTimeMillis()) : run.directory;
            run.diagnostics = new ShanghaiDiagnostics(context, segment, run.day);
            if (run.startedWallMs == 0) run.startedWallMs = System.currentTimeMillis();
            checkpoint(run);
            run.startDeadlineMs = SystemClock.elapsedRealtime() + START_TIMEOUT_MS;
            if (!run.diagnostics.start(run.id)) throw new IOException("Diagnostic capture did not start");
            record(run, "session_start", "logcat=" + run.logcat.captureId() + " owned=" + run.logcat.startedByShanghai);
            publish(run, ShanghaiTestState.Phase.STARTING, "");
            awaitCapture(run);
        } catch (Exception error) { fail(run, describe(error)); }
    }

    private void awaitCapture(Run run) {
        if (current != run || run.finishing) return;
        if (run.cancelled) { finish(run); return; }
        if (!run.logcat.isHealthy() || !run.diagnostics.isHealthy()) {
            fail(run, "RECORDING_FAILED", "Diagnostic recording failed during setup");
            return;
        }
        if (!run.logcat.isReady() || !run.diagnostics.isReady()) {
            if (SystemClock.elapsedRealtime() >= run.startDeadlineMs) {
                fail(run, "RECORDING_TIMEOUT", "Diagnostic recording readiness timed out");
            } else worker.schedule(() -> awaitCapture(run), 100, TimeUnit.MILLISECONDS);
            return;
        }
        run.captureReady = true;
        try {
            ShanghaiOutputGate.suspend();
            run.outputSuspended = true;
            if (run.cancelled) { finish(run); return; }
            run.instrumentSuspendAttempted = true;
            InstrumentProxyManager.get(context).suspendForShanghai();
            if (run.cancelled) { finish(run); return; }
            run.job = ShellWorkClient.prepare(context, "shanghai", run.id, new JSONObject()
                    .put("id", run.id).put("day", run.day).put("evidence", run.directory.getPath()));
            checkpoint(run);
            ShellWorkClient.launch(context, run.job);
            run.jobObservedMs = SystemClock.elapsedRealtime();
            publish(run, ShanghaiTestState.Phase.PREPARING, "");
            worker.schedule(() -> tick(run), 100, TimeUnit.MILLISECONDS);
        } catch (Exception error) { fail(run, describe(error)); }
    }

    private void attachShellRun(Run run) {
        try {
            run.logcat = LogcatRecorder.recoverForShanghai(context, run.originalLogcatOwned);
            // Android-owned SOME/IP binding is re-established; old journal files are never overwritten.
            File segment = new File(run.directory, "reattach-" + System.currentTimeMillis());
            run.diagnostics = new ShanghaiDiagnostics(context, segment, run.day);
            run.diagnostics.start(run.id);
            run.captureReady = true;
            ShellWorkClient.launch(context, run.job);
            run.jobObservedMs = SystemClock.elapsedRealtime();
            tick(run);
        } catch (Exception error) { fail(run, describe(error)); }
    }

    private void checkpoint(Run run) throws Exception {
        ShellRuntimeSession.prefs(context).edit().putString("shanghai_work", new JSONObject()
                .put("id", run.id).put("reset", run.resetOnly).put("job", run.job == null ? "" : run.job.getPath())
                .put("directory", run.directory == null ? "" : run.directory.getPath())
                .put("day", run.day).put("started", run.startedWallMs)
                .put("logcatOwned", run.logcat != null && run.logcat.startedByShanghai).toString()).commit();
    }

    private void reconcileWorker(Run run) throws IOException {
        if (SystemClock.elapsedRealtime() - run.jobObservedMs > 15_000) {
            JSONObject state = ShellWorkClient.state(context, run.job);
            if ("REGISTERED".equals(state.optString("state"))) ShellWorkClient.launch(context, run.job);
            run.jobObservedMs = SystemClock.elapsedRealtime();
        }
    }

    private void tick(Run run) {
        if (current != run || run.finishing) return;
        try {
            if (run.cancelled) { finish(run); return; }
            JSONObject status = ShellWorkClient.state(context, run.job);
            JSONObject progress = status.optJSONObject("progress");
            if (progress != null) {
                run.elapsed = progress.optInt("elapsed", run.elapsed);
                run.originElapsedMs = progress.optLong("origin", run.originElapsedMs);
            }
            if (ShellWorkClient.terminal(status.optString("state"))) { finish(run); return; }
            reconcileWorker(run);
            if (run.logcat != null && !run.logcat.isHealthy()) { fail(run, "LOGCAT_STOPPED", "Required Logcat stopped"); return; }
            publish(run, run.elapsed < ShanghaiTestState.PREPARATION_SECONDS
                    ? ShanghaiTestState.Phase.PREPARING : ShanghaiTestState.Phase.DRIVING, "");
            worker.schedule(() -> tick(run), 200, TimeUnit.MILLISECONDS);
        } catch (Exception error) { fail(run, describe(error)); }
    }

    private void fail(Run run, String error) {
        fail(run, "", error);
    }

    private void fail(Run run, String code, String error) {
        if (current != run || run.finishing) return;
        run.failure = error;
        run.failureCode = code;
        record(run, "failure", error);
        finish(run);
    }

    private void finish(Run run) {
        if (current != run || run.finishing) return;
        if (run.job != null) {
            try {
                JSONObject state = ShellWorkClient.state(context, run.job);
                if (!ShellWorkClient.terminal(state.optString("state"))) {
                    reconcileWorker(run);
                    if ((run.cancelled || !run.failure.isEmpty()) && !run.cancelRequested) {
                        run.cancelRequested = ShellWorkClient.cancel(context, run.job, run.resetAny
                                && "RUNNING".equals(state.optString("state")));
                    }
                    worker.schedule(() -> finish(run), 100, TimeUnit.MILLISECONDS);
                    return;
                }
                JSONObject result = state.optJSONObject("result");
                if (result == null) result = new JSONObject();
                run.completed = result.optBoolean("completed");
                run.elapsed = result.optInt("elapsed", run.elapsed);
                run.cleanupDetail = result.optString("cleanup");
                run.cleanupFailed = result.optBoolean("cleanupPending", !result.optBoolean("ok"));
                if (run.failure.isEmpty()) {
                    run.failure = result.optString("failure", result.optString("error"));
                    run.failureCode = result.optString("failureCode");
                } else {
                    String shellFailure = result.optString("failure", result.optString("error"));
                    if (!shellFailure.isEmpty()) noteFailure(run, "Shell " + result.optString("failureCode"), shellFailure);
                }
                run.blockingPackage = result.optString("blockingPackage");
                String workerState = state.optString("state");
                if (!"SUCCEEDED".equals(workerState) && run.failure.isEmpty())
                    run.failure = result.optString("error", "Shanghai shell operation "
                            + workerState.toLowerCase(java.util.Locale.ROOT));
                if (run.resetAny && !result.optBoolean("resetAny")) {
                    ShanghaiMockGps.Result reset = gps().resetAny();
                    run.cleanupDetail += (run.cleanupDetail.isEmpty() ? "" : "; ")
                            + "reset-any fallback: " + reset.code + ": " + reset.detail;
                    if (!reset.success()) run.cleanupFailed = true;
                }
                run.finishing = true;
                publish(run, ShanghaiTestState.Phase.FINISHING, "");
                finalizeCapture(run);
                return;
            } catch (Exception error) { noteFailure(run, "Worker completion", describe(error)); run.cleanupFailed = true; }
        }
        run.finishing = true;
        publish(run, ShanghaiTestState.Phase.FINISHING, "");
        try {
            ShanghaiMockGps.Result cleanup = run.resetOnly || run.resetAny
                    ? gps().resetAny() : gps().cleanupOwned();
            run.cleanupDetail = cleanup.code + ": " + cleanup.detail;
            record(run, "mock_cleanup", run.cleanupDetail);
            if (!cleanup.success()) run.cleanupFailed = true;
        } catch (RuntimeException error) {
            run.cleanupFailed = true;
            run.cleanupDetail = describe(error);
        }
        worker.schedule(() -> finalizeCapture(run), run.diagnostics == null ? 0L : CAPTURE_TAIL_MS,
                TimeUnit.MILLISECONDS);
    }

    private void finalizeCapture(Run run) {
        if (current != run) return;
        try {
            if (run.diagnostics != null) {
                try {
                    record(run, "session_end", "completed=" + run.completed + " reason=" + run.stopReason
                            + " failureCode=" + run.failureCode + " blockingPackage=" + run.blockingPackage
                            + " error=" + run.failure);
                    run.diagnostics.stop();
                } catch (Exception error) {
                    noteFailure(run, "Diagnostics", describe(error));
                }
            }
            if (run.logcat != null) {
                try {
                    LogcatRecorder.Result result = LogcatRecorder.releaseForShanghai(run.logcat);
                    if (!result.ok) noteFailure(run, "Logcat", result.detail);
                } catch (RuntimeException error) {
                    noteFailure(run, "Logcat", describe(error));
                }
            }
        } catch (Exception error) {
            if (run.failure.isEmpty()) run.failure = describe(error);
            AppEventLogger.event(context, "shanghai_finalize_error " + describe(error));
        } finally {
            if (run.instrumentSuspendAttempted) {
                try { InstrumentProxyManager.get(context).resumeAfterShanghai(); }
                catch (Exception error) {
                    noteFailure(run, "Instrument restore", describe(error));
                    record(run, "instrument_restore_error", describe(error));
                }
            }
            if (run.outputSuspended) {
                ShanghaiOutputGate.resume();
                try {
                    if (run.restoreOutput && !HudPrefs.isUserShutdownActive(context)) {
                        NavHudLiveSender.get(context).resumeAfterShanghai();
                    }
                } catch (RuntimeException error) {
                    noteFailure(run, "Output restore", describe(error));
                    record(run, "output_restore_error", describe(error));
                }
            }
            try { writeSummary(run); }
            catch (Exception error) {
                if (run.failure.isEmpty()) run.failure = "Summary: " + describe(error);
                record(run, "summary_error", describe(error));
            }
            boolean pending = run.cleanupFailed || gps().hasPendingRecovery();
            ShanghaiTestState.Phase phase = pending || !run.failure.isEmpty() ? ShanghaiTestState.Phase.ERROR
                    : run.completed ? ShanghaiTestState.Phase.COMPLETED : ShanghaiTestState.Phase.STOPPED;
            String detail = !run.failure.isEmpty() ? ShanghaiTestState.failureDetail(
                    HudPrefs.uiLanguage(context), run.failureCode, blockingApp(run.blockingPackage), run.failure) : "";
            if (pending) detail += (detail.isEmpty() ? "" : "\n")
                    + localized("Очищення GPS не завершене. Повторіть скидання.",
                    "GPS cleanup is incomplete. Retry reset.", "Очистка GPS не завершена. Повторите сброс.")
                    + (run.cleanupDetail.isEmpty() ? "" : "\n" + run.cleanupDetail);
            else if (detail.isEmpty() && run.resetOnly)
                detail = localized("Підміну GPS скинуто.", "GPS mock reset.", "Подмена GPS сброшена.");
            cached = new ShanghaiTestState(phase, run.elapsed, run.id, detail,
                    captureStatus(run, phase), pending, run.day, run.logcat == null ? "" : run.logcat.day());
            synchronized (this) {
                try { ShanghaiTestService.finish(context); }
                catch (RuntimeException error) { record(run, "service_stop_error", describe(error)); }
                current = null;
                if (!run.cleanupFailed) ShellRuntimeSession.prefs(context).edit().remove("shanghai_work").commit();
            }
            notifyUi();
            MainActivity.requestStorageRefreshAfterMutation(context, "shanghai-finished");
            completeStopCallbacks();
            completeRecoveryCallbacks();
        }
    }

    private void writeSummary(Run run) throws Exception {
        if (run.directory == null) return;
        JSONObject summary = new JSONObject().put("sessionId", run.id)
                .put("route", "shanghai_east_city_drive").put("startedWallMs", run.startedWallMs)
                .put("endedWallMs", System.currentTimeMillis()).put("elapsedSeconds", run.elapsed)
                .put("routeCompleted", run.completed).put("stopReason", run.stopReason)
                .put("error", run.failure).put("cleanup", run.cleanupDetail)
                .put("failureCode", run.failureCode).put("blockingPackage", run.blockingPackage)
                .put("secondaryFailures", run.secondaryFailures)
                .put("cleanupPending", run.cleanupFailed || gps().hasPendingRecovery())
                .put("physicalHudVerified", false).put("postCleanupCaptureMs", CAPTURE_TAIL_MS)
                .put("diagnostics", run.diagnostics == null ? "not_started" : run.diagnostics.coverage().toString());
        if (run.logcat != null) summary.put("logcat", new JSONObject()
                .put("captureId", run.logcat.captureId()).put("manifest", run.logcat.manifestFile().getAbsolutePath())
                .put("startedByShanghai", run.logcat.startedByShanghai).put("mode", run.logcat.mode())
                .put("fromWallMs", run.startedWallMs).put("toWallMs", System.currentTimeMillis()));
        try (FileOutputStream out = new FileOutputStream(new File(run.directory, "shanghai-session.json"))) {
            out.write(summary.toString(2).getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
    }

    private void record(Run run, String event, String detail) {
        // An observability failure must not prevent GPS or recorder cleanup.
        try {
            if (run.diagnostics != null) run.diagnostics.record(event, detail);
        } catch (RuntimeException ignored) { }
        try {
            AppEventLogger.event(context, "shanghai_" + event + " session=" + run.id + " " + detail);
        } catch (RuntimeException ignored) { }
    }

    private void noteFailure(Run run, String stage, String detail) {
        String failure = stage + ": " + detail;
        if (run.failure.isEmpty()) run.failure = failure;
        else run.secondaryFailures += (run.secondaryFailures.isEmpty() ? "" : "\n") + failure;
        record(run, "finalize_failure", failure);
    }

    private String blockingApp(String packageName) {
        if (packageName.isEmpty()) return "";
        try {
            android.content.pm.PackageManager packages = context.getPackageManager();
            String label = String.valueOf(packages.getApplicationLabel(packages.getApplicationInfo(packageName, 0)));
            if (!label.isEmpty() && !label.equals(packageName)) return label + " (" + packageName + ")";
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException error) {
            AppEventLogger.event(context, "shanghai_blocking_app_label package=" + packageName + " error=" + describe(error));
        }
        return packageName;
    }

    private void publish(Run run, ShanghaiTestState.Phase phase, String detail) {
        cached = new ShanghaiTestState(phase, run.elapsed, run.id, detail, captureStatus(run, phase), false,
                run.day, run.logcat == null ? "" : run.logcat.day());
        notifyUi();
    }

    private ShanghaiTestState.CaptureStatus captureStatus(Run run, ShanghaiTestState.Phase phase) {
        if (run.resetOnly) return ShanghaiTestState.CaptureStatus.NOT_STARTED;
        if (!run.failure.isEmpty() || phase == ShanghaiTestState.Phase.ERROR) {
            return ShanghaiTestState.CaptureStatus.FAILED;
        }
        if (run.logcat == null || run.diagnostics == null) {
            return ShanghaiTestState.CaptureStatus.STARTING;
        }
        ShanghaiDiagnostics.Coverage coverage = run.diagnostics.coverage();
        if ("failed".equals(coverage.state) || !coverage.failure.isEmpty() || !coverage.writersHealthy) {
            return ShanghaiTestState.CaptureStatus.FAILED;
        }
        boolean finalized = phase == ShanghaiTestState.Phase.COMPLETED
                || phase == ShanghaiTestState.Phase.STOPPED;
        if (!finalized) {
            if (!run.logcat.isHealthy() || !run.diagnostics.isHealthy()) {
                return ShanghaiTestState.CaptureStatus.FAILED;
            }
            if (!run.logcat.isReady() || !coverage.ready) return ShanghaiTestState.CaptureStatus.STARTING;
        } else if (!run.captureReady) {
            return ShanghaiTestState.CaptureStatus.PARTIAL;
        }
        if (run.logcat.reducedCoverage() || coverage.hasPartialCoverage()) {
            return ShanghaiTestState.CaptureStatus.PARTIAL;
        }
        return ShanghaiTestState.CaptureStatus.READY;
    }

    private String localized(String uk, String en, String ru) {
        String language = HudPrefs.uiLanguage(context);
        return "uk".equals(language) ? uk : "ru".equals(language) ? ru : en;
    }

    private void notifyUi() { MainActivity.publishSharedUiStateChange(); }

    private void completeStopCallbacks() {
        List<Runnable> callbacks;
        synchronized (this) {
            callbacks = new ArrayList<>(stopCompletions);
            stopCompletions.clear();
        }
        for (Runnable completion : callbacks) main.post(completion);
    }

    private void completeRecoveryCallbacks() {
        List<Runnable> callbacks;
        synchronized (this) {
            if (current != null || recoveryQueued || recoveryCompletions.isEmpty()) return;
            callbacks = new ArrayList<>(recoveryCompletions);
            recoveryCompletions.clear();
        }
        for (Runnable completion : callbacks) main.post(completion);
    }

    private static String describe(Throwable error) {
        return android.util.Log.getStackTraceString(error);
    }

    private static final class Run {
        final String id;
        File job;
        boolean originalLogcatOwned;
        boolean recovered;
        long jobObservedMs;
        final boolean resetOnly;
        volatile boolean cancelled;
        volatile boolean resetAny;
        volatile boolean finishing;
        boolean serviceReady;
        boolean outputSuspended;
        boolean instrumentSuspendAttempted;
        volatile boolean restoreOutput = true;
        boolean completed;
        boolean captureReady;
        boolean cleanupFailed;
        boolean cancelRequested;
        long originElapsedMs;
        long startDeadlineMs;
        long startedWallMs;
        int elapsed;
        String day = "";
        String failure = "";
        String failureCode = "";
        String blockingPackage = "";
        String secondaryFailures = "";
        String cleanupDetail = "";
        String stopReason = "";
        File directory;
        ShanghaiRoute route;
        LogcatRecorder.CaptureLease logcat;
        ShanghaiDiagnostics diagnostics;
        Run(boolean resetOnly) { this(resetOnly, UUID.randomUUID().toString()); }
        Run(boolean resetOnly, String id) { this.resetOnly = resetOnly; this.id = id; }
    }
}
