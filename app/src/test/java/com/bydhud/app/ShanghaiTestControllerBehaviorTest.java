package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 29, shadows = {
        ShanghaiBehaviorSupport.ShadowGps.class,
        ShanghaiBehaviorSupport.ShadowDiagnostics.class,
        ShanghaiBehaviorSupport.ShadowInstrument.class,
        ShanghaiBehaviorSupport.ShadowNavHud.class,
        ShanghaiBehaviorSupport.ShadowService.class,
        ShanghaiBehaviorSupport.ShadowMainActivity.class,
        ShanghaiBehaviorSupport.ShadowAppEventLogger.class,
        ShanghaiBehaviorSupport.ShadowHudPrefs.class
})
@LooperMode(LooperMode.Mode.PAUSED)
public final class ShanghaiTestControllerBehaviorTest {
    private ShanghaiTestController controller;
    private ShanghaiBehaviorSupport.ManualScheduler scheduler;
    private LogcatRecorder.Session sharedSession;
    private File scratch;

    @Before public void setUp() throws Exception {
        ShanghaiBehaviorSupport.reset();
        Context context = RuntimeEnvironment.getApplication();
        controller = ShanghaiTestController.get(context);
        ScheduledExecutorService original = ShanghaiBehaviorSupport.getField(controller, "worker");
        original.shutdownNow();
        scheduler = new ShanghaiBehaviorSupport.ManualScheduler();
        ShanghaiBehaviorSupport.setField(controller, "worker", scheduler);
        ShanghaiBehaviorSupport.setField(controller, "gps", new ShanghaiMockGps(context));

        scratch = Files.createTempDirectory("shanghai-controller-behavior").toFile();
        sharedSession = new LogcatRecorder.Session(context, "20260923", "existing-capture", scratch);
        LogcatRecorder.CaptureLease sharedLease = new LogcatRecorder.CaptureLease(sharedSession, false);
        ShanghaiBehaviorSupport.setField(LogcatRecorder.class, "activeSession", sharedSession);
        ShanghaiBehaviorSupport.setField(LogcatRecorder.class, "activeStartDay", "20260923");
        ShanghaiBehaviorSupport.setField(LogcatRecorder.class, "shanghaiLease", sharedLease);

        Class<?> runType = Class.forName(
                "com.bydhud.app.ShanghaiTestController$Run");
        Constructor<?> constructor = runType.getDeclaredConstructor(boolean.class);
        constructor.setAccessible(true);
        Object run = constructor.newInstance(false);
        setRunField(run, "logcat", sharedLease);
        setRunField(run, "diagnostics", new ShanghaiDiagnostics(
                context, new File(scratch, "diagnostics"), "20260923"));
        setRunField(run, "directory", scratch);
        setRunField(run, "day", "20260923");
        setRunField(run, "outputSuspended", true);
        setRunField(run, "instrumentSuspendAttempted", true);
        setRunField(run, "restoreOutput", true);
        ShanghaiBehaviorSupport.setField(controller, "current", run);
        ShanghaiBehaviorSupport.failHelperRestore = false;
        ShanghaiOutputGate.suspend();
    }

    @After public void tearDown() throws Exception {
        if (scheduler != null) scheduler.shutdownNow();
        ShanghaiOutputGate.resume();
        ShanghaiBehaviorSupport.setField(controller, "current", null);
        ShanghaiBehaviorSupport.setField(ShanghaiTestController.class, "instance", null);
        ShanghaiBehaviorSupport.setField(ShanghaiTestController.class, "cached", new ShanghaiTestState());
        ShanghaiBehaviorSupport.setField(LogcatRecorder.class, "activeSession", null);
        ShanghaiBehaviorSupport.setField(LogcatRecorder.class, "finalizingSession", null);
        ShanghaiBehaviorSupport.setField(LogcatRecorder.class, "shanghaiLease", null);
        ShanghaiBehaviorSupport.setField(LogcatRecorder.class, "activeStartDay", "");
        ShanghaiBehaviorSupport.setField(LogcatRecorder.class, "lastStatus", LogcatRecorder.STATUS_WAITING);
        ShanghaiBehaviorSupport.setField(LogcatRecorder.class, "lastDetail", "");
        ShanghaiBehaviorSupport.setField(LogcatRecorder.class, "lastSavedFile", null);
        ShanghaiBehaviorSupport.setField(NavHudLiveSender.class, "instance", null);
        ShanghaiBehaviorSupport.setField(InstrumentProxyManager.class, "instance", null);
        ShanghaiBehaviorSupport.reset();
        if (scratch != null) {
            Files.deleteIfExists(new File(scratch, "shanghai-session.json").toPath());
        }
        if (scratch != null) Files.deleteIfExists(scratch.toPath());
    }

