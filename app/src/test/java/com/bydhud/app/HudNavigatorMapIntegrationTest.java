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
        Capture.expectedSource = null;
        Capture.profileOverride = null;
        context = RuntimeEnvironment.getApplication();
        NativeSpeedLimitTestSupport.resetPreferences(context);
        HudPrefs.setSpeedLimitMode(context, HudPrefs.SPEED_LIMIT_OFF);
        mode(HudMapSettings.NATIVE);
        output = NativeSpeedLimitTestSupport.newCoordinator(context);
    }

    @After public void cleanup() { NativeSpeedLimitTestSupport.stopCoordinator(output); }

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
