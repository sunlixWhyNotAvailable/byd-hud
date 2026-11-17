package com.bydhud.app;

import android.content.Context;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

/** GPS ownership, clock and diagnostic child streams survive loss of every HUD app process. */
final class ShellShanghaiRoute {
    static JSONObject run(ShellWorkContext app, Context system, JSONObject input) throws Exception {
        File ownership = ShellWorkFiles.directory(new File(app.job.getParentFile(), "gps-owner"));
        ShellWorkContext gpsContext = new ShellWorkContext(system, system, ownership);
        try (RandomAccessFile owner = new RandomAccessFile(new File(ownership, "owner.lock"), "rw");
             FileLock lock = owner.getChannel().tryLock()) {
            if (lock == null) throw new IllegalStateException("A Shanghai GPS worker is already active");
            ShanghaiMockGps gps = new ShanghaiMockGps(gpsContext);
            String action = input.optString("action", "route");
            if (!"route".equals(action)) {
                ShanghaiMockGps.Result cleanup = "reset".equals(action) ? gps.resetAny() : gps.recoverOwned();
                return new JSONObject().put("completed", false).put("cleanup", cleanup.code + ": " + cleanup.detail)
                        .put("cleanupPending", !cleanup.success() || gps.hasPendingRecovery());
            }
            if (gps.hasPendingRecovery()) {
                ShanghaiMockGps.Result cleanup = gps.recoverOwned();
                if (!cleanup.success() || gps.hasPendingRecovery()) throw new IllegalStateException(cleanup.detail);
            }
            File evidence = new File(input.getString("evidence"));
            ShellWorkFiles.directory(evidence);
            File shellEvidence = ShellWorkFiles.directory(new File(evidence, "shell-runtime"));
            ShanghaiDiagnosticAdb diagnostics = new ShanghaiDiagnosticAdb(app, shellEvidence,
                    tokenFromSessionId(input.getString("id")));
            boolean completed = false;
            long origin = 0;
            JSONObject finalCoverage = null;
            int elapsed = 0;
            String failure = "";
            ShanghaiMockGps.Result gpsFailure = null;
            ShanghaiMockGps.Result cleanup = null;
            boolean resetAnyRequested = false;
            try (ShanghaiSessionJournal journal = new ShanghaiSessionJournal(new File(shellEvidence, "route.jsonl"))) {
                try {
                    diagnostics.start();
                    diagnostics.resolveReadiness(5_000);
                    ShellWorkEntryPoint.progress(new JSONObject().put("phase", "DIAGNOSTICS_READY")
                            .put("diagnosticCoverage", diagnostics.coverageJson()));
                    ShanghaiRoute route = ShanghaiRoute.load(app);
                    ShanghaiMockGps.Result begun = gps.begin(input.getString("id"));
                    journal.record("mock_begin", begun.code + ": " + begun.detail);
                    if (!begun.success()) {
                        gpsFailure = begun;
                        throw new IllegalStateException(begun.detail);
                    }
                    while (elapsed <= ShanghaiRoute.DURATION_SECONDS) {
                        if (Thread.currentThread().isInterrupted()) break;
                        ShanghaiRoute.Point point = route.pointAt(elapsed);
                        ShanghaiMockGps.Result injected = gps.inject(point);
                        journal.record("route_point", "second=" + elapsed + " lat=" + point.latitude
                                + " lon=" + point.longitude + " result=" + injected.code);
                        if (!injected.success()) {
                            gpsFailure = injected;
                            throw new IllegalStateException(injected.detail);
                        }
                        if (origin == 0) origin = gps.lastInjectedElapsedMs();
                        ShellWorkEntryPoint.progress(new JSONObject().put("phase", elapsed < 15 ? "PREPARING" : "DRIVING")
                                .put("elapsed", elapsed).put("origin", origin)
                                .put("diagnosticCoverage", diagnostics.coverageJson()));
                        if (elapsed == ShanghaiRoute.DURATION_SECONDS) { completed = true; break; }
                        long now = SystemClock.elapsedRealtime();
                        long next = origin + ((now - origin) / 1000 + 1) * 1000;
                        Thread.sleep(Math.max(1, next - now));
                        elapsed = (int) Math.min(ShanghaiRoute.DURATION_SECONDS, (SystemClock.elapsedRealtime() - origin) / 1000);
                    }
                } catch (InterruptedException stopped) {
                    // Explicit Stop cancels the route; ordinary application death never interrupts this thread.
                } catch (Exception error) {
                    failure = android.util.Log.getStackTraceString(error);
                    try { journal.record("failure", failure); }
                    catch (Exception journalFailure) {
                        error.addSuppressed(journalFailure);
                        failure = android.util.Log.getStackTraceString(error);
                    }
                }
                finally {
                    Thread.interrupted();
                    try {
                        File resetAny = ShellWorkEntryPoint.currentControl == null ? null
                                : new File(ShellWorkEntryPoint.currentControl, "reset-any");
                        resetAnyRequested = resetAny != null && resetAny.isFile();
                        cleanup = resetAnyRequested ? gps.resetAny() : gps.cleanupOwned();
                    } catch (Exception error) {
                        failure = appendFailure(failure, error, "gps-cleanup");
                    }
                    try {
                        journal.record("mock_cleanup", cleanup == null ? "cleanup unconfirmed"
                                : cleanup.code + ": " + cleanup.detail);
                    } catch (Exception error) {
                        failure = appendFailure(failure, error, "cleanup-journal");
                    }
                    try { Thread.sleep(5_000); } catch (InterruptedException ignored) { Thread.interrupted(); }
                    boolean diagnosticsClosed = false;
                    try { diagnostics.close(); diagnosticsClosed = true; }
                    catch (Exception error) {
                        failure = appendFailure(failure, error, "diagnostics-close");
                    }
                    try {
                        finalCoverage = diagnostics.coverageJson().put("finalized", diagnosticsClosed);
                        ShellWorkEntryPoint.progress(new JSONObject().put("phase", "CAPTURE_STOPPED")
                                .put("elapsed", elapsed).put("origin", origin)
                                .put("diagnosticCoverage", finalCoverage));
                    } catch (Exception error) {
                        failure = appendFailure(failure, error, "finalize-coverage");
                    }
                }
            } catch (Exception error) {
                failure = appendFailure(failure, error, "route-journal");
            }
            boolean cleanupPending = true;
            try { cleanupPending = cleanup == null || !cleanup.success() || gps.hasPendingRecovery(); }
            catch (Exception error) { failure = appendFailure(failure, error, "cleanup-readback"); }
            return new JSONObject().put("completed", completed).put("elapsed", elapsed).put("origin", origin)
                    .put("failure", failure).put("cleanup", cleanup == null ? "cleanup unconfirmed"
                            : cleanup.code + ": " + cleanup.detail)
                    .put("failureCode", gpsFailure == null ? "" : gpsFailure.code.name())
                    .put("blockingPackage", gpsFailure == null ? "" : gpsFailure.blockingPackage)
                    .put("resetAny", resetAnyRequested)
                    .put("diagnosticCoverage", finalCoverage)
                    .put("cleanupPending", cleanupPending);
        }
    }

