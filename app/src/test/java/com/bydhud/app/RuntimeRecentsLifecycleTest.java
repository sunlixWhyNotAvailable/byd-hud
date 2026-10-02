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

    @Test public void existingAlarmRecoversManualSessionAfterProcessDeath() {
        UserRuntimeSession.PROCESS.activate();
        HudRuntimeWatchdog.schedule(context, "test-active-session");
        Intent recovery = scheduledRecovery();
        assertTrue(recovery.getBooleanExtra(HudRuntimeWatchdog.EXTRA_RESUME_SESSION, false));
        UserRuntimeSession.PROCESS.shutdown(); // Lost process state, not explicit user Shutdown.
        assertFalse(UserRuntimeSession.allowsRuntime(context));

        new HudRuntimeWatchdogReceiver().onReceive(context, recovery);

        assertTrue(UserRuntimeSession.allowsRuntime(context));
        assertEquals(1, Work.recoveryRequests);
    }

    @Test public void stickyRestartContinuesSessionWithAutoStartOff() {
        HudRuntimeService service = Robolectric.buildService(HudRuntimeService.class).get();
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1));
        assertTrue(UserRuntimeSession.allowsRuntime(context));
        assertNotNull(Gate.pending);
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

    @Test public void coldCallbackCannotStartRuntimeWhenAutoStartIsOff() {
        HudRuntimeService service = Robolectric.buildService(HudRuntimeService.class).get();
        assertEquals(Service.START_NOT_STICKY,
                service.onStartCommand(new Intent("cold-start"), 0, 1));
        new HudRuntimeWatchdogReceiver().onReceive(context,
                new Intent(HudRuntimeWatchdog.ACTION_RUNTIME_WATCHDOG));
        assertFalse(UserRuntimeSession.allowsRuntime(context));
        assertEquals(0, Work.recoveryRequests);
        assertNull(Gate.pending);
    }

    @Test public void autoStartAlarmDoesNotInventAUserSession() {
        HudPrefs.setBootEnabled(context, true);
        HudRuntimeWatchdog.schedule(context, "test-auto-start-only");
        Intent recovery = scheduledRecovery();
        assertFalse(recovery.getBooleanExtra(HudRuntimeWatchdog.EXTRA_RESUME_SESSION, false));
        HudPrefs.setBootEnabled(context, false);
        new HudRuntimeWatchdogReceiver().onReceive(context, recovery);
        assertFalse(UserRuntimeSession.allowsRuntime(context));
        assertEquals(0, Work.recoveryRequests);
    }

    @Test public void unlockWithAutoStartOffPreservesTheActiveSessionAlarm() throws Exception {
        UserRuntimeSession.PROCESS.activate();
        HudRuntimeWatchdog.schedule(context, "test-before-unlock");
        Intent recovery = scheduledRecovery();
        var receive = BootReceiver.class.getDeclaredMethod("completeReceive", Context.class, String.class);
        receive.setAccessible(true);
        receive.invoke(null, context, Intent.ACTION_USER_PRESENT);
        assertEquals(recovery.getAction(), scheduledRecovery().getAction());
        assertTrue(scheduledRecovery().getBooleanExtra(HudRuntimeWatchdog.EXTRA_RESUME_SESSION, false));
        assertTrue(UserRuntimeSession.allowsRuntime(context));
        assertEquals(0, Work.recoveryRequests);
    }

    private Intent scheduledRecovery() {
        var alarm = shadowOf(alarms).peekNextScheduledAlarm();
        assertNotNull(alarm);
        return shadowOf(alarm.operation).getSavedIntent();
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
