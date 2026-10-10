package com.bydhud.app;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class,
        shadows = {SomeIpRoadInfoDiagnosticsTest.Writer.class, SomeIpRoadInfoDiagnosticsTest.Store.class})
@LooperMode(LooperMode.Mode.PAUSED)
public final class SomeIpRoadInfoDiagnosticsTest {
    private static final List<String> rows = new CopyOnWriteArrayList<>();
    private TestContext context;
    private ShanghaiBehaviorSupport.ControlledSomeIpBinder binder;

    @Before public void setup() {
        rows.clear();
        ReflectionHelpers.setStaticField(SomeIpRoadInfoDiagnostics.class, "instance", null);
        binder = new ShanghaiBehaviorSupport.ControlledSomeIpBinder(-1);
        context = new TestContext(RuntimeEnvironment.getApplication(), binder);
        context.getSharedPreferences("byd_hud_prefs", 0).edit().clear().commit();
    }

    @After public void cleanup() throws Exception {
        SomeIpRoadInfoDiagnostics.onRuntimeStopped();
        SomeIpRoadInfoDiagnostics diagnostics = SomeIpRoadInfoDiagnostics.get(context);
        Handler handler = ReflectionHelpers.getField(diagnostics, "control");
        if (handler != null) {
            java.util.concurrent.CountDownLatch idle = new java.util.concurrent.CountDownLatch(1);
            handler.post(idle::countDown);
            assertTrue(idle.await(4, java.util.concurrent.TimeUnit.SECONDS));
            handler.getLooper().quitSafely();
        }
        ReflectionHelpers.setStaticField(SomeIpRoadInfoDiagnostics.class, "instance", null);
    }

    @Test public void requiresRuntimeAndDetailedModeAndReleasesOnlyOwnedSubscription() throws Exception {
        SomeIpRoadInfoDiagnostics.onRuntimeStarted(context);
        assertEquals(0, context.binds);
        assertNull(SomeIpRoadInfoDiagnostics.beforeSend(SomeIpHudClient.HUD_ROAD_INFO_TOPIC, packet(1)));
        detailed(true);
        await(() -> has("subscription"));
        assertEquals(java.util.Collections.singletonList(SomeIpHudClient.HUD_ROAD_INFO_TOPIC), binder.requestedTopics);
        assertEquals(java.util.Collections.singletonList(SomeIpHudClient.HUD_NAVI_INFO_SERVICE_ID), binder.startedServices);
        IBinder oldCallback = binder.callback;
        detailed(false);
        assertNull(SomeIpRoadInfoDiagnostics.beforeSend(SomeIpHudClient.HUD_ROAD_INFO_TOPIC, packet(1)));
        await(() -> has("stop"));
        assertEquals(binder.requestedTopics, binder.unsubscribedTopics);
        assertEquals(binder.startedServices, binder.stoppedServices);
        assertEquals(1, context.unbinds);
        ShanghaiBehaviorSupport.sendEvent(oldCallback, SomeIpHudClient.HUD_ROAD_INFO_TOPIC, 1, packet(1));
        assertEquals(0, count("receive"));
        assertTrue(has("late_callback"));
        rows.clear();
        detailed(true);
        await(() -> has("subscription"));
        assertEquals(2, context.binds);
    }

    @Test public void preservesEveryReceiveAndDistinguishesOldCurrentAndUnmatchedValues() throws Exception {
        start();
        RoadInfoTxHistory.Attempt left = SomeIpRoadInfoDiagnostics.beforeSend(SomeIpHudClient.HUD_ROAD_INFO_TOPIC, packet(1));
        assertNotNull(left);
        left.complete(0);
        receive(packet(1));
        JSONObject first = lastReceive();
        assertTrue(first.getBoolean("positiveControlObserved"));
        assertTrue(first.getBoolean("latestTxMatches"));
        SystemClock.sleep(2);
        RoadInfoTxHistory.Attempt right = SomeIpRoadInfoDiagnostics.beforeSend(SomeIpHudClient.HUD_ROAD_INFO_TOPIC, packet(2));
        right.complete(0);
        receive(packet(1));
        assertEquals(left.sequence, lastReceive().getLong("txSequence"));
        assertFalse(lastReceive().getBoolean("latestTxMatches"));
        receive(packet(1));
        assertEquals(3, count("receive"));
        receive(packet(9));
        assertEquals("not_found_in_retained_history", lastReceive().getString("match"));
        assertFalse(lastReceive().has("payloadBase64"));
    }

    @Test public void failedTxDoesNotEstablishPositiveControlAndMalformedRxIsVisible() throws Exception {
        start();
        RoadInfoTxHistory.Attempt attempt = SomeIpRoadInfoDiagnostics.beforeSend(SomeIpHudClient.HUD_ROAD_INFO_TOPIC, packet(1));
        attempt.complete(5);
        receive(packet(1));
        assertFalse(lastReceive().getBoolean("positiveControlObserved"));
        assertEquals(5, lastReceive().getInt("txResult"));
        receive(new byte[]{10, 12, 0});
        assertTrue(has("rx_parse_error"));
        assertEquals(1, count("receive"));
        SomeIpRoadInfoDiagnostics.onRuntimeStopped();
        await(() -> has("stop"));
        JSONObject stop = last("stop");
        assertEquals(2, stop.getInt("received"));
        assertEquals(1, stop.getInt("parseErrors"));
        assertEquals("inconclusive_without_control", stop.getString("interpretation"));
    }

