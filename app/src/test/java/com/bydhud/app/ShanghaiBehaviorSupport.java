package com.bydhud.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import org.json.JSONObject;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Delayed;

/** Small, test-only controls for the Shanghai controller and SOME/IP Binder boundary. */
final class ShanghaiBehaviorSupport {
    static final List<String> EVENTS = Collections.synchronizedList(new ArrayList<>());
    static final String CLIENT_DESCRIPTOR = "ts.car.someip.sdk.ISomeIpClientInterface";
    static final String CALLBACK_DESCRIPTOR = "ts.car.someip.sdk.ISomeIpCallback";
    static ShanghaiMockGps.Result gpsCleanupResult = ShanghaiMockGps.Result.of(
            ShanghaiMockGps.Code.SUCCESS, "test cleanup");
    static boolean failHelperRestore;
    static boolean failDiagnosticsStop;
    private static NavHudLiveSender navHud;
    private static InstrumentProxyManager instrument;

    private ShanghaiBehaviorSupport() { }

    static void reset() {
        EVENTS.clear();
        gpsCleanupResult = ShanghaiMockGps.Result.of(
                ShanghaiMockGps.Code.SUCCESS, "test cleanup");
        failHelperRestore = false;
        failDiagnosticsStop = false;
        navHud = null;
        instrument = null;
    }

