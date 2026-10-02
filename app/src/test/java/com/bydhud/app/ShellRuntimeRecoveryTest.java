package com.bydhud.app;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.provider.Settings;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ShellRuntimeRecoveryTest {
    @Test public void onlySameBootActiveSessionIsRecoverableAndShutdownRevokesIt() {
        Context context = RuntimeEnvironment.getApplication();
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 12);
        HudPrefs.setUserShutdownActive(context, false);
        ShellRuntimeSession.captureServiceConnected(context);
        assertTrue(ShellRuntimeSession.hadCaptureServices(context));
        assertTrue(ShellRuntimeSession.arm(context));
        assertTrue(ShellRuntimeSession.mayRestore(context));
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 13);
        assertFalse(ShellRuntimeSession.mayRestore(context));
        assertFalse(ShellRuntimeSession.hadCaptureServices(context));
        assertFalse(ShellRuntimeSession.mayRestore(true, false, -1, -1));
        ShellRuntimeSession.arm(context);
        ShellRuntimeSession.prefs(context).edit().putString("capture_id", "recording-1")
                .putString("capture_day", "20261002").commit();
        HudPrefs.setUserShutdownActive(context, true);
        assertFalse(ShellRuntimeSession.mayRestore(context));
        assertFalse(ShellRuntimeSession.arm(context));
        assertFalse(ShellRuntimeSession.hadCaptureServices(context));
        assertEquals("recording-1", ShellRuntimeSession.prefs(context).getString("capture_id", ""));
        assertTrue(ShellRuntimeSession.prefs(context).getBoolean("capture_stop", false));
    }

    @Test public void lostClientCanReattachWithoutRestartingShellAndOldDeathCannotDetachNewClient() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        InstrumentNavigationProxyService runtime = new InstrumentNavigationProxyService(context, 7,
                "0123456789abcdef0123456789abcdef", Binder.getCallingUid(), "0123456789abcdef", 108);
        Client first = new Client();
        runtime.connect(7, "0123456789abcdef0123456789abcdef", first);
        assertTrue(runtime.isConnected());
        runtime.setRecoveryEnabled(7, true);
        runtime.suspendOutput(7);
        java.lang.reflect.Method death = InstrumentNavigationProxyService.class.getDeclaredMethod(
                "onClientDied", IInstrumentNavigationClient.class);
        death.setAccessible(true);
        death.invoke(runtime, first);
        assertFalse(runtime.isConnected());
        Client replacement = new Client();
        runtime.connect(7, "0123456789abcdef0123456789abcdef", replacement);
        assertTrue(runtime.isConnected());
        java.lang.reflect.Field suspension = InstrumentNavigationProxyService.class.getDeclaredField("outputSuspended");
        suspension.setAccessible(true);
        assertTrue(suspension.getBoolean(runtime));
        runtime.resumeOutput(7);
        assertFalse(suspension.getBoolean(runtime));
        death.invoke(runtime, first);
        assertTrue("stale death must not detach the replacement", runtime.isConnected());
        assertTrue(InstrumentProxyContract.hasCapability(replacement.result, InstrumentProxyContract.CAP_SHELL_RUNTIME));
    }

    @Test public void checkAndUiCoordinatesRoundTripWithoutViews() throws Exception {
        HudCheckState check = new HudCheckState().selectMode(HudCheckState.Mode.EXTENDED)
                .stepExtended(3).withAutomatic(false).toggleRun();
        assertEquals(check, HudCheckState.restore(check.checkpoint()));
        RuntimeUiSession ui = new RuntimeUiSession();
        ui.restore("{\"tab\":\"Options\",\"section\":\"map-display\",\"positions\":{\"options:map-display\":[3,42]}}");
        RuntimeUiSession.Session restored = ui.getOrCreate(() -> "Apps");
        assertEquals("map-display", restored.selectedOptionsSection());
        assertEquals(new RuntimeUiSession.Viewport(3, 42), restored.viewport("options:map-display"));
    }

    @Test public void pendingCaptureStopDoesNotCompleteWhileShellIsDisconnected() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ShellRuntimeSession.prefs(context).edit().putString("capture_id", "pending-stop")
                .putString("capture_day", "20261002").commit();
        java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger();
        LogcatRecorder.stopAsync(context, completed::incrementAndGet);
        java.lang.reflect.Field worker = LogcatRecorder.class.getDeclaredField("WORKER");
        worker.setAccessible(true);
        ((java.util.concurrent.ExecutorService) worker.get(null)).submit(() -> { }).get(5,
                java.util.concurrent.TimeUnit.SECONDS);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(0, completed.get());
        assertEquals("pending-stop", ShellRuntimeSession.prefs(context).getString("capture_id", ""));
        assertTrue(ShellRuntimeSession.prefs(context).getBoolean("capture_stop", false));
    }

    private static final class Client extends IInstrumentNavigationClient.Stub {
        Bundle result;
        @Override public void onProxyConnected(long generation, Bundle result) { this.result = result; }
        @Override public void onProxyPong(long generation, long token) { }
        @Override public void onProxyStopping(long generation, String reason) { }
    }
}
