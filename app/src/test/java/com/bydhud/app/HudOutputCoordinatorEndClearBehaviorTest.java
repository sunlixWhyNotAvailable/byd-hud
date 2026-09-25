package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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
import org.robolectric.annotation.LooperMode;

import java.util.List;
import java.util.stream.Collectors;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, application = Application.class, shadows = {
        NativeSpeedLimitTestSupport.InstrumentProxy.class,
        NativeSpeedLimitTestSupport.SomeIpClient.class,
        NativeSpeedLimitTestSupport.TxLog.class,
        NativeSpeedLimitTestSupport.EventsLog.class,
        NativeSpeedLimitTestSupport.DebugWriter.class
})
@LooperMode(LooperMode.Mode.PAUSED)
public final class HudOutputCoordinatorEndClearBehaviorTest {
    private Context context;
    private HudOutputCoordinator coordinator;

    @Before public void setUp() {
        NativeSpeedLimitTestSupport.reset();
        context = RuntimeEnvironment.getApplication();
        NativeSpeedLimitTestSupport.resetPreferences(context);
        HudPrefs.setSpeedLimitMode(context, HudPrefs.SPEED_LIMIT_NATIVE);
        context.getSharedPreferences("byd_hud_prefs", Context.MODE_PRIVATE).edit()
                .putInt("speed_limit_native_clear_mode", 2)
                .putInt("speed_limit_native_clear_mode_native", 3).commit();
        NativeSpeedLimitTestSupport.raw = 13;
        coordinator = NativeSpeedLimitTestSupport.newCoordinator(context);
    }

    @After public void tearDown() {
        NativeSpeedLimitTestSupport.stopCoordinator(coordinator);
        DirectSpeedLimitStore.clear("com.waze");
    }

    @Test public void manualStopClearsHudImmediatelyWithoutNativeIoAndKeepsOrdinaryCadence() {
        startManualOutput();
        NativeSpeedLimitTestSupport.missingOperation = NativeSpeedLimitEngine.READ;
        long startedAt = SystemClock.elapsedRealtime();
        int[] completed = {0};
        coordinator.stopManualOutput("manual-stop", () -> completed[0]++);
        NativeSpeedLimitTestSupport.idleMainLooper();

        assertEquals(startedAt, packets("final_clear").get(0).at);
        assertEquals(1, completed[0]);
        NativeSpeedLimitTestSupport.idleMainLooperFor(779L);
        assertEquals(0, NativeSpeedLimitTestSupport.countEvents("transport:stop"));
        NativeSpeedLimitTestSupport.idleMainLooperFor(1L);
        assertEquals(1, NativeSpeedLimitTestSupport.countEvents("transport:stop"));
        List<NativeSpeedLimitTestSupport.Packet> clears = packets("final_clear");
        assertEquals(5, clears.size());
        for (int i = 0; i < clears.size(); i++) {
            assertEquals(startedAt + 120L * i, clears.get(i).at);
        }
        assertFalse(NativeSpeedLimitTestSupport.EVENTS.stream()
                .anyMatch(event -> event.startsWith("native:")));
        assertTrue(packets("native_end_keepalive").isEmpty());
        assertEquals(1, completed[0]);
    }

    @Test public void directStopCancelsPendingLimitAndSendsNoFurtherActiveRoadInfo() {
        NativeSpeedLimitTestSupport.raw = 12;
        DirectSpeedLimitStore.update("com.waze", 60, 60, "km/h", 1L);
        startDirectOutput(1L);
        assertTrue(NativeSpeedLimitTestSupport.EVENTS.contains("native:1=7"));
        long startedAt = SystemClock.elapsedRealtime();
        int activePackets = packets("payload").size();
        coordinator.endNavigationOutput("com.waze", 1L, "route-end", startedAt, null);
        NativeSpeedLimitTestSupport.idleMainLooper();
        assertEquals(startedAt, packets("final_clear").get(0).at);
        NativeSpeedLimitTestSupport.idleMainLooperFor(20_000L);

        assertFalse(NativeSpeedLimitTestSupport.EVENTS.contains("native:2=60"));
        assertFalse(NativeSpeedLimitTestSupport.EVENTS.contains("native:2=1"));
        assertEquals(activePackets, packets("payload").size());
        assertTrue(packets("native_end_keepalive").isEmpty());
        assertEquals(5, packets("final_clear").size());
        assertEquals(1, NativeSpeedLimitTestSupport.countEvents("transport:stop"));
    }

