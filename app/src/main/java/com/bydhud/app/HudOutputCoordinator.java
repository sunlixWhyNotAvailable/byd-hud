package com.bydhud.app;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

// Serializes every HUD producer through one SOME/IP owner.
final class HudOutputCoordinator {
    enum Source {
        NONE,
        LEGACY,
        DIRECT,
        MANUAL
    }

    private static final String TAG = "BydHudOutput";
    private static final long DIRECT_INTERVAL_MS = 50L;
    private static final long DEFAULT_INTERVAL_MS = 1000L;
    private static final long SOURCE_CLEAR_DELAY_MS = 120L;
    private static final int FINAL_CLEAR_COUNT = 5;
    private static final long FINAL_CLEAR_INTERVAL_MS = 120L;
    private static final long FINAL_STOP_DELAY_MS = 300L;
    private static final long BIND_RETRY_MS = 200L;
    private static final int BIND_RETRY_LIMIT = 30;
    private static final long STATS_INTERVAL_MS = 30_000L;

    private static HudOutputCoordinator instance;

    static synchronized HudOutputCoordinator get(Context context) {
        if (instance == null) {
            instance = new HudOutputCoordinator(context.getApplicationContext());
        }
        return instance;
    }

    private final Context context;
    private final HandlerThread workerThread = new HandlerThread("BydHudOutput");
    private final Handler worker;
    private final SomeIpHudClient client;

    private boolean manualEnabled;
    private boolean directEnabled;
    private boolean legacyEnabled;
    private HudState manualState;
    private HudState legacyState;
    private DirectTbtFrame directFrame;
    private volatile Source activeSource = Source.NONE;
    private Source pendingSource = Source.NONE;
    private String pendingReason = "";
    private boolean pendingNeedsClear;
    private long pendingActivationNotBeforeMs;
    private boolean serviceStarted;
    private boolean sendScheduled;
    private int generation;
    private int directCounter;
    private int bindAttempts;
    private long sendCount;
    private long sendFailures;
    private long sendDurationMs;
    private long lastStatsLogMs;
    private DirectTbtFrame preparedDirectFrame;
    private DirectTbtPayload.Prepared preparedDirectPayload;
    private int preparedDirectOptionsRevision = -1;
    private long pendingDirectReceivedAtMs;
    private String pendingDirectReason = "";

    private final Runnable sendLoop = new Runnable() {
        @Override
        public void run() {
            sendScheduled = false;
            sendActive("loop");
        }
    };

    private final Runnable bindRetry = new Runnable() {
        @Override
        public void run() {
            if (desiredSource() == Source.NONE
                    && activeSource == Source.NONE
                    && pendingSource == Source.NONE) {
                return;
            }
            if (client.isBound()) {
                bindAttempts = 0;
                resumeActivation("bind-ready");
                return;
            }
            bindAttempts++;
            if (bindAttempts >= BIND_RETRY_LIMIT) {
                HudDeliveryStatus.recordFailure();
                log("bind timeout attempts=" + bindAttempts);
                client.unbind();
                bindAttempts = 0;
                worker.postDelayed(transportRecovery, DEFAULT_INTERVAL_MS);
                return;
            }
            worker.postDelayed(this, BIND_RETRY_MS);
        }
    };

    private final Runnable transportRecovery = new Runnable() {
        @Override
        public void run() {
            if (desiredSource() == Source.NONE) {
                return;
            }
            if (pendingSource != Source.NONE) {
                continuePendingTransition(generation);
            } else {
                ensureBoundOnWorker("transport-recovery");
            }
        }
    };

    private HudOutputCoordinator(Context context) {
        this.context = context;
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        client = new SomeIpHudClient(context, new SomeIpHudClient.Listener() {
            @Override
            public void onClientLog(String line) {
                log("someip " + line);
            }

            @Override
            public void onTransportConnected() {
                worker.post(() -> {
                    bindAttempts = 0;
                    resumeActivation("connected");
                });
            }

            @Override
            public void onTransportUnavailable(String reason) {
                worker.post(() -> handleTransportFailure(reason, null));
            }
        });
    }

