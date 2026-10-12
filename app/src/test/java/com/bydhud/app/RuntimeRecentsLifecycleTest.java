package com.bydhud.app;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlarmManager;
import android.app.Application;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;

/** Exercises real task-removal/recovery entrypoints without starting vehicle transports. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class,
        shadows = {RuntimeRecentsLifecycleTest.Gate.class, RuntimeRecentsLifecycleTest.Work.class})
@LooperMode(LooperMode.Mode.PAUSED)
public final class RuntimeRecentsLifecycleTest {
    private Context context;
    private AlarmManager alarms;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        HudPrefs.setBootEnabled(context, false);
        HudPrefs.setUserShutdownActive(context, false);
        UserRuntimeSession.PROCESS.shutdown();
        Work.recoveryRequests = 0;
        Gate.pending = null;
    }

    @Test public void swipeKeepsActiveRuntimeWithEitherAutoStartSetting() {
        for (boolean autoStart : new boolean[]{true, false}) {
            HudPrefs.setBootEnabled(context, autoStart);
            UserRuntimeSession.PROCESS.activate();
            HudRuntimeService service = Robolectric.buildService(HudRuntimeService.class).get();
            service.onTaskRemoved(new Intent(context, MainActivity.class));

            assertFalse(shadowOf(service).isStoppedBySelf());
            assertTrue(UserRuntimeSession.allowsRuntime(context));
            assertNotNull("recovery remains scheduled after swipe", scheduledRecovery());
            assertNotNull("service keepalive is admitted through boot cleanup", Gate.pending);
        }
    }

    @Test public void explicitShutdownRejectsStickyAndPreviouslyScheduledRecovery() {
        UserRuntimeSession.PROCESS.activate();
        HudRuntimeWatchdog.schedule(context, "test-before-shutdown");
        Intent recovery = scheduledRecovery();
        UserRuntimeSession.PROCESS.shutdown();
        HudPrefs.setUserShutdownActive(context, true);

        HudRuntimeService service = Robolectric.buildService(HudRuntimeService.class).get();
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1));
        new HudRuntimeWatchdogReceiver().onReceive(context, recovery);

        assertFalse(UserRuntimeSession.allowsRuntime(context));
        assertEquals(0, Work.recoveryRequests);
        assertNull(shadowOf(alarms).getNextScheduledAlarm());
    }

    private Intent scheduledRecovery() {
        var alarm = shadowOf(alarms).peekNextScheduledAlarm();
        assertNotNull(alarm);
        return shadowOf(alarm.operation).getSavedIntent();
    }

    @Test public void latestCheckpointAndAllStopWaitersSurviveOrderedPersistence() throws Exception {
        assertTrue(ShellRuntimeSession.arm(context));
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        ShellRuntimeSession.afterPersistence(() -> {
            entered.countDown();
            try { assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new AssertionError(e); }
        });
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        java.util.concurrent.CountDownLatch stopped = new java.util.concurrent.CountDownLatch(2);
        ShellRuntimeSession.checkpointOutput(context, "running", null);
        ShellRuntimeSession.checkpointOutput(context, "stopped", stopped::countDown);
        ShellRuntimeSession.checkpointOutput(context, "stopped", stopped::countDown);
        release.countDown();
        assertTrue(stopped.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals("stopped", ShellRuntimeSession.prefs(context).getString("output", ""));
        HudPrefs.setUserShutdownActive(context, true);
        ShellRuntimeSession.checkpointOutput(context, "late-running", null);
        java.util.concurrent.CountDownLatch complete = new java.util.concurrent.CountDownLatch(1);
        ShellRuntimeSession.afterPersistence(complete::countDown);
        assertTrue(complete.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertFalse(ShellRuntimeSession.prefs(context).getBoolean("active", true));
        assertFalse(ShellRuntimeSession.prefs(context).contains("output"));
    }

    @Implements(BootCleanupGate.class)
    public static class Gate {
        static Runnable pending;
        @Implementation protected static void runWhenReady(Context context, Runnable runnable) {
            pending = runnable;
        }
    }

    @Implements(HudRuntimeSupervisor.class)
    public static class Work {
        static int recoveryRequests;
        @Implementation protected static boolean hasActiveRuntimeWork(Context context) { return true; }
        @Implementation protected static void ensureStarted(Context context, String reason) {
            recoveryRequests++;
        }
    }
}
