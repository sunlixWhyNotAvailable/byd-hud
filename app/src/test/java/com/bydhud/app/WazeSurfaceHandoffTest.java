package com.bydhud.app;

import static org.junit.Assert.*;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;

import android.app.ActivityOptions;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class, shadows = {
        NativeSpeedLimitTestSupport.EventsLog.class, WazeSurfaceHandoffTest.Host.class,
        WazeSurfaceHandoffTest.Displays.class, WazeSurfaceHandoffTest.Output.class,
        WazeSurfaceHandoffTest.Channel.class, WazeSurfaceHandoffTest.Maps.class,
        WazeSurfaceHandoffTest.Tbt.class})
public final class WazeSurfaceHandoffTest {
    private static NavHudLiveSender sender;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Handler handler;
    private int launches;
    private int launchDisplay;
    private boolean denyLaunch;

    @Before public void setup() throws Exception {
        NativeSpeedLimitTestSupport.reset();
        Host.task = 61;
        Host.display = 16;
        Host.visible = true;
        Host.finished = 0;
        Displays.moves = 0;
        Context context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public void startActivity(Intent intent, Bundle options) {
                launches++;
                ActivityOptions launchOptions = ReflectionHelpers.callStaticMethod(
                        ActivityOptions.class, "fromBundle", from(Bundle.class, options));
                launchDisplay = launchOptions.getLaunchDisplayId();
                if (denyLaunch) throw new SecurityException("display permission denied");
                Host.task = 61;
                Host.display = launchDisplay;
                set("wazeSurfaceTaskId", 61);
                set("wazeSurfaceInstanceId", 9L);
            }
        };
        NavCapturePrefs.setHudEnabled(context, "com.waze", true);
        sender = ReflectionHelpers.callConstructor(NavHudLiveSender.class, from(Context.class, context));
        HandlerThread stateThread = ReflectionHelpers.getField(sender, "stateThread");
        stateThread.quitSafely();
        stateThread.join(2000L);
        assertFalse(stateThread.isAlive());
        handler = new Handler(Looper.getMainLooper());
        set("handler", handler);
        set("active", true);
        set("activePackage", "com.waze");
        set("wazeDirectNavigating", true);
        set("wazeSurfaceEnabledForRoute", true);
        set("wazeRouteGeneration", 7);
        set("wazeSurfaceTaskId", 61);
        set("wazeSurfaceInstanceId", 9L);
    }

    @After public void cleanup() throws Exception {
        worker.shutdownNow();
        assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
        if (handler != null) handler.removeCallbacksAndMessages(null);
        sender = null;
    }

    @Test public void movedHostWaitsForReadinessWithoutRelaunchOrClosure() throws Exception {
        denyLaunch = true; // The vehicle rejects launching again on the secondary display.
        CompletableFuture<Boolean> result = handoff();
        pumpUntil(() -> result.isDone() || waitingLogged());
        assertFalse("handoff must wait for readiness", result.isDone());
        assertEquals(0, launches);
        assertEquals(0, Host.finished);
        ready();
        pumpUntil(result::isDone);
        assertTrue(result.get());
        assertEquals(0, launches);
        assertEquals(0, Displays.moves);
        assertEquals(0, Host.finished);
    }

    @Test public void missingHostLaunchesOnTabletThenMovesAndLateLaunchDoesNotDuplicateIt() throws Exception {
        Host.task = -1;
        Host.display = -1;
        CompletableFuture<Boolean> result = handoff();
        pumpUntil(result::isDone);
        assertTrue(result.get());
        assertEquals(1, launches);
        assertEquals(0, launchDisplay);
        assertEquals(1, Displays.moves);
        assertEquals(16, Host.display);
        ReflectionHelpers.callInstanceMethod(sender, "requestMissingWazeSurfaceForHandoff",
                from(String.class, "late-queued-request"));
        assertEquals(1, launches);
        assertEquals(0, Host.finished);
    }

    @Test public void deniedForegroundLaunchPreservesExistingHostButMissingHostStillFails() {
        denyLaunch = true;
        Host.visible = false;
        set("wazeSurfaceReadyInstanceId", 9L);
        requestForeground();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(6, TimeUnit.SECONDS);
        assertEquals(1, launches);
        assertEquals(0, Host.finished);
        assertFalse(ReflectionHelpers.getField(sender, "wazeSurfaceDismissedForRoute"));
        assertFalse(ReflectionHelpers.getField(sender, "wazeSurfaceLaunchPending"));
        assertEquals(9L, (long) ReflectionHelpers.getField(sender, "wazeSurfaceReadyInstanceId"));
        Host.task = -1;
        requestForeground();
        assertEquals(2, launches);
        assertTrue(ReflectionHelpers.getField(sender, "wazeSurfaceDismissedForRoute"));
        assertEquals(7, (int) ReflectionHelpers.getField(sender, "wazeSurfaceFailureRouteGeneration"));
    }

    private CompletableFuture<Boolean> handoff() {
        return CompletableFuture.supplyAsync(
                () -> sender.ensureWazeSurfaceOnDisplayBlocking(16, "test-transfer", 4000L), worker);
    }

    private void requestForeground() {
        ReflectionHelpers.callInstanceMethod(sender, "requestWazeSurfaceActivity",
                from(String.class, "test-foreground"), from(boolean.class, true), from(int.class, 16));
    }

    private static void ready() {
        set("wazeSurfaceActive", true);
        set("wazeSurfaceReadyInstanceId", 9L);
        set("wazeSurfaceReadyDisplayId", 16);
        set("wazeSurfaceReadyEpoch", 2L);
    }

    private static void set(String name, Object value) { ReflectionHelpers.setField(sender, name, value); }

    private static boolean waitingLogged() {
        synchronized (NativeSpeedLimitTestSupport.EVENTS) {
            return NativeSpeedLimitTestSupport.EVENTS.stream()
                    .anyMatch(line -> line.contains("surface_wait task=61"));
        }
    }

    private static void pumpUntil(BooleanSupplier done) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!done.getAsBoolean() && System.nanoTime() < end) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(5L);
        }
        assertTrue("handoff did not reach the expected observable state", done.getAsBoolean());
    }

    @Implements(WazeSurfaceActivity.class)
    public static final class Host {
        static volatile int task, display;
        static boolean visible;
        static int finished;
        @Implementation protected static int activeTaskId() { return task; }
        @Implementation protected static int activeDisplayId() { return display; }
        @Implementation protected static long activeInstanceId() { return 9L; }
        @Implementation protected static long activeSurfaceEpoch() { return 2L; }
        @Implementation protected static boolean hasValidSurface() { return task >= 0; }
        @Implementation protected static boolean isVisible() { return visible; }
        @Implementation protected static void finishActive(String reason) { finished++; task = -1; }
    }

    @Implements(NavAppDisplayController.class)
    public static final class Displays {
        static volatile int moves;
        @Implementation protected static NavAppDisplayController get(Context context) {
            return Shadow.newInstanceOf(NavAppDisplayController.class);
        }
        @Implementation protected NavAppDisplayState lastState(String packageName) { return null; }
        @Implementation protected NavAppDisplayState moveTaskIdToDisplayBlocking(
                String pkg, int task, int display, String reason, BooleanSupplier current) {
            assertTrue(current.getAsBoolean());
            moves++;
            Host.display = display;
            ready();
            return new NavAppDisplayState(pkg, task, display, true, "shell move confirmed");
        }
    }

    @Implements(HudOutputCoordinator.class)
    public static final class Output {
        @Implementation protected static HudOutputCoordinator get(Context context) { return null; }
    }

    @Implements(WazeDirectChannel.class)
    public static final class Channel {
        @Implementation protected void __constructor__(Context context, WazeDirectChannel.Listener listener) { }
        @Implementation protected void prepareSurfaceHandoff(String reason) { }
    }

    @Implements(GMapsDirectChannel.class)
    public static final class Maps {
        @Implementation protected void __constructor__(Context context, GMapsDirectChannel.Listener listener) { }
    }

    @Implements(VehicleTbtPublisher.class)
    public static final class Tbt {
        @Implementation protected void __constructor__(Context context, Handler handler) { }
    }
}
