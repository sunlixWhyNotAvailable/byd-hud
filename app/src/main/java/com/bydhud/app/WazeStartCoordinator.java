package com.bydhud.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.SystemClock;

import java.util.concurrent.FutureTask;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;

/** Event-driven admission from a real Waze window plus a coalesced process check. */
final class WazeStartCoordinator {
    private static final ReconcileGate RECONCILE = new ReconcileGate();
    private static final Object PROCESS_CHECK_LOCK = new Object();
    private static ProcessCheck pendingProcessCheck;
    private static String lastDecision = "";

    enum Presence { RUNNING, ABSENT, UNAVAILABLE }

    static boolean refreshRuntime(Context context) {
        boolean enabled = UserRuntimeSession.PROCESS.allowsRuntime(
                HudPrefs.isBootEnabled(context), HudPrefs.isUserShutdownActive(context))
                && (NavCapturePrefs.isHudEnabled(context, WazeDirectChannel.OWNER_PACKAGE)
                || NavHudLiveSender.shouldObserveTbtWithoutHud(context, WazeDirectChannel.OWNER_PACKAGE));
        long updateMs = Long.MIN_VALUE;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    WazeDirectChannel.OWNER_PACKAGE, 0);
            updateMs = info.lastUpdateTime;
        } catch (Exception missing) {
            enabled = false;
        }
        WazeStartAdmission.PROCESS.updateRuntime(enabled, updateMs,
                WazeRouteLifecycleStore.isBridgeSupported(context));
        return enabled;
    }

    static void acceptedRoute(Context context, WazeRouteLifecycleStore.RecordResult result,
            long eventElapsedMs) {
        refreshRuntime(context);
        WazeStartAdmission.PROCESS.acceptedRoute(result.snapshot.active,
                result.rawNavigating, result.terminal, eventElapsedMs);
    }

    static void requestLegacyReconcile(Context context, String reason) {
        Context appContext = context.getApplicationContext();
        if (!RECONCILE.request(reason)) return;
        Thread worker = new Thread(() -> {
            String trigger;
            while ((trigger = RECONCILE.next()) != null) {
                try {
                    if (!refreshRuntime(appContext)
                            || WazeRouteLifecycleStore.isBridgeSupported(appContext)) continue;
                    long epoch = WazeStartAdmission.PROCESS.epoch();
                    long windowObservedMs = NavAccessibilityService.observeWazeApplicationWindow();
                    refreshRuntime(appContext);
                    WazeStartAdmission.PROCESS.observedWindow(epoch, windowObservedMs);
                    if (!WazeStartAdmission.PROCESS.hasFreshLegacyWindow(
                            epoch, SystemClock.elapsedRealtime())) {
                        logDecision(appContext, "waiting_window", trigger, epoch);
                        continue;
                    }
                    ProcessObservation observation = readProcess(appContext, epoch);
                    refreshRuntime(appContext);
                    WazeStartAdmission.PROCESS.observedProcess(epoch,
                            observation.canAdmit(epoch, SystemClock.elapsedRealtime()),
                            observation.startedElapsedMs);
                    WazeStartAdmission.Permit permit = WazeStartAdmission.PROCESS.acquire(
                            SystemClock.elapsedRealtime());
                    boolean admitted = permit != null && permit.source == WazeStartAdmission.Source.LEGACY_WINDOW;
                    logDecision(appContext, admitted ? "window_ready" : "waiting_evidence",
                            trigger, epoch);
                    if (admitted) NavHudLiveSender.onWazeLegacyWindowObserved(appContext, trigger);
                } catch (RuntimeException unavailable) {
                    logDecision(appContext, "waiting_UNAVAILABLE", trigger,
                            WazeStartAdmission.PROCESS.epoch());
                }
            }
        }, "BydHudWazePresence");
        worker.setDaemon(true);
        worker.start();
    }

    /** Called only on a channel worker before a new bind, never from the UI thread. */
    static WazeStartAdmission.Permit beforeBind(Context context, boolean retry, String reason) {
        if (!refreshRuntime(context)) return null;
        WazeStartAdmission.Permit permit = beforeBind(WazeStartAdmission.PROCESS, retry,
                SystemClock::elapsedRealtime, epoch -> readProcess(context, epoch),
                () -> refreshRuntime(context));
        logDecision(context, permit == null ? "waiting" : "admitted_" + permit.source,
                reason, WazeStartAdmission.PROCESS.epoch());
        return permit;
    }

    /** The final decision is shared by both channel modes; collaborators keep its races testable. */
    static WazeStartAdmission.Permit beforeBind(WazeStartAdmission admission, boolean retry,
            LongSupplier clock, LongFunction<ProcessObservation> processRead, Runnable refresh) {
        long epoch = admission.epoch();
        WazeStartAdmission.Permit permit = admission.acquire(clock.getAsLong());
        if (retry && (permit == null || permit.source == WazeStartAdmission.Source.LEGACY_WINDOW)
                && admission.hasFreshLegacyWindow(epoch, clock.getAsLong())) {
            ProcessObservation observation = processRead.apply(epoch);
            refresh.run();
            admission.observedProcess(epoch,
                    observation.canAdmit(epoch, clock.getAsLong()),
                    observation.startedElapsedMs);
            permit = admission.acquire(clock.getAsLong());
        }
        return epoch == admission.epoch() ? permit : null;
    }

    static ProcessObservation readProcess(Context context, long epoch) {
        ProcessCheck check;
        boolean owner;
        synchronized (PROCESS_CHECK_LOCK) {
            owner = pendingProcessCheck == null || pendingProcessCheck.epoch != epoch;
            if (owner) pendingProcessCheck = new ProcessCheck(context, epoch);
            check = pendingProcessCheck;
        }
        try {
            if (owner) check.task.run();
            return check.task.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new ProcessObservation(Presence.UNAVAILABLE, epoch, -1L);
        } catch (Exception unavailable) {
            return new ProcessObservation(Presence.UNAVAILABLE, epoch, -1L);
        } finally {
            if (owner) synchronized (PROCESS_CHECK_LOCK) {
                if (pendingProcessCheck == check) pendingProcessCheck = null;
            }
        }
    }

    private static final class ProcessCheck {
        final long epoch;
        final FutureTask<ProcessObservation> task;

        ProcessCheck(Context context, long epoch) {
            this.epoch = epoch;
            task = new FutureTask<>(() -> {
                // Conservative lower bound: sleep or descheduling after the shell read
                // must never make an old result appear freshly observed by its waiter.
                long started = SystemClock.elapsedRealtime();
                Presence presence;
                try {
                    LocalAdbBridge.ShellResult result = LocalAdbBridge.runRuntimeShellCommandOnce(
                            context, "pidof com.waze");
                    presence = parsePresence(result.exitCode, result.output);
                } catch (Exception unavailable) {
                    presence = Presence.UNAVAILABLE;
                }
                return new ProcessObservation(presence, epoch, started);
            });
        }
    }

    static final class ProcessObservation {
        final Presence presence;
        final long epoch;
        final long startedElapsedMs;

        ProcessObservation(Presence presence, long epoch, long startedElapsedMs) {
            this.presence = presence;
            this.epoch = epoch;
            this.startedElapsedMs = startedElapsedMs;
        }

        boolean canAdmit(long expectedEpoch, long nowElapsedMs) {
            return presence == Presence.RUNNING && epoch == expectedEpoch
                    && startedElapsedMs >= 0L && nowElapsedMs >= startedElapsedMs
                    && nowElapsedMs - startedElapsedMs <= WazeStartAdmission.FRESH_MS;
        }
    }

    static Presence parsePresence(int exitCode, String output) {
        String value = output == null ? "" : output.trim();
        if ((exitCode == 0 || exitCode == 1) && value.isEmpty()) return Presence.ABSENT;
        if (exitCode != 0 || !value.matches("[1-9][0-9]*(\\s+[1-9][0-9]*)*")) {
            return Presence.UNAVAILABLE;
        }
        return Presence.RUNNING;
    }

    private static synchronized void logDecision(Context context, String decision,
            String reason, long epoch) {
        String key = decision + ":" + epoch;
        if (key.equals(lastDecision)) return;
        lastDecision = key;
        AppEventLogger.event(context, "waze_start " + decision + " epoch=" + epoch
                + " trigger=" + reason);
    }

    /** One worker plus one coalesced follow-up; a launch during a check is not lost. */
    static final class ReconcileGate {
        private boolean running;
        private String pendingReason;

        synchronized boolean request(String reason) {
            pendingReason = reason == null ? "event" : reason;
            if (running) return false;
            running = true;
            return true;
        }

        synchronized String next() {
            String next = pendingReason;
            pendingReason = null;
            if (next == null) running = false;
            return next;
        }
    }
}