    @SuppressWarnings("unchecked")
    static <T> T getField(Object target, String name) throws Exception {
        Field field = target instanceof Class
                ? ((Class<?>) target).getDeclaredField(name)
                : target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target instanceof Class ? null : target);
    }

    static void setField(Object target, String name, Object value) throws Exception {
        Field field = target instanceof Class
                ? ((Class<?>) target).getDeclaredField(name)
                : target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target instanceof Class ? null : target, value);
    }

    static <T> T newShadowedInstance(Class<T> type) {
        return Shadow.newInstanceOf(type);
    }

    @Implements(ShanghaiMockGps.class)
    public static class ShadowGps {
        @Implementation protected void __constructor__(Context context) { }

        @Implementation protected ShanghaiMockGps.Result cleanupOwned() {
            EVENTS.add("gps.cleanup");
            return gpsCleanupResult;
        }

        @Implementation protected boolean hasPendingRecovery() { return false; }
    }

    @Implements(ShanghaiDiagnostics.class)
    public static class ShadowDiagnostics {
        @Implementation protected boolean start(String sessionId) { EVENTS.add("diagnostics.start"); return true; }

        @Implementation protected void record(String event, String detail) {
            EVENTS.add("diagnostics.record:" + event);
            EVENTS.add("diagnostics.detail:" + event + ":" + detail);
        }

        @Implementation protected void stop() throws IOException {
            EVENTS.add("diagnostics.stop");
            if (failDiagnosticsStop) throw new IOException("injected capture stop failure");
        }

        @Implementation protected boolean isReady() { return true; }

        @Implementation protected boolean isHealthy() { return true; }

        @Implementation protected ShanghaiDiagnostics.Coverage coverage() {
            return new ShanghaiDiagnostics.Coverage(
                    "capturing", true, true, true, "", new JSONObject());
        }
    }

    @Implements(InstrumentProxyManager.class)
    public static class ShadowInstrument {
        @Implementation protected static InstrumentProxyManager get(Context context) {
            if (instrument == null) instrument = newShadowedInstance(InstrumentProxyManager.class);
            return instrument;
        }

        @Implementation protected void resumeAfterShanghai() throws IOException {
            boolean leaseReleased = !LogcatRecorder.isReservedForShanghai();
            boolean recordingRetained = LogcatRecorder.isRecording();
            EVENTS.add("instrument.resume:leaseReleased=" + leaseReleased
                    + ":recordingRetained=" + recordingRetained);
            if (failHelperRestore) throw new IOException("injected helper restore failure");
        }
    }

    @Implements(NavHudLiveSender.class)
    public static class ShadowNavHud {
        @Implementation protected static NavHudLiveSender get(Context context) {
            if (navHud == null) navHud = newShadowedInstance(NavHudLiveSender.class);
            return navHud;
        }

        @Implementation protected void resumeAfterShanghai() { EVENTS.add("output.resume"); }
    }

    @Implements(ShanghaiTestService.class)
    public static class ShadowService {
        @Implementation protected static void finish(Context context) { EVENTS.add("service.finish"); }
    }

    @Implements(MainActivity.class)
    public static class ShadowMainActivity {
        @Implementation protected static void publishSharedUiStateChange() { }

        @Implementation protected static void requestStorageRefreshAfterMutation(
                Context context, String reason) { }
    }

    @Implements(AppEventLogger.class)
    public static class ShadowAppEventLogger {
        @Implementation protected static void event(Context context, String line) { }
    }

    @Implements(HudPrefs.class)
    public static class ShadowHudPrefs {
        @Implementation protected static boolean isUserShutdownActive(Context context) { return false; }

        @Implementation protected static String uiLanguage(Context context) { return "en"; }
    }

    static final class ManualScheduler extends AbstractExecutorService
            implements ScheduledExecutorService {
        private final Queue<Runnable> queue = new ArrayDeque<>();
        private boolean shutdown;

        @Override public void execute(Runnable command) {
            if (shutdown) throw new java.util.concurrent.RejectedExecutionException();
            queue.add(command);
        }

        @Override public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            QueuedFuture<Void> future = new QueuedFuture<>(java.util.concurrent.Executors
                    .callable(command, null));
            execute(future);
            return future;
        }

        @Override public <V> ScheduledFuture<V> schedule(Callable<V> callable,
                long delay, TimeUnit unit) {
            QueuedFuture<V> future = new QueuedFuture<>(callable);
            execute(future);
            return future;
        }

        @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable command,
                long initialDelay, long period, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command,
                long initialDelay, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        int queuedCount() { return queue.size(); }

        void runUntilIdle() {
            Runnable next;
            while ((next = queue.poll()) != null) next.run();
        }

        @Override public void shutdown() { shutdown = true; }

        @Override public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> pending = new ArrayList<>(queue);
            queue.clear();
            return pending;
        }

        @Override public boolean isShutdown() { return shutdown; }

        @Override public boolean isTerminated() { return shutdown && queue.isEmpty(); }

        @Override public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }

        private static final class QueuedFuture<V> extends FutureTask<V>
                implements ScheduledFuture<V> {
            QueuedFuture(Callable<V> callable) { super(callable); }

            @Override public long getDelay(TimeUnit unit) { return 0L; }

            @Override public int compareTo(Delayed other) {
                return Long.compare(getDelay(TimeUnit.NANOSECONDS),
                        other.getDelay(TimeUnit.NANOSECONDS));
            }
        }
    }

    static final class BoundContext extends ContextWrapper {
        final ControlledSomeIpBinder binder;

        BoundContext(Context base, ControlledSomeIpBinder binder) {
            super(base);
            this.binder = binder;
        }

        @Override public Context getApplicationContext() { return this; }

        @Override public boolean bindService(Intent intent, ServiceConnection connection, int flags) {
            connection.onServiceConnected(intent.getComponent(), binder);
            return true;
        }

        @Override public void unbindService(ServiceConnection connection) { }
    }

    static final class ControlledSomeIpBinder extends Binder {
        final long refusedTopic;
        final List<Long> requestedTopics = new ArrayList<>();
        final List<Long> unsubscribedTopics = new ArrayList<>();
        final List<Long> startedServices = new ArrayList<>();
        final List<Long> stoppedServices = new ArrayList<>();
        IBinder callback;
        boolean callbackUnregistered;

        ControlledSomeIpBinder(long refusedTopic) { this.refusedTopic = refusedTopic; }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == 1) {
                data.enforceInterface(CLIENT_DESCRIPTOR);
                callback = data.readStrongBinder();
                reply.writeNoException();
                return true;
            }
            data.enforceInterface(CLIENT_DESCRIPTOR);
            if (code == 2) {
                data.readStrongBinder();
                callbackUnregistered = true;
                reply.writeNoException();
                return true;
            }
            long id = data.readLong();
            int result = 0;
            if (code == 4) startedServices.add(id);
            else if (code == 5) stoppedServices.add(id);
            else if (code == 8) {
                requestedTopics.add(id);
                if (id == refusedTopic) result = 5;
            } else if (code == 9) unsubscribedTopics.add(id);
            reply.writeNoException();
            reply.writeInt(result);
            return true;
        }
    }

    static void sendEvent(IBinder callback, long topic, long timestamp, byte[] payload)
            throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CALLBACK_DESCRIPTOR);
            data.writeInt(1);
            data.writeLong(topic);
            data.writeLong(timestamp);
            data.writeInt(payload.length);
            data.writeByteArray(payload);
            data.setDataPosition(0);
            callback.transact(1, data, reply, 0);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