    boolean isBound() {
        return client.isBound();
    }

    void ensureBound(String reason) {
        worker.post(() -> ensureBoundOnWorker(reason));
    }

    void publishManual(HudState state, String reason) {
        HudState copy = state == null ? null : state.copy();
        worker.post(() -> {
            manualState = copy;
            if (activeSource == Source.MANUAL) {
                scheduleImmediate(reason);
            }
        });
    }

    void setManualEnabled(boolean enabled, String reason) {
        worker.post(() -> {
            manualEnabled = enabled;
            reconcile(reason);
        });
    }

    void publishLegacy(HudState state, String reason) {
        HudState copy = state == null ? null : state.copy();
        worker.post(() -> {
            legacyState = copy;
            if (activeSource == Source.LEGACY) {
                scheduleImmediate(reason);
            }
        });
    }

    void publishDirect(DirectTbtFrame frame, String reason, long receivedAtMs) {
        worker.post(() -> {
            directFrame = frame;
            preparedDirectFrame = null;
            preparedDirectPayload = null;
            pendingDirectReceivedAtMs = receivedAtMs;
            pendingDirectReason = safe(reason);
            if (activeSource == Source.DIRECT && !sendScheduled) {
                scheduleSend(0L);
            }
        });
    }

    void selectNavigationSource(Source source, String reason) {
        if (source != Source.DIRECT && source != Source.LEGACY && source != Source.NONE) {
            throw new IllegalArgumentException("navigation source=" + source);
        }
        worker.post(() -> {
            directEnabled = source == Source.DIRECT;
            legacyEnabled = source == Source.LEGACY;
            if (source == Source.NONE) {
                directFrame = null;
                legacyState = null;
            }
            reconcile(reason);
        });
    }

    void endDirectOutput(String reason, long detectedAtMs) {
        worker.post(() -> {
            boolean directSelected = directEnabled
                    || activeSource == Source.DIRECT
                    || pendingSource == Source.DIRECT;
            if (!directSelected) {
                logAsync("direct end ignored reason=" + safe(reason)
                        + " detectedAtElapsedMs=" + detectedAtMs);
                return;
            }
            long now = SystemClock.elapsedRealtime();
            logAsync("direct end accepted reason=" + safe(reason)
                    + " detectedAtElapsedMs=" + detectedAtMs
                    + " workerHandoffMs=" + Math.max(0L, now - detectedAtMs));
            directEnabled = false;
            directFrame = null;
            preparedDirectFrame = null;
            preparedDirectPayload = null;
            pendingDirectReceivedAtMs = 0L;
            pendingDirectReason = "";
            reconcile(reason, detectedAtMs);
        });
    }

    void resetTransport(String reason) {
        worker.post(() -> {
            generation++;
            cancelScheduledWork();
            stopServiceAndUnbind(reason);
            activeSource = Source.NONE;
            pendingSource = Source.NONE;
            pendingReason = "";
            pendingNeedsClear = false;
            pendingActivationNotBeforeMs = 0L;
            serviceStarted = false;
            HudDeliveryStatus.reset();
            reconcile("reset:" + reason);
        });
    }

    void shutdown(String reason) {
        worker.post(() -> {
            manualEnabled = false;
            directEnabled = false;
            legacyEnabled = false;
            manualState = null;
            directFrame = null;
            legacyState = null;
            reconcile(reason);
        });
    }

    private void reconcile(String reason) {
        reconcile(reason, 0L);
    }

