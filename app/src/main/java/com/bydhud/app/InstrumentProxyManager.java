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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
    private boolean pingInFlight;
    private long pingToken;
    private InstrumentProxyStore.Identity helperIdentity =
            InstrumentProxyStore.Identity.none();
    private boolean helperIdentityLoaded;
    private CapabilityMode capabilityMode = CapabilityMode.NONE;

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
                return;
            }
            runtimeActive = true;
            long now = SystemClock.elapsedRealtime();
            if (state == State.READY && proxyBinder != null && proxyBinder.isBinderAlive()) {
                schedulePingLocked(proxy, generation);
                return;
            }
            if (state == State.STARTING || state == State.BLOCKED || now < nextRetryAtMs) {
                return;
            }
            if (state == State.READY) {
                staleProxy = proxy;
                staleGeneration = generation;
                clearProxyLocked();
            }
            state = State.STARTING;
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
        try {
            registerHandoffReceiver();
        } catch (RuntimeException error) {
            failStart(requestGeneration, "receiver " + describe(error), false);
            return;
        }
        synchronized (lock) {
            if (!runtimeActive || state != State.STARTING
                    || generation != requestGeneration || !nonce.equals(requestNonce)
                    || !launchToken.equals(requestLaunchToken)) {
                unregisterHandoffReceiver();
                return;
            }
        }
        log("start requested generation=" + requestGeneration + " reason=" + safe(reason));
        worker.execute(() -> launch(
                requestGeneration, requestNonce, requestLaunchToken));
        worker.schedule(() -> handleStartTimeout(requestGeneration),
                START_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    void onAuthorizationVerified() {
        synchronized (lock) {
            if (state == State.BLOCKED) return;
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
            activeOperationTerminal = "terminal_guidance_clear".equals(operation);
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
        if (currentResult) deliver(callback, result);
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
        List<ResultCallback> resetTerminalCallbacks = null;
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
        }
        worker.execute(() -> beginConnect(requestGeneration, requestNonce, binder));
    }

    void shutdown(String reason) {
        IInstrumentNavigationProxy current;
        long currentGeneration;
        synchronized (lock) {
            runtimeActive = false;
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
        }
        unregisterHandoffReceiver();
        resetCallWorker("shutdown");
        if (current != null) {
            shutdownCandidate(current, currentGeneration);
        }
        worker.execute(() -> cleanupHelper("shutdown"));
        log("shutdown reason=" + safe(reason));
    }

    private void launch(long requestGeneration, String requestNonce,
            String requestLaunchToken) {
        synchronized (lock) {
            if (!runtimeActive || state != State.STARTING
                    || generation != requestGeneration || !nonce.equals(requestNonce)
                    || !launchToken.equals(requestLaunchToken)) {
                unregisterHandoffReceiver();
                return;
            }
        }
        if (!LocalAdbBridge.isCurrentKeyKnownAuthorized(context)) {
            failStart(requestGeneration, "rsa key not authorized", false);
            return;
        }
        try {
            int appUid = context.getApplicationInfo().uid;
            if (!migrateLegacyHelperOnce(appUid, requestGeneration)) {
                return;
            }
            InstrumentProxyStore.Identity staleIdentity = helperIdentitySnapshot();
            LocalAdbBridge.ShellResult cleanup =
                    LocalAdbBridge.stopInstrumentProxy(context, staleIdentity);
            if (!cleanup.success()) {
                failStart(requestGeneration,
                        "stale cleanup " + cleanup.shortDetail(), false);
                return;
            }
            if (!clearHelperIdentity(staleIdentity)) {
                failStart(requestGeneration,
                        "stale identity clear persistence failed", false);
                return;
            }

            InstrumentProxyStore.Identity pending = InstrumentProxyStore.Identity.pending(
                    appUid, requestGeneration, requestNonce,
                    requestLaunchToken, BuildConfig.VERSION_CODE);
            if (!setHelperIdentity(pending)) {
                failStart(requestGeneration, "pending identity persistence failed", false);
                return;
            }
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
                        "launch " + result.shortDetail(), result.exitCode != 126);
                return;
            }
            int launchedPid = LocalAdbBridge.instrumentProxyPid(result);
            if (launchedPid <= 0 || !setHelperIdentity(pending.withPid(launchedPid))) {
                failStart(requestGeneration, "launch pid persistence failed", true);
            }
        } catch (IOException | RuntimeException error) {
            failStart(requestGeneration, describe(error), true);
        }
    }

    private boolean migrateLegacyHelperOnce(int appUid, long requestGeneration)
            throws IOException {
        if (InstrumentProxyStore.legacyMigrationComplete(context, appUid)) return true;
        LocalAdbBridge.ShellResult result =
                LocalAdbBridge.stopLegacyInstrumentProxy(context, appUid);
        if (!result.success()) {
            failStart(requestGeneration,
                    "legacy cleanup " + result.shortDetail(), false);
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
        if (!InstrumentProxyContract.isReady(result)
                || !InstrumentProxyContract.hasUsableNavigationCapability(result)
                || connectedMode == CapabilityMode.NONE) {
            shutdownCandidate(candidate, requestGeneration);
            synchronized (lock) {
                if (generation != requestGeneration || state != State.STARTING) return;
                clearProxyLocked();
                state = State.BLOCKED;
                capabilityMode = CapabilityMode.NONE;
                nextRetryAtMs = Long.MAX_VALUE;
            }
            unregisterHandoffReceiver();
            log("capability circuit open generation=" + requestGeneration
                    + " capabilities=" + InstrumentProxyContract.capabilities(result)
                    + " error=" + InstrumentProxyContract.error(result));
            worker.execute(() -> cleanupHelper("capability-blocked"));
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
            helperIdentity = connectedIdentity;
            capabilityMode = connectedMode;
            connectedAtMs = SystemClock.elapsedRealtime();
            nextRetryAtMs = 0L;
        }
        unregisterHandoffReceiver();
        log("ready generation=" + requestGeneration
                + " pid=" + connectedIdentity.pid
                + " uid=" + connectedIdentity.uid
                + " capabilities=" + connectedMode);
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
        synchronized (lock) {
            if (state != State.STARTING || generation != requestGeneration) return;
        }
        failStart(requestGeneration, "handoff timeout", true);
    }

    private void failStart(long requestGeneration, String error, boolean retryOnce) {
        boolean immediateRetry = false;
        synchronized (lock) {
            if (generation != requestGeneration || state != State.STARTING) return;
            clearProxyLocked();
            state = State.IDLE;
            rapidFailureCount++;
            if (shouldRetryStart(runtimeActive, retryOnce, rapidFailureCount)) {
                nextRetryAtMs = 0L;
                immediateRetry = true;
            } else {
                nextRetryAtMs = SystemClock.elapsedRealtime() + RETRY_DELAY_MS;
            }
        }
        unregisterHandoffReceiver();
        log("start failed generation=" + requestGeneration + " error=" + safe(error)
                + " immediateRetry=" + immediateRetry);
        worker.execute(() -> cleanupHelper("start-failed"));
        if (immediateRetry) ensureStarted("bounded-retry");
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
        }
        resetCallWorker("binder-death");
        worker.execute(() -> cleanupHelper("binder-death"));
        log("binder died generation=" + requestGeneration + " immediateRetry=" + retry);
        if (wasReady) notifyUnavailable("binder-death");
        if (retry) ensureStarted("binder-death");
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
        try {
            LocalAdbBridge.ShellResult result =
                    LocalAdbBridge.stopInstrumentProxy(context, expected);
            if (!result.success()) {
                log("cleanup failed reason=" + safe(reason)
                        + " result=" + result.shortDetail());
            } else {
                if (!clearHelperIdentity(expected)) {
                    log("cleanup identity clear failed reason=" + safe(reason));
                }
            }
        } catch (IOException error) {
            log("cleanup failed reason=" + safe(reason)
                    + " error=" + describe(error));
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
