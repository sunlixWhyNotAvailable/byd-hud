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
        HudPrefs.setNativeSpeedLimitClearMode(context,
                HudPrefs.SPEED_LIMIT_NATIVE_CLEAR_AT_NAVIGATION_END);
        NativeSpeedLimitTestSupport.raw = 13;
        coordinator = NativeSpeedLimitTestSupport.newCoordinator(context);
    }

    @After public void tearDown() {
        NativeSpeedLimitTestSupport.stopCoordinator(coordinator);
    }

    @Test public void nativeTerminalClearConfirmsBeforeHudClearAndTransportStop() {
        startManualOutput();
        int[] completed = {0};
        int stopStarted = NativeSpeedLimitTestSupport.EVENTS.size();
        coordinator.stopManualOutput("manual-stop", true, () -> completed[0]++);

        NativeSpeedLimitTestSupport.idleMainLooper();
        NativeSpeedLimitTestSupport.idleMainLooperFor(1_000L);

        int terminalLimit = NativeSpeedLimitTestSupport.EVENTS.indexOf("native:2=1");
        int terminalRoad = indexOfAfter("native:1=6", terminalLimit + 1);
        int confirmationRead = indexOfAfter("native:0=0", terminalRoad + 1);
        int firstHudClear = indexOfAfter("transport:send", stopStarted);
        int transportStop = NativeSpeedLimitTestSupport.EVENTS.indexOf("transport:stop");

        assertTrue("terminal Native target was written", terminalLimit >= 0);
        assertTrue("Native road restoration followed its clear target", terminalRoad > terminalLimit);
        assertTrue("terminal target was read back", confirmationRead > terminalRoad);
        assertTrue("HUD clear followed terminal readback", firstHudClear > confirmationRead);
        assertTrue("transport stopped after HUD clear", transportStop > firstHudClear);
        assertEquals("one terminal clear attempt", 1,
                NativeSpeedLimitTestSupport.countEvents("native:2=1"));
        assertEquals("five final HUD clears", 5,
                countAfter("transport:send", confirmationRead));
        assertEquals("transport stopped once", 1,
                NativeSpeedLimitTestSupport.countEvents("transport:stop"));
        assertEquals(1, completed[0]);
    }

    @Test public void repeatedStopJoinsOneTerminalAttemptAndCompletesEachWaiterOnce() {
        startManualOutput();
        int[] first = {0};
        int[] second = {0};

        coordinator.stopManualOutput("manual-stop", true, () -> first[0]++);
        coordinator.stopManualOutput("duplicate-manual-stop", true, () -> second[0]++);
        NativeSpeedLimitTestSupport.idleMainLooper();
        NativeSpeedLimitTestSupport.idleMainLooperFor(1_000L);

        assertEquals(1, first[0]);
        assertEquals(1, second[0]);
        assertEquals(1, NativeSpeedLimitTestSupport.countEvents("native:2=1"));
        assertEquals(1, NativeSpeedLimitTestSupport.countEvents("transport:stop"));
    }

    @Test public void missingNativeCallbackEndsWithinSharedTeardownDeadline() {
        startManualOutput();
        NativeSpeedLimitTestSupport.missingOperation = NativeSpeedLimitEngine.READ;
        int[] completed = {0};
        long startedAt = SystemClock.elapsedRealtime();

        coordinator.stopManualOutput("manual-stop", true, () -> completed[0]++);
        NativeSpeedLimitTestSupport.idleMainLooper();
        assertEquals(1, NativeSpeedLimitTestSupport.countEvents("native:0=0"));
        assertFalse(NativeSpeedLimitTestSupport.EVENTS.contains("native:2=1"));

        NativeSpeedLimitTestSupport.idleMainLooperFor(NativeSpeedLimitEngine.CLEAR_TIMEOUT_MS);

        assertEquals(NativeSpeedLimitEngine.CLEAR_TIMEOUT_MS,
                SystemClock.elapsedRealtime() - startedAt);
        assertEquals(1, completed[0]);
        assertEquals(1, NativeSpeedLimitTestSupport.countEvents("transport:stop"));
        assertFalse(NativeSpeedLimitTestSupport.EVENTS.contains("native:2=1"));
    }

    @Test public void nativeWriteErrorCompletesOnceWithoutSendingRemainingSteps() {
        startManualOutput();
        NativeSpeedLimitTestSupport.failOperation = NativeSpeedLimitEngine.ROAD;
        int[] completed = {0};

        coordinator.stopManualOutput("manual-stop", true, () -> completed[0]++);
        NativeSpeedLimitTestSupport.idleMainLooper();
        NativeSpeedLimitTestSupport.idleMainLooperFor(1_000L);

        assertEquals(1, completed[0]);
        assertEquals(1, NativeSpeedLimitTestSupport.countEvents("native:1=7"));
        assertFalse(NativeSpeedLimitTestSupport.EVENTS.contains("native:2=1"));
        assertFalse(NativeSpeedLimitTestSupport.EVENTS.contains("native:1=6"));
        assertEquals(1, NativeSpeedLimitTestSupport.countEvents("transport:stop"));
    }

    @Test public void newSessionCancelsPendingTerminalWriteAndIgnoresLateCallback() {
        startManualOutput();
        NativeSpeedLimitTestSupport.deferNextOperation = NativeSpeedLimitEngine.ROAD;
        coordinator.stopManualOutput("manual-stop", true, null);
        NativeSpeedLimitTestSupport.idleMainLooper();

        NativeSpeedLimitTestSupport.Pending stale = NativeSpeedLimitTestSupport.pending;
        assertNotNull(stale);
        assertEquals(NativeSpeedLimitEngine.ROAD, stale.operation);
        assertEquals(7, stale.value);

        coordinator.cancelNativeEndClear("navigation-session-start");
        coordinator.setManualEnabled(true, "new-session");
        coordinator.publishManual(new HudState(), "new-session-frame");
        NativeSpeedLimitTestSupport.idleMainLooper();

        assertFalse(stale.current.getAsBoolean());
        NativeSpeedLimitTestSupport.releasePending(true);
        NativeSpeedLimitTestSupport.idleMainLooperFor(1_000L);

        assertFalse("retired session must not send its terminal target",
                NativeSpeedLimitTestSupport.EVENTS.contains("native:2=1"));
        assertFalse("retired session must not restore its road field",
                NativeSpeedLimitTestSupport.EVENTS.contains("native:1=6"));
        assertEquals("new session keeps the transport alive", 0,
                NativeSpeedLimitTestSupport.countEvents("transport:stop"));
    }

    private void startManualOutput() {
        coordinator.setManualEnabled(true, "test-session-start");
        coordinator.publishManual(new HudState(), "test-frame");
        NativeSpeedLimitTestSupport.idleMainLooper();
        assertTrue(NativeSpeedLimitTestSupport.EVENTS.contains("transport:start"));
        assertTrue(NativeSpeedLimitTestSupport.EVENTS.contains("transport:send"));
    }

    private static int indexOfAfter(String expected, int start) {
        synchronized (NativeSpeedLimitTestSupport.EVENTS) {
            for (int i = Math.max(0, start); i < NativeSpeedLimitTestSupport.EVENTS.size(); i++) {
                if (expected.equals(NativeSpeedLimitTestSupport.EVENTS.get(i))) return i;
            }
        }
        return -1;
    }

    private static int countAfter(String expected, int start) {
        int count = 0;
        synchronized (NativeSpeedLimitTestSupport.EVENTS) {
            for (int i = Math.max(0, start); i < NativeSpeedLimitTestSupport.EVENTS.size(); i++) {
                if (expected.equals(NativeSpeedLimitTestSupport.EVENTS.get(i))) count++;
            }
        }
        return count;
    }
}
