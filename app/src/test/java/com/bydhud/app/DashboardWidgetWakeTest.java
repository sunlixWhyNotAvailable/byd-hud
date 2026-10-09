package com.bydhud.app;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
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

/** Real wake callbacks and overlay lifecycle, with transport/status I/O isolated. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class,
        shadows = {DashboardWidgetWakeTest.Status.class, DashboardWidgetWakeTest.Events.class,
                DashboardWidgetWakeTest.Runtime.class, DashboardWidgetWakeTest.Updates.class})
@LooperMode(LooperMode.Mode.PAUSED)
public final class DashboardWidgetWakeTest {
    private Application app;
    private DashboardWidgetOverlayService overlay;

    @Before public void setup() throws Exception {
        app = RuntimeEnvironment.getApplication();
        Events.lines.clear();
        Status.permission = DashboardWidgetLifecyclePolicy.Permission.GRANTED;
        Status.refreshes = 0;
        Runtime.checks = 0;
        HudPrefs.setBootEnabled(app, true);
        HudPrefs.setUserShutdownActive(app, false);
        UserRuntimeSession.PROCESS.shutdown();
        for (String field : new String[]{"service", "wakeReceiver", "displayListener", "pendingWake"}) set(field, null);
        set("lifecycle", DashboardWidgetLifecyclePolicy.initialState());
        configure("Circle", false);
        shadowOf(app.getSystemService(PowerManager.class)).setIsInteractive(true);
        shadowOf(app.getSystemService(DisplayManager.class).getDisplay(0)).setState(Display.STATE_ON);
    }

    @After public void cleanup() throws Exception {
        if (overlay != null) overlay.onDestroy();
        BroadcastReceiver receiver = (BroadcastReceiver) get("wakeReceiver");
        if (receiver != null) app.unregisterReceiver(receiver);
        DisplayManager.DisplayListener listener = (DisplayManager.DisplayListener) get("displayListener");
        if (listener != null) app.getSystemService(DisplayManager.class).unregisterDisplayListener(listener);
    }

    @Test public void duplicateWakeStartsOnlyOneMissingOverlay() {
        wake("display-on");
        wake("screen-on");
        assertEquals(DashboardWidgetOverlayService.class.getName(), nextStart().getComponent().getClassName());
        assertNull(nextStart());
        assertTrue(has("source=display-on"));
        assertTrue(has("source=screen-on"));
    }

    @Test public void existingOverlayRelayoutsWithoutReplacementAndLogsOneDraw() throws Exception {
        overlay = Robolectric.buildService(DashboardWidgetOverlayService.class).create().get();
        overlay.onStartCommand(null, 0, 1);
        idle();
        View anchor = anchor();
        Object parent = anchor.getParent();
        Events.lines.clear();
        wake("display-on");
        assertSame(anchor, anchor());
        assertSame(parent, anchor.getParent());
        assertNull(nextStart());
        assertTrue(has("window_relayout part=anchor"));
        assertTrue(has("wake_redraw_requested source=display-on"));
        anchor.getViewTreeObserver().dispatchOnDraw();
        anchor.getViewTreeObserver().dispatchOnDraw();
        idle();
        assertEquals(1, Events.lines.stream().filter(s -> s.contains("first_draw source=display-on")).count());
        assertTrue(has("physicalVisibility=unverified"));
    }

    @Test public void hiddenOffShutdownAndInactiveSessionDoNotRestore() throws Exception {
        configure("Circle", true);
        wake("screen-on");
        assertTrue(has("skip=user-hidden"));
        assertTrue(DashboardWidgetController.snapshot(app).getHidden());
        configure("Off", false);
        wake("screen-on");
        assertTrue(has("skip=disabled"));
        configure("Circle", false);
        HudPrefs.setUserShutdownActive(app, true);
        wake("screen-on");
        assertTrue(has("shutdown=true"));
        HudPrefs.setUserShutdownActive(app, false);
        HudPrefs.setBootEnabled(app, false);
        wake("screen-on");
        assertTrue(has("skip=runtime-disabled"));
        assertNull(nextStart());
        assertEquals(0, Status.refreshes);
    }

    @Test public void missingWindowIsReattachedWithoutReplacingItsService() throws Exception {
        overlay = Robolectric.buildService(DashboardWidgetOverlayService.class).create().get();
        overlay.onStartCommand(null, 0, 1);
        idle();
        View anchor = anchor();
        Field windows = DashboardWidgetOverlayService.class.getDeclaredField("windows");
        windows.setAccessible(true);
        ((WindowManager) windows.get(overlay)).removeViewImmediate(anchor);
        assertNull(anchor.getParent());
        Events.lines.clear();

        wake("screen-on");

        assertSame(overlay, get("service"));
        assertNotNull(anchor.getParent());
        assertNull(nextStart());
        assertTrue(has("window_added part=anchor missingParent=true"));
        assertFalse(has("window_failed"));
    }

    @Test public void bootAndQuickbootReconcileEvenWhenSupervisorKeepsExistingRuntime() throws Exception {
        var receive = BootReceiver.class.getDeclaredMethod("completeReceive", Context.class, String.class);
        receive.setAccessible(true);
        receive.invoke(null, app, Intent.ACTION_BOOT_COMPLETED);
        idle();
        receive.invoke(null, app, "android.intent.action.QUICKBOOT_POWERON");
        idle();

        assertEquals(2, Runtime.checks);
        assertTrue(has("wake source=receiver:android.intent.action.BOOT_COMPLETED"));
        assertTrue(has("wake source=receiver:android.intent.action.QUICKBOOT_POWERON"));
        assertNotNull(nextStart());
        assertNull(nextStart());
    }

    @Test public void activeManualSessionRecoversWithAutoStartOff() {
        HudPrefs.setBootEnabled(app, false);
        UserRuntimeSession.PROCESS.activate();
        wake("screen-on");
        assertNotNull(nextStart());
    }

    @Test public void unknownPermissionWaitsAndRefreshResumesPendingWake() {
        Status.permission = DashboardWidgetLifecyclePolicy.Permission.UNKNOWN;
        wake("display-on");
        assertNull(nextStart());
        assertEquals(1, Status.refreshes);
        Status.permission = DashboardWidgetLifecyclePolicy.Permission.GRANTED;
        DashboardWidgetController.onRuntimePermissionsRefreshed(app);
        idle();
        assertNotNull(nextStart());
    }

    @Test public void deniedPermissionCannotCreateOverlay() {
        Status.permission = DashboardWidgetLifecyclePolicy.Permission.DENIED;
        wake("screen-on");
        assertNull(nextStart());
        assertTrue(has("permission=DENIED"));
    }

    @Test public void displayListenerFiltersSecondaryAndRepeatedOnEvents() throws Exception {
        DashboardWidgetController.onRuntimeStart(app);
        nextStart();
        DisplayManager.DisplayListener listener = (DisplayManager.DisplayListener) get("displayListener");
        Display display = app.getSystemService(DisplayManager.class).getDisplay(0);
        Events.lines.clear();
        listener.onDisplayChanged(7);
        listener.onDisplayChanged(0);
        assertFalse(has("wake source="));
        shadowOf(display).setState(Display.STATE_OFF);
        listener.onDisplayChanged(0);
        shadowOf(display).setState(Display.STATE_ON);
        listener.onDisplayChanged(0);
        idle();
        assertTrue(has("wake source=display-on"));
        assertNull(nextStart()); // The original service-start request remains in flight.
    }

    @Test public void screenBroadcastRechecksReadinessAndDoesNotDuplicateObservers() throws Exception {
        DashboardWidgetController.onRuntimeStart(app);
        Object receiver = get("wakeReceiver");
        Object listener = get("displayListener");
        nextStart();
        shadowOf(app.getSystemService(PowerManager.class)).setIsInteractive(false);
        app.sendBroadcast(new Intent(Intent.ACTION_SCREEN_ON));
        idle();
        assertTrue(has("skip=screen-not-ready"));
        shadowOf(app.getSystemService(PowerManager.class)).setIsInteractive(true);
        app.sendBroadcast(new Intent(Intent.ACTION_SCREEN_ON));
        idle();
        assertSame(receiver, get("wakeReceiver"));
        assertSame(listener, get("displayListener"));
        assertTrue(has("wake source=screen-on"));
    }

    private void configure(String shape, boolean hidden) throws Exception {
        app.getSharedPreferences("bydhud_dashboard_widget", 0).edit()
                .putString("shape", shape).putBoolean("hidden", hidden).apply();
        set("loaded", false);
    }
    private void wake(String source) { DashboardWidgetController.onRuntimeWake(app, source); idle(); }
    private void idle() { shadowOf(Looper.getMainLooper()).idle(); }
    private Intent nextStart() { return shadowOf(app).getNextStartedService(); }
    private boolean has(String part) { return Events.lines.stream().anyMatch(s -> s.contains(part)); }
    private View anchor() throws Exception {
        Field field = DashboardWidgetOverlayService.class.getDeclaredField("anchorView");
        field.setAccessible(true);
        return (View) field.get(overlay);
    }
    private Object get(String name) throws Exception {
        Field field = DashboardWidgetController.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
    private void set(String name, Object value) throws Exception {
        Field field = DashboardWidgetController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    @Implements(MainActivity.class)
    public static class Status {
        static DashboardWidgetLifecyclePolicy.Permission permission;
        static int refreshes;
        @Implementation protected static DashboardWidgetLifecyclePolicy.Permission cachedDashboardOverlayPermission() { return permission; }
        @Implementation protected static void requestRuntimeStatusRefresh(Context context, boolean force, String reason) { refreshes++; }
        @Implementation protected static void publishSharedUiStateChange() {}
    }
    @Implements(AppEventLogger.class)
    public static class Events {
        static final List<String> lines = new ArrayList<>();
        @Implementation protected static void event(Context context, String line) { lines.add(line); }
    }
    @Implements(HudRuntimeSupervisor.class)
    public static class Runtime {
        static int checks;
        @Implementation protected static void ensureStarted(Context context, String reason) { checks++; }
    }
    @Implements(AppUpdateManager.class)
    public static class Updates {
        @Implementation protected static void onRuntimeWake(Context context, String action) {}
    }
}
