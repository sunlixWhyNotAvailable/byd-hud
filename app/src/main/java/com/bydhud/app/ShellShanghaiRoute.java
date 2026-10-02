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
                    input.getString("id").replace("-", "").substring(0, 16));
            boolean completed = false;
            long origin = 0;
            int elapsed = 0;
            String failure = "";
            ShanghaiMockGps.Result cleanup;
            try (ShanghaiSessionJournal journal = new ShanghaiSessionJournal(new File(shellEvidence, "route.jsonl"))) {
                try {
                    diagnostics.start();
                    diagnostics.resolveReadiness(5_000);
                    ShanghaiRoute route = ShanghaiRoute.load(app);
                    ShanghaiMockGps.Result begun = gps.begin(input.getString("id"));
                    journal.record("mock_begin", begun.code + ": " + begun.detail);
                    if (!begun.success()) throw new IllegalStateException(begun.detail);
                    while (elapsed <= ShanghaiRoute.DURATION_SECONDS) {
                        if (Thread.currentThread().isInterrupted()) break;
                        ShanghaiRoute.Point point = route.pointAt(elapsed);
                        ShanghaiMockGps.Result injected = gps.inject(point);
                        journal.record("route_point", "second=" + elapsed + " lat=" + point.latitude
                                + " lon=" + point.longitude + " result=" + injected.code);
                        if (!injected.success()) throw new IllegalStateException(injected.detail);
                        if (origin == 0) origin = gps.lastInjectedElapsedMs();
                        ShellWorkEntryPoint.progress(new JSONObject().put("phase", elapsed < 15 ? "PREPARING" : "DRIVING")
                                .put("elapsed", elapsed).put("origin", origin));
                        if (elapsed == ShanghaiRoute.DURATION_SECONDS) { completed = true; break; }
                        long now = SystemClock.elapsedRealtime();
                        long next = origin + ((now - origin) / 1000 + 1) * 1000;
                        Thread.sleep(Math.max(1, next - now));
                        elapsed = (int) Math.min(ShanghaiRoute.DURATION_SECONDS, (SystemClock.elapsedRealtime() - origin) / 1000);
                    }
                } catch (InterruptedException stopped) {
                    // Explicit Stop cancels the route; ordinary application death never interrupts this thread.
                } catch (Exception error) { failure = error.toString(); journal.record("failure", failure); }
                finally {
                    Thread.interrupted();
                    cleanup = new File(app.job, "reset-any").exists() ? gps.resetAny() : gps.cleanupOwned();
                    journal.record("mock_cleanup", cleanup.code + ": " + cleanup.detail);
                    try { Thread.sleep(5_000); } catch (InterruptedException ignored) { Thread.interrupted(); }
                    diagnostics.close();
                    ShellWorkFiles.write(new File(shellEvidence, "diagnostic-coverage.json"), diagnostics.coverageJson());
                }
            }
            return new JSONObject().put("completed", completed).put("elapsed", elapsed).put("origin", origin)
                    .put("failure", failure).put("cleanup", cleanup.code + ": " + cleanup.detail)
                    .put("cleanupPending", !cleanup.success() || gps.hasPendingRecovery());
        }
    }
}