    private static String appendFailure(String primary, Exception error, String stage) {
        String detail = FailureDiagnostics.format(error, "shanghai", stage);
        android.util.Log.e("BydHudShanghai", stage, error);
        return primary.isEmpty() ? detail : primary + "\nCleanup: " + detail;
    }

    static String tokenFromSessionId(String id) throws java.security.NoSuchAlgorithmException {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("Shanghai session ID is missing");
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder token = new StringBuilder(12);
        for (int i = 0; i < 6; i++) token.append(String.format(java.util.Locale.ROOT, "%02x", digest[i] & 0xff));
        return token.toString();
    }

    static void retainCoverage(File control, JSONObject result) {
        if (result.has("diagnosticCoverage")) return;
        try {
            JSONObject coverage = coverageFromState(ShellWorkFiles.read(new File(control, "state.json")));
            if (coverage != null) ShanghaiJson.put(result, "diagnosticCoverage", coverage);
        } catch (Exception error) {
            ShanghaiJson.put(result, "diagnosticCoverageRecoveryError",
                    FailureDiagnostics.format(error, "shanghai", "retain-coverage"));
        }
    }

    static JSONObject coverageFromState(JSONObject state) {
        JSONObject result = state.optJSONObject("result");
        JSONObject coverage = result == null ? null : result.optJSONObject("diagnosticCoverage");
        if (coverage == null) {
            JSONObject progress = state.optJSONObject("progress");
            coverage = progress == null ? null : progress.optJSONObject("diagnosticCoverage");
        }
        if (coverage == null) return null;
        JSONObject snapshot = ShanghaiJson.copy(coverage);
        ShanghaiJson.put(snapshot, "workerState", state.optString("state"));
        if ("INTERRUPTED".equals(state.optString("state"))
                || (ShellWorkClient.terminal(state.optString("state")) && !snapshot.optBoolean("finalized")))
            ShanghaiJson.put(snapshot, "interrupted", true);
        return snapshot;
    }

    static void writeCoverage(File directory, JSONObject coverage)
            throws java.io.IOException {
        if (coverage == null) return;
        File file = new File(directory, "shell-runtime/diagnostic-coverage.json");
        // Terminal evidence is immutable; live snapshots stay in private work progress.
        if (file.isFile()) return;
        try {
            ShellWorkFiles.write(file, coverage);
        } finally {
            NavigationLogStorage.markStorageFileChanged(file);
        }
    }
}
