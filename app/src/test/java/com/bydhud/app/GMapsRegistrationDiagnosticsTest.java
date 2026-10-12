package com.bydhud.app;

import static org.junit.Assert.*;

import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;

import com.bydhud.gmapsdiag.NavInfoLogger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class GMapsRegistrationDiagnosticsTest {
    private Context context;
    private GMapsDirectChannel channel;
    private DirectSessionLog session;
    private final List<String> logs = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<String> callbacks = new ArrayList<>();
    private final List<Message> replies = new ArrayList<>();
    private Messenger reply;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        // Robolectric replaces the app sandbox between methods; production has one context.
        Method resetStorage = NavigationLogStorage.class.getDeclaredMethod("invalidatePublicRootCache");
        resetStorage.setAccessible(true);
        resetStorage.invoke(null);
        session = DirectSessionLog.start(context, "gmaps-direct", "diagnostic-test");
        GMapsDirectChannel.Listener listener = (GMapsDirectChannel.Listener) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{GMapsDirectChannel.Listener.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("onLog")) {
                        String line = (String) args[0];
                        logs.add(line);
                        AppEventLogger.event(context, "nav_live gmaps_direct " + line);
                        session.event("channel", line);
                    } else callbacks.add(method.getName());
                    return null;
                });
        channel = new GMapsDirectChannel(context, listener);
        set("running", true);
        set("channelId", "test-request-1");
        set("lastIdentitySystemCaptureMs", android.os.SystemClock.elapsedRealtime());
        reply = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            replies.add(Message.obtain(message));
            return true;
        }));
    }

    @After public void tearDown() throws Exception {
        NavInfoLogger.registerClient(context, new Intent(NavInfoLogger.ACTION_UNREGISTER)
                .putExtra(NavInfoLogger.EXTRA_IDENTITY, identity("com.bydhud.app")));
        session.end("test-end");
        assertTrue(WazeCaptureDebugWriter.get().awaitCheckpoint(2000));
        Field thread = GMapsDirectChannel.class.getDeclaredField("thread");
        thread.setAccessible(true);
        ((HandlerThread) thread.get(channel)).quitSafely();
    }

    @Test public void wrongCreatorReportsPackageAndUidAndCannotRegister() throws Exception {
        NavInfoLogger.registerClient(context, request().putExtra(
                NavInfoLogger.EXTRA_IDENTITY, identity("another.app")));
        deliverReplies();
        assertTrue(logs.toString().contains("identityReason=creator_package_mismatch"));
        assertTrue(logs.toString().contains("creatorPackage=another.app"));
        assertTrue(logs.toString().contains("creatorUid=10456"));
        assertTrue(NavInfoLogger.noClient());
        assertFalse(connected());
        assertTrue(callbacks.isEmpty());
    }

    @Test
    @Config(shadows = IdentityCapture.class)
    public void identityEvidenceCompletesQueuedChannelsAndReasonsOnce() throws Exception {
        IdentityCapture.entered = new java.util.concurrent.CountDownLatch(1);
        IdentityCapture.release = new java.util.concurrent.CountDownLatch(1);
        IdentityCapture.calls.set(0);
        set("lastIdentitySystemCaptureMs", -60_000L);
        set("identityChannelId", "first-channel");
        Method capture = GMapsDirectChannel.class.getDeclaredMethod("captureIdentitySystemEvidence", String.class);
        capture.setAccessible(true);
        capture.invoke(channel, "rejected");
        try {
            assertTrue(IdentityCapture.entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
            capture.invoke(channel, "rejected");
            set("identityChannelId", "replacement-channel");
            set("sessionGeneration", 2L);
            capture.invoke(channel, "rejected");
            capture.invoke(channel, "missing_hello_or_registration_result");
            capture.invoke(channel, "missing_hello_or_registration_result");
        } finally { IdentityCapture.release.countDown(); }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(4);
        while (logs.stream().filter(line -> line.contains("state=complete")).count() < 3
                && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(3, IdentityCapture.calls.get());
        assertEquals(3, logs.stream().filter(line -> line.contains("state=complete")).count());
        assertEquals(1, logs.stream().filter(line -> line.equals(
                "identity_system channelId=first-channel state=scheduled reason=rejected")).count());
        assertEquals(1, logs.stream().filter(line -> line.equals(
                "identity_system channelId=replacement-channel state=scheduled reason=rejected")).count());
        assertEquals(1, logs.stream().filter(line -> line.equals(
                "identity_system channelId=replacement-channel state=scheduled reason=missing_hello_or_registration_result")).count());
        assertEquals(1, logs.stream().filter(line -> line.contains("channelId=first-channel line=0 evidence-")).count());
        assertEquals(2, logs.stream().filter(line -> line.contains("channelId=replacement-channel line=0 evidence-")).count());
    }

    @org.robolectric.annotation.Implements(LocalAdbBridge.class)
    public static class IdentityCapture {
        static java.util.concurrent.CountDownLatch entered, release;
        static final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        @org.robolectric.annotation.Implementation
        protected static LocalAdbBridge.ShellResult captureGMapsIdentityEvidence(Context context) throws Exception {
            int call = calls.incrementAndGet();
            if (call == 1) {
                entered.countDown();
                if (!release.await(4, java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("capture blocked");
            }
            return new LocalAdbBridge.ShellResult("evidence-" + call, 0, "", false, 0L, "ok", "");
        }
    }

    private Intent request() {
        return new Intent("ACTION_START_CHANNEL")
                .putExtra(NavInfoLogger.EXTRA_CLIENT, reply)
                .putExtra(NavInfoLogger.EXTRA_CHANNEL_ID, "test-request-1")
                .putExtra(NavInfoLogger.EXTRA_PROTOCOL_VERSION, 3)
                .putExtra(NavInfoLogger.EXTRA_DIAGNOSTICS, true);
    }

    private PendingIntent identity(String creator) {
        PendingIntent token = PendingIntent.getService(context, 2101,
                new Intent(context, HudRuntimeService.class), PendingIntent.FLAG_IMMUTABLE);
        Shadows.shadowOf(token).setCreatorPackage(creator);
        Shadows.shadowOf(token).setCreatorUid(10456);
        return token;
    }

    private void deliverReplies() throws Exception {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        for (Message message : replies) deliver(message);
    }

    private void deliver(Message message) throws Exception {
        Method receive = GMapsDirectChannel.class.getDeclaredMethod("handleMessage", Message.class);
        receive.setAccessible(true);
        receive.invoke(channel, message);
    }

    private void set(String name, Object value) throws Exception {
        Field field = field(name);
        field.set(channel, value);
    }

    private Field field(String name) throws Exception {
        Field field = GMapsDirectChannel.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private boolean connected() throws Exception {
        Field field = GMapsDirectChannel.class.getDeclaredField("connected");
        field.setAccessible(true);
        return field.getBoolean(channel);
    }
}
