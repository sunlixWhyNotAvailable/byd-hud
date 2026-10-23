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

import org.json.JSONObject;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Owns the bounded startup, handoff, liveness, and restart policy for the shell helper. */
final class InstrumentProxyManager {
    private static final String TAG = "BydHudInstrumentProxy";
    private static final long START_TIMEOUT_MS = 5_000L;
    private static final long STARTUP_DEADLINE_MS = 45_000L;
    private static final long CALL_TIMEOUT_MS = 3_000L;
    private static final long PING_TIMEOUT_MS = 2_000L;
    private static final long RETRY_DELAY_MS = 30_000L;
    private static final long STABLE_CONNECTION_MS = 30_000L;
    private static final int SHELL_UID = 2_000;
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final Object INSTANCE_LOCK = new Object();
    @SuppressLint("StaticFieldLeak")
    private static volatile InstrumentProxyManager instance;

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
    private final ExecutorService shanghaiTransitions;
    private ExecutorService calls;
    private long callEpoch;
    private long navigationOutputGeneration;
    private final List<NavigationOutputFence> navigationOutputFences = new ArrayList<>();
    private long activeOperationId;
    private long activeOperationGeneration;
    private ResultCallback activeOperationCallback;
    private ResultCallback vehiclePowerCallback;
    private boolean activeOperationTerminal;
    private PendingGuidance pendingGuidance;
    private PendingGuidance deferredGuidance;
    private boolean guidanceScheduled;
    private boolean guidanceBarrierActive;
    private boolean terminalClearInFlight;
    private long guidanceBarrierToken;
    private int terminalClearProfile = ManeuverCompatibilityProfile.AMAP;
    private boolean terminalClearIncludeNavigationFields = true;
    private boolean terminalClearClusterDebug;
    private long terminalClearOutputGeneration;
    private final List<ResultCallback> terminalClearCallbacks = new ArrayList<>();
    private PendingTerminalGuidanceClear deferredTerminalClear;
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
    private long startupDeadlineAtMs;
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
    private boolean directFidCapable;
    private boolean trafficLightCapable;
    private boolean nativeSpeedCapable;
    private boolean navigationOutputReady;
    private boolean clusterDebugOutputReady;
    private boolean shanghaiSuspended;
    private boolean shanghaiHelperAcknowledged;

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
        this.shanghaiTransitions = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BydHudInstrumentShanghai");
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
            if (shanghaiSuspended) {
                logStartSkip(reason, "shanghai-suspended");
                return;
            }
            if (HudPrefs.isUserShutdownActive(context) && !"owned-gps-cleanup".equals(reason)) {
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
            startupDeadlineAtMs = now + STARTUP_DEADLINE_MS;
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
            if (shanghaiSuspended || !runtimeActive || state != State.STARTING
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
            worker.execute(() -> {
                launch(requestGeneration, requestNonce, requestLaunchToken);
                long remaining;
                synchronized (lock) {
                    remaining = generation == requestGeneration
                            ? Math.max(0L, startupDeadlineAtMs - SystemClock.elapsedRealtime()) : 0L;
                }
                worker.schedule(() -> handleStartTimeout(requestGeneration),
                        remaining, TimeUnit.MILLISECONDS);
            });
        }
    }

    /** Reattach to the authenticated survivor before considering a new helper launch. */
    void awaitExistingRuntime() {
        synchronized (lock) {
            if (state != State.IDLE || !ShellRuntimeSession.mayRestore(context)) return;
            InstrumentProxyStore.Identity existing = helperIdentitySnapshot();
            if (!InstrumentProxyStore.canReconnect(context, existing)) return;
            runtimeActive = true;
            generation = existing.generation;
            nonce = existing.nonce;
            launchToken = existing.token;
            state = State.STARTING;
            startStage = "reattaching";
            startupDeadlineAtMs = SystemClock.elapsedRealtime() + STARTUP_DEADLINE_MS;
            registerHandoffReceiver();
            long expected = generation;
            log("reattach requested generation=" + generation + " pid=" + existing.pid);
            worker.schedule(() -> {
                boolean missing;
                synchronized (lock) {
                    if (state != State.STARTING || generation != expected) return;
                    missing = "reattaching".equals(startStage);
                    if (missing) { state = State.IDLE; lock.notifyAll(); }
                }
                if (missing) ensureStarted("survivor-not-found");
                else handleStartTimeout(expected);
            }, STARTUP_DEADLINE_MS, TimeUnit.MILLISECONDS);
        }
    }

