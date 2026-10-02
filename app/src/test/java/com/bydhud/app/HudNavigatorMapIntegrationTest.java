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
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicInteger;

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
        Capture.expectedSource = null;
        Capture.profileOverride = null;
        context = RuntimeEnvironment.getApplication();
        NativeSpeedLimitTestSupport.resetPreferences(context);
        HudPrefs.setSpeedLimitMode(context, HudPrefs.SPEED_LIMIT_OFF);
        mode(HudMapSettings.NATIVE);
        output = NativeSpeedLimitTestSupport.newCoordinator(context);
    }

    @After public void cleanup() { NativeSpeedLimitTestSupport.stopCoordinator(output); }

    @Test public void changedNativeFramesAreCappedAtFiveHzAndOnlyLatestIsSent() {
        HudPrefs.setMapSettings(context, HudPrefs.mapSettings(context).withUpdateRateHz(5));
        start("com.waze", 1);
        frame(new byte[]{1}, 1);
        assertEquals(1, packets("map_frame").size());
        frame(new byte[]{2}, 2);
        frame(new byte[]{3}, 3);
        assertEquals(1, packets("map_frame").size());
        NativeSpeedLimitTestSupport.idleMainLooperFor(50);
        assertEquals(2, packets("map_frame").size());
        assertArrayEquals(HudMapImage.nativePayload(new byte[]{3}), packets("map_frame").get(1).payload);
        // A profile revision with identical PNG bytes is still just a cached repeat.
        frame(new byte[]{3}, 4);
        NativeSpeedLimitTestSupport.idleMainLooperFor(900);
        assertEquals(2, packets("map_frame").size());
        NativeSpeedLimitTestSupport.idleMainLooperFor(50);
        assertEquals(3, packets("map_frame").size());
    }

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

    @Test public void defaultOneHzCapsChangedNativeFramesUntilRateIsRaised() {
        start("com.waze", 1);
        frame(new byte[]{1}, 1);
        frame(new byte[]{2}, 2);
        NativeSpeedLimitTestSupport.idleMainLooperFor(400);
        assertEquals(1, packets("map_frame").size());
        HudPrefs.setMapSettings(context, HudPrefs.mapSettings(context).withUpdateRateHz(5));
        frame(new byte[]{3}, 3);
        assertEquals(2, packets("map_frame").size());
        assertArrayEquals(HudMapImage.nativePayload(new byte[]{3}), packets("map_frame").get(1).payload);
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

    @Test public void profileDraftSwitchKeepsOneManualSessionAndRestoresLatestDirectOwner() {
        start("com.waze", 1);
        DirectTbtFrame oldDirect = directFrame("Old Road");
        output.publishDirect(oldDirect, "old-direct", SystemClock.elapsedRealtime(),
                "com.waze", 1);
        NativeSpeedLimitTestSupport.idleMainLooper();

        HudMapProfile firstDraft = new HudMapProfile(
                HudMapProfile.Source.GOOGLE_MAPS, 0, 0, 100);
        AtomicInteger frameCallbacks = new AtomicInteger();
        output.startMapProfileCalibration(77L, firstDraft, frameCallbacks::incrementAndGet);
        output.publishManualMapLive(new HudState(), DirectTbtFrame.empty(), 77L,
                "profile-calibration");
        output.setManualEnabled(true, "profile-calibration");
        NativeSpeedLimitTestSupport.idleMainLooperFor(250);

        assertEquals(GMapsDirectChannel.PACKAGE_NAME, Capture.owner);
        assertEquals(HudMapProfile.Source.GOOGLE_MAPS, Capture.expectedSource);
        assertEquals(firstDraft, Capture.profileOverride);
        assertEquals(77L, (long) ReflectionHelpers.getField(output, "manualMapLiveSession"));
        assertEquals(77L, (long) ReflectionHelpers.getField(output,
                "mapProfileCalibrationSession"));
        NativeSpeedLimitController speed = ReflectionHelpers.getField(output, "nativeSpeed");
        assertEquals(77L, (long) ReflectionHelpers.getField(speed, "mapLiveSession"));
        assertTrue((boolean) ReflectionHelpers.getField(speed, "mapProfileCalibration"));
        Capture.image = new NavigatorMapCapture.Snapshot(
                new byte[]{1, 2, 3}, 1L, SystemClock.elapsedRealtime(),
                HudMapProfile.Source.GOOGLE_MAPS);
        Capture.callback.run();
        NativeSpeedLimitTestSupport.idleMainLooperFor(50);
        assertEquals(1, frameCallbacks.get());
        assertArrayEquals(new byte[]{1, 2, 3},
                (byte[]) ReflectionHelpers.getField(output, "navigatorMapPng"));

        Runnable calibrationCallback = Capture.callback;
        int stops = Capture.stops;
        int clears = packets("direct_loss_clear").size();
        output.selectNavigationSource(HudOutputCoordinator.Source.NONE, "waze-resume");
        NativeSpeedLimitTestSupport.idleMainLooperFor(50);
        output.publishDirect(directFrame("New Road"), "new-direct", SystemClock.elapsedRealtime(),
                "com.waze", 2);
        output.selectNavigationSource(HudOutputCoordinator.Source.DIRECT, "waze-start", "com.waze", 2);
        NativeSpeedLimitTestSupport.idleMainLooperFor(50);
        output.clearDirectFrameForLoss("com.waze", 2, "waze-loss", SystemClock.elapsedRealtime());
        NativeSpeedLimitTestSupport.idleMainLooperFor(50);
        assertEquals("direct lifecycle must not stop calibration", stops, Capture.stops);
        assertEquals(clears, packets("direct_loss_clear").size());
        assertSame(calibrationCallback, Capture.callback);
        assertEquals(firstDraft, Capture.profileOverride);
        assertArrayEquals(new byte[]{1, 2, 3},
                (byte[]) ReflectionHelpers.getField(output, "navigatorMapPng"));

        HudMapProfile nextDraft = new HudMapProfile(
                HudMapProfile.Source.WAZE_SURFACE, 15, -4, 125);
        output.updateMapProfileCalibration(
                77L, nextDraft, frameCallbacks::incrementAndGet);
        NativeSpeedLimitTestSupport.idleMainLooperFor(100);
        assertEquals("com.waze", Capture.owner);
        assertEquals(HudMapProfile.Source.WAZE_SURFACE, Capture.expectedSource);
        assertEquals(nextDraft, Capture.profileOverride);
        assertEquals(77L, (long) ReflectionHelpers.getField(output, "manualMapLiveSession"));
        assertEquals(77L, (long) ReflectionHelpers.getField(output,
                "mapProfileCalibrationSession"));
        assertEquals(77L, (long) ReflectionHelpers.getField(speed, "mapLiveSession"));
        assertTrue((boolean) ReflectionHelpers.getField(speed, "mapProfileCalibration"));
        Capture.image = new NavigatorMapCapture.Snapshot(
                // Capture.stop() advances the global frame revision to 2 when changing source.
                new byte[]{4, 5, 6}, 3L, SystemClock.elapsedRealtime(),
                HudMapProfile.Source.WAZE_SURFACE);
        Capture.callback.run();
        NativeSpeedLimitTestSupport.idleMainLooperFor(50);
        assertEquals(2, frameCallbacks.get());
        assertArrayEquals(new byte[]{4, 5, 6},
                (byte[]) ReflectionHelpers.getField(output, "navigatorMapPng"));

        DirectTbtFrame latestDirect = directFrame("Latest Road");
        output.publishDirect(latestDirect, "latest-direct", SystemClock.elapsedRealtime(),
                "com.waze", 2);
        NativeSpeedLimitTestSupport.idleMainLooper();
        assertSame(latestDirect, ReflectionHelpers.getField(output, "directFrame"));
        assertEquals(HudOutputCoordinator.Source.MANUAL,
                ReflectionHelpers.getField(output, "activeSource"));

        output.endMapProfileCalibration(77L, "profile-hide");
        output.endMapLiveSession(77L, "profile-hide");
        output.stopManualOutput("profile-hide", null);
        NativeSpeedLimitTestSupport.idleMainLooperFor(250);
        assertEquals(HudOutputCoordinator.Source.DIRECT,
                ReflectionHelpers.getField(output, "activeSource"));
        assertSame(latestDirect, ReflectionHelpers.getField(output, "directFrame"));
        assertEquals("com.waze", Capture.owner);
        assertNull(Capture.expectedSource);

        Capture.image = new NavigatorMapCapture.Snapshot(
                new byte[]{9, 8, 7}, 9L, SystemClock.elapsedRealtime(),
                HudMapProfile.Source.WAZE);
        Capture.callback.run();
        NativeSpeedLimitTestSupport.idleMainLooperFor(50);
        assertArrayEquals(new byte[]{9, 8, 7},
                (byte[]) ReflectionHelpers.getField(output, "navigatorMapPng"));
        assertEquals(0L, (long) ReflectionHelpers.getField(speed, "mapLiveSession"));
        assertFalse((boolean) ReflectionHelpers.getField(speed, "mapProfileCalibration"));
    }

    private static DirectTbtFrame directFrame(String road) {
        return new DirectTbtFrame(1, 2, 3, 420, road, "Turn", road,
                null, null, Collections.emptyList(), DirectTbtFrame.AlertOverlay.inactive());
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
        static int stops;
        static String owner;
        static long generation;
        static Runnable callback;
        static NavigatorMapCapture.Snapshot image;
        static HudMapProfile.Source expectedSource;
        static HudMapProfile profileOverride;
        @Implementation protected static void activate(Context context, String pkg, long session, Runnable changed) {
            activateCapture(pkg, session, null, changed);
        }
        @Implementation protected static void activate(Context context, String pkg, long session,
                HudMapProfile.Source source, Runnable changed) {
            activateCapture(pkg, session, source, changed);
        }
        private static void activateCapture(String pkg, long session,
                HudMapProfile.Source source, Runnable changed) {
            owner = pkg;
            generation = session;
            expectedSource = source;
            callback = changed;
        }
        @Implementation protected static void setProfileOverride(HudMapProfile profile) {
            profileOverride = profile;
        }
        @Implementation protected static void refreshProfiles() {
            profileOverride = null;
        }
        @Implementation protected static void setActiveSourceMode(HudMapProfile.Source source) {
        }
        @Implementation protected static void stop(String reason) {
            stops++;
            owner = "";
            expectedSource = null;
            profileOverride = null;
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