    @Test public void matchingRxBeforeBinderReplyIsConfirmedWhenTheSendCompletes() throws Exception {
        start();
        RoadInfoTxHistory.Attempt attempt = SomeIpRoadInfoDiagnostics.beforeSend(SomeIpHudClient.HUD_ROAD_INFO_TOPIC, packet(1));
        receive(packet(1));
        assertFalse(lastReceive().getBoolean("txCompleted"));
        assertFalse(lastReceive().getBoolean("positiveControlObserved"));
        assertFalse(has("positive_control"));
        attempt.complete(0);
        assertEquals(1, count("positive_control"));
        SomeIpRoadInfoDiagnostics.onRuntimeStopped();
        await(() -> has("stop"));
        assertTrue(last("stop").getBoolean("positiveControlObserved"));
    }

    @Test public void refusedSubscriptionClosesAndReportsInconclusive() throws Exception {
        binder = new ShanghaiBehaviorSupport.ControlledSomeIpBinder(SomeIpHudClient.HUD_ROAD_INFO_TOPIC);
        context.binder = binder;
        detailed(true);
        SomeIpRoadInfoDiagnostics.onRuntimeStarted(context);
        await(() -> has("stop"));
        assertTrue(has("unavailable"));
        assertFalse(last("stop").getBoolean("positiveControlObserved"));
        assertTrue(binder.unsubscribedTopics.isEmpty());
        assertEquals(binder.startedServices, binder.stoppedServices);
    }

    @Test public void runtimeStopCancelsPendingReconnectAndLateCallbacks() throws Exception {
        start();
        IBinder old = binder.callback;
        context.connection.onServiceDisconnected(context.component);
        SomeIpRoadInfoDiagnostics.onRuntimeStopped();
        await(() -> has("stop"));
        Handler handler = ReflectionHelpers.getField(SomeIpRoadInfoDiagnostics.get(context), "control");
        org.robolectric.Shadows.shadowOf(handler.getLooper()).idleFor(java.time.Duration.ofSeconds(2));
        assertEquals(1, context.binds);
        receiveWith(old, packet(1));
        assertEquals(0, count("receive"));
    }

    @Test public void malformedBinderFrameIsReportedWithoutCrashingTheCallback() throws Exception {
        start();
        android.os.Parcel data = android.os.Parcel.obtain(), reply = android.os.Parcel.obtain();
        try {
            data.writeInterfaceToken(ShanghaiBehaviorSupport.CALLBACK_DESCRIPTOR);
            data.writeInt(1);
            data.writeLong(SomeIpHudClient.HUD_ROAD_INFO_TOPIC);
            data.writeLong(1);
            data.writeInt(20);
            data.writeByteArray(new byte[]{1, 2});
            binder.callback.transact(1, data, reply, 0);
            reply.readException();
            assertTrue(has("callback_parse_error"));
            assertEquals(0, count("receive"));
        } finally { data.recycle(); reply.recycle(); }
    }

    private void start() throws Exception {
        detailed(true);
        assertEquals(0, context.binds);
        SomeIpRoadInfoDiagnostics.onRuntimeStarted(context);
        await(() -> has("subscription"));
    }

    private void detailed(boolean enabled) {
        context.getSharedPreferences("byd_hud_prefs", 0).edit()
                .putBoolean("detailed_debug_artifacts", enabled).commit();
        SomeIpRoadInfoDiagnostics.onDetailedModeChanged(context);
    }

    private void receive(byte[] packet) throws Exception {
        receiveWith(binder.callback, packet);
    }

    private static void receiveWith(IBinder callback, byte[] packet) throws Exception {
        ShanghaiBehaviorSupport.sendEvent(callback, SomeIpHudClient.HUD_ROAD_INFO_TOPIC, 123, packet);
    }

    private static byte[] packet(int direction) { return RoadInfoDiagnosticFrameTest.packet(direction, 100); }
    private static int count(String event) {
        return (int) rows.stream().filter(row -> row.contains("\"event\":\"" + event + "\"")).count();
    }
    private static boolean has(String event) { return count(event) > 0; }
    private static JSONObject lastReceive() throws Exception { return last("receive"); }
    private static JSONObject last(String event) throws Exception {
        String result = rows.stream().filter(row -> row.contains("\"event\":\"" + event + "\""))
                .reduce((a, b) -> b).orElseThrow();
        return new JSONObject(result);
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 4_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue("Timed out; rows=" + rows, condition.getAsBoolean());
    }

    @Implements(WazeCaptureDebugWriter.class) public static class Writer {
        @Implementation protected static WazeCaptureDebugWriter get() { return Shadow.newInstanceOf(WazeCaptureDebugWriter.class); }
        @Implementation protected boolean someIpRx(Runnable work) { work.run(); return true; }
    }
    @Implements(NavCaptureStore.class) public static class Store {
        @Implementation protected static void writeSomeIpRx(Context context, String day, String line) { rows.add(line); }
    }

    private static final class TestContext extends ContextWrapper {
        ShanghaiBehaviorSupport.ControlledSomeIpBinder binder;
        volatile int binds, unbinds;
        volatile ServiceConnection connection;
        android.content.ComponentName component;
        TestContext(Context base, ShanghaiBehaviorSupport.ControlledSomeIpBinder binder) { super(base); this.binder = binder; }
        @Override public Context getApplicationContext() { return this; }
        @Override public boolean bindService(Intent intent, ServiceConnection connection, int flags) {
            binds++;
            this.connection = connection;
            this.component = intent.getComponent();
            assertTrue(intent.getType().startsWith("bydhud-roadinfo-rx-"));
            assertEquals("com.ts.car.someip.service.manager.SomeIpClientService", intent.getComponent().getClassName());
            connection.onServiceConnected(intent.getComponent(), binder);
            return true;
        }
        @Override public void unbindService(ServiceConnection connection) { unbinds++; }
    }
}
