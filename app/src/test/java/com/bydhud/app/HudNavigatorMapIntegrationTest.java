package com.bydhud.app;

import android.app.Application;
import android.content.Context;
import android.os.SystemClock;

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
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/** Exercise the actual output owner with only capture and vehicle I/O replaced. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class, shadows = {
        NativeSpeedLimitTestSupport.InstrumentProxy.class,
        HudNavigatorMapIntegrationTest.Client.class,
        HudNavigatorMapIntegrationTest.Capture.class,
        NativeSpeedLimitTestSupport.TxLog.class,
        NativeSpeedLimitTestSupport.EventsLog.class,
        NativeSpeedLimitTestSupport.DebugWriter.class
})
@LooperMode(LooperMode.Mode.PAUSED)
public final class HudNavigatorMapIntegrationTest {
    private Context context;
    private HudOutputCoordinator output;
    private static final List<Long> topics = new ArrayList<>();

    @Before public void setup() {
        NativeSpeedLimitTestSupport.reset();
        topics.clear();
        Capture.owner = "";
        Capture.callback = null;
        Capture.image = new NavigatorMapCapture.Snapshot(new byte[0], 0, 0);
        context = RuntimeEnvironment.getApplication();
        NativeSpeedLimitTestSupport.resetPreferences(context);
        HudPrefs.setSpeedLimitMode(context, HudPrefs.SPEED_LIMIT_OFF);
        mode(HudMapSettings.NATIVE);
        output = NativeSpeedLimitTestSupport.newCoordinator(context);
    }

    @After public void cleanup() { NativeSpeedLimitTestSupport.stopCoordinator(output); }

    @Test public void nativeMapUsesSeparateTopicAtOneHzAndExpiryDoesNotClearGuidance() {
        start("com.waze", 1);
        assertEquals("com.waze", Capture.owner);
        frame(new byte[]{1, 2, 3}, 1);
        assertEquals(1, packets("map_frame").size());
        assertFalse(packets("map_frame").get(0).retainPayload);
        assertTrue(packets("payload").get(0).retainPayload);
        assertTrue(topics.contains(SomeIpHudClient.HUD_TOPIC_8003));
        NativeSpeedLimitTestSupport.idleMainLooperFor(900);
        assertEquals(1, packets("map_frame").size());
        NativeSpeedLimitTestSupport.idleMainLooperFor(100);
        assertEquals(2, packets("map_frame").size());
        int guidance = packets("payload").size();
        frame(new byte[0], 2);
        assertEquals(1, packets("map_clear").size());
        assertTrue(packets("map_clear").get(0).retainPayload);
        assertArrayEquals(new byte[]{10, 0}, packets("map_clear").get(0).payload);
        assertTrue(packets("payload").size() > guidance);
        assertTrue(packets("final_clear").isEmpty());
        frame(new byte[]{4}, 3);
        NativeSpeedLimitTestSupport.idleMainLooperFor(1000);
        assertEquals(3, packets("map_frame").size());
    }

    @Test public void offStopsCaptureAndRouteEndCannotBeResurrectedByOldCallback() {
        start("com.waze", 1);
        frame(new byte[]{1}, 1);
        Runnable stale = Capture.callback;
        mode(HudMapSettings.OFF);
        NativeSpeedLimitTestSupport.idleMainLooperFor(50);
        assertEquals("", Capture.owner);
        assertEquals(1, packets("map_clear").size());
        int sent = packets("map_frame").size();
        stale.run();
        NativeSpeedLimitTestSupport.idleMainLooperFor(1000);
        assertEquals(sent, packets("map_frame").size());
        output.endNavigationOutput("com.waze", 1, "end", SystemClock.elapsedRealtime(), null);
        NativeSpeedLimitTestSupport.idleMainLooperFor(1000);
        assertEquals("", Capture.owner);
        assertFalse(packets("final_clear").isEmpty());
    }

    @Test public void mapsIdentityAndGenerationChangeDiscardPreviousImage() {
        start(GMapsDirectChannel.PACKAGE_NAME, 1);
        frame(new byte[]{1}, 1);
        Runnable old = Capture.callback;
        start(GMapsDirectChannel.PACKAGE_NAME, 2);
        NativeSpeedLimitTestSupport.idleMainLooperFor(50);
        assertEquals(GMapsDirectChannel.PACKAGE_NAME, Capture.owner);
        assertEquals(2, Capture.generation);
        assertEquals(0, ((byte[]) ReflectionHelpers.getField(output, "navigatorMapPng")).length);
        int sent = packets("map_frame").size();
        old.run();
        NativeSpeedLimitTestSupport.idleMainLooperFor(1000);
        assertEquals(sent, packets("map_frame").size());
    }

    @Test public void diagnosticAndShanghaiSuspendCaptureWithoutMapWritesIntoShanghai() throws Exception {
        start("com.waze", 1);
        frame(new byte[]{1}, 1);
        output.setManualEnabled(true, "diagnostic");
        output.publishManual(new HudState(), "sample");
        NativeSpeedLimitTestSupport.idleMainLooperFor(200);
        assertEquals("", Capture.owner);
        output.stopManualOutput("diagnostic-end", null);
        NativeSpeedLimitTestSupport.idleMainLooperFor(200);
        assertEquals("com.waze", Capture.owner);
        frame(new byte[]{2}, 3);
        ShanghaiOutputGate.suspend();
        int mapWrites = packets("map_frame").size() + packets("map_clear").size();
        NativeSpeedLimitTestSupport.idleMainLooperFor(1200);
        assertEquals("", Capture.owner);
        assertEquals(mapWrites, packets("map_frame").size() + packets("map_clear").size());
        ShanghaiOutputGate.resume();
    }

    @Test public void nativeToExperimentalClearsNativePlaneAndKeepsCaptureOwner() {
        start("com.waze", 1);
        frame(new byte[]{1}, 1);
        mode(HudMapSettings.EXPERIMENTAL);
        NativeSpeedLimitTestSupport.idleMainLooperFor(50);
        assertEquals("com.waze", Capture.owner);
        assertEquals(1, packets("map_clear").size());
        int sent = packets("map_frame").size();
        NativeSpeedLimitTestSupport.idleMainLooperFor(1000);
        assertEquals(sent, packets("map_frame").size());
        DirectTbtPayload.Options options = ReflectionHelpers.getField(output, "preparedDirectOptions");
        assertArrayEquals(new byte[]{1}, (byte[]) ReflectionHelpers.getField(options, "mapPng"));
        List<NativeSpeedLimitTestSupport.Packet> guidance = packets("payload");
        assertFalse(guidance.get(guidance.size() - 1).retainPayload);
        frame(new byte[0], 2);
        guidance = packets("payload");
        assertTrue(guidance.get(guidance.size() - 1).retainPayload);
    }

    private void mode(int mode) {
        HudPrefs.setMapSettings(context, HudPrefs.mapSettings(context).withMode(mode));
    }
    private void start(String owner, long session) {
        output.publishDirect(DirectTbtFrame.empty(), "frame", SystemClock.elapsedRealtime(), owner, session);
        output.selectNavigationSource(HudOutputCoordinator.Source.DIRECT, "start", owner, session);
        NativeSpeedLimitTestSupport.idleMainLooper();
    }
    private void frame(byte[] png, long revision) {
        Capture.image = new NavigatorMapCapture.Snapshot(png, revision, SystemClock.elapsedRealtime());
        Capture.callback.run();
        NativeSpeedLimitTestSupport.idleMainLooperFor(50);
    }
    private static List<NativeSpeedLimitTestSupport.Packet> packets(String kind) {
        return NativeSpeedLimitTestSupport.PACKETS.stream().filter(p -> kind.equals(p.kind))
                .collect(Collectors.toList());
    }

    @Implements(NavigatorMapCapture.class)
    public static final class Capture {
        static String owner;
        static long generation;
        static Runnable callback;
        static NavigatorMapCapture.Snapshot image;
        @Implementation protected static void activate(Context context, String pkg, long session, Runnable changed) {
            owner = pkg; generation = session; callback = changed;
        }
        @Implementation protected static void stop(String reason) {
            owner = "";
            image = new NavigatorMapCapture.Snapshot(new byte[0], image.revision() + 1, 0);
        }
        @Implementation protected static NavigatorMapCapture.Snapshot snapshot() { return image; }
    }

    @Implements(SomeIpHudClient.class)
    public static final class Client {
        @Implementation protected boolean isBound() { return true; }
        @Implementation protected boolean hasBinding() { return true; }
        @Implementation protected void bind() { }
        @Implementation protected void unbind() { }
        @Implementation protected int start() { return 0; }
        @Implementation protected int stop() { return 0; }
        @Implementation protected int send(byte[] payload) { return 0; }
        @Implementation protected int sendToTopic(long topic, byte[] payload) {
            topics.add(topic); return 0;
        }
    }
}