    /** Reads existing references and lifecycle state without calling the helper or its Binder. */
    static JSONObject configurationSnapshot() throws Exception {
        InstrumentProxyManager current = instance;
        if (current == null) {
            return new JSONObject().put("status", "unavailable")
                    .put("reason", "not_initialized");
        }
        synchronized (current.lock) {
            long now = SystemClock.elapsedRealtime();
            return new JSONObject().put("status", "ok").put("source", "process_memory")
                    .put("capturedElapsedMs", now).put("coherence", "existing_state_lock")
                    .put("state", current.state.name()).put("generation", current.generation)
                    .put("runtimeActive", current.runtimeActive).put("startStage", current.startStage)
                    .put("proxyReferencePresent", current.proxy != null)
                    .put("binderReferencePresent", current.proxyBinder != null)
                    .put("connectingProxyReferencePresent", current.connectingProxy != null)
                    .put("connectingBinderReferencePresent", current.connectingBinder != null)
                    .put("connectedAgeMs", current.connectedAtMs > 0L
                            ? Math.max(0L, now - current.connectedAtMs) : JSONObject.NULL)
                    .put("retryDelayMs", Math.max(0L, current.nextRetryAtMs - now))
                    .put("rapidFailureCount", current.rapidFailureCount)
                    .put("pingInFlight", current.pingInFlight)
                    .put("capabilityMode", current.capabilityMode.name())
                    .put("shellRuntimeReady", current.state == State.READY && current.proxy != null)
                    .put("navigationOutputReady", current.navigationOutputReady)
                    .put("clusterDebugOutputReady", current.clusterDebugOutputReady)
                    .put("directFidCapable", current.directFidCapable)
                    .put("trafficLightCapable", current.trafficLightCapable)
                    .put("nativeSpeedCapable", current.nativeSpeedCapable)
                    .put("lastResult", new JSONObject().put("status", "unsupported")
                            .put("reason", "not_recorded"))
                    .put("binderLiveness", "not_queried");
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
                runtimeActive = ShellRuntimeSession.mayRestore(context);
                if (state == State.STARTING && !runtimeActive) {
                    log("start cancelled generation=" + generation
                            + " reason=no-output lastStage=" + startStage);
                    clearProxyLocked();
                    state = State.IDLE;
                    generation = generationCounter.incrementAndGet();
                    nonce = "";
                    launchToken = "";
                    unregisterHandoffReceiver();
                    lock.notifyAll();
                    worker.execute(() -> cleanupHelper("output-ended"));
                }
                return;
            }
            ensureStarted("output:" + safe(reason));
        }
    }

    /** Synchronously surrenders helper-owned output before the stock Shanghai test starts. */
    void suspendForShanghai() throws IOException, InterruptedException {
        IInstrumentNavigationProxy current = null;
        IInstrumentNavigationProxy starting = null;
        long requestGeneration = 0L;
        boolean cancelledStart = false;
        synchronized (lock) {
            shanghaiSuspended = true;
            clusterDebugOutputReady = false;
            outputRetry.cancel();
            if (state == State.READY && proxy != null && proxyBinder != null
                    && proxyBinder.isBinderAlive()) {
                if (shanghaiHelperAcknowledged) return;
                current = proxy;
                requestGeneration = generation;
            } else if (state == State.STARTING) {
                cancelledStart = true;
                starting = connectingProxy;
                requestGeneration = generation;
                clearProxyLocked();
                state = State.IDLE;
                generation = generationCounter.incrementAndGet();
                nonce = "";
                launchToken = "";
                startStage = "shanghai-suspended";
                unregisterHandoffReceiver();
                shanghaiHelperAcknowledged = true;
                lock.notifyAll();
            } else {
                shanghaiHelperAcknowledged = true;
            }
        }
        if (starting != null) shutdownCandidate(starting, requestGeneration);
        if (cancelledStart) resetCallWorker("shanghai-startup-cancelled");
        if (current == null) {
            if (!runShanghaiCleanup()) {
                throw new IOException("Instrument helper cleanup could not be confirmed");
            }
            return;
        }
        awaitWorkerBarrier("suspend lifecycle");
        final IInstrumentNavigationProxy active = current;
        final long activeGeneration = requestGeneration;
        Bundle result = runShanghaiTransition(
                () -> active.suspendOutput(activeGeneration), "suspend");
        String error = InstrumentProxyContract.error(result);
        if (!error.isEmpty()) throw new IOException(error);
        synchronized (lock) {
            if (shanghaiSuspended && proxy == active && generation == activeGeneration) {
                shanghaiHelperAcknowledged = true;
                navigationOutputReady = false;
                lock.notifyAll();
            }
        }
    }

    /** Restores helper output after Shanghai cleanup; callers should invoke this in a finally path. */
    void resumeAfterShanghai() throws IOException, InterruptedException {
        IInstrumentNavigationProxy current;
        IBinder currentBinder;
        long currentGeneration;
        boolean navigationCapable;
        synchronized (lock) {
            current = state == State.READY ? proxy : null;
            currentBinder = state == State.READY ? proxyBinder : null;
            currentGeneration = generation;
            navigationCapable = capabilityMode != CapabilityMode.NONE;
        }
        boolean failed = false;
        boolean resumed = false;
        boolean navigationReadyPublished = false;
        boolean clusterDebugReadyPublished = false;
        try {
            if (current != null) {
                final IInstrumentNavigationProxy active = current;
                final long activeGeneration = currentGeneration;
                Bundle result = runShanghaiTransition(
                        () -> active.resumeOutput(activeGeneration), "resume");
                String error = InstrumentProxyContract.error(result);
                if (!error.isEmpty()) throw new IOException(error);
                resumed = true;
            }
        } catch (IOException | InterruptedException | RuntimeException error) {
            failed = true;
            if (current != null) handleCallFailure(currentGeneration, currentBinder, error);
            throw error;
        } finally {
            boolean restart;
            synchronized (lock) {
                shanghaiSuspended = false;
                shanghaiHelperAcknowledged = false;
                if (resumed && state == State.READY && proxy == current
                        && generation == currentGeneration && proxyBinder != null
                        && proxyBinder.isBinderAlive()) {
                    clusterDebugOutputReady = true;
                    clusterDebugReadyPublished = true;
                    if (navigationCapable) {
                        navigationOutputReady = true;
                        navigationReadyPublished = true;
                    }
                    lock.notifyAll();
                }
                restart = runtimeActive && outputRetry.isActive();
            }
            if (navigationReadyPublished || clusterDebugReadyPublished) notifyReadyListeners();
            if (restart) ensureStarted(failed
                    ? "shanghai-resume-recovery" : "shanghai-resumed");
        }
    }

    private Bundle runShanghaiTransition(ShanghaiTransition transition, String operation)
            throws IOException, InterruptedException {
        Future<Bundle> task = shanghaiTransitions.submit(transition::invoke);
        try {
            return task.get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            task.cancel(true);
            throw new IOException("Instrument helper Shanghai " + operation + " timed out");
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RemoteException) {
                throw new IOException(describe(cause), cause);
            }
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new IOException(describe(cause), cause);
        }
    }

    private void awaitWorkerBarrier(String operation) throws IOException, InterruptedException {
        Future<?> barrier;
        try {
            barrier = worker.submit(() -> { });
        } catch (RejectedExecutionException error) {
            throw new IOException("Instrument helper " + operation + " unavailable", error);
        }
        try {
            barrier.get(START_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            barrier.cancel(true);
            throw new IOException("Instrument helper " + operation + " timed out");
        } catch (ExecutionException error) {
            throw new IOException("Instrument helper " + operation + " failed", error.getCause());
        }
    }

    private boolean runShanghaiCleanup() throws IOException, InterruptedException {
        Future<Boolean> cleanup;
        try {
            cleanup = worker.submit(() -> cleanupHelper("shanghai-suspended"));
        } catch (RejectedExecutionException error) {
            throw new IOException("Instrument helper cleanup unavailable", error);
        }
        try {
            return cleanup.get(START_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            cleanup.cancel(true);
            throw new IOException("Instrument helper cleanup timed out");
        } catch (ExecutionException error) {
            throw new IOException("Instrument helper cleanup failed", error.getCause());
        }
    }

    private void logStartSkip(String reason, String skip) {
        log("start skipped generation=" + generation + " trigger=" + safe(reason)
                + " reason=" + skip);
    }

    void onRuntimeStopped(String reason, boolean forceShutdown) {
        synchronized (lock) {
            if (!forceShutdown && ShellRuntimeSession.mayRestore(context)) return;
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
            // A fresh successful ADB authorization starts a new bounded startup sequence.
            rapidFailureCount = 0;
        }
        ensureStarted("rsa-authorized");
    }

    void addReadyListener(Runnable listener) {
        if (listener == null) return;
        readyListeners.addIfAbsent(listener);
        boolean ready;
        synchronized (lock) {
            ready = state == State.READY && proxy != null
                    && (navigationOutputReady || clusterDebugOutputReady);
        }
        if (ready) listener.run();
    }

    private void notifyReadyListeners() {
        for (Runnable listener : readyListeners) {
            try {
                listener.run();
            } catch (RuntimeException error) {
                log("ready listener failed type=" + error.getClass().getSimpleName());
            }
        }
    }

    void addUnavailableListener(UnavailableListener listener) {
        if (listener != null) unavailableListeners.addIfAbsent(listener);
    }

    /** Invalidates queued status/guidance work and completes after prior calls drain. */
    void fenceNavigationOutput(Runnable onFenced) {
        PendingGuidance pending;
        PendingGuidance deferred;
        PendingTerminalGuidanceClear deferredTerminal;
        List<ResultCallback> cancelledTerminalCallbacks = Collections.emptyList();
        NavigationOutputFence fence = null;
        ExecutorService executor;
        synchronized (callLock) {
            navigationOutputGeneration++;
            pending = pendingGuidance;
            pendingGuidance = null;
            guidanceScheduled = false;
            deferred = deferredGuidance;
            deferredGuidance = null;
            deferredTerminal = deferredTerminalClear;
            deferredTerminalClear = null;
            if (deferredTerminal != null) {
                cancelledTerminalCallbacks = new ArrayList<>(deferredTerminal.callbacks);
            }
            executor = calls;
            if (onFenced != null) {
                fence = new NavigationOutputFence(onFenced, callEpoch);
                navigationOutputFences.add(fence);
            }
        }
        if (pending != null) {
            deliver(pending.callback,
                    Result.unavailable("superseded by navigation output policy change"));
        }
        if (deferred != null) {
            deliver(deferred.callback,
                    Result.unavailable("superseded by navigation output policy change"));
        }
        for (ResultCallback terminalCallback : cancelledTerminalCallbacks) {
            deliver(terminalCallback,
                    Result.unavailable("superseded by navigation output policy change"));
        }
        if (fence != null) {
            NavigationOutputFence requestFence = fence;
            if (!enqueueCall(executor, () -> finishNavigationOutputFence(
                    requestFence, requestFence.callEpoch))) {
                finishNavigationOutputFence(requestFence, requestFence.callEpoch);
            }
        }
    }

    private void finishNavigationOutputFence(
            NavigationOutputFence fence, long scheduledCallEpoch) {
        Runnable callback = null;
        boolean reschedule = false;
        synchronized (callLock) {
            if (!navigationOutputFences.contains(fence)) return;
            if (scheduledCallEpoch != callEpoch || fence.callEpoch != callEpoch) {
                fence.callEpoch = callEpoch;
                reschedule = true;
            } else {
                navigationOutputFences.remove(fence);
                callback = fence.callback;
            }
        }
        if (reschedule) {
            enqueueNavigationOutputFence(fence);
        } else if (callback != null) {
            callback.run();
        }
    }

    private void enqueueNavigationOutputFence(NavigationOutputFence fence) {
        ExecutorService executor;
        long scheduledCallEpoch;
        synchronized (callLock) {
            if (!navigationOutputFences.contains(fence)) return;
            executor = calls;
            scheduledCallEpoch = fence.callEpoch;
        }
        if (!enqueueCall(executor, () -> finishNavigationOutputFence(
                fence, scheduledCallEpoch))) {
            finishNavigationOutputFence(fence, scheduledCallEpoch);
        }
    }

    private long navigationOutputGenerationSnapshot() {
        synchronized (callLock) {
            return navigationOutputGeneration;
        }
    }

    private boolean isNavigationOutputGenerationCurrent(long expectedGeneration) {
        synchronized (callLock) {
            return navigationOutputGeneration == expectedGeneration;
        }
    }

    private static Bundle staleNavigationOutputResult() {
        return InstrumentProxyContract.operationResult(Collections.emptyList(), "", true,
                "superseded by navigation output policy change");
    }

    void sendNavigationStatus(int status, ResultCallback callback) {
        if (!InstrumentProxyContract.validStatus(status)) {
            deliver(callback, Result.unavailable("invalid navigation status"));
            return;
        }
        long outputGeneration = navigationOutputGenerationSnapshot();
        submitCall("navigation_status", callback,
                (current, currentGeneration) -> isNavigationOutputGenerationCurrent(
                        outputGeneration)
                        ? current.sendNavigationStatus(currentGeneration, status)
                        : staleNavigationOutputResult());
    }

    void sendClusterDebugStatus(int status, ResultCallback callback) {
        if (!InstrumentProxyContract.validStatus(status)) {
            deliver(callback, Result.unavailable("invalid navigation status"));
            return;
        }
        long outputGeneration = navigationOutputGenerationSnapshot();
        submitCall("clusterdebug_status", callback,
                (current, currentGeneration) -> isNavigationOutputGenerationCurrent(
                        outputGeneration)
                        ? current.sendClusterDebugStatus(currentGeneration, status)
                        : staleNavigationOutputResult());
    }

    void sendNativeEta(int[] values, AtomicLong admissionRevision,
            long expectedAdmissionRevision, ResultCallback callback) {
        if (!NativeEtaTransportPolicy.validInstrumentValues(values)) {
            deliver(callback, Result.unavailable("invalid native ETA values"));
            return;
        }
        boolean ready;
        long expectedSessionGeneration;
        synchronized (lock) {
            ready = state == State.READY && proxy != null && proxyBinder != null
                    && proxyBinder.isBinderAlive() && navigationOutputReady
                    && directFidCapable;
            expectedSessionGeneration = generation;
        }
        if (!ready) {
            deliver(callback, Result.unavailable("direct FID capability unavailable"));
            return;
        }
        int[] copy = values.clone();
        submitCall("native_eta", callback, (remote, activeGeneration) -> {
            if (admissionRevision == null
                    || admissionRevision.get() != expectedAdmissionRevision) {
                return InstrumentProxyContract.operationResult(
                        Collections.emptyList(), "native ETA request superseded before dispatch");
            }
            if (activeGeneration != expectedSessionGeneration) {
                return InstrumentProxyContract.operationResult(
                        Collections.emptyList(), "native ETA Instrument session changed before dispatch");
            }
            return remote.sendNativeEta(activeGeneration,
                        copy[0], copy[1], copy[2], copy[3], copy[4],
                        copy[5], copy[6], copy[7], copy[8], copy[9]);
        });
    }

    void sendGuidance(int compatibilityProfile, int icon, int distanceMeters, String road,
            int[] laneDirections, int[] laneRecommendations, ResultCallback callback) {
        sendGuidance(compatibilityProfile, icon, distanceMeters, road,
                laneDirections, laneRecommendations, true, callback);
    }

    void sendGuidance(int compatibilityProfile, int icon, int distanceMeters, String road,
            int[] laneDirections, int[] laneRecommendations,
            boolean includeNavigationFields, ResultCallback callback) {
        queueGuidance(compatibilityProfile, icon, distanceMeters, road,
                laneDirections, laneRecommendations, includeNavigationFields,
                false, callback);
    }

    void sendClusterDebugGuidance(int icon, int distanceMeters, String road,
            ResultCallback callback) {
        if (!ClusterDebugPayload.validGuidance(icon, distanceMeters, road)) {
            deliver(callback, Result.unavailable("invalid or oversized ClusterDebug guidance"));
            return;
        }
        queueGuidance(ManeuverCompatibilityProfile.AMAP, icon, distanceMeters, road,
                new int[0], new int[0], true, true, callback);
    }

    private void queueGuidance(int compatibilityProfile, int icon, int distanceMeters,
            String road, int[] laneDirections, int[] laneRecommendations,
            boolean includeNavigationFields, boolean clusterDebug,
            ResultCallback callback) {
        if (!InstrumentProxyContract.validGuidance(compatibilityProfile,
                icon, distanceMeters, road, laneDirections, laneRecommendations)) {
            deliver(callback, Result.unavailable("invalid guidance frame"));
            return;
        }
        PendingGuidance superseded;
        ExecutorService executor = null;
        boolean deferredPath;
        long outputGeneration;
        synchronized (callLock) {
            outputGeneration = navigationOutputGeneration;
            PendingGuidance next = new PendingGuidance(
                    compatibilityProfile, icon, distanceMeters, preserveText(road),
                    laneDirections, laneRecommendations, includeNavigationFields,
                    clusterDebug, outputGeneration, callback);
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
            return state == State.READY && proxy != null && navigationOutputReady
                    && trafficLightCapable;
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
    void sendTerminalGuidanceClear(int compatibilityProfile, ResultCallback callback) {
        sendTerminalGuidanceClear(compatibilityProfile, true, callback);
    }

    void sendTerminalGuidanceClear(int compatibilityProfile,
            boolean includeNavigationFields, ResultCallback callback) {
        enqueueTerminalGuidanceClear(compatibilityProfile, includeNavigationFields,
                false, callback);
    }

    void sendClusterDebugTerminalGuidanceClear(ResultCallback callback) {
        enqueueTerminalGuidanceClear(ManeuverCompatibilityProfile.AMAP,
                true, true, callback);
    }

    private void enqueueTerminalGuidanceClear(int compatibilityProfile,
            boolean includeNavigationFields, boolean clusterDebug,
            ResultCallback callback) {
        if (!clusterDebug
                && !InstrumentProxyContract.validCompatibilityProfile(compatibilityProfile)) {
            deliver(callback, Result.unavailable("invalid guidance profile"));
            return;
        }
        PendingGuidance superseded;
        PendingGuidance deferredSuperseded = null;
        PendingTerminalGuidanceClear deferredTerminalSuperseded = null;
        ExecutorService executor = null;
        long barrierToken = 0L;
        long expectedEpoch = 0L;
        long outputGeneration = 0L;
        synchronized (callLock) {
            superseded = pendingGuidance;
            pendingGuidance = null;
            guidanceScheduled = false;
            if (guidanceBarrierActive) {
                // A second terminal is still the final frame.  Drop the frame
                // deferred behind the first clear instead of replaying it after
                // the joined terminal callbacks complete.
                // Keep the first clear's profile: it describes the output being retired.
                if (shouldDropDeferredGuidanceForDuplicateTerminalForTest(
                        true, deferredGuidance != null)) {
                    deferredSuperseded = deferredGuidance;
                    deferredGuidance = null;
                }
                if (terminalClearClusterDebug == clusterDebug
                        && terminalClearIncludeNavigationFields == includeNavigationFields
                        && terminalClearProfile == compatibilityProfile
                        && terminalClearOutputGeneration == navigationOutputGeneration) {
                    if (callback != null) terminalClearCallbacks.add(callback);
                } else {
                    if (deferredTerminalClear != null
                            && deferredTerminalClear.matches(compatibilityProfile,
                                    includeNavigationFields, clusterDebug,
                                    navigationOutputGeneration)) {
                        deferredTerminalClear.addCallback(callback);
                    } else {
                        deferredTerminalSuperseded = deferredTerminalClear;
                        deferredTerminalClear = new PendingTerminalGuidanceClear(
                                compatibilityProfile, includeNavigationFields,
                                clusterDebug, navigationOutputGeneration, callback);
                    }
                }
            } else {
                guidanceBarrierActive = true;
                terminalClearInFlight = false;
                terminalClearProfile = compatibilityProfile;
                terminalClearIncludeNavigationFields = includeNavigationFields;
                terminalClearClusterDebug = clusterDebug;
                terminalClearOutputGeneration = navigationOutputGeneration;
                barrierToken = ++guidanceBarrierToken;
                expectedEpoch = callEpoch;
                outputGeneration = navigationOutputGeneration;
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
        if (deferredTerminalSuperseded != null) {
            deferredTerminalSuperseded.deliver(Result.unavailable(
                    "superseded by a different terminal clear policy"));
        }
        if (executor == null) return;
        final long requestToken = barrierToken;
        final long requestEpoch = expectedEpoch;
        final long requestOutputGeneration = outputGeneration;
        if (!enqueueCall(executor,
                () -> executeTerminalGuidanceClear(requestToken, requestEpoch,
                        requestOutputGeneration))) {
            finishTerminalGuidanceClear(requestToken,
                    Result.unavailable("proxy call worker unavailable"));
        }
    }

    static boolean shouldDropDeferredGuidanceForDuplicateTerminalForTest(
            boolean barrierActive, boolean deferredPresent) {
        return barrierActive && deferredPresent;
    }

    private void executeTerminalGuidanceClear(long requestToken, long requestEpoch,
            long outputGeneration) {
        int outgoingProfile;
        boolean includeNavigationFields;
        boolean clusterDebug;
        synchronized (callLock) {
            if (!guidanceBarrierActive || requestToken != guidanceBarrierToken
                    || requestEpoch != callEpoch) {
                return;
            }
            includeNavigationFields = terminalClearIncludeNavigationFields;
            clusterDebug = terminalClearClusterDebug;
            terminalClearInFlight = true;
            outgoingProfile = terminalClearProfile;
        }
        executeCall(clusterDebug ? "terminal_clusterdebug_guidance_clear"
                        : "terminal_guidance_clear",
                result -> finishTerminalGuidanceClear(requestToken, result),
                (current, currentGeneration) -> isNavigationOutputGenerationCurrent(
                        outputGeneration)
                        ? clusterDebug
                                ? current.sendClusterDebugGuidance(currentGeneration,
                                        0, -1, "")
                                : current.sendGuidance(currentGeneration, outgoingProfile,
                                0, -1, "", new int[0], new int[0],
                                includeNavigationFields)
                        : staleNavigationOutputResult());
    }

    private void finishTerminalGuidanceClear(long requestToken, Result result) {
        List<ResultCallback> callbacks;
        ExecutorService executor = null;
        PendingGuidance deferred;
        PendingTerminalGuidanceClear nextTerminal;
        long nextBarrierToken = 0L;
        long nextOutputGeneration = 0L;
        long nextEpoch = 0L;
        synchronized (callLock) {
            if (!guidanceBarrierActive || requestToken != guidanceBarrierToken) return;
            terminalClearInFlight = false;
            callbacks = new ArrayList<>(terminalClearCallbacks);
            terminalClearCallbacks.clear();
            nextTerminal = deferredTerminalClear;
            deferredTerminalClear = null;
            if (nextTerminal != null) {
                guidanceBarrierActive = true;
                terminalClearProfile = nextTerminal.compatibilityProfile;
                terminalClearIncludeNavigationFields = nextTerminal.includeNavigationFields;
                terminalClearClusterDebug = nextTerminal.clusterDebug;
                terminalClearOutputGeneration = nextTerminal.outputGeneration;
                terminalClearCallbacks.addAll(nextTerminal.callbacks);
                nextBarrierToken = ++guidanceBarrierToken;
                nextOutputGeneration = nextTerminal.outputGeneration;
                nextEpoch = callEpoch;
                executor = calls;
                deferred = null;
            } else {
                guidanceBarrierActive = false;
                terminalClearProfile = ManeuverCompatibilityProfile.AMAP;
                terminalClearIncludeNavigationFields = true;
                terminalClearClusterDebug = false;
                terminalClearOutputGeneration = navigationOutputGeneration;
                deferred = deferredGuidance;
                deferredGuidance = null;
                if (deferred != null) {
                    pendingGuidance = deferred;
                    guidanceScheduled = true;
                    executor = calls;
                }
            }
        }
        if (nextTerminal != null) {
            long nextRequestToken = nextBarrierToken;
            long requestEpoch = nextEpoch;
            long requestOutputGeneration = nextOutputGeneration;
            if (!enqueueCall(executor, () -> executeTerminalGuidanceClear(
                    nextRequestToken, requestEpoch, requestOutputGeneration))) {
                finishTerminalGuidanceClear(nextRequestToken,
                        Result.unavailable("proxy call worker unavailable"));
            }
        } else if (deferred != null && !enqueueCall(executor, this::drainGuidance)) {
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
        executeCall(request.clusterDebug ? "clusterdebug_guidance" : "guidance",
                request.callback,
                (current, currentGeneration) -> isNavigationOutputGenerationCurrent(
                        request.outputGeneration)
                        ? request.clusterDebug
                                ? current.sendClusterDebugGuidance(currentGeneration,
                                        request.icon, request.distanceMeters, request.road)
                                : current.sendGuidance(currentGeneration,
                                request.compatibilityProfile, request.icon,
                                request.distanceMeters, request.road,
                                request.laneDirections, request.laneRecommendations,
                                request.includeNavigationFields)
                        : staleNavigationOutputResult());
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
        // Reading power is independent of navigation output ownership, including Shanghai.
        if ("vehicle_power".equals(operation)) {
            executeAllowedCall(operation, callback, remoteCall);
            return;
        }
        if (!ShanghaiOutputGate.enterWrite()) {
            deliver(callback, Result.unavailable("stock Shanghai test owns navigation output"));
            return;
        }
        try { executeAllowedCall(operation, callback, remoteCall); }
        finally { ShanghaiOutputGate.leaveWrite(); }
    }

    void readVehiclePower(ResultCallback callback) {
        boolean ready;
        synchronized (lock) { ready = state == State.READY && proxyBinder != null && proxyBinder.isBinderAlive(); }
        if (!ready) {
            ensureStarted("dashboard-widget-power");
            deliver(callback, Result.unavailable("vehicle power helper not ready"));
            return;
        }
        synchronized (callLock) {
            if (vehiclePowerCallback != null) {
                deliver(callback, Result.unavailable("vehicle power read already pending"));
                return;
            }
            vehiclePowerCallback = callback;
        }
        submitCall("vehicle_power", result -> {
            synchronized (callLock) {
                if (vehiclePowerCallback != callback) return;
                vehiclePowerCallback = null;
            }
            deliver(callback, result);
        }, (remote, requestGeneration) -> {
            synchronized (callLock) {
                if (vehiclePowerCallback != callback) {
                    return InstrumentProxyContract.operationResult(Collections.emptyList(),
                            "vehicle power read cancelled before dispatch");
                }
            }
            return remote.readVehiclePower(requestGeneration);
        });
    }

    void nativeSpeedOperation(int operation, int value, BooleanSupplier current,
            Consumer<NativeSpeedLimitEngine.Result> callback) {
        if (!NativeSpeedLimitEngine.validOperation(operation, value)) {
            throw new IllegalArgumentException("invalid native speed operation");
        }
        boolean ready;
        synchronized (lock) { ready = state == State.READY && proxyBinder != null && proxyBinder.isBinderAlive(); }
        if (!ready) ensureStarted("native-speed");
        submitCall("native_speed:" + operation, result -> {
            NativeSpeedLimitEngine.Result nativeResult = result.nativeSpeed;
            long now = SystemClock.elapsedRealtime();
            callback.accept(nativeResult != null ? nativeResult
                    : new NativeSpeedLimitEngine.Result(false, 0, now, now, result.error));
        }, (remote, requestGeneration) -> {
            if (!current.getAsBoolean()) {
                return InstrumentProxyContract.operationResult(Collections.emptyList(), "cancelled before dispatch");
            }
            // A late-initializing Setting/ADAS API may recover during the same helper session.
            return remote.nativeSpeedOperation(requestGeneration, operation, value);
        });
    }

    private void executeAllowedCall(
            String operation, ResultCallback callback, RemoteCall remoteCall) {
        IInstrumentNavigationProxy current;
        IBinder currentBinder;
        long currentGeneration;
        boolean outputReady;
        synchronized (lock) {
            if (shanghaiSuspended && !"vehicle_power".equals(operation)) {
                deliver(callback, Result.unavailable(
                        "stock Shanghai test owns navigation output"));
                return;
            }
            current = proxy;
            currentBinder = proxyBinder;
            currentGeneration = generation;
            if (isClusterDebugOperation(operation)) {
                outputReady = clusterDebugOutputReady;
            } else {
                outputReady = navigationOutputReady || operation.startsWith("native_speed:")
                        || ("vehicle_power".equals(operation) && state == State.READY);
            }
        }
        if (!outputReady) {
            deliver(callback, Result.unavailable(isClusterDebugOperation(operation)
                    ? "ClusterDebug shell output unavailable"
                    : "navigation output capability unavailable"));
            return;
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
                    || "terminal_clusterdebug_guidance_clear".equals(operation)
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
            result = Result.unavailable("vehicle_power".equals(operation)
                    ? "vehicle_power IPC: " + Log.getStackTraceString(error)
                    : isClusterDebugOperation(operation)
                            ? describeClusterDebugFailure(operation, error) : describe(error));
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
        ResultCallback powerCallback;
        PendingGuidance pending;
        PendingGuidance deferred;
        PendingTrafficLight trafficPending;
        PendingTrafficLight trafficDeferred;
        List<ResultCallback> resetTerminalCallbacks = null;
        List<ResultCallback> resetTrafficLightCallbacks = null;
        List<NavigationOutputFence> navigationFences;
        boolean activeTerminal;
        boolean scheduleDeferred = false;
        ExecutorService deferredExecutor = null;
        synchronized (callLock) {
            previous = calls;
            callEpoch++;
            calls = newCallExecutor(callEpoch);
            navigationFences = new ArrayList<>(navigationOutputFences);
            for (NavigationOutputFence fence : navigationFences) {
                fence.callEpoch = callEpoch;
            }
            activeOperationId = 0L;
            activeOperationGeneration = 0L;
            activeCallback = activeOperationCallback;
            powerCallback = vehiclePowerCallback;
            vehiclePowerCallback = null;
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
                terminalClearProfile = ManeuverCompatibilityProfile.AMAP;
                terminalClearIncludeNavigationFields = true;
                terminalClearClusterDebug = false;
                terminalClearOutputGeneration = navigationOutputGeneration;
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
            if (deferredTerminalClear != null) {
                if (resetTerminalCallbacks == null) resetTerminalCallbacks = new ArrayList<>();
                resetTerminalCallbacks.addAll(deferredTerminalClear.callbacks);
                deferredTerminalClear = null;
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
        // Generic queued work is discarded by shutdownNow even before it becomes active.
        if (powerCallback != null) {
            deliver(powerCallback, Result.unavailable("vehicle power read cancelled: " + safe(reason)));
        }
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
        for (NavigationOutputFence fence : navigationFences) {
            enqueueNavigationOutputFence(fence);
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
            if (shanghaiSuspended || !runtimeActive || state != State.STARTING
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
            navigationOutputReady = false;
            startStage = "shutdown";
            // Queue cleanup before a later lifecycle request can queue its launch.
            if (current != null) shutdownCandidate(current, currentGeneration);
            worker.execute(() -> cleanupHelper("shutdown"));
            unregisterHandoffReceiver();
            lock.notifyAll();
        }
        resetCallWorker("shutdown");
        log("shutdown reason=" + safe(reason));
    }

    private void launch(long requestGeneration, String requestNonce,
            String requestLaunchToken) {
        synchronized (lock) {
            if (shanghaiSuspended || !runtimeActive || state != State.STARTING
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
            LocalAdbBridge.ShellResult result;
            try {
                result = LocalAdbBridge.launchInstrumentProxy(
                        context,
                        context.getApplicationInfo().sourceDir,
                        requestGeneration,
                        requestNonce,
                        appUid,
                        requestLaunchToken,
                        BuildConfig.VERSION_CODE);
            } catch (IOException error) {
                // The child may already have handed off its Binder. Only the authenticated
                // connect result can establish readiness; preserve the pending identity.
                if (observeStartStage(requestGeneration, "launch-transport-uncertain")) {
                    log("launch transport uncertain generation=" + requestGeneration
                            + " error=" + error.getClass().getSimpleName()
                            + " replay=false awaiting=authenticated-handoff timeoutMs="
                            + START_TIMEOUT_MS);
                }
                return;
            }
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
                log("launch confirmed generation=" + requestGeneration + " pid=" + launchedPid
                        + " exit=" + result.exitCode);
            }
        } catch (IOException | RuntimeException error) {
            failStart(requestGeneration, error.getClass().getSimpleName(), true);
        }
    }

    private boolean observeStartStage(long requestGeneration, String stage) {
        synchronized (lock) {
            if (shanghaiSuspended || !runtimeActive || state != State.STARTING
                    || generation != requestGeneration) {
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
            if (shanghaiSuspended || !runtimeActive || state != State.STARTING
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
            if (shanghaiSuspended || !runtimeActive || state != State.STARTING
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
            if (shanghaiSuspended || !runtimeActive || state != State.STARTING
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
        if (pendingIdentity.startTimeTicks > 0
                && pendingIdentity.startTimeTicks != InstrumentProxyContract.proxyStartTimeTicks(result)) {
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
        boolean connectedNavigationOutput =
                InstrumentProxyContract.hasUsableNavigationCapability(result);
        if (!InstrumentProxyContract.isReady(result)
                || !InstrumentProxyContract.hasUsableCapability(result)) {
            shutdownCandidate(candidate, requestGeneration);
            synchronized (lock) {
                if (generation != requestGeneration || state != State.STARTING) return;
                clearProxyLocked();
                state = State.BLOCKED;
                outputRetry.cancel();
                capabilityMode = CapabilityMode.NONE;
                navigationOutputReady = false;
                clusterDebugOutputReady = false;
                nextRetryAtMs = Long.MAX_VALUE;
                unregisterHandoffReceiver();
                worker.execute(() -> cleanupHelper("capability-blocked"));
                lock.notifyAll();
            }
            log("capability circuit open generation=" + requestGeneration
                    + " capabilities=" + InstrumentProxyContract.capabilities(result)
                    + " error=" + InstrumentProxyContract.error(result));
            return;
        }
        try {
            candidate.setRecoveryEnabled(requestGeneration, ShellRuntimeSession.mayRestore(context));
        } catch (RemoteException error) {
            failStart(requestGeneration, "recovery handshake failed", true);
            return;
        }
        synchronized (lock) {
            if (shanghaiSuspended || !runtimeActive || state != State.STARTING
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
            navigationOutputReady = false;
            clusterDebugOutputReady = false;
            trafficLightCapable = connectedTrafficLight;
            nativeSpeedCapable = InstrumentProxyContract.hasCapability(result,
                    InstrumentProxyContract.CAP_NATIVE_SPEED);
            directFidCapable = InstrumentProxyContract.hasCapability(result,
                    InstrumentProxyContract.CAP_DIRECT_FID);
            connectedAtMs = SystemClock.elapsedRealtime();
            nextRetryAtMs = 0L;
            // Preserve the failure streak: a handshaken helper may still die before stability.
            unregisterHandoffReceiver();
            lock.notifyAll();
        }
        log("ready generation=" + requestGeneration
                + " pid=" + connectedIdentity.pid
                + " uid=" + connectedIdentity.uid
                + " capabilities=" + connectedMode);
        // Do not hold the manager lock across Binder. Publish navigation readiness only after
        // the output resume succeeds and this generation is still the active connection.
        boolean shouldResume = !ShanghaiOutputGate.isSuspended()
                && !new ShanghaiMockGps(context).hasPendingRecovery();
        if (shouldResume) {
            try {
                Bundle resumed = runShanghaiTransition(
                        () -> candidate.resumeOutput(requestGeneration), "recover");
                String error = InstrumentProxyContract.error(resumed);
                if (!error.isEmpty()) throw new IOException(error);
            } catch (Exception error) {
                handleCallFailure(requestGeneration, binder, error);
                return;
            }
        }
        boolean outputReady = false;
        boolean clusterDebugReady = false;
        synchronized (lock) {
            if (shouldResume && proxy == candidate && proxyBinder == binder
                    && state == State.READY && generation == requestGeneration
                    && !shanghaiSuspended && binder.isBinderAlive()) {
                clusterDebugOutputReady = true;
                clusterDebugReady = true;
                if (connectedNavigationOutput) {
                    navigationOutputReady = true;
                    outputReady = true;
                }
                lock.notifyAll();
            }
        }
        worker.execute(() -> clearStartupDiagnostic(connectedIdentity));
        LogcatRecorder.recoverShellCapture(context);
        if (outputReady || clusterDebugReady) notifyReadyListeners();
    }

    Bundle registerWork(String id, String type, java.io.File artifact, JSONObject request,
            android.os.ParcelFileDescriptor requestFile, int offset,
            android.os.ParcelFileDescriptor[] snapshots, boolean complete, int boot) throws IOException {
        boolean cleanupOnly = "shanghai".equals(type) && "cleanup".equals(request.optString("action"));
        if (!cleanupOnly && !ShellRuntimeSession.arm(context))
            throw new IOException("Shell runtime is not armed for this operation");
        IInstrumentNavigationProxy current = awaitShellProxy(
                cleanupOnly ? "owned-gps-cleanup" : "independent-work");
        long currentGeneration;
        synchronized (lock) { currentGeneration = generation; }
        try {
            current.setRecoveryEnabled(currentGeneration, ShellRuntimeSession.mayRestore(context));
            return current.registerWork(currentGeneration, id, type, artifact.getCanonicalPath(),
                    boot, requestFile, offset, snapshots, complete);
        } catch (RemoteException error) {
            throw new IOException("Cannot register trusted shell operation", error);
        }
    }

    Bundle workState(String id) throws IOException {
        IInstrumentNavigationProxy current = awaitShellProxy("independent-work-state");
        long currentGeneration;
        synchronized (lock) { currentGeneration = generation; }
        try { return current.workState(currentGeneration, id); }
        catch (RemoteException error) { throw new IOException("Cannot read trusted shell state", error); }
    }

    Bundle retireExportArtifacts(String request, boolean acquire) throws IOException {
        IInstrumentNavigationProxy current;
        if (acquire) {
            try { current = awaitShellProxy("export-retention"); }
            catch (IOException unavailable) { throw new IOException("cleanup_transport_unavailable", unavailable); }
        }
        else synchronized (lock) { current = state == State.READY ? proxy : null; }
        long currentGeneration;
        synchronized (lock) { currentGeneration = generation; }
        if (current == null) throw new IOException("cleanup_transport_unavailable");
        try {
            Bundle reply = current.retireExportArtifacts(currentGeneration, request);
            if (reply == null || reply.getInt("schema", 0) != 1)
                throw new IOException("cleanup_protocol_unavailable");
            return reply;
        } catch (RemoteException | RuntimeException error) {
            // An older same-version retained helper may not implement the appended AIDL call.
            throw new IOException("cleanup_transport_or_protocol_unavailable", error);
        }
    }

    void completeWorkSign(String id, String requestId, boolean ok, String detail) throws IOException {
        IInstrumentNavigationProxy current = awaitShellProxy("independent-work-sign");
        long currentGeneration;
        synchronized (lock) { currentGeneration = generation; }
        try { current.completeWorkSign(currentGeneration, id, requestId, ok, detail); }
        catch (RemoteException error) { throw new IOException("Cannot complete trusted signing request", error); }
    }

    void launchWork(String id) throws IOException { launchWork(id, false); }

    void launchWork(String id, boolean cleanupOnly) throws IOException {
        if (!cleanupOnly && !ShellRuntimeSession.arm(context))
            throw new IOException("Shell runtime is not armed for this operation");
        IInstrumentNavigationProxy current = awaitShellProxy(
                cleanupOnly ? "owned-gps-cleanup" : "independent-work");
        long currentGeneration;
        synchronized (lock) { currentGeneration = generation; }
        try {
            current.setRecoveryEnabled(currentGeneration, ShellRuntimeSession.mayRestore(context));
            current.launchWork(currentGeneration, id);
        } catch (RemoteException error) { throw new IOException("Cannot launch trusted shell operation", error); }
    }

    private IInstrumentNavigationProxy awaitShellProxy(String reason) throws IOException {
        ensureStarted(reason);
        synchronized (lock) {
            while (state == State.STARTING) {
                long remaining = startupDeadlineAtMs - SystemClock.elapsedRealtime();
                if (remaining <= 0L) break;
                try { lock.wait(remaining); }
                catch (InterruptedException error) {
                    Thread.currentThread().interrupt(); throw new IOException("Interrupted waiting for shell runtime", error);
                }
            }
            if (state == State.READY && proxy != null && proxyBinder != null && proxyBinder.isBinderAlive())
                return proxy;
            throw new IOException("Trusted shell runtime unavailable: " + state.name().toLowerCase(java.util.Locale.ROOT)
                    + " stage=" + startStage);
        }
    }

    boolean cancelWork(String id) {
        return cancelWork(id, false);
    }

    boolean cancelWork(String id, boolean resetAnyMockGps) {
        IInstrumentNavigationProxy current;
        long currentGeneration;
        synchronized (lock) { current = proxy; currentGeneration = generation; }
        try { return current != null && current.cancelWork(
                currentGeneration, id, resetAnyMockGps); }
        catch (RemoteException | RuntimeException error) { return false; }
    }

    Bundle startSystemCapture(String id, String cursor, android.os.ParcelFileDescriptor file)
            throws IOException {
        IInstrumentNavigationProxy current = awaitShellProxy("logcat-capture");
        long currentGeneration;
        synchronized (lock) { currentGeneration = generation; }
        try { return current.startSystemCapture(currentGeneration, id, cursor, file); }
        catch (RemoteException error) { throw new IOException(error); }
    }

    static final class RuntimeUnavailableException extends IOException {
        RuntimeUnavailableException() { super("shell runtime unavailable before capture dispatch"); }
    }

    Bundle systemCaptureState(String id, boolean stop) throws IOException {
        IInstrumentNavigationProxy current;
        long currentGeneration;
        synchronized (lock) { current = proxy; currentGeneration = generation; }
        if (current == null) throw new IOException("shell runtime unavailable");
        try { return current.systemCaptureState(currentGeneration, id, stop); }
        catch (RemoteException error) { throw new IOException(error); }
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
            lock.notifyAll();
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
            rapidFailureCount = binderFailureCountAfterDisconnect(rapidFailureCount, uptime);
            clearProxyLocked();
            state = State.IDLE;
            lock.notifyAll();
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

    private boolean cleanupHelper(String reason) {
        InstrumentProxyStore.Identity expected = helperIdentitySnapshot();
        log("cleanup started generation=" + expected.generation + " reason=" + safe(reason));
        if ("start-failed".equals(reason) || "capability-blocked".equals(reason)) {
            try {
                String diagnostic = LocalAdbBridge.instrumentProxyStartupDiagnostic(
                        context, expected);
                log("startup diagnostic generation=" + expected.generation + " "
                        + diagnostic);
                clearStartupDiagnostic(expected);
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
                return false;
            } else {
                if (!clearHelperIdentity(expected)) {
                    log("cleanup identity clear failed reason=" + safe(reason));
                    return false;
                } else {
                    log("cleanup complete generation=" + expected.generation
                            + " reason=" + safe(reason));
                    return true;
                }
            }
        } catch (IOException error) {
            log("cleanup failed reason=" + safe(reason)
                    + " generation=" + expected.generation
                    + " error=" + error.getClass().getSimpleName());
            return false;
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
        navigationOutputReady = false;
        clusterDebugOutputReady = false;
        trafficLightCapable = false;
        directFidCapable = false;
        nativeSpeedCapable = false;
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

    static int binderFailureCountAfterDisconnect(int priorFailures, long connectedDurationMs) {
        return (connectedDurationMs >= STABLE_CONNECTION_MS ? 0 : priorFailures) + 1;
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

    private static boolean isClusterDebugOperation(String operation) {
        return operation != null && (operation.startsWith("clusterdebug_")
                || "terminal_clusterdebug_guidance_clear".equals(operation));
    }

    private static String describeClusterDebugFailure(String operation, Throwable error) {
        StringWriter trace = new StringWriter();
        if (error != null) error.printStackTrace(new PrintWriter(trace));
        return "stage=ipc_call;operation=" + safe(operation) + "\n"
                + (error == null ? "unknown ClusterDebug IPC failure" : trace.toString());
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

    private interface ShanghaiTransition {
        Bundle invoke() throws RemoteException;
    }

    private static final class PendingGuidance {
        final int compatibilityProfile;
        final int icon;
        final int distanceMeters;
        final String road;
        final int[] laneDirections;
        final int[] laneRecommendations;
        final boolean includeNavigationFields;
        final boolean clusterDebug;
        final long outputGeneration;
        final ResultCallback callback;

        PendingGuidance(int compatibilityProfile, int icon, int distanceMeters,
                String road, int[] laneDirections, int[] laneRecommendations,
                boolean includeNavigationFields, boolean clusterDebug,
                long outputGeneration,
                ResultCallback callback) {
            this.compatibilityProfile = compatibilityProfile;
            this.icon = icon;
            this.distanceMeters = distanceMeters;
            this.road = road;
            this.laneDirections = laneDirections.clone();
            this.laneRecommendations = laneRecommendations.clone();
            this.includeNavigationFields = includeNavigationFields;
            this.clusterDebug = clusterDebug;
            this.outputGeneration = outputGeneration;
            this.callback = callback;
        }
    }

    private static final class NavigationOutputFence {
        final Runnable callback;
        long callEpoch;

        NavigationOutputFence(Runnable callback, long callEpoch) {
            this.callback = callback;
            this.callEpoch = callEpoch;
        }
    }

    private static final class PendingTerminalGuidanceClear {
        final int compatibilityProfile;
        final boolean includeNavigationFields;
        final boolean clusterDebug;
        final long outputGeneration;
        final List<ResultCallback> callbacks = new ArrayList<>();

        PendingTerminalGuidanceClear(int compatibilityProfile,
                boolean includeNavigationFields, boolean clusterDebug,
                long outputGeneration,
                ResultCallback callback) {
            this.compatibilityProfile = compatibilityProfile;
            this.includeNavigationFields = includeNavigationFields;
            this.clusterDebug = clusterDebug;
            this.outputGeneration = outputGeneration;
            addCallback(callback);
        }

        boolean matches(int profile, boolean includeFields, boolean useClusterDebug,
                long generation) {
            return compatibilityProfile == profile
                    && includeNavigationFields == includeFields
                    && clusterDebug == useClusterDebug
                    && outputGeneration == generation;
        }

        void addCallback(ResultCallback callback) {
            if (callback != null) callbacks.add(callback);
        }

        void deliver(Result result) {
            for (ResultCallback callback : callbacks) {
                InstrumentProxyManager.deliver(callback, result);
            }
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
        final boolean intentionallySkipped;
        final String skipReason;
        final String navigationDetail;
        NativeSpeedLimitEngine.Result nativeSpeed;
        int vehiclePower = -1;

        private Result(boolean available,
                List<InstrumentProxyContract.Operation> operations, String error,
                boolean intentionallySkipped, String skipReason, String navigationDetail) {
            this.available = available;
            this.operations = operations;
            this.error = safe(error);
            this.intentionallySkipped = intentionallySkipped;
            this.skipReason = safe(skipReason);
            this.navigationDetail = safe(navigationDetail);
        }

        static Result from(Bundle result) {
            String error = InstrumentProxyContract.error(result);
            List<InstrumentProxyContract.Operation> operations =
                    InstrumentProxyContract.operations(result);
            Result parsed = new Result(result != null, operations, error,
                    InstrumentProxyContract.intentionallySkipped(result),
                    InstrumentProxyContract.skipReason(result),
                    InstrumentProxyContract.navigationDetail(result));
            if (result != null && error.isEmpty()) {
                parsed.vehiclePower = result.getInt("vehicle_power", -1);
            }
            if (result != null && result.containsKey("native_started")) {
                parsed.nativeSpeed = new NativeSpeedLimitEngine.Result(error.isEmpty(),
                        result.getInt("native_raw"), result.getLong("native_started"),
                        result.getLong("native_finished"), error);
            }
            return parsed;
        }

        static Result unavailable(String error) {
            return new Result(false, Collections.emptyList(), error, false, "", "");
        }
    }
}
