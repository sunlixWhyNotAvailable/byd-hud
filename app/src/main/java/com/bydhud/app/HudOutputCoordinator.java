package com.bydhud.app;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.util.function.Consumer;

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
    private static final long FINAL_CLEAR_TIMEOUT_MS = 6_000L;
    private static final long BIND_RETRY_MS = 200L;
    private static final int BIND_RETRY_LIMIT = 30;
    private static final long BIND_TIMEOUT_MS = BIND_RETRY_MS * BIND_RETRY_LIMIT;
    private static final long DIRECT_LEASE_MS = 15_000L;
    private static final int RESULT_OK = 0;
    private static final int RESULT_NOT_STARTED = 11;
    private static final int RESULT_ALREADY_STARTED = 13;
    private static final long[] PROTOCOL_RETRY_DELAYS_MS = {1_000L, 2_000L, 5_000L};
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
    private final SomeIpTxLog txLog;

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
    private boolean protocolRetryScheduled;
    private int protocolFailureCount;
    private int generation;
    private int directCounter;
    private int bindGeneration;
    private long bindDeadlineMs;
    private boolean bindCheckScheduled;
    private long sendCount;
    private long sendFailures;
    private long sendDurationMs;
    private long lastStatsLogMs;
    private DirectTbtFrame preparedDirectFrame;
    private DirectTbtPayload.Prepared preparedDirectPayload;
    private byte[] preparedDirectSemanticPayload;
    private GMapsDirectChannel.BitmapSelection directBitmapSelection;
    private GMapsDirectChannel.BitmapSelection preparedDirectBitmapSelection;
    private Consumer<String> directBitmapTxLogger;
    private Consumer<String> preparedDirectBitmapTxLogger;
    private String lastBitmapTxDiagnosticKey = "";
    private String directChannel = "waze_direct";
    private Source lastTransportSource = Source.NONE;
    private String lastTransportChannel = "transport";
    private int preparedDirectOptionsRevision = -1;
    private boolean directAlertClearPending;
    private boolean directLossClearPending;
    private boolean directLossClearSent;
    private String directOwnerPackage = "";
    private long directOwnerSessionGeneration = Long.MIN_VALUE;
    private long directLeaseDeadlineMs;
    private long pendingDirectReceivedAtMs;
    private String pendingDirectReason = "";
    private long directFirstFrameAtMs;
    private long directServiceReadyAtMs;
    private boolean directFirstPhysicalSendLogged;
    private Runnable pendingFinalClearCompletion;
    private Runnable pendingTransitionClearCompletion;
    private boolean pendingFinalClearSucceeded;
    private boolean finalClearInProgress;
    private long finalClearStartedAtMs;

    private final Runnable directLeaseExpiry = this::expireDirectLease;

    private final Runnable sendLoop = new Runnable() {
        @Override
        public void run() {
            sendScheduled = false;
            sendActive("loop");
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

    private final Runnable protocolRetry = new Runnable() {
        @Override
        public void run() {
            protocolRetryScheduled = false;
            if (desiredSource() == Source.NONE) {
                return;
            }
            if (!client.isBound()) {
                ensureBoundOnWorker("protocol-retry");
                return;
            }
            resumeActivation("protocol-retry");
        }
    };

    private HudOutputCoordinator(Context context) {
        this.context = context;
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        txLog = SomeIpTxLog.get(context);
        client = new SomeIpHudClient(context, new SomeIpHudClient.Listener() {
            @Override
            public void onClientLog(String line) {
                log("someip " + line);
            }

            @Override
            public void onTransportConnected() {
                worker.post(() -> {
                    finishBindAttempt();
                    flushPendingDirectLossClear();
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

    void publishDirect(DirectTbtFrame frame, String reason, long receivedAtMs,
            String ownerPackage, long ownerSessionGeneration) {
        publishDirect(frame, reason, receivedAtMs, null, null,
                ownerPackage, ownerSessionGeneration);
    }

    void publishDirect(DirectTbtFrame frame, String reason, long receivedAtMs,
            GMapsDirectChannel.BitmapSelection bitmapSelection,
            Consumer<String> bitmapTxLogger,
            String ownerPackage, long ownerSessionGeneration) {
        worker.post(() -> {
            if (!claimDirectOwner(ownerPackage, ownerSessionGeneration)) return;
            directFrame = frame;
            preparedDirectFrame = null;
            preparedDirectPayload = null;
            preparedDirectSemanticPayload = null;
            directBitmapSelection = bitmapSelection;
            directBitmapTxLogger = bitmapTxLogger;
            directChannel = bitmapTxLogger == null ? "waze_direct" : "gmaps_direct";
            if (bitmapSelection == null) lastBitmapTxDiagnosticKey = "";
            pendingDirectReceivedAtMs = receivedAtMs;
            pendingDirectReason = safe(reason);
            if (directFirstFrameAtMs <= 0L) {
                directFirstFrameAtMs = receivedAtMs > 0L
                        ? receivedAtMs : SystemClock.elapsedRealtime();
            }
            directLossClearSent = false;
            renewDirectLeaseOnWorker(ownerPackage, ownerSessionGeneration, reason);
            if (activeSource == Source.DIRECT && !sendScheduled) {
                scheduleSend(0L);
            }
        });
    }

    void claimDirectOwnerForPromotion(String ownerPackage, long ownerSessionGeneration,
            String reason, Consumer<Boolean> completion) {
        worker.post(() -> {
            DirectPromotionDecision decision = directPromotionDecision(
                    directSelectedOnWorker(), directOwnerPackage,
                    directOwnerSessionGeneration, ownerPackage, ownerSessionGeneration);
            if (decision == DirectPromotionDecision.REPLACE_DORMANT) {
                invalidateDirectOwnerOnWorker("promotion:" + reason);
            }
            boolean claimed = decision != DirectPromotionDecision.REJECT
                    && claimDirectOwner(ownerPackage, ownerSessionGeneration);
            log("direct promotion owner=" + safe(ownerPackage)
                    + " session=" + ownerSessionGeneration
                    + " decision=" + decision
                    + " reason=" + safe(reason));
            runCompletion(completion, claimed);
        });
    }

    void clearDirectAlertAndRepublish(String ownerPackage, long ownerSessionGeneration,
            DirectTbtFrame frame, String reason, long receivedAtMs) {
        worker.post(() -> {
            if (!matchesDirectOwner(ownerPackage, ownerSessionGeneration)
                    || frame == null || (!directEnabled
                    && activeSource != Source.DIRECT
                    && pendingSource != Source.DIRECT)) {
                return;
            }
            directFrame = frame;
            preparedDirectFrame = null;
            preparedDirectPayload = null;
            preparedDirectSemanticPayload = null;
            directBitmapSelection = null;
            directBitmapTxLogger = null;
            lastBitmapTxDiagnosticKey = "";
            pendingDirectReceivedAtMs = receivedAtMs;
            pendingDirectReason = safe(reason);
            directAlertClearPending = true;
            renewDirectLeaseOnWorker(ownerPackage, ownerSessionGeneration, reason);
            if (activeSource == Source.DIRECT) {
                worker.removeCallbacks(sendLoop);
                sendScheduled = false;
                scheduleImmediate(reason);
            }
        });
    }

    void selectNavigationSource(Source source, String reason) {
        selectNavigationSource(source, reason, "", -1L);
    }

    void selectNavigationSource(Source source, String reason,
            String ownerPackage, long ownerSessionGeneration) {
        if (source != Source.DIRECT && source != Source.LEGACY && source != Source.NONE) {
            throw new IllegalArgumentException("navigation source=" + source);
        }
        worker.post(() -> {
            if (source == Source.DIRECT
                    && !matchesDirectOwner(ownerPackage, ownerSessionGeneration)) {
                log("direct source rejected owner=" + safe(ownerPackage)
                        + " session=" + ownerSessionGeneration);
                return;
            }
            if (source != Source.DIRECT) {
                invalidateDirectOwnerOnWorker("source=" + source + " reason=" + reason);
            }
            directEnabled = source == Source.DIRECT;
            legacyEnabled = source == Source.LEGACY;
            if (source != Source.DIRECT) {
                directAlertClearPending = false;
                lastBitmapTxDiagnosticKey = "";
            }
            if (source == Source.NONE) {
                directFrame = null;
                directBitmapSelection = null;
                directBitmapTxLogger = null;
                legacyState = null;
            }
            reconcile(reason);
        });
    }

    void endDirectOutput(String ownerPackage, long ownerSessionGeneration,
            String reason, long detectedAtMs) {
        endDirectOutput(ownerPackage, ownerSessionGeneration, reason, detectedAtMs, null);
    }

    void endDirectOutput(String ownerPackage, long ownerSessionGeneration,
            String reason, long detectedAtMs, Runnable firstClearCompletion) {
        worker.post(() -> {
            endDirectOutputOnWorker(ownerPackage, ownerSessionGeneration, reason,
                    detectedAtMs, firstClearCompletion, false);
        });
    }

    void endNavigationOutput(String ownerPackage, long ownerSessionGeneration,
            String reason, long detectedAtMs, Runnable firstClearCompletion) {
        worker.post(() -> {
            boolean directSelected = directSelectedOnWorker();
            if (directSelected) {
                endDirectOutputOnWorker(ownerPackage, ownerSessionGeneration, reason,
                        detectedAtMs, firstClearCompletion, true);
                return;
            }
            boolean legacySelected = legacyEnabled
                    || activeSource == Source.LEGACY
                    || pendingSource == Source.LEGACY;
            if (!legacySelected) {
                invalidateDormantDirectOwnerOnWorker(
                        ownerPackage, ownerSessionGeneration, "navigation-end:" + reason);
                reconcile(reason, detectedAtMs, firstClearCompletion);
                return;
            }
            legacyEnabled = false;
            legacyState = null;
            invalidateDirectOwnerOnWorker("navigation-end:" + reason);
            reconcile(reason, detectedAtMs, firstClearCompletion);
        });
    }

    private void endDirectOutputOnWorker(String ownerPackage, long ownerSessionGeneration,
            String reason, long detectedAtMs, Runnable firstClearCompletion,
            boolean administrativeStop) {
        if (!directSelectedOnWorker()) {
            invalidateDormantDirectOwnerOnWorker(
                    ownerPackage, ownerSessionGeneration, "end:" + reason);
            logAsync("direct end ignored reason=" + safe(reason)
                    + " detectedAtElapsedMs=" + detectedAtMs);
            reconcile(reason, detectedAtMs, firstClearCompletion);
            return;
        }
        if (!claimDirectOwner(ownerPackage, ownerSessionGeneration)) {
            logAsync("direct end rejected owner=" + safe(ownerPackage)
                    + " session=" + ownerSessionGeneration
                    + " reason=" + safe(reason));
            if (!administrativeStop) return;
            if (!shouldClearForAdministrativeStopForTest(
                    directOwnerPackage, ownerPackage)) {
                logAsync("direct administrative stop skipped; owner already moved current="
                        + safe(directOwnerPackage) + " requested=" + safe(ownerPackage));
                runCompletion(firstClearCompletion);
                return;
            }
            logAsync("direct administrative stop continuing with current output reason="
                    + safe(reason));
        }
        long now = SystemClock.elapsedRealtime();
        logAsync("direct end accepted reason=" + safe(reason)
                + " detectedAtElapsedMs=" + detectedAtMs
                + " workerHandoffMs=" + Math.max(0L, now - detectedAtMs));
        directEnabled = false;
        directFrame = null;
        preparedDirectFrame = null;
        preparedDirectPayload = null;
        preparedDirectSemanticPayload = null;
        directBitmapSelection = null;
        preparedDirectBitmapSelection = null;
        directBitmapTxLogger = null;
        preparedDirectBitmapTxLogger = null;
        lastBitmapTxDiagnosticKey = "";
        directAlertClearPending = false;
        directLossClearPending = false;
        directLossClearSent = false;
        pendingDirectReceivedAtMs = 0L;
        pendingDirectReason = "";
        invalidateDirectOwnerOnWorker("end:" + reason);
        reconcile(reason, detectedAtMs, firstClearCompletion);
    }

    void renewDirectLease(String ownerPackage, long ownerSessionGeneration, String reason) {
        worker.post(() -> {
            if (!matchesDirectOwner(ownerPackage, ownerSessionGeneration)) return;
            renewDirectLeaseOnWorker(ownerPackage, ownerSessionGeneration, reason);
        });
    }

    void clearDirectFrameForLoss(String ownerPackage, long ownerSessionGeneration,
            String reason, long detectedAtMs) {
        worker.post(() -> clearDirectFrameForLossOnWorker(
                ownerPackage, ownerSessionGeneration, reason, detectedAtMs));
    }

    void clearDirectFrameForSupersedingSession(String ownerPackage,
            long newSessionGeneration, String reason, long detectedAtMs) {
        worker.post(() -> {
            String owner = safe(ownerPackage);
            long previousSessionGeneration = directOwnerSessionGeneration;
            if (!shouldClearForSupersedingDirectSession(
                    directSelectedOnWorker(), directOwnerPackage,
                    previousSessionGeneration, owner, newSessionGeneration)) {
                return;
            }
            clearDirectFrameForLossOnWorker(
                    owner, previousSessionGeneration, reason, detectedAtMs);
        });
    }

    void resetTransport(String reason) {
        worker.post(() -> {
            Runnable interruptedCompletion = takePendingClearCompletions();
            generation++;
            cancelScheduledWork();
            stopServiceAndUnbind(reason);
            activeSource = Source.NONE;
            pendingSource = Source.NONE;
            pendingReason = "";
            pendingNeedsClear = false;
            pendingActivationNotBeforeMs = 0L;
            serviceStarted = false;
            directAlertClearPending = false;
            invalidateDirectOwnerOnWorker("transport-reset:" + reason);
            lastBitmapTxDiagnosticKey = "";
            HudDeliveryStatus.reset();
            runCompletion(interruptedCompletion);
            reconcile("reset:" + reason);
        });
    }

    void shutdown(String reason) {
        worker.post(() -> {
            boolean interruptedClear = finalClearInProgress
                    || pendingFinalClearCompletion != null
                    || pendingTransitionClearCompletion != null;
            Runnable interruptedCompletion = takePendingClearCompletions();
            manualEnabled = false;
            directEnabled = false;
            legacyEnabled = false;
            manualState = null;
            directFrame = null;
            directBitmapSelection = null;
            directBitmapTxLogger = null;
            lastBitmapTxDiagnosticKey = "";
            directAlertClearPending = false;
            invalidateDirectOwnerOnWorker("shutdown:" + reason);
            legacyState = null;
            if (interruptedClear) {
                generation++;
                cancelScheduledWork();
                stopServiceAndUnbind(reason);
                activeSource = Source.NONE;
                pendingSource = Source.NONE;
                pendingReason = "";
                pendingNeedsClear = false;
                pendingActivationNotBeforeMs = 0L;
                HudDeliveryStatus.reset();
                runCompletion(interruptedCompletion);
            } else {
                reconcile(reason);
            }
        });
    }

    private void reconcile(String reason) {
        reconcile(reason, 0L, null);
    }

    private void reconcile(String reason, long endDetectedAtMs) {
        reconcile(reason, endDetectedAtMs, null);
    }

    private void reconcile(String reason, long endDetectedAtMs,
            Runnable firstClearCompletion) {
        Source target = desiredSource();
        if (pendingSource == Source.NONE && target == activeSource) {
            if (target == Source.NONE && pendingFinalClearCompletion != null) {
                pendingFinalClearCompletion = chainCompletions(
                        pendingFinalClearCompletion, firstClearCompletion);
                return;
            }
            runCompletion(firstClearCompletion);
            if (target == Source.NONE && client.hasBinding()) {
                stopServiceAndUnbind(reason);
                HudDeliveryStatus.reset();
            }
            return;
        }
        if (pendingSource == target && target != Source.NONE) {
            pendingTransitionClearCompletion = chainCompletions(
                    pendingTransitionClearCompletion, firstClearCompletion);
            if (!pendingNeedsClear) completeTransitionClear();
            return;
        }
        int transitionGeneration = ++generation;
        cancelScheduledWork();
        boolean unfinishedFinalClear = pendingFinalClearCompletion != null;
        Runnable carriedCompletion = chainCompletions(
                pendingFinalClearCompletion, pendingTransitionClearCompletion);
        pendingFinalClearCompletion = null;
        pendingTransitionClearCompletion = null;
        finalClearInProgress = false;
        finalClearStartedAtMs = 0L;
        Runnable clearCompletion = chainCompletions(carriedCompletion, firstClearCompletion);
        Source previous = activeSource;
        boolean stillNeedsClear = pendingNeedsClear || unfinishedFinalClear;
        long existingBarrierDeadline = pendingActivationNotBeforeMs;
        activeSource = Source.NONE;
        pendingSource = Source.NONE;
        pendingReason = "";
        pendingNeedsClear = false;
        pendingActivationNotBeforeMs = 0L;
        HudDeliveryStatus.reset();
        if (target == Source.NONE) {
            pendingFinalClearCompletion = clearCompletion;
            pendingFinalClearSucceeded = false;
            finalClearInProgress = previous != Source.NONE;
            finalClearStartedAtMs = finalClearInProgress
                    ? SystemClock.elapsedRealtime() : 0L;
            beginFinalStop(previous, reason, transitionGeneration, 1,
                    endDetectedAtMs);
            return;
        }
        pendingTransitionClearCompletion = clearCompletion;
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
        if (protocolRetryScheduled) {
            return;
        }
        if (!pendingNeedsClear) {
            completeTransitionClear();
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
        completeTransitionClear();
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
        if (!client.isBound()) {
            ensureBoundOnWorker(reason);
        }
        if (target == Source.DIRECT && directLossClearPending) {
            if (!client.isBound()) {
                return;
            }
            flushPendingDirectLossClear();
            if (directLossClearPending) return;
        }
        if (!hasFrame(target)) {
            HudDeliveryStatus.reset();
            log("waiting source=" + target + " reason=" + reason);
            return;
        }
        if (!client.isBound()) return;
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
            finishBindAttempt();
            flushPendingDirectLossClear();
            resumeActivation(reason);
            return;
        }
        if (!client.hasBinding()) {
            beginBindAttempt(reason);
            return;
        }
        scheduleBindCheck(bindGeneration);
    }

    private void beginBindAttempt(String reason) {
        int attemptGeneration = ++bindGeneration;
        bindDeadlineMs = SystemClock.elapsedRealtime() + BIND_TIMEOUT_MS;
        bindCheckScheduled = false;
        client.bind();
        log("bind requested generation=" + attemptGeneration + " reason=" + reason);
        scheduleBindCheck(attemptGeneration);
    }

    private void scheduleBindCheck(int attemptGeneration) {
        if (bindCheckScheduled || attemptGeneration != bindGeneration) return;
        bindCheckScheduled = true;
        worker.postDelayed(() -> checkBindAttempt(attemptGeneration), BIND_RETRY_MS);
    }

    private void checkBindAttempt(int attemptGeneration) {
        if (attemptGeneration != bindGeneration) return;
        bindCheckScheduled = false;
        if (desiredSource() == Source.NONE
                && activeSource == Source.NONE
                && pendingSource == Source.NONE) {
            finishBindAttempt();
            return;
        }
        if (client.isBound()) {
            finishBindAttempt();
            resumeActivation("bind-ready");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (bindDeadlineReachedForTest(bindDeadlineMs, now)) {
            HudDeliveryStatus.recordFailure();
            log("bind timeout generation=" + attemptGeneration
                    + " elapsedMs=" + BIND_TIMEOUT_MS);
            client.unbind();
            finishBindAttempt();
            worker.postDelayed(transportRecovery, DEFAULT_INTERVAL_MS);
            return;
        }
        scheduleBindCheck(attemptGeneration);
    }

    private void finishBindAttempt() {
        bindDeadlineMs = 0L;
        bindCheckScheduled = false;
    }

    static boolean bindDeadlineReachedForTest(long deadlineMs, long nowMs) {
        return deadlineMs > 0L && nowMs >= deadlineMs;
    }

    private void resumeActivation(String reason) {
        if (pendingSource != Source.NONE) {
            continuePendingTransition(generation);
        } else {
            activateDesired(reason);
        }
    }

    private void scheduleImmediate(String reason) {
        if (protocolRetryScheduled) {
            return;
        }
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
        if (protocolRetryScheduled
                || activeSource == Source.NONE
                || !hasFrame(activeSource)) {
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
                int startResult = startService(source, channelFor(source), reason);
                if (!isStartReadyResult(startResult)) {
                    handleProtocolResult("start source=" + source, startResult);
                    return;
                }
                serviceStarted = true;
                if (source == Source.DIRECT) {
                    directServiceReadyAtMs = SystemClock.elapsedRealtime();
                }
                log("service ready source=" + source + " result=" + startResult);
            }
            if (source == Source.DIRECT && directLossClearPending) {
                if (!sendRequiredClear("direct producer loss", source, "direct_loss_clear")) {
                    return;
                }
                directLossClearPending = false;
                directLossClearSent = true;
            }
            if (source == Source.DIRECT && directAlertClearPending) {
                if (!sendRequiredClear(
                        "direct alert preference disabled", source, "alert_clear")) {
                    return;
                }
                directAlertClearPending = false;
            }
            byte[] payload = buildPayload(source);
            byte[] semanticPayload = source == Source.DIRECT
                    ? preparedDirectSemanticPayload : payload;
            int result = sendPayload(source, channelFor(source), "payload", reason,
                    payload, semanticPayload);
            long duration = SystemClock.elapsedRealtime() - startedAt;
            sendCount++;
            sendDurationMs += duration;
            HudDeliveryStatus.recordNonClearResult(result);
            if (!isPayloadSuccessResult(result)) {
                handleProtocolResult("send source=" + source, result);
                return;
            }
            recordPayloadSuccess();
            if (source == Source.DIRECT) {
                emitBitmapTxAfterSuccess(SystemClock.elapsedRealtime());
            }
            maybeLogStats(source, payload.length, duration);
            if (source == Source.DIRECT) {
                long sentAtMs = SystemClock.elapsedRealtime();
                if (!directFirstPhysicalSendLogged && directFirstFrameAtMs > 0L) {
                    long serviceReadyAtMs = directServiceReadyAtMs > 0L
                            ? directServiceReadyAtMs : sentAtMs;
                    log("direct first_physical_send firstFrameToServiceReadyMs="
                            + Math.max(0L, serviceReadyAtMs - directFirstFrameAtMs)
                            + " serviceReadyToSendMs="
                            + Math.max(0L, sentAtMs - serviceReadyAtMs)
                            + " firstFrameToSendMs="
                            + Math.max(0L, sentAtMs - directFirstFrameAtMs));
                    directFirstPhysicalSendLogged = true;
                }
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
        } catch (RemoteException e) {
            sendFailures++;
            handleTransportFailure("send source=" + source, e);
        } catch (RuntimeException e) {
            handleProtocolException("send source=" + source, e);
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
                preparedDirectSemanticPayload = preparedDirectPayload.build(0);
                preparedDirectBitmapSelection = directBitmapSelection;
                preparedDirectBitmapTxLogger = directBitmapTxLogger;
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

    private void emitBitmapTxAfterSuccess(long sentAtMs) {
        String key = bitmapTxDiagnosticKey(
                0, preparedDirectBitmapSelection, preparedDirectPayload);
        if (key.isEmpty()) {
            if (preparedDirectPayload != null
                    && preparedDirectPayload.maneuverPngBytes() == 0) {
                lastBitmapTxDiagnosticKey = "";
            }
            return;
        }
        if (!shouldEmitBitmapTx(lastBitmapTxDiagnosticKey, key)) return;
        lastBitmapTxDiagnosticKey = key;
        String message = preparedDirectBitmapSelection.txMessage(
                preparedDirectPayload, sentAtMs);
        try {
            if (preparedDirectBitmapTxLogger != null) {
                preparedDirectBitmapTxLogger.accept(message);
            } else {
                log(message);
            }
        } catch (RuntimeException error) {
            log("bitmap_tx log failed error=" + error.getClass().getSimpleName());
        }
    }

    static String bitmapTxDiagnosticKey(int sendResult,
            GMapsDirectChannel.BitmapSelection selection,
            DirectTbtPayload.Prepared prepared) {
        if (sendResult != 0 || selection == null || prepared == null) return "";
        return selection.txKey(prepared);
    }

    static boolean shouldEmitBitmapTx(String lastKey, String nextKey) {
        return nextKey != null && !nextKey.isEmpty() && !nextKey.equals(lastKey);
    }

    private void beginFinalStop(Source previous, String reason, int stopGeneration, int frame,
                                long endDetectedAtMs) {
        if (stopGeneration != generation) {
            return;
        }
        if (previous == Source.NONE) {
            pendingFinalClearSucceeded = true;
            finalClearInProgress = false;
            finalClearStartedAtMs = 0L;
            completeFinalClear();
            stopServiceAndUnbind(reason);
            HudDeliveryStatus.reset();
            return;
        }
        if (finalClearTimedOutForTest(
                finalClearStartedAtMs, SystemClock.elapsedRealtime())) {
            abortFinalClearAfterTimeout(previous, reason);
            return;
        }
        if (!client.isBound()) {
            if (!client.hasBinding()) {
                client.bind();
                log("final clear bind requested reason=" + reason);
            }
            worker.postDelayed(
                    () -> beginFinalStop(previous, reason, stopGeneration, frame,
                            endDetectedAtMs),
                    BIND_RETRY_MS);
            return;
        }
        if (!serviceStarted) {
            try {
                int startResult = startService(previous, channelFor(previous),
                        "final-clear:" + reason);
                if (!isStartReadyResult(startResult)) {
                    worker.postDelayed(
                            () -> beginFinalStop(previous, reason, stopGeneration, frame,
                                    endDetectedAtMs),
                            DEFAULT_INTERVAL_MS);
                    return;
                }
                serviceStarted = true;
            } catch (RemoteException | RuntimeException error) {
                log("final clear start failed reason=" + reason
                        + " error=" + safe(error.getMessage()));
                worker.postDelayed(
                        () -> beginFinalStop(previous, reason, stopGeneration, frame,
                                endDetectedAtMs),
                        DEFAULT_INTERVAL_MS);
                return;
            }
        }
        long clearedAtMs = sendClearBestEffort(
                "final " + previous + " frame=" + frame + " reason=" + reason,
                previous, "final_clear");
        if (clearedAtMs >= 0L) {
            pendingFinalClearSucceeded = true;
        }
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
        if (!pendingFinalClearSucceeded) {
            log("final clear retry reason=" + reason);
            long retryEndDetectedAtMs = remainingEndDetectedAtMs;
            worker.postDelayed(
                    () -> beginFinalStop(previous, reason, stopGeneration, 1,
                            retryEndDetectedAtMs),
                    DEFAULT_INTERVAL_MS);
            return;
        }
        worker.postDelayed(() -> {
            if (stopGeneration != generation) {
                return;
            }
            finalClearInProgress = false;
            finalClearStartedAtMs = 0L;
            pendingFinalClearSucceeded = false;
            stopServiceAndUnbind(reason);
            HudDeliveryStatus.reset();
            completeFinalClear();
        }, FINAL_STOP_DELAY_MS);
    }

    private void abortFinalClearAfterTimeout(Source previous, String reason) {
        log("final clear timeout; hard transport reset source=" + previous
                + " reason=" + reason);
        cancelScheduledWork();
        stopServiceAndUnbind("final-clear-timeout:" + reason);
        activeSource = Source.NONE;
        pendingSource = Source.NONE;
        pendingReason = "";
        pendingNeedsClear = false;
        pendingActivationNotBeforeMs = 0L;
        finalClearInProgress = false;
        finalClearStartedAtMs = 0L;
        pendingFinalClearSucceeded = false;
        HudDeliveryStatus.reset();
        completeFinalClear();
    }

    static boolean finalClearTimedOutForTest(long startedAtMs, long nowMs) {
        return startedAtMs > 0L && nowMs - startedAtMs >= FINAL_CLEAR_TIMEOUT_MS;
    }

    private boolean sendTransitionClear() {
        if (!client.isBound()) {
            return false;
        }
        try {
            if (!serviceStarted) {
                int startResult = startService(
                        pendingSource, channelFor(pendingSource), "transition-clear");
                if (!isStartReadyResult(startResult)) {
                    handleProtocolResult("transition-clear start", startResult);
                    return false;
                }
                serviceStarted = true;
                if (pendingSource == Source.DIRECT) {
                    directServiceReadyAtMs = SystemClock.elapsedRealtime();
                }
            }
            if (!sendRequiredClear(
                    pendingReason, pendingSource, "transition_clear")) {
                return false;
            }
            log("transition clear result=0 target=" + pendingSource
                    + " reason=" + pendingReason);
            return true;
        } catch (RemoteException e) {
            handleTransportFailure("transition-clear", e);
            return false;
        } catch (RuntimeException e) {
            handleProtocolException("transition-clear", e);
            return false;
        }
    }

    private static void runCompletion(Runnable completion) {
        if (completion == null) return;
        try {
            completion.run();
        } catch (RuntimeException ignored) {
        }
    }

    private static void runCompletion(Consumer<Boolean> completion, boolean result) {
        if (completion == null) return;
        try {
            completion.accept(result);
        } catch (RuntimeException ignored) {
        }
    }

    private static Runnable chainCompletions(Runnable first, Runnable second) {
        if (first == null) return second;
        if (second == null) return first;
        return () -> {
            runCompletion(first);
            runCompletion(second);
        };
    }

    private void completeTransitionClear() {
        Runnable completion = pendingTransitionClearCompletion;
        pendingTransitionClearCompletion = null;
        runCompletion(completion);
    }

    private void completeFinalClear() {
        Runnable completion = pendingFinalClearCompletion;
        pendingFinalClearCompletion = null;
        runCompletion(completion);
    }

    private Runnable takePendingClearCompletions() {
        Runnable completion = chainCompletions(
                pendingFinalClearCompletion, pendingTransitionClearCompletion);
        pendingFinalClearCompletion = null;
        pendingTransitionClearCompletion = null;
        pendingFinalClearSucceeded = false;
        finalClearInProgress = false;
        finalClearStartedAtMs = 0L;
        return completion;
    }

    private boolean sendRequiredClear(String reason, Source source, String kind)
            throws RemoteException {
        byte[] payload = DirectTbtPayload.buildClear();
        int result = sendPayload(source, channelFor(source), kind, reason, payload, payload);
        logAsync("clear result=" + result + " reason=" + reason);
        if (!isPayloadSuccessResult(result)) {
            handleProtocolResult(kind, result);
            return false;
        }
        recordPayloadSuccess();
        return true;
    }

    private long sendClearBestEffort(String reason, Source source, String kind) {
        if (!client.isBound()) {
            return -1L;
        }
        try {
            byte[] payload = DirectTbtPayload.buildClear();
            int result = sendPayload(source, channelFor(source), kind, reason, payload, payload);
            long completedAtMs = SystemClock.elapsedRealtime();
            logAsync("clear result=" + result + " reason=" + reason);
            return result == 0 ? completedAtMs : -1L;
        } catch (RemoteException | RuntimeException e) {
            logAsync("clear failed reason=" + reason + " error=" + safe(e.getMessage()));
            return -1L;
        }
    }

    private void stopServiceAndUnbind(String reason) {
        ++bindGeneration;
        finishBindAttempt();
        worker.removeCallbacks(transportRecovery);
        worker.removeCallbacks(protocolRetry);
        protocolRetryScheduled = false;
        protocolFailureCount = 0;
        if (serviceStarted && client.isBound()) {
            long startedAtMs = SystemClock.elapsedRealtime();
            try {
                int result = client.stop();
                txLog.recordLifecycle("service_stop", sourceName(lastTransportSource),
                        lastTransportChannel, reason, result, "",
                        SystemClock.elapsedRealtime() - startedAtMs);
            } catch (RemoteException | RuntimeException e) {
                txLog.recordLifecycle("service_stop", sourceName(lastTransportSource),
                        lastTransportChannel, reason, null,
                        e.getClass().getSimpleName() + ":" + safe(e.getMessage()),
                        SystemClock.elapsedRealtime() - startedAtMs);
                log("stop failed reason=" + reason + " error=" + safe(e.getMessage()));
            }
        }
        serviceStarted = false;
        client.unbind();
        directCounter = 0;
        lastTransportSource = Source.NONE;
        lastTransportChannel = "transport";
        log("transport stopped reason=" + reason);
    }

    private int startService(Source source, String channel, String reason) throws RemoteException {
        long startedAtMs = SystemClock.elapsedRealtime();
        lastTransportSource = source;
        lastTransportChannel = channel;
        try {
            int result = client.start();
            txLog.recordLifecycle("service_start", sourceName(source), channel,
                    reason, result, "", SystemClock.elapsedRealtime() - startedAtMs);
            return result;
        } catch (RemoteException | RuntimeException e) {
            txLog.recordLifecycle("service_start", sourceName(source), channel,
                    reason, null, e.getClass().getSimpleName() + ":" + safe(e.getMessage()),
                    SystemClock.elapsedRealtime() - startedAtMs);
            throw e;
        }
    }

    private int sendPayload(
            Source source,
            String channel,
            String kind,
            String reason,
            byte[] payload,
            byte[] semanticPayload) throws RemoteException {
        long startedAtMs = SystemClock.elapsedRealtime();
        lastTransportSource = source;
        lastTransportChannel = channel;
        try {
            int result = client.send(payload);
            txLog.recordSend(sourceName(source), channel, SomeIpHudClient.HUD_ROAD_INFO_TOPIC,
                    kind, reason, payload, semanticPayload, result, "",
                    SystemClock.elapsedRealtime() - startedAtMs);
            return result;
        } catch (RemoteException | RuntimeException e) {
            txLog.recordSend(sourceName(source), channel, SomeIpHudClient.HUD_ROAD_INFO_TOPIC,
                    kind, reason, payload, semanticPayload, null,
                    e.getClass().getSimpleName() + ":" + safe(e.getMessage()),
                    SystemClock.elapsedRealtime() - startedAtMs);
            throw e;
        }
    }

    private String channelFor(Source source) {
        if (source == Source.DIRECT) return directChannel;
        if (source == Source.LEGACY) return "legacy";
        if (source == Source.MANUAL) return "manual";
        return "transport";
    }

    private static String sourceName(Source source) {
        if (source == Source.DIRECT) return "direct";
        if (source == Source.LEGACY) return "legacy";
        if (source == Source.MANUAL) return "manual";
        return "none";
    }

    private void handleTransportFailure(String reason, Throwable error) {
        HudDeliveryStatus.recordFailure();
        serviceStarted = false;
        sendScheduled = false;
        protocolRetryScheduled = false;
        protocolFailureCount = 0;
        directServiceReadyAtMs = 0L;
        directFirstPhysicalSendLogged = false;
        worker.removeCallbacks(sendLoop);
        ++bindGeneration;
        finishBindAttempt();
        worker.removeCallbacks(transportRecovery);
        worker.removeCallbacks(protocolRetry);
        client.unbind();
        log("transport failure reason=" + reason
                + (error == null ? "" : " error=" + error.getClass().getSimpleName()
                + ":" + safe(error.getMessage())));
        if (desiredSource() != Source.NONE) {
            worker.postDelayed(transportRecovery, DEFAULT_INTERVAL_MS);
        }
    }

    private void handleProtocolResult(String reason, int result) {
        sendFailures++;
        if (resultMarksServiceUnstarted(result)) {
            serviceStarted = false;
        }
        handleProtocolFailure(reason + " result=" + result);
    }

    private void handleProtocolException(String reason, RuntimeException error) {
        sendFailures++;
        handleProtocolFailure(reason + " error=" + error.getClass().getSimpleName()
                + ":" + safe(error.getMessage()));
    }

    private void handleProtocolFailure(String detail) {
        HudDeliveryStatus.recordFailure();
        sendScheduled = false;
        worker.removeCallbacks(sendLoop);
        if (protocolRetryScheduled || desiredSource() == Source.NONE) {
            return;
        }
        protocolFailureCount++;
        long delayMs = protocolRetryDelayMs(protocolFailureCount);
        protocolRetryScheduled = true;
        worker.postDelayed(protocolRetry, delayMs);
        log("protocol backoff delayMs=" + delayMs + " " + detail);
    }

    private void recordPayloadSuccess() {
        protocolFailureCount = 0;
        protocolRetryScheduled = false;
        worker.removeCallbacks(protocolRetry);
    }

    static boolean isStartReadyResult(int result) {
        return result == RESULT_OK || result == RESULT_ALREADY_STARTED;
    }

    static boolean isPayloadSuccessResult(int result) {
        return result == RESULT_OK;
    }

    static boolean resultMarksServiceUnstarted(int result) {
        return result == RESULT_NOT_STARTED;
    }

    static long protocolRetryDelayMs(int failureCount) {
        int index = Math.max(1, failureCount) - 1;
        return PROTOCOL_RETRY_DELAYS_MS[Math.min(index, PROTOCOL_RETRY_DELAYS_MS.length - 1)];
    }

    private boolean directSelectedOnWorker() {
        return directEnabled
                || activeSource == Source.DIRECT
                || pendingSource == Source.DIRECT;
    }

    private boolean claimDirectOwner(String ownerPackage, long ownerSessionGeneration) {
        String owner = safe(ownerPackage);
        DirectOwnerDecision decision = directOwnerDecision(
                directOwnerPackage, directOwnerSessionGeneration,
                owner, ownerSessionGeneration);
        if (decision == DirectOwnerDecision.REJECT) {
            log("direct owner rejected owner=" + owner
                    + " session=" + ownerSessionGeneration);
            return false;
        }
        if (decision == DirectOwnerDecision.ADVANCE) {
            boolean preservePendingLossClear = shouldPreservePendingLossClearOnAdvance(
                    directLossClearPending, directOwnerPackage,
                    directOwnerSessionGeneration, owner, ownerSessionGeneration);
            worker.removeCallbacks(directLeaseExpiry);
            directLeaseDeadlineMs = 0L;
            directLossClearPending = preservePendingLossClear;
            directLossClearSent = false;
            directFrame = null;
            preparedDirectFrame = null;
            preparedDirectPayload = null;
            preparedDirectSemanticPayload = null;
            directBitmapSelection = null;
            preparedDirectBitmapSelection = null;
            directBitmapTxLogger = null;
            preparedDirectBitmapTxLogger = null;
            directAlertClearPending = false;
            lastBitmapTxDiagnosticKey = "";
            pendingDirectReceivedAtMs = 0L;
            pendingDirectReason = "";
            directOwnerPackage = owner;
            directOwnerSessionGeneration = ownerSessionGeneration;
            resetDirectTransportTiming();
            log("direct owner advanced owner=" + owner
                    + " session=" + ownerSessionGeneration);
        }
        return true;
    }

    static DirectOwnerDecision directOwnerDecision(
            String currentOwner, long currentGeneration,
            String incomingOwner, long incomingGeneration) {
        String current = safe(currentOwner);
        String incoming = safe(incomingOwner);
        if (incoming.isEmpty() || incomingGeneration < 0L) {
            return DirectOwnerDecision.REJECT;
        }
        if (current.isEmpty()) return DirectOwnerDecision.ADVANCE;
        if (!current.equals(incoming) || incomingGeneration < currentGeneration) {
            return DirectOwnerDecision.REJECT;
        }
        return incomingGeneration == currentGeneration
                ? DirectOwnerDecision.ACCEPT
                : DirectOwnerDecision.ADVANCE;
    }

    static boolean shouldPreservePendingLossClearOnAdvance(boolean clearPending,
            String currentOwner, long currentGeneration,
            String incomingOwner, long incomingGeneration) {
        return clearPending
                && safe(currentOwner).equals(safe(incomingOwner))
                && incomingGeneration > currentGeneration;
    }

    static DirectPromotionDecision directPromotionDecision(
            boolean directSelected, String currentOwner, long currentGeneration,
            String incomingOwner, long incomingGeneration) {
        DirectOwnerDecision ownerDecision = directOwnerDecision(
                currentOwner, currentGeneration, incomingOwner, incomingGeneration);
        if (ownerDecision != DirectOwnerDecision.REJECT) {
            return DirectPromotionDecision.CLAIM;
        }
        String current = safe(currentOwner);
        String incoming = safe(incomingOwner);
        if (!directSelected && !current.isEmpty() && !incoming.isEmpty()
                && incomingGeneration >= 0L && !current.equals(incoming)) {
            return DirectPromotionDecision.REPLACE_DORMANT;
        }
        return DirectPromotionDecision.REJECT;
    }

    static boolean shouldClearForAdministrativeStopForTest(
            String currentOwner, String requestedOwner) {
        String current = safe(currentOwner);
        return current.isEmpty() || current.equals(safe(requestedOwner));
    }

    enum DirectOwnerDecision {
        REJECT,
        ACCEPT,
        ADVANCE
    }

    enum DirectPromotionDecision {
        REJECT,
        CLAIM,
        REPLACE_DORMANT
    }

    private boolean matchesDirectOwner(String ownerPackage, long ownerSessionGeneration) {
        return matchesDirectOwnerForTest(
                directOwnerPackage, directOwnerSessionGeneration,
                ownerPackage, ownerSessionGeneration);
    }

    static boolean matchesDirectOwnerForTest(
            String currentOwner, long currentGeneration,
            String incomingOwner, long incomingGeneration) {
        String current = safe(currentOwner);
        return !current.isEmpty()
                && current.equals(safe(incomingOwner))
                && currentGeneration == incomingGeneration;
    }

    static boolean shouldInvalidateDormantDirectOwnerForTest(
            boolean directSelected, String currentOwner, String requestedOwner) {
        String current = safe(currentOwner);
        return !directSelected && !current.isEmpty()
                && current.equals(safe(requestedOwner));
    }

    private void renewDirectLeaseOnWorker(String ownerPackage,
            long ownerSessionGeneration, String reason) {
        if (!matchesDirectOwner(ownerPackage, ownerSessionGeneration)) return;
        long now = SystemClock.elapsedRealtime();
        directLeaseDeadlineMs = now + DIRECT_LEASE_MS;
        worker.removeCallbacks(directLeaseExpiry);
        worker.postDelayed(directLeaseExpiry, DIRECT_LEASE_MS);
        if (!safe(reason).isEmpty()) {
            log("direct lease renewed owner=" + safe(ownerPackage)
                    + " session=" + ownerSessionGeneration
                    + " reason=" + safe(reason));
        }
    }

    private void expireDirectLease() {
        if (directLeaseDeadlineMs <= 0L) return;
        long now = SystemClock.elapsedRealtime();
        if (!isDirectLeaseExpired(directLeaseDeadlineMs, now)) {
            worker.postDelayed(directLeaseExpiry, directLeaseDeadlineMs - now);
            return;
        }
        String owner = directOwnerPackage;
        long session = directOwnerSessionGeneration;
        directLeaseDeadlineMs = 0L;
        clearDirectFrameForLossOnWorker(owner, session, "direct-lease-expired", now);
    }

    private void clearDirectFrameForLossOnWorker(String ownerPackage,
            long ownerSessionGeneration, String reason, long detectedAtMs) {
        if (!matchesDirectOwner(ownerPackage, ownerSessionGeneration)
                || !directSelectedOnWorker()) {
            return;
        }
        if (!shouldQueueDirectLossClear(
                true, true, directLossClearSent, directLossClearPending)) return;
        directFrame = null;
        preparedDirectFrame = null;
        preparedDirectPayload = null;
        preparedDirectSemanticPayload = null;
        directBitmapSelection = null;
        preparedDirectBitmapSelection = null;
        directBitmapTxLogger = null;
        preparedDirectBitmapTxLogger = null;
        lastBitmapTxDiagnosticKey = "";
        directAlertClearPending = false;
        pendingDirectReceivedAtMs = 0L;
        pendingDirectReason = "";
        worker.removeCallbacks(directLeaseExpiry);
        directLeaseDeadlineMs = 0L;
        directLossClearPending = true;
        logAsync("direct producer loss owner=" + safe(ownerPackage)
                + " session=" + ownerSessionGeneration
                + " reason=" + safe(reason)
                + " detectedAtElapsedMs=" + detectedAtMs);
        if (client.isBound()) {
            flushPendingDirectLossClear();
        }
        if (directLossClearPending) {
            ensureBoundOnWorker("direct-loss-clear");
        }
    }

    static boolean shouldQueueDirectLossClear(boolean ownerAccepted, boolean directSelected,
            boolean clearSent, boolean clearPending) {
        return ownerAccepted && directSelected && !clearSent && !clearPending;
    }

    static boolean shouldClearForSupersedingDirectSession(boolean directSelected,
            String currentOwner, long currentGeneration,
            String incomingOwner, long incomingGeneration) {
        return directSelected
                && safe(currentOwner).equals(safe(incomingOwner))
                && incomingGeneration > currentGeneration;
    }

    static boolean isDirectLeaseExpired(long deadlineMs, long nowMs) {
        return deadlineMs > 0L && nowMs >= deadlineMs;
    }

    private boolean flushPendingDirectLossClear() {
        if (!directLossClearPending || !directSelectedOnWorker() || !client.isBound()) {
            return false;
        }
        try {
            if (!serviceStarted) {
                int startResult = startService(
                        Source.DIRECT, channelFor(Source.DIRECT), "direct-loss-clear");
                if (!isStartReadyResult(startResult)) {
                    handleProtocolResult("direct-loss-clear start", startResult);
                    return false;
                }
                serviceStarted = true;
            }
            if (!sendRequiredClear("direct producer loss", Source.DIRECT,
                    "direct_loss_clear")) {
                return false;
            }
            directLossClearPending = false;
            directLossClearSent = true;
            return true;
        } catch (RemoteException | RuntimeException error) {
            logAsync("direct loss clear failed error=" + safe(error.getMessage()));
            return false;
        }
    }

    private void invalidateDirectOwnerOnWorker(String reason) {
        worker.removeCallbacks(directLeaseExpiry);
        directLeaseDeadlineMs = 0L;
        directOwnerPackage = "";
        directOwnerSessionGeneration = Long.MIN_VALUE;
        directLossClearPending = false;
        directLossClearSent = false;
        resetDirectTransportTiming();
        log("direct owner invalidated reason=" + safe(reason));
    }

    private void invalidateDormantDirectOwnerOnWorker(String ownerPackage,
            long ownerSessionGeneration, String reason) {
        if (shouldInvalidateDormantDirectOwnerForTest(
                directSelectedOnWorker(), directOwnerPackage, ownerPackage)) {
            invalidateDirectOwnerOnWorker("dormant:" + reason);
        }
    }

    private void resetDirectTransportTiming() {
        directFirstFrameAtMs = 0L;
        directServiceReadyAtMs = 0L;
        directFirstPhysicalSendLogged = false;
    }

    private void cancelScheduledWork() {
        worker.removeCallbacks(sendLoop);
        ++bindGeneration;
        finishBindAttempt();
        worker.removeCallbacks(transportRecovery);
        worker.removeCallbacks(protocolRetry);
        sendScheduled = false;
        protocolRetryScheduled = false;
        protocolFailureCount = 0;
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
