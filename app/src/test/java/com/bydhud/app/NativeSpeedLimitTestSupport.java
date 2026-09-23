package com.bydhud.app;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Shared test doubles at the Android and vehicle transport boundaries. */
final class NativeSpeedLimitTestSupport {
    static final List<String> EVENTS = Collections.synchronizedList(new ArrayList<>());

    static int raw;
    static int lastLimit;
    static int failOperation;
    static int deferNextOperation;
    static int missingOperation;
    static Pending pending;

    private static InstrumentProxyManager instrumentProxy;
    private static SomeIpTxLog someIpTxLog;
    private static WazeCaptureDebugWriter debugWriter;

    private NativeSpeedLimitTestSupport() { }

    static void reset() {
        EVENTS.clear();
        raw = 0;
        lastLimit = 0;
        failOperation = -1;
        deferNextOperation = -1;
        missingOperation = -1;
        pending = null;
        instrumentProxy = null;
        someIpTxLog = null;
        debugWriter = null;
        ShanghaiOutputGate.resume();
    }

    static void resetPreferences(Context context) {
        context.getSharedPreferences("byd_hud_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        HudPrefs.setUserShutdownActive(context, false);
        HudPrefs.setSpeedLimitMode(context, HudPrefs.SPEED_LIMIT_NATIVE);
        HudPrefs.setNativeSpeedLimitClearMode(context, HudPrefs.SPEED_LIMIT_NATIVE_CLEAR_NEVER);
        HudPrefs.setNativeSpeedLimitFallbackMode(context,
                HudPrefs.SPEED_LIMIT_NATIVE_FALLBACK_LANES);
    }

    static void releasePending(boolean success) {
        Pending call = pending;
        if (call == null) throw new AssertionError("no deferred native operation");
        pending = null;
        complete(call.operation, call.value, call.callback, call.startedAt, success);
    }

    static HudOutputCoordinator newCoordinator(Context context) {
        setCoordinatorSingleton(null);
        HudOutputCoordinator coordinator = HudOutputCoordinator.get(context);
        HandlerThread ownerThread = ReflectionHelpers.getField(coordinator, "workerThread");
        Handler testWorker = new Handler(Looper.getMainLooper());
        ReflectionHelpers.setField(coordinator, "worker", testWorker);
        NativeSpeedLimitController nativeSpeed = new NativeSpeedLimitController(context,
                testWorker, () -> coordinatorOutputActive(coordinator), owner -> {
                    ReflectionHelpers.setField(coordinator,
                            "preparedDirectOptionsRevision", -1);
                    NavHudLiveSender.onNativeSpeedBitmapChanged(owner);
                });
        ReflectionHelpers.setField(coordinator, "nativeSpeed", nativeSpeed);
        stopThread(ownerThread);
        return coordinator;
    }

    static void stopCoordinator(HudOutputCoordinator coordinator) {
        if (coordinator != null) {
            coordinator.shutdown("native-speed-test-cleanup");
            idleMainLooper();
            idleMainLooperFor(10_000L);
            HandlerThread ownerThread = ReflectionHelpers.getField(coordinator, "workerThread");
            stopThread(ownerThread);
        }
        setCoordinatorSingleton(null);
    }

    private static boolean coordinatorOutputActive(HudOutputCoordinator coordinator) {
        HudOutputCoordinator.Source active = ReflectionHelpers.getField(coordinator, "activeSource");
        if (active == HudOutputCoordinator.Source.NONE) return false;
        HudOutputCoordinator.Source desired = ReflectionHelpers.callInstanceMethod(
                coordinator, "desiredSource");
        boolean hasFrame = ReflectionHelpers.callInstanceMethod(coordinator, "hasFrame",
                ReflectionHelpers.ClassParameter.from(HudOutputCoordinator.Source.class, active));
        boolean serviceStarted = ReflectionHelpers.getField(coordinator, "serviceStarted");
        boolean bound = ReflectionHelpers.callInstanceMethod(coordinator, "clientBoundForNative");
        return active == desired && hasFrame && serviceStarted && bound;
    }

    private static void stopThread(HandlerThread thread) {
        thread.quitSafely();
        try {
            thread.join(5_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("coordinator worker did not stop", interrupted);
        }
        if (thread.isAlive()) throw new AssertionError("coordinator worker did not stop");
    }

    static void idleMainLooper() {
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    static void idleMainLooperFor(long millis) {
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(millis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    static int countEvents(String expected) {
        int count = 0;
        synchronized (EVENTS) {
            for (String event : EVENTS) if (expected.equals(event)) count++;
        }
        return count;
    }

    private static void setCoordinatorSingleton(HudOutputCoordinator coordinator) {
        try {
            Field field = HudOutputCoordinator.class.getDeclaredField("instance");
            field.setAccessible(true);
            field.set(null, coordinator);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static InstrumentProxyManager instrumentProxy(Context context) {
        if (instrumentProxy == null) {
            instrumentProxy = Shadow.newInstanceOf(InstrumentProxyManager.class);
        }
        return instrumentProxy;
    }

    private static SomeIpTxLog txLog(Context context) {
        if (someIpTxLog == null) someIpTxLog = Shadow.newInstanceOf(SomeIpTxLog.class);
        return someIpTxLog;
    }

    private static WazeCaptureDebugWriter debugWriter() {
        if (debugWriter == null) debugWriter = Shadow.newInstanceOf(WazeCaptureDebugWriter.class);
        return debugWriter;
    }

    private static void runNativeOperation(int operation, int value,
            BooleanSupplier current, Consumer<NativeSpeedLimitEngine.Result> callback) {
        long startedAt = SystemClock.elapsedRealtime();
        EVENTS.add("native:" + operation + "=" + value);
        if (!current.getAsBoolean()) {
            callback.accept(new NativeSpeedLimitEngine.Result(false, raw, startedAt,
                    SystemClock.elapsedRealtime(), "stale before dispatch"));
            return;
        }
        if (operation == NativeSpeedLimitEngine.LIMIT) lastLimit = value;
        if (operation == deferNextOperation) {
            deferNextOperation = -1;
            pending = new Pending(operation, value, current, callback, startedAt);
            return;
        }
        if (operation == missingOperation) return;
        complete(operation, value, callback, startedAt, operation != failOperation);
    }

    private static void complete(int operation, int value,
            Consumer<NativeSpeedLimitEngine.Result> callback, long startedAt,
            boolean success) {
        if (success && operation == NativeSpeedLimitEngine.LIMIT) lastLimit = value;
        if (success && operation == NativeSpeedLimitEngine.ROAD && value == 6) {
            raw = lastLimit == 1 ? 1 : lastLimit / 5 + 1;
        }
        callback.accept(new NativeSpeedLimitEngine.Result(success, raw, startedAt,
                SystemClock.elapsedRealtime(), success ? "" : "unavailable"));
    }

    static final class Pending {
        final int operation;
        final int value;
        final BooleanSupplier current;
        final Consumer<NativeSpeedLimitEngine.Result> callback;
        final long startedAt;

        Pending(int operation, int value, BooleanSupplier current,
                Consumer<NativeSpeedLimitEngine.Result> callback, long startedAt) {
            this.operation = operation;
            this.value = value;
            this.current = current;
            this.callback = callback;
            this.startedAt = startedAt;
        }
    }

    @Implements(InstrumentProxyManager.class)
    public static final class InstrumentProxy {
        @Implementation protected static InstrumentProxyManager get(Context context) {
            return instrumentProxy(context);
        }

        @Implementation protected void nativeSpeedOperation(int operation, int value,
                boolean readyOnly, BooleanSupplier current,
                Consumer<NativeSpeedLimitEngine.Result> callback) {
            runNativeOperation(operation, value, current, callback);
        }

        @Implementation protected void setOutputDemand(boolean active, String reason) {
            EVENTS.add("helper:demand=" + active);
        }
    }

    @Implements(SomeIpHudClient.class)
    public static final class SomeIpClient {
        @Implementation protected boolean isBound() { return true; }
        @Implementation protected boolean hasBinding() { return true; }
        @Implementation protected void bind() { EVENTS.add("transport:bind"); }
        @Implementation protected void unbind() { EVENTS.add("transport:unbind"); }
        @Implementation protected int start() {
            EVENTS.add("transport:start");
            return 0;
        }
        @Implementation protected int stop() {
            EVENTS.add("transport:stop");
            return 0;
        }
        @Implementation protected int send(byte[] payload) {
            EVENTS.add("transport:send");
            return 0;
        }
    }

    @Implements(SomeIpTxLog.class)
    public static final class TxLog {
        @Implementation protected static SomeIpTxLog get(Context context) {
            return txLog(context);
        }
        @Implementation protected void recordSend(String source, String channel, long topic,
                String kind, String reason, byte[] payload, byte[] semanticPayload,
                Integer result, String error, long durationMs) { }
        @Implementation protected void recordLifecycle(String kind, String source,
                String channel, String reason, Integer result, String error,
                long durationMs) { }
    }

    @Implements(AppEventLogger.class)
    public static final class EventsLog {
        @Implementation protected static void event(Context context, String line) {
            EVENTS.add("event:" + line);
        }
    }

    @Implements(WazeCaptureDebugWriter.class)
    public static final class DebugWriter {
        @Implementation protected static WazeCaptureDebugWriter get() {
            return debugWriter();
        }
        @Implementation protected boolean appEvent(Context context, String line) { return true; }
        @Implementation protected boolean someIpTx(Runnable work) { return true; }
    }
}