    private void reconcile(String reason, long endDetectedAtMs) {
        Source target = desiredSource();
        if (pendingSource == Source.NONE && target == activeSource) {
            if (target == Source.NONE && client.hasBinding()) {
                stopServiceAndUnbind(reason);
                HudDeliveryStatus.reset();
            }
            return;
        }
        if (pendingSource == target && target != Source.NONE) {
            return;
        }
        int transitionGeneration = ++generation;
        cancelScheduledWork();
        Source previous = activeSource;
        boolean stillNeedsClear = pendingNeedsClear;
        long existingBarrierDeadline = pendingActivationNotBeforeMs;
        activeSource = Source.NONE;
        pendingSource = Source.NONE;
        pendingReason = "";
        pendingNeedsClear = false;
        pendingActivationNotBeforeMs = 0L;
        HudDeliveryStatus.reset();
        if (target == Source.NONE) {
            beginFinalStop(previous, reason, transitionGeneration, 1, endDetectedAtMs);
            return;
        }
        pendingSource = target;
        pendingReason = reason;
        pendingNeedsClear = previous != Source.NONE || stillNeedsClear;
        if (!pendingNeedsClear) {
            pendingActivationNotBeforeMs = existingBarrierDeadline;
        }
        continuePendingTransition(transitionGeneration);
    }

    private void continuePendingTransition(int transitionGeneration) {
        if (transitionGeneration != generation || pendingSource == Source.NONE) {
            return;
        }
        if (desiredSource() != pendingSource) {
            reconcile("pending-target-changed");
            return;
        }
        if (!pendingNeedsClear) {
            long remainingMs = pendingActivationNotBeforeMs - SystemClock.elapsedRealtime();
            if (remainingMs > 0L) {
                worker.postDelayed(
                        () -> finishPendingTransition(transitionGeneration),
                        remainingMs);
                return;
            }
            finishPendingTransition(transitionGeneration);
            return;
        }
        if (!client.isBound()) {
            ensureBoundOnWorker("transition-clear");
            return;
        }
        if (!sendTransitionClear()) {
            return;
        }
        pendingNeedsClear = false;
        pendingActivationNotBeforeMs =
                SystemClock.elapsedRealtime() + SOURCE_CLEAR_DELAY_MS;
        HudDeliveryStatus.reset();
        worker.postDelayed(
                () -> finishPendingTransition(transitionGeneration),
                SOURCE_CLEAR_DELAY_MS);
    }

    private void finishPendingTransition(int transitionGeneration) {
        if (transitionGeneration != generation || pendingSource == Source.NONE) {
            return;
        }
        Source target = pendingSource;
        String reason = pendingReason;
        if (desiredSource() != target) {
            reconcile("pending-target-changed");
            return;
        }
        pendingSource = Source.NONE;
        pendingReason = "";
        pendingNeedsClear = false;
        pendingActivationNotBeforeMs = 0L;
        activeSource = target;
        activateDesired(reason);
    }

    private void activateDesired(String reason) {
        if (pendingSource != Source.NONE) {
            continuePendingTransition(generation);
            return;
        }
        Source target = desiredSource();
        if (target == Source.NONE) {
            return;
        }
        activeSource = target;
        if (!hasFrame(target)) {
            HudDeliveryStatus.reset();
            log("waiting source=" + target + " reason=" + reason);
            return;
        }
        if (!client.isBound()) {
            ensureBoundOnWorker(reason);
            return;
        }
        scheduleImmediate(reason);
    }

    private Source desiredSource() {
        return chooseSource(manualEnabled, directEnabled, legacyEnabled);
    }

    static Source chooseSource(boolean manual, boolean direct, boolean legacy) {
        if (manual) return Source.MANUAL;
        if (direct) return Source.DIRECT;
        if (legacy) return Source.LEGACY;
        return Source.NONE;
    }

    private boolean hasFrame(Source source) {
        if (source == Source.MANUAL) {
            return manualState != null;
        }
        if (source == Source.DIRECT) {
            return directFrame != null;
        }
        if (source == Source.LEGACY) {
            return legacyState != null;
        }
        return false;
    }

    private void ensureBoundOnWorker(String reason) {
        if (client.isBound()) {
            resumeActivation(reason);
            return;
        }
        if (!client.hasBinding()) {
            client.bind();
            log("bind requested reason=" + reason);
        }
        worker.removeCallbacks(bindRetry);
        worker.postDelayed(bindRetry, BIND_RETRY_MS);
    }

    private void resumeActivation(String reason) {
        if (pendingSource != Source.NONE) {
            continuePendingTransition(generation);
        } else {
            activateDesired(reason);
        }
    }

