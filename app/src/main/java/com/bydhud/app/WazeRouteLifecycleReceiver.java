package com.bydhud.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

// Receives authenticated route transitions from the patched Waze process.
public final class WazeRouteLifecycleReceiver extends BroadcastReceiver {
    private static final String EXTRA_EVENT_TYPE = "event_type";
    private static final String EXTRA_SPEED_LIMIT = "speed_limit";
    private static final String EXTRA_SPEED_UNIT = "speed_unit";
    private static final long RECEIVER_TIMEOUT_MS = 8_000L;
    private static final long TRUST_TIMEOUT_MS = 2_000L;

    private static final class AsyncRuntime {
        static final Handler WATCHDOG = new Handler(Looper.getMainLooper());
        static final ExecutorService EVENTS = Executors.newSingleThreadExecutor(
                runnable -> namedThread(runnable, "bydhud-waze-events"));
        static final ExecutorService TRUST = Executors.newSingleThreadExecutor(
                runnable -> namedThread(runnable, "bydhud-waze-trust"));
    }

    private static final class AsyncCompletion implements Runnable {
        private final PendingResult pendingResult;
        private final long deadlineElapsedMs;
        private final AtomicBoolean open = new AtomicBoolean(true);

        AsyncCompletion(PendingResult pendingResult) {
            this.pendingResult = pendingResult;
            deadlineElapsedMs = SystemClock.elapsedRealtime() + RECEIVER_TIMEOUT_MS;
        }

        boolean isOpen() {
            if (!open.get()) return false;
            if (SystemClock.elapsedRealtime() < deadlineElapsedMs) return true;
            run();
            return false;
        }

        void finish() {
            if (!open.compareAndSet(true, false)) return;
            AsyncRuntime.WATCHDOG.removeCallbacks(this);
            pendingResult.finish();
        }

        @Override
        public void run() {
            if (open.compareAndSet(true, false)) pendingResult.finish();
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null
                || !WazeRouteLifecycleStore.ACTION.equals(intent.getAction())) {
            return;
        }
        final Context appContext = context.getApplicationContext();

        final String eventType;
        final int protocol;
        final long eventElapsedMs;
        final long bridgeGeneration;
        final int bridgeCapabilities;
        try {
            if (!intent.hasExtra(WazeRouteLifecycleStore.EXTRA_PROTOCOL_VERSION)
                    || !intent.hasExtra(WazeRouteLifecycleStore.EXTRA_EVENT_ELAPSED_MS)) {
                log(context, "ignored reason=missing_extras");
                return;
            }
            protocol = intent.getIntExtra(
                    WazeRouteLifecycleStore.EXTRA_PROTOCOL_VERSION, -1);
            eventType = intent.getStringExtra(EXTRA_EVENT_TYPE);
            eventElapsedMs = intent.getLongExtra(
                    WazeRouteLifecycleStore.EXTRA_EVENT_ELAPSED_MS, 0L);
            bridgeGeneration = intent.getLongExtra(
                    WazeRouteLifecycleStore.EXTRA_BRIDGE_GENERATION, 0L);
            bridgeCapabilities = intent.getIntExtra(
                    WazeRouteLifecycleStore.EXTRA_BRIDGE_CAPABILITIES, 0);
        } catch (RuntimeException malformed) {
            log(context, "ignored reason=malformed_extras");
            return;
        }
        if (protocol != WazeRouteLifecycleStore.PROTOCOL_VERSION) {
            log(context, "ignored reason=protocol_mismatch value=" + protocol);
            return;
        }
        if (bridgeGeneration < 0L || bridgeCapabilities < 0) {
            log(context, "ignored reason=invalid_bridge_metadata");
            return;
        }

        final Runnable delivery;
        try {
            if ("speed_limit".equals(eventType)) {
                if (!intent.hasExtra(EXTRA_SPEED_LIMIT)) {
                    log(context, "speed_limit ignored reason=invalid_payload");
                    return;
                }
                int limit = intent.getIntExtra(EXTRA_SPEED_LIMIT, -1);
                String unit = intent.getStringExtra(EXTRA_SPEED_UNIT);
                if (limit < 0 || limit > 300 || unit == null || unit.length() > 16) {
                    log(context, "speed_limit ignored reason=invalid_payload");
                    return;
                }
                delivery = () -> handleSpeedLimit(appContext, limit, unit, eventElapsedMs,
                        bridgeGeneration, bridgeCapabilities, "");
            } else {
                if (!intent.hasExtra(WazeRouteLifecycleStore.EXTRA_NAVIGATING)) {
                    log(context, "ignored reason=missing_extras");
                    return;
                }
                boolean navigating = intent.getBooleanExtra(
                        WazeRouteLifecycleStore.EXTRA_NAVIGATING, false);
                boolean reasonAvailable = intent.hasExtra(
                        WazeRouteLifecycleStore.EXTRA_REASON_CODE);
                int reasonCode = intent.getIntExtra(
                        WazeRouteLifecycleStore.EXTRA_REASON_CODE,
                        WazeRouteLifecycleStore.REASON_UNAVAILABLE);
                delivery = () -> handleRoute(appContext, navigating, reasonCode, reasonAvailable,
                        eventElapsedMs, bridgeGeneration, bridgeCapabilities, "");
            }
        } catch (RuntimeException malformed) {
            log(context, "ignored reason=malformed_extras");
            return;
        }

