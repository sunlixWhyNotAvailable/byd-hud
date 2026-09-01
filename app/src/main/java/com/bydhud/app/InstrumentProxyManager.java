package com.bydhud.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/** Owns the bounded startup, handoff, liveness, and restart policy for the shell helper. */
final class InstrumentProxyManager {
    private static final String TAG = "BydHudInstrumentProxy";
    private static final long START_TIMEOUT_MS = 5_000L;
    private static final long CALL_TIMEOUT_MS = 3_000L;
    private static final long PING_TIMEOUT_MS = 2_000L;
    private static final long RETRY_DELAY_MS = 30_000L;
    private static final long STABLE_CONNECTION_MS = 30_000L;
    private static final int SHELL_UID = 2_000;
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final Object INSTANCE_LOCK = new Object();
    @SuppressLint("StaticFieldLeak")
    private static InstrumentProxyManager instance;

    private enum State {
        IDLE,
        STARTING,
        READY,
        BLOCKED
    }

    enum CapabilityMode {
        NONE,
        FID_ONLY,
        SDK_ONLY,
        FULL
    }

    private final Context context;
    private final Object lock = new Object();
    private final Object callLock = new Object();
    private final Object receiverLock = new Object();
    private final ScheduledExecutorService worker;
    private ExecutorService calls;
    private long callEpoch;
    private long activeOperationId;
    private long activeOperationGeneration;
    private ResultCallback activeOperationCallback;
    private boolean activeOperationTerminal;
    private PendingGuidance pendingGuidance;
    private PendingGuidance deferredGuidance;
    private boolean guidanceScheduled;
    private boolean guidanceBarrierActive;
    private boolean terminalClearInFlight;
    private long guidanceBarrierToken;
    private final List<ResultCallback> terminalClearCallbacks = new ArrayList<>();
    private PendingTrafficLight pendingTrafficLight;
    private PendingTrafficLight deferredTrafficLight;
    private boolean trafficLightScheduled;
    private boolean trafficLightBarrierActive;
    private long trafficLightBarrierToken;
    private final List<ResultCallback> trafficLightClearCallbacks = new ArrayList<>();
    private final AtomicLong generationCounter = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong operationCounter = new AtomicLong();
    private final SecureRandom random = new SecureRandom();
    private final CopyOnWriteArrayList<Runnable> readyListeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<UnavailableListener> unavailableListeners =
            new CopyOnWriteArrayList<>();
    private final InstrumentProxyReceiver handoffReceiver = new InstrumentProxyReceiver();
    private boolean handoffReceiverRegistered;
    private final IInstrumentNavigationClient client =
            new IInstrumentNavigationClient.Stub() {
                @Override
                public void onProxyConnected(long generation, Bundle result) {
                    if (!isShellCallback()) return;
                    worker.execute(() -> completeConnect(generation, result));
                }

                @Override
                public void onProxyPong(long generation, long token) {
                    if (!isShellCallback()) return;
                    worker.execute(() -> completePing(token, generation, null, true));
                }

                @Override
                public void onProxyStopping(long generation, String reason) {
                    if (!isShellCallback()) return;
                    worker.execute(() -> handleRemoteStop(generation, reason));
                }
            };

    private State state = State.IDLE;
    private IInstrumentNavigationProxy proxy;
    private IBinder proxyBinder;
    private IInstrumentNavigationProxy connectingProxy;
    private IBinder connectingBinder;
    private long generation;
    private String nonce = "";
    private String launchToken = "";
    private long connectedAtMs;
    private long nextRetryAtMs;
    private int rapidFailureCount;
    private boolean runtimeActive;
    private final OutputRetry outputRetry = new OutputRetry();
    private String startStage = "idle";
    private boolean pingInFlight;
    private long pingToken;
    private InstrumentProxyStore.Identity helperIdentity =
            InstrumentProxyStore.Identity.none();
    private boolean helperIdentityLoaded;
    private CapabilityMode capabilityMode = CapabilityMode.NONE;
    private boolean trafficLightCapable;

    static InstrumentProxyManager get(Context context) {
        synchronized (INSTANCE_LOCK) {
            if (instance == null) {
                instance = new InstrumentProxyManager(context.getApplicationContext());
            }
            return instance;
        }
    }

