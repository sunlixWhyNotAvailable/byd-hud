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
        Context shell = system.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
        ShellWorkContext gpsContext = new ShellWorkContext(shell, system, ownership);
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
            long lastCoverageWriteMs = Long.MIN_VALUE;
            int elapsed = 0;
            String failure = "";
            ShanghaiMockGps.Result gpsFailure = null;
            ShanghaiMockGps.Result cleanup;
            boolean resetAnyRequested = false;
            try (ShanghaiSessionJournal journal = new ShanghaiSessionJournal(new File(shellEvidence, "route.jsonl"))) {
                try {
                    diagnostics.start();
                    diagnostics.resolveReadiness(5_000);
                    writeCoverage(shellEvidence, diagnostics);
                    lastCoverageWriteMs = SystemClock.elapsedRealtime();
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
                                .put("elapsed", elapsed).put("origin", origin));
                        long coverageNow = SystemClock.elapsedRealtime();
                        if (coverageNow - lastCoverageWriteMs >= 1_000L) {
                            writeCoverage(shellEvidence, diagnostics);
                            lastCoverageWriteMs = coverageNow;
                        }
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
                    journal.record("failure", failure);
                }
                finally {
                    Thread.interrupted();
                    File resetAny = ShellWorkEntryPoint.currentControl == null ? null
                            : new File(ShellWorkEntryPoint.currentControl, "reset-any");
                    resetAnyRequested = resetAny != null && resetAny.isFile();
                    cleanup = resetAnyRequested
                            ? gps.resetAny() : gps.cleanupOwned();
                    journal.record("mock_cleanup", cleanup.code + ": " + cleanup.detail);
                    try { Thread.sleep(5_000); } catch (InterruptedException ignored) { Thread.interrupted(); }
                    diagnostics.close();
                    writeCoverage(shellEvidence, diagnostics);
                }
            }
            return new JSONObject().put("completed", completed).put("elapsed", elapsed).put("origin", origin)
                    .put("failure", failure).put("cleanup", cleanup.code + ": " + cleanup.detail)
                    .put("failureCode", gpsFailure == null ? "" : gpsFailure.code.name())
                    .put("blockingPackage", gpsFailure == null ? "" : gpsFailure.blockingPackage)
                    .put("resetAny", resetAnyRequested)
                    .put("cleanupPending", !cleanup.success() || gps.hasPendingRecovery());
        }
    }

    static String tokenFromSessionId(String id) throws java.security.NoSuchAlgorithmException {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("Shanghai session ID is missing");
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder token = new StringBuilder(12);
        for (int i = 0; i < 6; i++) token.append(String.format(java.util.Locale.ROOT, "%02x", digest[i] & 0xff));
        return token.toString();
    }

    private static void writeCoverage(File directory, ShanghaiDiagnosticAdb diagnostics)
            throws java.io.IOException {
        File file = new File(directory, "diagnostic-coverage.json");
        try {
            ShellWorkFiles.write(file, diagnostics.coverageJson());
        } finally {
            NavigationLogStorage.markStorageFileChanged(file);
        }
    }
}