        enqueue(appContext, goAsync(), "v1",
                () -> WazeRouteLifecycleStore.isBridgeSupported(appContext), delivery);
    }

    static void enqueue(Context context, PendingResult pendingResult, String channel,
            Callable<Boolean> trustCheck, Runnable delivery) {
        Context appContext = context.getApplicationContext();
        AsyncCompletion completion = new AsyncCompletion(pendingResult);
        AsyncRuntime.WATCHDOG.postDelayed(completion, RECEIVER_TIMEOUT_MS);
        try {
            AsyncRuntime.EVENTS.execute(() -> {
                try {
                    if (!completion.isOpen()) return;
                    String trustDecision = awaitTrust(trustCheck);
                    if (!completion.isOpen()) return;
                    if (!"trusted".equals(trustDecision)) {
                        log(appContext, channel + " ignored reason=" + trustDecision);
                        return;
                    }
                    delivery.run();
                } catch (RuntimeException failure) {
                    if (completion.isOpen()) {
                        log(appContext, channel + " ignored reason=delivery_failed"
                                + " type=" + failure.getClass().getSimpleName());
                    }
                } finally {
                    completion.finish();
                }
            });
        } catch (RejectedExecutionException rejected) {
            log(appContext, channel + " ignored reason=executor_rejected");
            completion.finish();
        }
    }

    private static String awaitTrust(Callable<Boolean> trustCheck) {
        final Future<Boolean> future;
        try {
            future = AsyncRuntime.TRUST.submit(trustCheck);
        } catch (RejectedExecutionException rejected) {
            return "trust_rejected";
        }
        try {
            return Boolean.TRUE.equals(future.get(TRUST_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                    ? "trusted" : "untrusted_identity";
        } catch (TimeoutException timeout) {
            future.cancel(true);
            return "trust_timeout";
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return "trust_interrupted";
        } catch (ExecutionException failure) {
            return "trust_failed";
        }
    }

    static void handleSpeedLimit(Context context, int limit, String unit, long eventElapsedMs,
            long bridgeGeneration, int bridgeCapabilities, String prefix) {
        long receivedElapsedMs = SystemClock.elapsedRealtime();
        WazeRouteLifecycleStore.SpeedRecordResult result =
                WazeRouteLifecycleStore.recordSpeedEvent(context, bridgeGeneration,
                        bridgeCapabilities, eventElapsedMs);
        log(context, prefix + "speed_limit value=" + limit + " unit=" + unit
                + " accepted=" + result.accepted
                + " decision=" + result.reason
                + " senderElapsedMs=" + eventElapsedMs
                + " receiverElapsedMs=" + receivedElapsedMs
                + " latencyMs=" + Math.max(0L, receivedElapsedMs - eventElapsedMs)
                + " bridgeGeneration=" + bridgeGeneration
                + " bridgeCapabilities=" + bridgeCapabilities);
        if (!result.accepted) return;
        NavHudLiveSender.onWazeSpeedLimitEvent(context.getApplicationContext(), limit, unit,
                eventElapsedMs, bridgeGeneration, bridgeCapabilities);
    }

    static void handleRoute(Context context, boolean navigating, int reasonCode,
            boolean reasonAvailable, long eventElapsedMs, long bridgeGeneration,
            int bridgeCapabilities, String prefix) {
        long receivedElapsedMs = SystemClock.elapsedRealtime();
        WazeRouteLifecycleStore.RecordResult result = WazeRouteLifecycleStore.recordBridge(
                context, navigating, reasonCode, reasonAvailable, eventElapsedMs,
                bridgeGeneration, bridgeCapabilities);
        log(context, prefix + "event navigating=" + navigating
                + " reasonCode=" + reasonCode
                + " reasonName=" + result.reasonName
                + " routeActive=" + result.snapshot.active
                + " terminal=" + result.terminal
                + " accepted=" + result.accepted
                + " changed=" + result.changed
                + " decision=" + result.reason
                + " senderElapsedMs=" + eventElapsedMs
                + " receiverElapsedMs=" + receivedElapsedMs
                + " latencyMs=" + Math.max(0L, receivedElapsedMs - eventElapsedMs)
                + " bridgeGeneration=" + bridgeGeneration
                + " bridgeCapabilities=" + bridgeCapabilities);
        if (result.accepted) dispatchAccepted(context, eventElapsedMs, result);
    }

    static void dispatchAccepted(Context context, long eventElapsedMs,
            WazeRouteLifecycleStore.RecordResult result) {
        NavHudLiveSender.onWazeRouteLifecycleEvent(
                context.getApplicationContext(), result.snapshot.active, result.terminal,
                eventElapsedMs, result.snapshot.bridgeGeneration,
                result.snapshot.bridgeCapabilities, result.changed, result.reason);
        if (result.snapshot.active
                && HudPrefs.isBootEnabled(context)
                && !HudPrefs.isUserShutdownActive(context)
                && (NavCapturePrefs.isHudEnabled(
                context, WazeRouteLifecycleStore.WAZE_PACKAGE)
                || NavHudLiveSender.shouldObserveTbtWithoutHud(
                context, WazeRouteLifecycleStore.WAZE_PACKAGE))) {
            HudRuntimeService.startPersistent(context, "waze-route-start");
        }
    }

    private static Thread namedThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    static void log(Context context, String value) {
        AppEventLogger.event(context, "waze_route_lifecycle " + value);
    }
}