    private InstrumentProxyManager(Context context) {
        this.context = context;
        this.worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BydHudInstrumentProxy");
            thread.setDaemon(true);
            return thread;
        });
        this.calls = newCallExecutor(0L);
    }

    void ensureStarted(String reason) {
        long requestGeneration;
        String requestNonce;
        String requestLaunchToken;
        IInstrumentNavigationProxy staleProxy = null;
        long staleGeneration = 0L;
        synchronized (lock) {
            if (HudPrefs.isUserShutdownActive(context)) {
                runtimeActive = false;
                outputRetry.setActive(false);
                logStartSkip(reason, "user-shutdown");
                return;
            }
            runtimeActive = true;
            long now = SystemClock.elapsedRealtime();
            if (state == State.READY && proxyBinder != null && proxyBinder.isBinderAlive()) {
                logStartSkip(reason, "already-ready");
                schedulePingLocked(proxy, generation);
                return;
            }
            if (state == State.STARTING || state == State.BLOCKED) {
                logStartSkip(reason, state == State.STARTING ? "already-starting" : "capability-blocked");
                return;
            }
            if (now < nextRetryAtMs) {
                logStartSkip(reason, "backoff");
                scheduleOutputRetryLocked();
                return;
            }
            outputRetry.cancel();
            if (state == State.READY) {
                staleProxy = proxy;
                staleGeneration = generation;
                clearProxyLocked();
            }
            state = State.STARTING;
            startStage = "requested";
            generation = Math.max(1L, generationCounter.incrementAndGet());
            nonce = newNonce();
            launchToken = newLaunchToken();
            requestGeneration = generation;
            requestNonce = nonce;
            requestLaunchToken = launchToken;
        }
        if (staleProxy != null) {
            resetCallWorker("replace-stale");
            shutdownCandidate(staleProxy, staleGeneration);
        }
        synchronized (lock) {
            if (!runtimeActive || state != State.STARTING
                    || generation != requestGeneration || !nonce.equals(requestNonce)
                    || !launchToken.equals(requestLaunchToken)) {
                return;
            }
            try {
                registerHandoffReceiver();
            } catch (RuntimeException error) {
                failStart(requestGeneration, "receiver " + error.getClass().getSimpleName(), false);
                return;
            }
            log("start requested generation=" + requestGeneration + " reason=" + safe(reason));
            worker.execute(() -> launch(
                    requestGeneration, requestNonce, requestLaunchToken));
            worker.schedule(() -> handleStartTimeout(requestGeneration),
                    START_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
    }

    /** Called only by accepted shared output lifecycle transitions, never by each frame. */
    void setOutputDemand(boolean active, String reason) {
        synchronized (lock) {
            if (!outputRetry.setActive(active)) return;
            log("output demand=" + active + " generation=" + generation
                    + " reason=" + safe(reason));
            if (!active) {
                // Retain a healthy helper so source/manual handoffs do not recreate it.
                // Calls already queued for final clear/status can still finish.
                runtimeActive = false;
                if (state == State.STARTING) {
                    log("start cancelled generation=" + generation
                            + " reason=no-output lastStage=" + startStage);
                    clearProxyLocked();
                    state = State.IDLE;
                    generation = generationCounter.incrementAndGet();
                    nonce = "";
                    launchToken = "";
                    unregisterHandoffReceiver();
                    worker.execute(() -> cleanupHelper("output-ended"));
                }
                return;
            }
            ensureStarted("output:" + safe(reason));
        }
    }

    private void logStartSkip(String reason, String skip) {
        log("start skipped generation=" + generation + " trigger=" + safe(reason)
                + " reason=" + skip);
    }

    void onRuntimeStopped(String reason, boolean forceShutdown) {
        synchronized (lock) {
            if (!shouldShutdownForRuntimeStop(outputRetry.isActive(), forceShutdown)) {
                log("runtime stop retained generation=" + generation + " reason=active-output");
                return;
            }
            shutdown(reason);
        }
    }

    static boolean shouldShutdownForRuntimeStop(boolean activeOutput, boolean forceShutdown) {
        return forceShutdown || !activeOutput;
    }

    private void scheduleOutputRetryLocked() {
        if (!runtimeActive || state != State.IDLE) return;
        long delay = Math.max(0L, nextRetryAtMs - SystemClock.elapsedRealtime());
        if (outputRetry.schedule(worker, delay, generation, requestGeneration -> {
            synchronized (lock) {
                if (!runtimeActive || !outputRetry.isActive()
                        || state != State.IDLE || generation != requestGeneration) return;
                log("retry fired generation=" + requestGeneration + " trigger=output-demand");
                ensureStarted("output-backoff-retry");
            }
        })) {
            log("retry scheduled generation=" + generation + " delayMs=" + delay
                    + " trigger=output-demand");
        }
    }

    void onAuthorizationVerified() {
        synchronized (lock) {
            if (state == State.BLOCKED) return;
            outputRetry.cancel();
            nextRetryAtMs = 0L;
            rapidFailureCount = 0;
        }
        ensureStarted("rsa-authorized");
    }

    void addReadyListener(Runnable listener) {
        if (listener == null) return;
        readyListeners.addIfAbsent(listener);
        boolean ready;
        synchronized (lock) {
            ready = state == State.READY && proxy != null;
        }
        if (ready) listener.run();
    }

    void addUnavailableListener(UnavailableListener listener) {
        if (listener != null) unavailableListeners.addIfAbsent(listener);
    }

    void sendNavigationStatus(int status, ResultCallback callback) {
        if (!InstrumentProxyContract.validStatus(status)) {
            deliver(callback, Result.unavailable("invalid navigation status"));
            return;
        }
        submitCall("navigation_status", callback,
                (current, currentGeneration) ->
                        current.sendNavigationStatus(currentGeneration, status));
    }

    void sendGuidance(int icon, int distanceMeters, String road,
            int[] laneDirections, int[] laneRecommendations, ResultCallback callback) {
        if (!InstrumentProxyContract.validGuidance(
                icon, distanceMeters, road, laneDirections, laneRecommendations)) {
            deliver(callback, Result.unavailable("invalid guidance frame"));
            return;
        }
        PendingGuidance superseded;
        ExecutorService executor = null;
        boolean deferredPath;
        synchronized (callLock) {
            PendingGuidance next = new PendingGuidance(
                    icon, distanceMeters, preserveText(road),
                    laneDirections, laneRecommendations, callback);
            deferredPath = guidanceBarrierActive;
            if (deferredPath) {
                superseded = deferredGuidance;
                deferredGuidance = next;
            } else {
                superseded = pendingGuidance;
                pendingGuidance = next;
                if (!guidanceScheduled) {
                    guidanceScheduled = true;
                    executor = calls;
                }
            }
        }
        if (superseded != null) {
            deliver(superseded.callback, Result.unavailable(
                    deferredPath
                            ? "coalesced during terminal clear"
                            : "coalesced by newer frame"));
        }
        if (executor != null && !enqueueCall(executor, this::drainGuidance)) {
            failPendingGuidance("proxy call worker unavailable");
        }
    }

    void sendHudCheckTrafficLight(int sampleIndex, String reason) {
        sendHudCheckTrafficLight(sampleIndex, reason, null);
    }

    void sendHudCheckTrafficLight(int sampleIndex, String reason, ResultCallback callback) {
        if (!HudCheckTrafficLight.validSampleIndex(sampleIndex)) {
            deliver(callback, Result.unavailable("unknown traffic-light sample"));
            return;
        }
        if (sampleIndex == HudCheckTrafficLight.CLEAR) {
            sendTrafficLightClear(reason, callback);
            return;
        }
        if (!trafficLightCapabilityAvailable()) {
            deliver(callback, Result.unavailable("traffic-light capability unavailable"));
            return;
        }
        PendingTrafficLight superseded;
        ExecutorService executor = null;
        boolean deferredPath;
        synchronized (callLock) {
            PendingTrafficLight next = new PendingTrafficLight(sampleIndex,
                    preserveText(reason), callback);
            deferredPath = trafficLightBarrierActive;
            if (deferredPath) {
                superseded = deferredTrafficLight;
                deferredTrafficLight = next;
            } else {
                superseded = pendingTrafficLight;
                pendingTrafficLight = next;
                if (!trafficLightScheduled) {
                    trafficLightScheduled = true;
                    executor = calls;
                }
            }
        }
        if (superseded != null) {
            deliver(superseded.callback, Result.unavailable(
                    deferredPath ? "coalesced during traffic-light clear"
                            : "coalesced by newer traffic-light sample"));
        }
        if (executor != null && !enqueueCall(executor, this::drainTrafficLight)) {
            failPendingTrafficLight("proxy call worker unavailable");
        }
    }

    private boolean trafficLightCapabilityAvailable() {
        synchronized (lock) {
            return state == State.READY && proxy != null && trafficLightCapable;
        }
    }

    private void sendTrafficLightClear(String reason, ResultCallback callback) {
        PendingTrafficLight superseded;
        PendingTrafficLight deferredSuperseded = null;
        ExecutorService executor = null;
        long barrierToken = 0L;
        long expectedEpoch = 0L;
        synchronized (callLock) {
            superseded = pendingTrafficLight;
            pendingTrafficLight = null;
            trafficLightScheduled = false;
            if (trafficLightBarrierActive) {
                deferredSuperseded = deferredTrafficLight;
                deferredTrafficLight = null;
                if (callback != null) trafficLightClearCallbacks.add(callback);
            } else {
                trafficLightBarrierActive = true;
                barrierToken = ++trafficLightBarrierToken;
                expectedEpoch = callEpoch;
                trafficLightClearCallbacks.clear();
                if (callback != null) trafficLightClearCallbacks.add(callback);
                executor = calls;
            }
        }
        if (superseded != null) {
            deliver(superseded.callback,
                    Result.unavailable("superseded by traffic-light clear"));
        }
        if (deferredSuperseded != null) {
            deliver(deferredSuperseded.callback,
                    Result.unavailable("superseded by duplicate traffic-light clear"));
        }
        if (executor == null) return;
        final long requestToken = barrierToken;
        final long requestEpoch = expectedEpoch;
        if (!enqueueCall(executor,
                () -> executeTrafficLightClear(requestToken, requestEpoch, reason))) {
            finishTrafficLightClear(requestToken,
                    Result.unavailable("proxy call worker unavailable"));
        }
    }

    private void executeTrafficLightClear(
            long requestToken, long requestEpoch, String reason) {
        synchronized (callLock) {
            if (!trafficLightBarrierActive || requestToken != trafficLightBarrierToken
                    || requestEpoch != callEpoch) {
                return;
            }
        }
        executeCall("traffic_light_clear:" + safe(reason),
                result -> finishTrafficLightClear(requestToken, result),
                (current, currentGeneration) -> current.sendHudCheckTrafficLight(
                        currentGeneration, HudCheckTrafficLight.CLEAR));
    }

    private void finishTrafficLightClear(long requestToken, Result result) {
        List<ResultCallback> callbacks;
        ExecutorService executor = null;
        PendingTrafficLight deferred;
        synchronized (callLock) {
            if (!trafficLightBarrierActive || requestToken != trafficLightBarrierToken) return;
            trafficLightBarrierActive = false;
            callbacks = new ArrayList<>(trafficLightClearCallbacks);
            trafficLightClearCallbacks.clear();
            deferred = deferredTrafficLight;
            deferredTrafficLight = null;
            if (deferred != null) {
                pendingTrafficLight = deferred;
                trafficLightScheduled = true;
                executor = calls;
            }
        }
        if (deferred != null && !enqueueCall(executor, this::drainTrafficLight)) {
            failPendingTrafficLight("proxy call worker unavailable");
        }
        for (ResultCallback clearCallback : callbacks) {
            deliver(clearCallback, result);
        }
    }

    private void drainTrafficLight() {
        PendingTrafficLight request;
        synchronized (callLock) {
            request = pendingTrafficLight;
            pendingTrafficLight = null;
            trafficLightScheduled = false;
        }
        if (request == null) return;
        executeCall("traffic_light:" + request.reason, request.callback,
                (current, currentGeneration) -> current.sendHudCheckTrafficLight(
                        currentGeneration, request.sampleIndex));
    }

    private void failPendingTrafficLight(String reason) {
        PendingTrafficLight failed;
        synchronized (callLock) {
            failed = pendingTrafficLight;
            pendingTrafficLight = null;
            trafficLightScheduled = false;
        }
        if (failed != null) deliver(failed.callback, Result.unavailable(reason));
    }

    /** Clears Instrument guidance and fences the next normal frame behind that clear. */
    void sendTerminalGuidanceClear(ResultCallback callback) {
        PendingGuidance superseded;
        PendingGuidance deferredSuperseded = null;
        ExecutorService executor = null;
        long barrierToken = 0L;
        long expectedEpoch = 0L;
        synchronized (callLock) {
            superseded = pendingGuidance;
            pendingGuidance = null;
            guidanceScheduled = false;
            if (guidanceBarrierActive) {
                // A second terminal is still the final frame.  Drop the frame
                // deferred behind the first clear instead of replaying it after
                // the joined terminal callbacks complete.
                if (shouldDropDeferredGuidanceForDuplicateTerminalForTest(
                        true, deferredGuidance != null)) {
                    deferredSuperseded = deferredGuidance;
                    deferredGuidance = null;
                }
                if (callback != null) terminalClearCallbacks.add(callback);
            } else {
                guidanceBarrierActive = true;
                terminalClearInFlight = false;
                barrierToken = ++guidanceBarrierToken;
                expectedEpoch = callEpoch;
                terminalClearCallbacks.clear();
                if (callback != null) terminalClearCallbacks.add(callback);
                executor = calls;
            }
        }
        if (superseded != null) {
            deliver(superseded.callback,
                    Result.unavailable("superseded by terminal clear"));
        }
        if (deferredSuperseded != null) {
            deliver(deferredSuperseded.callback,
                    Result.unavailable("superseded by duplicate terminal clear"));
        }
        if (executor == null) return;
        final long requestToken = barrierToken;
        final long requestEpoch = expectedEpoch;
        if (!enqueueCall(executor,
                () -> executeTerminalGuidanceClear(requestToken, requestEpoch))) {
            finishTerminalGuidanceClear(requestToken,
                    Result.unavailable("proxy call worker unavailable"));
        }
    }

    static boolean shouldDropDeferredGuidanceForDuplicateTerminalForTest(
            boolean barrierActive, boolean deferredPresent) {
        return barrierActive && deferredPresent;
    }

    private void executeTerminalGuidanceClear(long requestToken, long requestEpoch) {
        synchronized (callLock) {
            if (!guidanceBarrierActive || requestToken != guidanceBarrierToken
                    || requestEpoch != callEpoch) {
                return;
            }
            terminalClearInFlight = true;
        }
        executeCall("terminal_guidance_clear",
                result -> finishTerminalGuidanceClear(requestToken, result),
                (current, currentGeneration) -> current.sendGuidance(
                        currentGeneration, 0, -1, "", new int[0], new int[0]));
    }

    private void finishTerminalGuidanceClear(long requestToken, Result result) {
        List<ResultCallback> callbacks;
        ExecutorService executor = null;
        PendingGuidance deferred;
        synchronized (callLock) {
            if (!guidanceBarrierActive || requestToken != guidanceBarrierToken) return;
            guidanceBarrierActive = false;
            terminalClearInFlight = false;
            callbacks = new ArrayList<>(terminalClearCallbacks);
            terminalClearCallbacks.clear();
            deferred = deferredGuidance;
            deferredGuidance = null;
            if (deferred != null) {
                pendingGuidance = deferred;
                guidanceScheduled = true;
                executor = calls;
            }
        }
        if (deferred != null && !enqueueCall(executor, this::drainGuidance)) {
            failPendingGuidance("proxy call worker unavailable");
        }
        for (ResultCallback terminalCallback : callbacks) {
            deliver(terminalCallback, result);
        }
    }

    private void failPendingGuidance(String reason) {
        PendingGuidance failed;
        synchronized (callLock) {
            failed = pendingGuidance;
            pendingGuidance = null;
            guidanceScheduled = false;
        }
        if (failed != null) deliver(failed.callback, Result.unavailable(reason));
    }

    private void drainGuidance() {
        PendingGuidance request;
        synchronized (callLock) {
            request = pendingGuidance;
            pendingGuidance = null;
            guidanceScheduled = false;
        }
        if (request == null) return;
        executeCall("guidance", request.callback,
                (current, currentGeneration) -> current.sendGuidance(
                        currentGeneration, request.icon,
                        request.distanceMeters, request.road,
                        request.laneDirections, request.laneRecommendations));
    }

    private void submitCall(String operation, ResultCallback callback, RemoteCall remoteCall) {
        ExecutorService executor;
        synchronized (callLock) {
            executor = calls;
        }
        Runnable task = () -> executeCall(operation, callback, remoteCall);
        if (!enqueueCall(executor, task)) {
            deliver(callback, Result.unavailable("proxy call worker unavailable"));
        }
    }

    private boolean enqueueCall(ExecutorService preferred, Runnable task) {
        ExecutorService candidate = preferred;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                candidate.execute(task);
                return true;
            } catch (RejectedExecutionException ignored) {
                synchronized (callLock) {
                    candidate = calls;
                }
            }
        }
        return false;
    }

    private void executeCall(
            String operation, ResultCallback callback, RemoteCall remoteCall) {
        IInstrumentNavigationProxy current;
        IBinder currentBinder;
        long currentGeneration;
        synchronized (lock) {
            current = proxy;
            currentBinder = proxyBinder;
            currentGeneration = generation;
        }
        if (current == null || currentBinder == null) {
            deliver(callback, Result.unavailable("proxy unavailable"));
            return;
        }
        long operationId = operationCounter.incrementAndGet();
        long epoch;
        synchronized (callLock) {
            epoch = callEpoch;
            activeOperationId = operationId;
            activeOperationGeneration = currentGeneration;
            activeOperationCallback = callback;
            activeOperationTerminal = "terminal_guidance_clear".equals(operation)
                    || operation.startsWith("traffic_light_clear:");
        }
        worker.schedule(
                () -> handleCallTimeout(operationId, currentGeneration,
                        currentBinder, epoch, operation),
                CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        Result result;
        try {
            result = Result.from(remoteCall.invoke(current, currentGeneration));
        } catch (RemoteException | RuntimeException error) {
            result = Result.unavailable(describe(error));
            handleCallFailure(currentGeneration, currentBinder, error);
        }
        boolean currentResult;
        synchronized (callLock) {
            currentResult = callEpoch == epoch && activeOperationId == operationId;
            if (currentResult) {
                activeOperationId = 0L;
                activeOperationGeneration = 0L;
                activeOperationCallback = null;
                activeOperationTerminal = false;
            }
        }
        logTrafficLightResult(operation, result);
        if (currentResult) deliver(callback, result);
    }

    private void logTrafficLightResult(String operation, Result result) {
        if (operation == null || !operation.startsWith("traffic_light")) return;
        StringBuilder detail = new StringBuilder("traffic_light_result operation=")
                .append(safe(operation)).append(" available=")
                .append(result != null && result.available).append(" error=")
                .append(result == null ? "no result" : safe(result.error));
        if (result != null) {
            detail.append(" operations=");
            for (InstrumentProxyContract.Operation item : result.operations) {
                detail.append(item.name).append(':').append(item.result);
                if (!item.error.isEmpty()) detail.append('(').append(item.error).append(')');
                detail.append(',');
            }
        }
        // The publisher emits change-only HUD-check summaries to normal events.log.
        Log.i(TAG, detail.toString());
    }

    private void handleCallTimeout(
            long operationId, long requestGeneration, IBinder requestBinder,
            long epoch, String operation) {
        synchronized (callLock) {
            if (callEpoch != epoch || activeOperationId != operationId
                    || activeOperationGeneration != requestGeneration) {
                return;
            }
        }
        log("call timeout generation=" + requestGeneration
                + " operation=" + safe(operation));
        handleBinderDeath(requestGeneration, requestBinder);
    }

    private void schedulePingLocked(
            IInstrumentNavigationProxy current, long requestGeneration) {
        if (current == null || pingInFlight) return;
        IBinder requestBinder = current.asBinder();
        pingInFlight = true;
        long token = ++pingToken;
        worker.execute(() -> {
            try {
                current.ping(requestGeneration, token);
            } catch (RemoteException | RuntimeException error) {
                completePing(token, requestGeneration, requestBinder, false);
            }
        });
        worker.schedule(() -> completePing(
                        token, requestGeneration, requestBinder, false),
                PING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void completePing(long token, long requestGeneration,
            IBinder requestBinder, boolean success) {
        synchronized (lock) {
            if (!pingInFlight || token != pingToken) return;
            pingInFlight = false;
            if (success) return;
        }
        log("ping failed generation=" + requestGeneration);
        handleBinderDeath(requestGeneration, requestBinder);
    }

    private void resetCallWorker(String reason) {
        ExecutorService previous;
        ResultCallback activeCallback;
        PendingGuidance pending;
        PendingGuidance deferred;
        PendingTrafficLight trafficPending;
        PendingTrafficLight trafficDeferred;
        List<ResultCallback> resetTerminalCallbacks = null;
        List<ResultCallback> resetTrafficLightCallbacks = null;
        boolean activeTerminal;
        boolean scheduleDeferred = false;
        ExecutorService deferredExecutor = null;
        synchronized (callLock) {
            previous = calls;
            callEpoch++;
            calls = newCallExecutor(callEpoch);
            activeOperationId = 0L;
            activeOperationGeneration = 0L;
            activeCallback = activeOperationCallback;
            activeTerminal = activeOperationTerminal && activeCallback != null;
            activeOperationCallback = null;
            activeOperationTerminal = false;
            pending = pendingGuidance;
            pendingGuidance = null;
            guidanceScheduled = false;
            if (guidanceBarrierActive) {
                resetTerminalCallbacks = new ArrayList<>(terminalClearCallbacks);
                terminalClearCallbacks.clear();
                guidanceBarrierActive = false;
                terminalClearInFlight = false;
                guidanceBarrierToken++;
                deferred = deferredGuidance;
                deferredGuidance = null;
                if (deferred != null) {
                    pendingGuidance = deferred;
                    guidanceScheduled = true;
                    deferredExecutor = calls;
                    scheduleDeferred = true;
                }
            } else {
                deferred = null;
            }
            trafficPending = pendingTrafficLight;
            pendingTrafficLight = null;
            trafficLightScheduled = false;
            if (trafficLightBarrierActive) {
                resetTrafficLightCallbacks = new ArrayList<>(trafficLightClearCallbacks);
                trafficLightClearCallbacks.clear();
                trafficLightBarrierActive = false;
                trafficLightBarrierToken++;
                trafficDeferred = deferredTrafficLight;
                deferredTrafficLight = null;
            } else {
                trafficDeferred = null;
            }
        }
        previous.shutdownNow();
        if (activeCallback != null && !activeTerminal) {
            deliver(activeCallback, Result.unavailable("proxy reset: " + safe(reason)));
        }
        if (pending != null) {
            deliver(pending.callback, Result.unavailable("proxy reset: " + safe(reason)));
        }
        if (resetTerminalCallbacks != null) {
            Result reset = Result.unavailable("proxy reset: " + safe(reason));
            for (ResultCallback terminalCallback : resetTerminalCallbacks) {
                deliver(terminalCallback, reset);
            }
        }
        if (trafficPending != null) {
            deliver(trafficPending.callback, Result.unavailable(
                    "proxy reset: " + safe(reason)));
        }
        if (trafficDeferred != null) {
            deliver(trafficDeferred.callback, Result.unavailable(
                    "proxy reset: " + safe(reason)));
        }
        if (resetTrafficLightCallbacks != null) {
            Result reset = Result.unavailable("proxy reset: " + safe(reason));
            for (ResultCallback clearCallback : resetTrafficLightCallbacks) {
                deliver(clearCallback, reset);
            }
        }
        if (scheduleDeferred && !enqueueCall(deferredExecutor, this::drainGuidance)) {
            failPendingGuidance("proxy call worker unavailable");
        }
    }

    private static ExecutorService newCallExecutor(long epoch) {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BydHudInstrumentCall-" + epoch);
            thread.setDaemon(true);
            return thread;
        });
    }

    private static void deliver(ResultCallback callback, Result result) {
        if (callback != null) callback.onResult(result);
    }

    void acceptHandoff(long requestGeneration, String requestNonce, IBinder binder) {
        if (binder == null) return;
        synchronized (lock) {
            if (!runtimeActive || state != State.STARTING
                    || requestGeneration != generation
                    || !nonce.equals(requestNonce)) {
                log("handoff rejected generation=" + requestGeneration);
                return;
            }
            log("startup stage=handoff-received generation=" + requestGeneration);
        }
        worker.execute(() -> beginConnect(requestGeneration, requestNonce, binder));
    }

    void shutdown(String reason) {
        IInstrumentNavigationProxy current;
        long currentGeneration;
        synchronized (lock) {
            runtimeActive = false;
            outputRetry.setActive(false);
            current = proxy;
            currentGeneration = generation;
            clearProxyLocked();
            state = State.IDLE;
            generation = generationCounter.incrementAndGet();
            nonce = "";
            launchToken = "";
            nextRetryAtMs = 0L;
            rapidFailureCount = 0;
            capabilityMode = CapabilityMode.NONE;
            startStage = "shutdown";
            // Queue cleanup before a later lifecycle request can queue its launch.
            if (current != null) shutdownCandidate(current, currentGeneration);
            worker.execute(() -> cleanupHelper("shutdown"));
            unregisterHandoffReceiver();
        }
        resetCallWorker("shutdown");
        log("shutdown reason=" + safe(reason));
    }

    private void launch(long requestGeneration, String requestNonce,
            String requestLaunchToken) {
        synchronized (lock) {
            if (!runtimeActive || state != State.STARTING
                    || generation != requestGeneration || !nonce.equals(requestNonce)
                    || !launchToken.equals(requestLaunchToken)) {
                return;
            }
        }
        if (!observeStartStage(requestGeneration, "authorization-started")) return;
        if (!LocalAdbBridge.isCurrentKeyKnownAuthorized(context)) {
            failStart(requestGeneration, "rsa key not authorized", false);
            return;
        }
        if (!observeStartStage(requestGeneration, "authorization-ok")) return;
        try {
            int appUid = context.getApplicationInfo().uid;
            if (!observeStartStage(requestGeneration, "cleanup-started")) return;
            if (!migrateLegacyHelperOnce(appUid, requestGeneration)) {
                return;
            }
            InstrumentProxyStore.Identity staleIdentity = helperIdentitySnapshot();
            LocalAdbBridge.ShellResult cleanup =
                    LocalAdbBridge.stopInstrumentProxy(context, staleIdentity);
            if (!cleanup.success()) {
                failStart(requestGeneration,
                        "stale cleanup exit=" + cleanup.exitCode, false);
                return;
            }
            if (!clearHelperIdentity(staleIdentity)) {
                failStart(requestGeneration,
                        "stale identity clear persistence failed", false);
                return;
            }
            if (!observeStartStage(requestGeneration, "cleanup-complete")) return;

            InstrumentProxyStore.Identity pending = InstrumentProxyStore.Identity.pending(
                    appUid, requestGeneration, requestNonce,
                    requestLaunchToken, BuildConfig.VERSION_CODE);
            if (!setHelperIdentity(pending)) {
                failStart(requestGeneration, "pending identity persistence failed", false);
                return;
            }
            if (!observeStartStage(requestGeneration, "launch-started")) return;
            LocalAdbBridge.ShellResult result = LocalAdbBridge.launchInstrumentProxy(
                    context,
                    context.getApplicationInfo().sourceDir,
                    requestGeneration,
                    requestNonce,
                    appUid,
                    requestLaunchToken,
                    BuildConfig.VERSION_CODE);
            if (!result.success()) {
                failStart(requestGeneration,
                        "launch exit=" + result.exitCode, result.exitCode != 126);
                return;
            }
            int launchedPid = LocalAdbBridge.instrumentProxyPid(result);
            if (launchedPid <= 0 || !setHelperIdentity(pending.withPid(launchedPid))) {
                failStart(requestGeneration, "launch pid persistence failed", true);
            } else {
                observeStartStage(requestGeneration, "launch-complete");
            }
        } catch (IOException | RuntimeException error) {
            failStart(requestGeneration, error.getClass().getSimpleName(), true);
        }
    }

    private boolean observeStartStage(long requestGeneration, String stage) {
        synchronized (lock) {
            if (!runtimeActive || state != State.STARTING || generation != requestGeneration) {
                return false;
            }
            startStage = stage;
            log("startup stage=" + stage + " generation=" + requestGeneration);
            return true;
        }
    }

    private boolean migrateLegacyHelperOnce(int appUid, long requestGeneration)
            throws IOException {
        if (InstrumentProxyStore.legacyMigrationComplete(context, appUid)) return true;
        LocalAdbBridge.ShellResult result =
                LocalAdbBridge.stopLegacyInstrumentProxy(context, appUid);
        if (!result.success()) {
            failStart(requestGeneration,
                    "legacy cleanup exit=" + result.exitCode, false);
            return false;
        }
        if (!InstrumentProxyStore.markLegacyMigrationComplete(context, appUid)) {
            failStart(requestGeneration, "legacy migration persistence failed", false);
            return false;
        }
        log("legacy helper migration complete uid=" + appUid);
        return true;
    }

    private void beginConnect(long requestGeneration, String requestNonce, IBinder binder) {
        synchronized (lock) {
            if (!runtimeActive || state != State.STARTING
                    || requestGeneration != generation || !nonce.equals(requestNonce)) {
                return;
            }
        }
        if (!observeStartStage(requestGeneration, "handoff-accepted")) return;
        IInstrumentNavigationProxy candidate =
                IInstrumentNavigationProxy.Stub.asInterface(binder);
        try {
            binder.linkToDeath(() -> worker.execute(
                    () -> handleBinderDeath(requestGeneration, binder)), 0);
        } catch (RemoteException | RuntimeException error) {
            failStart(requestGeneration, "handoff " + describe(error), true);
            return;
        }
        synchronized (lock) {
            if (!runtimeActive || state != State.STARTING
                    || requestGeneration != generation || !nonce.equals(requestNonce)) {
                shutdownCandidate(candidate, requestGeneration);
                return;
            }
            connectingProxy = candidate;
            connectingBinder = binder;
        }
        try {
            if (!observeStartStage(requestGeneration, "connect-requested")) return;
            candidate.connect(requestGeneration, requestNonce, client);
        } catch (RemoteException | RuntimeException error) {
            failStart(requestGeneration, "handoff " + describe(error), true);
        }
    }

    private void completeConnect(long requestGeneration, Bundle result) {
        IInstrumentNavigationProxy candidate;
        IBinder binder;
        String requestNonce;
        String requestLaunchToken;
        synchronized (lock) {
            if (!runtimeActive || state != State.STARTING
                    || requestGeneration != generation) {
                return;
            }
            candidate = connectingProxy;
            binder = connectingBinder;
            requestNonce = nonce;
            requestLaunchToken = launchToken;
        }
        if (!observeStartStage(requestGeneration, "connect-received")) return;
        InstrumentProxyStore.Identity pendingIdentity = helperIdentitySnapshot();
        boolean expectedIdentity = InstrumentProxyContract.hasExpectedConnectionIdentity(
                result, requestGeneration, requestNonce, SHELL_UID,
                BuildConfig.VERSION_CODE, requestLaunchToken)
                && pendingIdentity.isValid()
                && pendingIdentity.uid == context.getApplicationInfo().uid
                && pendingIdentity.generation == requestGeneration
                && pendingIdentity.nonce.equals(requestNonce)
                && pendingIdentity.token.equals(requestLaunchToken);
        if (pendingIdentity.pid > 0
                && pendingIdentity.pid != InstrumentProxyContract.proxyPid(result)) {
            expectedIdentity = false;
        }
        if (candidate == null || binder == null || !expectedIdentity) {
            if (candidate != null) shutdownCandidate(candidate, requestGeneration);
            failStart(requestGeneration,
                    "handoff " + InstrumentProxyContract.error(result)
                            + " identityPid=" + InstrumentProxyContract.proxyPid(result)
                            + " identityUid=" + InstrumentProxyContract.proxyUid(result), true);
            return;
        }
        InstrumentProxyStore.Identity connectedIdentity = pendingIdentity.connected(
                InstrumentProxyContract.proxyPid(result),
                InstrumentProxyContract.proxyStartTimeTicks(result));
        if (!setHelperIdentity(connectedIdentity)) {
            shutdownCandidate(candidate, requestGeneration);
            failStart(requestGeneration, "connected identity persistence failed", false);
            return;
        }
        CapabilityMode connectedMode = capabilityMode(
                InstrumentProxyContract.hasCapability(
                        result, InstrumentProxyContract.CAP_DIRECT_FID),
                InstrumentProxyContract.hasCapability(
                        result, InstrumentProxyContract.CAP_INSTRUMENT_SDK));
        boolean connectedTrafficLight = InstrumentProxyContract.hasCapability(
                result, InstrumentProxyContract.CAP_TRAFFIC_LIGHT);
        if (!InstrumentProxyContract.isReady(result)
                || !InstrumentProxyContract.hasUsableNavigationCapability(result)
                || connectedMode == CapabilityMode.NONE) {
            shutdownCandidate(candidate, requestGeneration);
            synchronized (lock) {
                if (generation != requestGeneration || state != State.STARTING) return;
                clearProxyLocked();
                state = State.BLOCKED;
                outputRetry.cancel();
                capabilityMode = CapabilityMode.NONE;
                nextRetryAtMs = Long.MAX_VALUE;
                unregisterHandoffReceiver();
                worker.execute(() -> cleanupHelper("capability-blocked"));
            }
            log("capability circuit open generation=" + requestGeneration
                    + " capabilities=" + InstrumentProxyContract.capabilities(result)
                    + " error=" + InstrumentProxyContract.error(result));
            return;
        }
        synchronized (lock) {
            if (!runtimeActive || state != State.STARTING
                    || requestGeneration != generation) {
                shutdownCandidate(candidate, requestGeneration);
                return;
            }
            proxy = candidate;
            proxyBinder = binder;
            connectingProxy = null;
            connectingBinder = null;
            state = State.READY;
            startStage = "ready";
            outputRetry.cancel();
            helperIdentity = connectedIdentity;
            capabilityMode = connectedMode;
            trafficLightCapable = connectedTrafficLight;
            connectedAtMs = SystemClock.elapsedRealtime();
            nextRetryAtMs = 0L;
            unregisterHandoffReceiver();
        }
        log("ready generation=" + requestGeneration
                + " pid=" + connectedIdentity.pid
                + " uid=" + connectedIdentity.uid
                + " capabilities=" + connectedMode);
        worker.execute(() -> clearStartupDiagnostic(connectedIdentity));
        for (Runnable listener : readyListeners) {
            try {
                listener.run();
            } catch (RuntimeException error) {
                log("ready listener failed type=" + error.getClass().getSimpleName());
            }
        }
    }

    private void shutdownCandidate(
            IInstrumentNavigationProxy candidate, long requestGeneration) {
        worker.execute(() -> {
            try {
                candidate.shutdown(requestGeneration);
            } catch (RemoteException | RuntimeException ignored) {
                // Binder death and the helper handoff timeout are fallback cleanup paths.
            }
        });
    }

    private void handleStartTimeout(long requestGeneration) {
        String lastStage;
        synchronized (lock) {
            if (state != State.STARTING || generation != requestGeneration) return;
            lastStage = startStage;
        }
        failStart(requestGeneration, "handoff timeout lastStage=" + lastStage, true);
    }

    private void failStart(long requestGeneration, String error, boolean retryOnce) {
        boolean immediateRetry = false;
        String failedStage;
        synchronized (lock) {
            if (generation != requestGeneration || state != State.STARTING) return;
            failedStage = startStage;
            clearProxyLocked();
            state = State.IDLE;
            rapidFailureCount++;
            if (shouldRetryStart(runtimeActive, retryOnce, rapidFailureCount)) {
                nextRetryAtMs = 0L;
                immediateRetry = true;
            } else {
                nextRetryAtMs = SystemClock.elapsedRealtime() + RETRY_DELAY_MS;
            }
            worker.execute(() -> cleanupHelper("start-failed"));
            unregisterHandoffReceiver();
        }
        log("start failed generation=" + requestGeneration + " stage=" + failedStage
                + " error=" + safe(error)
                + " immediateRetry=" + immediateRetry);
        if (immediateRetry) retryImmediately(requestGeneration, "bounded-retry");
        else synchronized (lock) {
            scheduleOutputRetryLocked();
        }
    }

    private void handleCallFailure(
            long requestGeneration, IBinder requestBinder, Throwable error) {
        log("call failed generation=" + requestGeneration + " error=" + describe(error));
        worker.execute(() -> handleBinderDeath(requestGeneration, requestBinder));
    }

    private void handleRemoteStop(long requestGeneration, String reason) {
        log("remote stop generation=" + requestGeneration + " reason=" + safe(reason));
        handleBinderDeath(requestGeneration, null);
    }

    private void handleBinderDeath(long requestGeneration, IBinder requestBinder) {
        boolean retry;
        boolean wasReady;
        synchronized (lock) {
            if (requestGeneration != generation
                    || state == State.BLOCKED
                    || (state == State.IDLE && proxy == null)) return;
            if (requestBinder != null
                    && requestBinder != proxyBinder
                    && requestBinder != connectingBinder) {
                return;
            }
            wasReady = state == State.READY;
            long uptime = connectedAtMs == 0L ? 0L
                    : SystemClock.elapsedRealtime() - connectedAtMs;
            if (uptime >= STABLE_CONNECTION_MS) rapidFailureCount = 0;
            rapidFailureCount++;
            clearProxyLocked();
            state = State.IDLE;
            retry = shouldRetryBinder(runtimeActive, rapidFailureCount);
            nextRetryAtMs = retry ? 0L
                    : SystemClock.elapsedRealtime() + RETRY_DELAY_MS;
            worker.execute(() -> cleanupHelper("binder-death"));
        }
        resetCallWorker("binder-death");
        log("binder died generation=" + requestGeneration + " immediateRetry=" + retry);
        if (wasReady) notifyUnavailable("binder-death");
        if (retry) retryImmediately(requestGeneration, "binder-death");
        else synchronized (lock) {
            scheduleOutputRetryLocked();
        }
    }

    private void notifyUnavailable(String reason) {
        for (UnavailableListener listener : unavailableListeners) {
            try {
                listener.onUnavailable(safe(reason));
            } catch (RuntimeException error) {
                log("unavailable listener failed type="
                        + error.getClass().getSimpleName());
            }
        }
    }

    private static boolean isShellCallback() {
        return Binder.getCallingUid() == SHELL_UID;
    }

    private void cleanupHelper(String reason) {
        InstrumentProxyStore.Identity expected = helperIdentitySnapshot();
        log("cleanup started generation=" + expected.generation + " reason=" + safe(reason));
        if ("start-failed".equals(reason)) {
            try {
                log("startup diagnostic generation=" + expected.generation + " "
                        + LocalAdbBridge.instrumentProxyStartupDiagnostic(context, expected));
            } catch (IOException error) {
                log("startup diagnostic unavailable generation=" + expected.generation
                        + " error=" + error.getClass().getSimpleName());
            }
        } else {
            clearStartupDiagnostic(expected);
        }
        try {
            LocalAdbBridge.ShellResult result =
                    LocalAdbBridge.stopInstrumentProxy(context, expected);
            if (!result.success()) {
                log("cleanup failed reason=" + safe(reason)
                        + " generation=" + expected.generation + " exit=" + result.exitCode);
            } else {
                if (!clearHelperIdentity(expected)) {
                    log("cleanup identity clear failed reason=" + safe(reason));
                } else {
                    log("cleanup complete generation=" + expected.generation
                            + " reason=" + safe(reason));
                }
            }
        } catch (IOException error) {
            log("cleanup failed reason=" + safe(reason)
                    + " generation=" + expected.generation
                    + " error=" + error.getClass().getSimpleName());
        }
    }

    private InstrumentProxyStore.Identity helperIdentitySnapshot() {
        synchronized (lock) {
            if (!helperIdentityLoaded) {
                helperIdentity = InstrumentProxyStore.load(context);
                helperIdentityLoaded = true;
            }
            return helperIdentity;
        }
    }

    private boolean setHelperIdentity(InstrumentProxyStore.Identity identity) {
        if (!InstrumentProxyStore.save(context, identity)) return false;
        synchronized (lock) {
            helperIdentity = identity;
            helperIdentityLoaded = true;
        }
        return true;
    }

    private boolean clearHelperIdentity(InstrumentProxyStore.Identity expected) {
        if (!InstrumentProxyStore.clear(context, expected)) return false;
        synchronized (lock) {
            if (helperIdentity.sameLaunch(expected)) {
                helperIdentity = InstrumentProxyStore.Identity.none();
            }
            helperIdentityLoaded = true;
        }
        return true;
    }

    private void registerHandoffReceiver() {
        synchronized (receiverLock) {
            if (handoffReceiverRegistered) return;
            IntentFilter filter = new IntentFilter(InstrumentProxyContract.ACTION_CONNECTED);
            ContextCompat.registerReceiver(
                    context, handoffReceiver, filter, ContextCompat.RECEIVER_EXPORTED);
            handoffReceiverRegistered = true;
        }
    }

    private void unregisterHandoffReceiver() {
        synchronized (receiverLock) {
            if (!handoffReceiverRegistered) return;
            try {
                context.unregisterReceiver(handoffReceiver);
            } catch (IllegalArgumentException ignored) {
                // A process teardown may unregister framework receivers first.
            }
            handoffReceiverRegistered = false;
        }
    }

    private void clearProxyLocked() {
        proxy = null;
        proxyBinder = null;
        connectingProxy = null;
        connectingBinder = null;
        connectedAtMs = 0L;
        pingInFlight = false;
        pingToken++;
        capabilityMode = CapabilityMode.NONE;
        trafficLightCapable = false;
    }

    private String newNonce() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder value = new StringBuilder(32);
        for (byte item : bytes) {
            int unsigned = item & 0xff;
            value.append(HEX[unsigned >>> 4]).append(HEX[unsigned & 0x0f]);
        }
        return value.toString();
    }

    private String newLaunchToken() {
        byte[] bytes = new byte[8];
        random.nextBytes(bytes);
        StringBuilder value = new StringBuilder(16);
        for (byte item : bytes) {
            int unsigned = item & 0xff;
            value.append(HEX[unsigned >>> 4]).append(HEX[unsigned & 0x0f]);
        }
        return value.toString();
    }

    static CapabilityMode capabilityMode(boolean fid, boolean sdk) {
        if (fid && sdk) return CapabilityMode.FULL;
        if (fid) return CapabilityMode.FID_ONLY;
        if (sdk) return CapabilityMode.SDK_ONLY;
        return CapabilityMode.NONE;
    }

    static boolean shouldRetryStart(
            boolean runtimeActive, boolean transientFailure, int failureCount) {
        return runtimeActive && transientFailure && failureCount == 1;
    }

    static boolean shouldRetryBinder(boolean runtimeActive, int failureCount) {
        return runtimeActive && failureCount == 1;
    }

    private void retryImmediately(long requestGeneration, String reason) {
        synchronized (lock) {
            if (!runtimeActive || generation != requestGeneration || state != State.IDLE) return;
            ensureStarted(reason);
        }
    }

    private void clearStartupDiagnostic(InstrumentProxyStore.Identity expected) {
        try {
            LocalAdbBridge.clearInstrumentProxyStartupDiagnostic(context, expected);
        } catch (IOException error) {
            log("startup diagnostic cleanup failed generation=" + expected.generation
                    + " error=" + error.getClass().getSimpleName());
        }
    }

    /** One demand-owned timer; cancellation also fences work already dequeued by the executor. */
    static final class OutputRetry {
        private boolean active;
        private long token;
        private ScheduledFuture<?> task;

        synchronized boolean setActive(boolean value) {
            if (active == value) return false;
            active = value;
            if (!active) cancel();
            return true;
        }

        synchronized boolean isActive() {
            return active;
        }

        synchronized void cancel() {
            token++;
            if (task != null) task.cancel(false);
            task = null;
        }

        synchronized boolean schedule(ScheduledExecutorService worker, long delayMs,
                long generation, LongConsumer retry) {
            if (!active || task != null) return false;
            long requestToken = ++token;
            task = worker.schedule(() -> {
                synchronized (OutputRetry.this) {
                    if (!active || token != requestToken) return;
                    task = null;
                }
                retry.accept(generation);
            }, Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
            return true;
        }
    }

    private void log(String message) {
        Log.i(TAG, message);
        AppEventLogger.event(context, "instrument_proxy " + message);
    }

    private static String describe(Throwable error) {
        Throwable cause = error != null && error.getCause() != null
                ? error.getCause() : error;
        if (cause == null) return "unknown";
        String message = safe(cause.getMessage());
        return cause.getClass().getSimpleName()
                + (message.isEmpty() ? "" : ": " + message);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String preserveText(String value) {
        return value == null ? "" : value;
    }

    interface ResultCallback {
        void onResult(Result result);
    }

    interface UnavailableListener {
        void onUnavailable(String reason);
    }

    private interface RemoteCall {
        Bundle invoke(IInstrumentNavigationProxy proxy, long generation)
                throws RemoteException;
    }

    private static final class PendingGuidance {
        final int icon;
        final int distanceMeters;
        final String road;
        final int[] laneDirections;
        final int[] laneRecommendations;
        final ResultCallback callback;

        PendingGuidance(int icon, int distanceMeters,
                String road, int[] laneDirections, int[] laneRecommendations,
                ResultCallback callback) {
            this.icon = icon;
            this.distanceMeters = distanceMeters;
            this.road = road;
            this.laneDirections = laneDirections.clone();
            this.laneRecommendations = laneRecommendations.clone();
            this.callback = callback;
        }
    }

    private static final class PendingTrafficLight {
        final int sampleIndex;
        final String reason;
        final ResultCallback callback;

        PendingTrafficLight(int sampleIndex, String reason, ResultCallback callback) {
            this.sampleIndex = sampleIndex;
            this.reason = reason == null ? "" : reason;
            this.callback = callback;
        }
    }

    static final class Result {
        final boolean available;
        final List<InstrumentProxyContract.Operation> operations;
        final String error;

        private Result(boolean available,
                List<InstrumentProxyContract.Operation> operations, String error) {
            this.available = available;
            this.operations = operations;
            this.error = safe(error);
        }

        static Result from(Bundle result) {
            String error = InstrumentProxyContract.error(result);
            List<InstrumentProxyContract.Operation> operations =
                    InstrumentProxyContract.operations(result);
            return new Result(result != null, operations, error);
        }

        static Result unavailable(String error) {
            return new Result(false, Collections.emptyList(), error);
        }
    }
}