    private void scheduleImmediate(String reason) {
        if (activeSource == Source.DIRECT && sendScheduled) {
            return;
        }
        worker.removeCallbacks(sendLoop);
        sendScheduled = true;
        worker.post(sendLoop);
        if (!reason.startsWith("loop")) {
            log("send scheduled source=" + activeSource + " reason=" + reason);
        }
    }

    private void scheduleSend(long delayMs) {
        if (activeSource == Source.NONE || !hasFrame(activeSource)) {
            return;
        }
        worker.removeCallbacks(sendLoop);
        sendScheduled = true;
        worker.postDelayed(sendLoop, Math.max(0L, delayMs));
    }

    private void sendActive(String reason) {
        Source source = activeSource;
        if (source == Source.NONE || source != desiredSource() || !hasFrame(source)) {
            return;
        }
        if (!client.isBound()) {
            ensureBoundOnWorker("send-" + reason);
            return;
        }
        long startedAt = SystemClock.elapsedRealtime();
        try {
            if (!serviceStarted) {
                int startResult = client.start();
                if (startResult != 0) {
                    throw new RemoteException("start returned " + startResult);
                }
                serviceStarted = true;
                log("service started source=" + source);
            }
            byte[] payload = buildPayload(source);
            int result = client.send(payload);
            long duration = SystemClock.elapsedRealtime() - startedAt;
            sendCount++;
            sendDurationMs += duration;
            HudDeliveryStatus.recordNonClearResult(result);
            if (result != 0) {
                throw new RemoteException("send returned " + result);
            }
            maybeLogStats(source, payload.length, duration);
            if (source == Source.DIRECT) {
                if (pendingDirectReceivedAtMs > 0L) {
                    log("direct emitted_frame_first_send reason=" + pendingDirectReason
                            + " elapsedMs=" + Math.max(0L,
                            SystemClock.elapsedRealtime() - pendingDirectReceivedAtMs));
                    pendingDirectReceivedAtMs = 0L;
                    pendingDirectReason = "";
                }
                directCounter = (directCounter + 1) & 0xff;
            }
            scheduleSend(source == Source.DIRECT ? DIRECT_INTERVAL_MS : DEFAULT_INTERVAL_MS);
        } catch (RemoteException | RuntimeException e) {
            sendFailures++;
            handleTransportFailure("send source=" + source, e);
        }
    }

    private byte[] buildPayload(Source source) {
        if (source == Source.DIRECT) {
            int optionsRevision = HudPrefs.outputOptionsRevision();
            if (preparedDirectPayload == null
                    || preparedDirectFrame != directFrame
                    || preparedDirectOptionsRevision != optionsRevision) {
                preparedDirectFrame = directFrame;
                preparedDirectOptionsRevision = optionsRevision;
                preparedDirectPayload = DirectTbtPayload.prepare(
                        directFrame, DirectTbtPayload.Options.from(context));
            }
            return preparedDirectPayload.build(directCounter);
        }
        HudState state = source == Source.MANUAL ? manualState.copy() : legacyState.copy();
        state = HudDisplayPolicy.apply(
                state,
                HudPrefs.isSmallDistanceClampEnabled(context));
        HudOutputPreferences.apply(context, state);
        return HudRoadPayload.build(state);
    }

    private void beginFinalStop(Source previous, String reason, int stopGeneration, int frame,
                                long endDetectedAtMs) {
        if (stopGeneration != generation) {
            return;
        }
        if (!serviceStarted || !client.isBound()) {
            stopServiceAndUnbind(reason);
            HudDeliveryStatus.reset();
            return;
        }
        long clearedAtMs = sendClearBestEffort(
                "final " + previous + " frame=" + frame + " reason=" + reason);
        long remainingEndDetectedAtMs = endDetectedAtMs;
        if (clearedAtMs >= 0L && endDetectedAtMs > 0L) {
            logAsync("direct end first_clear detectedAtElapsedMs=" + endDetectedAtMs
                    + " endToClearMs=" + Math.max(0L,
                    clearedAtMs - endDetectedAtMs));
            remainingEndDetectedAtMs = 0L;
        }
        if (frame < FINAL_CLEAR_COUNT) {
            long nextEndDetectedAtMs = remainingEndDetectedAtMs;
            worker.postDelayed(
                    () -> beginFinalStop(previous, reason, stopGeneration, frame + 1,
                            nextEndDetectedAtMs),
                    FINAL_CLEAR_INTERVAL_MS);
            return;
        }
        worker.postDelayed(() -> {
            if (stopGeneration != generation) {
                return;
            }
            stopServiceAndUnbind(reason);
            HudDeliveryStatus.reset();
        }, FINAL_STOP_DELAY_MS);
    }

