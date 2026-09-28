package com.bydhud.app;

import static org.junit.Assert.*;

import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipFile;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class GMapsRegistrationDiagnosticsTest {
    private Context context;
    private GMapsDirectChannel channel;
    private DirectSessionLog session;
    private final List<String> logs = new ArrayList<>();
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

    @Test public void rejectionBeforeHelloIsInOrdinaryExportWithoutLogcat() throws Exception {
        // Present extra with null value matches the unresolved N9 boundary.
        NavInfoLogger.registerClient(context, request().putExtra(
                NavInfoLogger.EXTRA_IDENTITY, (PendingIntent) null));
        deliverReplies();
        assertTrue(logs.toString(), logs.toString().contains("identityReason=missing_token"));
        assertTrue(logs.toString().contains(NavInfoLogger.BRIDGE_BUILD));
        assertTrue(NavInfoLogger.noClient());
        assertFalse(connected());
        assertTrue(callbacks.isEmpty());
        assertTrue(WazeCaptureDebugWriter.get().awaitCheckpoint(2000));
        LogShareZip.Result result = LogShareZip.create(context,
                Collections.singletonList(NavCaptureStore.todayDir(System.currentTimeMillis())));
        assertTrue(result.detail, result.ok);
        boolean eventFound = false;
        boolean sessionFound = false;
        try (ZipFile archive = new ZipFile(result.file)) {
            for (java.util.zip.ZipEntry entry : Collections.list(archive.entries())) {
                if (!entry.getName().endsWith("events.log") && !entry.getName().endsWith("events.jsonl")) continue;
                String text = new String(archive.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
                if (text.contains("identityReason=missing_token")) {
                    eventFound |= entry.getName().endsWith("events.log");
                    sessionFound |= entry.getName().contains("gmaps-direct/");
                }
            }
        }
        assertTrue("ordinary event log", eventFound);
        assertTrue("direct-session log", sessionFound);
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

    @Test public void validIdentityStillRequiresHelloForConnection() throws Exception {
        NavInfoLogger.registerClient(context, request().putExtra(
                NavInfoLogger.EXTRA_IDENTITY, identity("com.bydhud.app")));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        for (Message message : replies) if (message.what == NavInfoLogger.MESSAGE_DIAGNOSTIC) deliver(message);
        assertFalse(connected());
        assertTrue(callbacks.isEmpty());
        assertTrue(logs.toString().contains("identityReason=trusted_creator"));
        assertTrue(logs.toString().contains("registration_result"));
        for (Message message : replies) if (message.what == NavInfoLogger.MESSAGE_HELLO) deliver(message);
        assertTrue(connected());
        assertTrue(callbacks.contains("onHandshakeAvailable"));
    }

    @Test public void diagnosticIsBoundedAndCorrelatedBeforeRouteEpochChecks() throws Exception {
        set("routeGenerationCapable", true);
        Message message = Message.obtain();
        message.what = NavInfoLogger.MESSAGE_DIAGNOSTIC;
        Bundle data = new Bundle();
        data.putInt("protocolVersion", 3);
        data.putString("channelId", "old-request");
        data.putString("detail", "stale-payload");
        message.setData(data);
        deliver(message);
        assertFalse(logs.toString().contains("stale-payload"));
        data.putString("channelId", "test-request-1");
        data.putString("detail", "header\n" + "x".repeat(2000));
        deliver(message);
        String last = logs.stream().filter(line -> line.startsWith("bridge_diagnostic channelId="))
                .reduce((a, b) -> b).orElseThrow();
        assertTrue(last.contains("detail=header "));
        assertFalse(last.contains("\n"));
        assertTrue(last.length() < 1400);
        assertFalse(connected());
        assertTrue(callbacks.isEmpty());
    }

    @Test public void oldClientWithoutOptInGetsNoDiagnosticMessages() {
        Intent request = request();
        request.removeExtra(NavInfoLogger.EXTRA_DIAGNOSTICS);
        NavInfoLogger.registerClient(context, request);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(replies.isEmpty());
        assertTrue(NavInfoLogger.noClient());
    }

    @Test public void senderEvidenceIncludesFreshParcelLookupAndSurvivesOrdinaryExport() throws Exception {
        PendingIntent token = identity("com.bydhud.app");
        set("registrationIdentity", token);
        set("identityChannelId", "test-request-1");
        String snapshot = GMapsIdentityDiagnostics.snapshot(context, token, true);
        assertTrue(snapshot, snapshot.contains("cachedPackage=com.bydhud.app"));
        assertTrue(snapshot, snapshot.contains("freshPackage=com.bydhud.app"));
        assertTrue(snapshot, snapshot.contains("parcelEqual=true"));
        assertTrue(snapshot, snapshot.contains("freshService=true"));
        NavInfoLogger.registerClient(context, request().putExtra(NavInfoLogger.EXTRA_IDENTITY, (PendingIntent) null));
        deliverReplies();
        assertTrue(logs.toString().contains("stage=reply_rejected"));
        assertTrue(logs.toString().contains("identity_reply channelId=test-request-1"));
        assertTrue(WazeCaptureDebugWriter.get().awaitCheckpoint(2000));
        LogShareZip.Result result = LogShareZip.create(context,
                Collections.singletonList(NavCaptureStore.todayDir(System.currentTimeMillis())));
        assertTrue(result.detail, result.ok);
        int copies = 0;
        try (ZipFile archive = new ZipFile(result.file)) {
            for (java.util.zip.ZipEntry entry : Collections.list(archive.entries())) {
                if (!entry.getName().endsWith("events.log") && !entry.getName().endsWith("events.jsonl")) continue;
                String text = new String(archive.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
                if (text.contains("stage=reply_rejected") && text.contains("freshPackage=com.bydhud.app")) copies++;
            }
        }
        assertEquals("daily and session journals", 2, copies);
        assertFalse(connected());
    }

    @Test public void cancellationAndGetterFailureHaveExplicitIndependentEvidence() {
        PendingIntent token = PendingIntent.getService(context, GMapsIdentityDiagnostics.REQUEST,
                GMapsIdentityDiagnostics.identityIntent(context), GMapsIdentityDiagnostics.FLAGS);
        assertTrue(GMapsIdentityDiagnostics.snapshot(context, token, true).contains("existingEqual=true"));
        token.cancel();
        assertTrue(GMapsIdentityDiagnostics.snapshot(context, token, true).contains("existingPresent=false"));
        StringBuilder fields = new StringBuilder();
        GMapsIdentityDiagnostics.field(fields, "creatorPackage", () -> {
            throw new SecurityException("denied\nlookup", new IllegalStateException("cause"));
        });
        GMapsIdentityDiagnostics.field(fields, "creatorUid", () -> 10456);
        assertTrue(fields.toString().contains("creatorPackageError=java.lang.SecurityException"));
        assertTrue(fields.toString().contains("java.lang.IllegalStateException:cause"));
        assertTrue(fields.toString().contains("creatorUid=10456"));
        assertFalse(fields.toString().contains("\n"));
    }

    @Test public void sendUsesExactRetainedTokenAndLogsCreationEnvironment() throws Exception {
        Method send = GMapsDirectChannel.class.getDeclaredMethod("sendRegistration", String.class, boolean.class, int.class);
        send.setAccessible(true);
        assertEquals(true, send.invoke(channel, "ACTION_START_CHANNEL", true, 3));
        Field retained = GMapsDirectChannel.class.getDeclaredField("registrationIdentity");
        retained.setAccessible(true);
        List<Intent> sent = Shadows.shadowOf((Application) context).getBroadcastIntents();
        Intent request = sent.stream().filter(i -> "ACTION_START_CHANNEL".equals(i.getAction())).reduce((a,b) -> b).orElseThrow();
        assertSame(retained.get(channel), request.getParcelableExtra(NavInfoLogger.EXTRA_IDENTITY));
        assertTrue(logs.toString().contains("identity_environment build=" + GMapsIdentityDiagnostics.BUILD));
        assertTrue(logs.toString().contains("stage=before_send"));
        assertTrue(logs.toString().contains("stage=after_send"));
        assertTrue(logs.toString().contains("requestCode=2101"));
    }

    @Test @Config(sdk = 29)
    public void olderAndroidStillRecordsMetadataAndExplicitUnsupportedTypeApi() {
        String snapshot = GMapsIdentityDiagnostics.snapshot(context, identity("com.bydhud.app"), true);
        assertTrue(snapshot, snapshot.contains("cachedPackage=com.bydhud.app"));
        assertTrue(snapshot, snapshot.contains("freshPackage=com.bydhud.app"));
        assertTrue(snapshot, snapshot.contains("TypeRead=unsupported_api_29"));
        assertFalse(snapshot, snapshot.contains("NoSuchMethodError"));
    }

    @Test public void diagnosticAfterHelloDoesNotEraseSuccessfulHandshakeEvidence() throws Exception {
        set("identityChannelId", "test-request-1");
        NavInfoLogger.registerClient(context, request().putExtra(
                NavInfoLogger.EXTRA_IDENTITY, identity("com.bydhud.app")));
        deliverReplies();
        assertTrue(connected());
        Method send = GMapsDirectChannel.class.getDeclaredMethod("sendRegistration", String.class, boolean.class, int.class);
        send.setAccessible(true);
        send.invoke(channel, "ACTION_START_CHANNEL", true, 3);
        assertTrue(logs.toString(), logs.toString().contains("helloReceived=true"));
        assertFalse(logs.toString(), logs.toString().contains("reason=missing_hello_or_registration_result"));
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
        Field field = GMapsDirectChannel.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(channel, value);
    }

    private boolean connected() throws Exception {
        Field field = GMapsDirectChannel.class.getDeclaredField("connected");
        field.setAccessible(true);
        return field.getBoolean(channel);
    }
}