    @Test public void repeatedStopCleansOnceContinuesAfterHelperFailureAndRetainsSharedLogcat()
            throws Exception {
        ShanghaiBehaviorSupport.failHelperRestore = true;
        controller.stop("first", null);
        controller.stop("second", null);
        assertEquals(2, scheduler.queuedCount());
        scheduler.runUntilIdle();

        List<String> events = ShanghaiBehaviorSupport.EVENTS;
        assertEquals(1, count(events, "gps.cleanup"));
        assertEquals(1, count(events, "diagnostics.stop"));
        assertEquals(1, count(events, "instrument.resume:leaseReleased=true:recordingRetained=true"));
        assertEquals(1, count(events, "diagnostics.record:instrument_restore_error"));
        assertEquals(1, count(events,
                "diagnostics.detail:instrument_restore_error:IOException: injected helper restore failure"));
        assertEquals(1, count(events, "output.resume"));
        assertEquals(1, count(events, "service.finish"));
        assertBefore(events, "gps.cleanup", "diagnostics.record:session_end");
        assertBefore(events, "diagnostics.record:session_end", "diagnostics.stop");
        assertBefore(events, "diagnostics.stop", "instrument.resume:leaseReleased=true:recordingRetained=true");
        assertBefore(events, "instrument.resume:leaseReleased=true:recordingRetained=true",
                "output.resume");
        assertBefore(events, "output.resume", "service.finish");
        assertTrue(LogcatRecorder.isRecording());
        assertFalse(LogcatRecorder.isReservedForShanghai());
        assertFalse(ShanghaiOutputGate.isSuspended());
        assertEquals(ShanghaiTestState.Phase.ERROR, ShanghaiTestController.snapshot().phase);
    }

    @Test public void gpsCleanupFailureIsRecordedAndDoesNotBlockCaptureOrServiceCleanup()
            throws Exception {
        ShanghaiBehaviorSupport.gpsCleanupResult = ShanghaiMockGps.Result.of(
                ShanghaiMockGps.Code.PROVIDER_ERROR, "injected GPS cleanup failure");
        controller.stop("gps-failure", null);
        scheduler.runUntilIdle();

        List<String> events = ShanghaiBehaviorSupport.EVENTS;
        assertTrue("events=" + events, events.contains("diagnostics.record:mock_cleanup"));
        assertTrue("events=" + events, events.contains(
                "diagnostics.detail:mock_cleanup:PROVIDER_ERROR: injected GPS cleanup failure"));
        assertTrue("events=" + events, events.contains("diagnostics.stop"));
        assertTrue("events=" + events, events.contains(
                "instrument.resume:leaseReleased=true:recordingRetained=true"));
        assertTrue("events=" + events, events.contains("output.resume"));
        assertTrue("events=" + events, events.contains("service.finish"));
        assertTrue(LogcatRecorder.isRecording());
        assertTrue(ShanghaiTestController.snapshot().cleanupPending);
        assertEquals(ShanghaiTestState.Phase.ERROR, ShanghaiTestController.snapshot().phase);
    }

    @Test public void captureStopFailureStillReleasesSharedLeaseRestoresOutputsAndStopsService()
            throws Exception {
        ShanghaiBehaviorSupport.failDiagnosticsStop = true;
        controller.stop("capture-failure", null);
        scheduler.runUntilIdle();

        List<String> events = ShanghaiBehaviorSupport.EVENTS;
        assertTrue("events=" + events, events.contains("diagnostics.stop"));
        assertTrue("events=" + events, events.contains(
                "instrument.resume:leaseReleased=true:recordingRetained=true"));
        assertTrue("events=" + events, events.contains("output.resume"));
        assertTrue("events=" + events, events.contains("service.finish"));
        assertTrue(LogcatRecorder.isRecording());
        assertFalse(LogcatRecorder.isReservedForShanghai());
        assertEquals(ShanghaiTestState.Phase.ERROR, ShanghaiTestController.snapshot().phase);
        JSONObject summary = new JSONObject(new String(Files.readAllBytes(
                new File(scratch, "shanghai-session.json").toPath()), StandardCharsets.UTF_8));
        assertTrue(summary.getString("error").contains("injected capture stop failure"));
    }

    @Test public void controllerStopsOnlyItsOwnedLogcatCaptureOnHealthyCleanup() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        LogcatRecorder.Session ownedSession = new LogcatRecorder.Session(
                context, "20260923", "shanghai-owned", scratch);
        ownedSession.finalized = true;
        ownedSession.finishFuture = CompletableFuture.completedFuture(null);
        LogcatRecorder.CaptureLease ownedLease = new LogcatRecorder.CaptureLease(ownedSession, true);
        ShanghaiBehaviorSupport.setField(LogcatRecorder.class, "activeSession", ownedSession);
        ShanghaiBehaviorSupport.setField(LogcatRecorder.class, "shanghaiLease", ownedLease);
        Object run = ShanghaiBehaviorSupport.getField(controller, "current");
        setRunField(run, "logcat", ownedLease);
        ShanghaiBehaviorSupport.failHelperRestore = false;

        controller.stop("healthy", null);
        scheduler.runUntilIdle();

        List<String> events = ShanghaiBehaviorSupport.EVENTS;
        assertFalse(LogcatRecorder.isRecording());
        assertFalse(LogcatRecorder.isReservedForShanghai());
        assertTrue(ownedSession.stopRequested);
        assertTrue("events=" + events, events.contains(
                "instrument.resume:leaseReleased=true:recordingRetained=false"));
        assertTrue("events=" + events, events.contains("output.resume"));
        assertTrue("events=" + events, events.contains("service.finish"));
        assertEquals(ShanghaiTestState.Phase.STOPPED, ShanghaiTestController.snapshot().phase);
    }

    private static int count(List<String> events, String expected) {
        int count = 0;
        synchronized (events) {
            for (String event : events) if (expected.equals(event)) count++;
        }
        return count;
    }

    private static void assertBefore(List<String> events, String first, String second) {
        assertTrue(events.indexOf(first) >= 0);
        assertTrue(events.indexOf(first) < events.indexOf(second));
    }

    private static void setRunField(Object run, String name, Object value) throws Exception {
        ShanghaiBehaviorSupport.setField(run, name, value);
    }
}