    private boolean sendTransitionClear() {
        if (!client.isBound()) {
            return false;
        }
        try {
            if (!serviceStarted) {
                int startResult = client.start();
                if (startResult != 0) {
                    throw new RemoteException("start returned " + startResult);
                }
                serviceStarted = true;
            }
            int result = client.send(DirectTbtPayload.buildClear());
            if (result != 0) {
                throw new RemoteException("clear returned " + result);
            }
            log("transition clear result=0 target=" + pendingSource
                    + " reason=" + pendingReason);
            return true;
        } catch (RemoteException | RuntimeException e) {
            handleTransportFailure("transition-clear", e);
            return false;
        }
    }

    private long sendClearBestEffort(String reason) {
        if (!client.isBound()) {
            return -1L;
        }
        try {
            int result = client.send(DirectTbtPayload.buildClear());
            long completedAtMs = SystemClock.elapsedRealtime();
            logAsync("clear result=" + result + " reason=" + reason);
            return result == 0 ? completedAtMs : -1L;
        } catch (RemoteException | RuntimeException e) {
            logAsync("clear failed reason=" + reason + " error=" + safe(e.getMessage()));
            return -1L;
        }
    }

    private void stopServiceAndUnbind(String reason) {
        worker.removeCallbacks(bindRetry);
        worker.removeCallbacks(transportRecovery);
        if (serviceStarted && client.isBound()) {
            try {
                client.stop();
            } catch (RemoteException e) {
                log("stop failed reason=" + reason + " error=" + safe(e.getMessage()));
            }
        }
        serviceStarted = false;
        client.unbind();
        directCounter = 0;
        log("transport stopped reason=" + reason);
    }

    private void handleTransportFailure(String reason, Throwable error) {
        HudDeliveryStatus.recordFailure();
        serviceStarted = false;
        sendScheduled = false;
        worker.removeCallbacks(sendLoop);
        worker.removeCallbacks(bindRetry);
        worker.removeCallbacks(transportRecovery);
        client.unbind();
        log("transport failure reason=" + reason
                + (error == null ? "" : " error=" + error.getClass().getSimpleName()
                + ":" + safe(error.getMessage())));
        if (desiredSource() != Source.NONE) {
            worker.postDelayed(transportRecovery, DEFAULT_INTERVAL_MS);
        }
    }

    private void cancelScheduledWork() {
        worker.removeCallbacks(sendLoop);
        worker.removeCallbacks(bindRetry);
        worker.removeCallbacks(transportRecovery);
        sendScheduled = false;
    }

    private void maybeLogStats(Source source, int payloadBytes, long lastSendMs) {
        long now = SystemClock.elapsedRealtime();
        if (lastStatsLogMs != 0L && now - lastStatsLogMs < STATS_INTERVAL_MS) {
            return;
        }
        long average = sendCount == 0L ? 0L : sendDurationMs / sendCount;
        log("stats source=" + source
                + " sends=" + sendCount
                + " failures=" + sendFailures
                + " lastSendMs=" + lastSendMs
                + " avgSendMs=" + average
                + " payloadBytes=" + payloadBytes);
        lastStatsLogMs = now;
    }

    private void log(String line) {
        Log.i(TAG, line);
        AppEventLogger.event(context, "hud_output " + line);
    }

    private void logAsync(String line) {
        Log.i(TAG, line);
        WazeCaptureDebugWriter.get().appEvent(context, "hud_output " + line);
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