    @Test public void repeatedStopJoinsOrdinaryClearAndCompletesEachWaiterOnce() {
        startManualOutput();
        int[] first = {0};
        int[] second = {0};
        coordinator.stopManualOutput("manual-stop", () -> first[0]++);
        coordinator.stopManualOutput("duplicate-manual-stop", () -> second[0]++);
        NativeSpeedLimitTestSupport.idleMainLooper();
        NativeSpeedLimitTestSupport.idleMainLooperFor(1_000L);
        assertEquals(1, first[0]);
        assertEquals(1, second[0]);
        assertEquals(5, packets("final_clear").size());
        assertEquals(1, NativeSpeedLimitTestSupport.countEvents("transport:stop"));
        assertFalse(NativeSpeedLimitTestSupport.EVENTS.contains("native:2=1"));
    }

    @Test public void lateNativeReadCannotDelayStopOrRestartWrites() {
        NativeSpeedLimitTestSupport.raw = 12;
        NativeSpeedLimitTestSupport.deferNextOperation = NativeSpeedLimitEngine.READ;
        DirectSpeedLimitStore.update("com.waze", 60, 60, "km/h", 1L);
        startDirectOutput(1L);
        NativeSpeedLimitTestSupport.Pending stale = NativeSpeedLimitTestSupport.pending;
        assertNotNull(stale);
        int[] completed = {0};
        long startedAt = SystemClock.elapsedRealtime();
        coordinator.endNavigationOutput("com.waze", 1L, "route-end", startedAt,
                () -> completed[0]++);
        NativeSpeedLimitTestSupport.idleMainLooper();
        assertFalse(stale.current.getAsBoolean());
        assertEquals(startedAt, packets("final_clear").get(0).at);
        assertEquals(1, completed[0]);
        NativeSpeedLimitTestSupport.idleMainLooperFor(780L);
        assertEquals(1, NativeSpeedLimitTestSupport.countEvents("transport:stop"));
        NativeSpeedLimitTestSupport.releasePending(true);
        NativeSpeedLimitTestSupport.idleMainLooperFor(20_000L);
        assertFalse(NativeSpeedLimitTestSupport.EVENTS.stream()
                .anyMatch(event -> event.startsWith("native:1=") || event.startsWith("native:2=")));
        assertEquals(1, completed[0]);
    }

    @Test public void newSessionPreventsOldDelayedTransportStop() {
        startManualOutput();
        coordinator.stopManualOutput("manual-stop", null);
        NativeSpeedLimitTestSupport.idleMainLooper();
        NativeSpeedLimitTestSupport.idleMainLooperFor(100L);
        startManualOutput();
        NativeSpeedLimitTestSupport.idleMainLooperFor(1_000L);
        assertEquals("new session keeps the transport alive", 0,
                NativeSpeedLimitTestSupport.countEvents("transport:stop"));
        assertTrue(packets("payload").size() > 1);
        assertFalse(NativeSpeedLimitTestSupport.EVENTS.contains("native:2=1"));
    }

    private static List<NativeSpeedLimitTestSupport.Packet> packets(String kind) {
        return NativeSpeedLimitTestSupport.PACKETS.stream()
                .filter(packet -> packet.kind.equals(kind)).collect(Collectors.toList());
    }

    private void startManualOutput() {
        coordinator.setManualEnabled(true, "test-session-start");
        coordinator.publishManual(new HudState(), "test-frame");
        NativeSpeedLimitTestSupport.idleMainLooper();
        assertTrue(NativeSpeedLimitTestSupport.EVENTS.contains("transport:start"));
        assertTrue(NativeSpeedLimitTestSupport.EVENTS.contains("transport:send"));
    }

    private void startDirectOutput(long session) {
        coordinator.publishDirect(DirectTbtFrame.empty(), "test-frame",
                SystemClock.elapsedRealtime(), "com.waze", session);
        coordinator.selectNavigationSource(HudOutputCoordinator.Source.DIRECT,
                "test-start", "com.waze", session);
        NativeSpeedLimitTestSupport.idleMainLooper();
        assertFalse(packets("payload").isEmpty());
    }
}
