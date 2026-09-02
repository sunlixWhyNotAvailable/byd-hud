package com.bydhud.app;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class DashboardWidgetLifecycleContractTest {
    private static String source(String name) throws Exception {
        Path root = Path.of("src/main/java/com/bydhud/app");
        if (!Files.isDirectory(root)) root = Path.of("app/src/main/java/com/bydhud/app");
        return new String(Files.readAllBytes(root.resolve(name)), StandardCharsets.UTF_8);
    }

    @Test public void overlayHasIndependentLifecycleAndHiddenWindowDoesNotConsumeTouches() throws Exception {
        String service = source("DashboardWidgetOverlayService.kt");
        assertTrue(service.contains("TYPE_APPLICATION_OVERLAY"));
        assertTrue(service.contains("FLAG_NOT_FOCUSABLE"));
        assertTrue(service.contains("FLAG_NOT_TOUCH_MODAL"));
        assertTrue(service.contains("windows.removeViewImmediate(anchorView)"));
        assertTrue(service.contains("windows.removeViewImmediate(menuView)"));
        assertTrue(service.contains("private var anchorAttached = false"));
        assertTrue(service.contains("private var menuAttached = false"));
        assertTrue(service.contains("if (menu == null) detachMenu()"));
        String detachMenu = service.substring(service.indexOf("private fun detachMenu()"),
                service.indexOf("internal fun removeOverlay()"));
        assertFalse(detachMenu.contains("closing = true"));
        assertTrue(service.contains("closing = true"));
        assertTrue(service.contains("!closing && DashboardWidgetController.state.visible"));
        assertTrue(service.contains("SideEffect { updateWindows(widgetState) }"));
        assertTrue(service.contains("windowAnimations = 0"));
        assertTrue(service.contains("setViewTreeLifecycleOwner"));
        assertTrue(service.contains("setViewTreeSavedStateRegistryOwner"));
        assertTrue(service.contains("anchorView.disposeComposition()"));
        assertTrue(service.contains("menuView.disposeComposition()"));
        assertTrue(service.contains("Display.DEFAULT_DISPLAY"));
        assertTrue(service.contains("return START_STICKY"));
        assertTrue(service.contains("!HudPrefs.isUserShutdownActive(this)"));
        assertTrue(service.contains("DashboardWidgetController.hasOverlayPermission()"));
        assertFalse(service.contains("Settings.canDrawOverlays"));
        assertFalse(service.contains("NavHudLiveSender"));
    }

    @Test public void anchorAndMenuShareOneServiceOwnedInactivityTimer() throws Exception {
        String service = source("DashboardWidgetOverlayService.kt");
        assertTrue(service.contains("DashboardWidgetAnchorContent("));
        assertTrue(service.contains("DashboardWidgetMenuContent("));
        assertEquals(2, service.split("onInteraction = \\{ markInteraction\\(\\) \\}", -1).length - 1);
        assertTrue(service.contains("private val inactivityCallback = Runnable"));
        assertTrue(service.contains("main.removeCallbacks(inactivityCallback)"));
        assertTrue(service.contains("main.postDelayed(inactivityCallback, INACTIVITY_TIMEOUT_MS)"));
        assertTrue(service.contains("state.copy(expanded = false)"));
    }

    @Test public void onlyExplicitModeTapDispatchesAndShutdownInvalidatesPendingCallback() throws Exception {
        String controller = source("DashboardWidgetController.kt");
        assertEquals(1, controller.split("\\.requestWidgetMode\\(", -1).length - 1);
        assertTrue(controller.contains("HudPrefs.isBootEnabled(context) && !HudPrefs.isUserShutdownActive(context)"));
        assertTrue(controller.contains("state = state.onAppOpened()"));
        assertTrue(controller.contains("generation != commandGeneration"));
        assertTrue(controller.contains("private fun stop(context: Context)"));
        assertTrue(controller.contains("service?.removeOverlay()"));
        assertFalse(controller.contains("Settings.canDrawOverlays"));
        assertTrue(controller.contains("MainActivity.cachedDashboardOverlayPermission()"));
        String hide = controller.substring(controller.indexOf("fun hide(context: Context)"),
                controller.indexOf("fun requestMode(context: Context"));
        assertTrue(hide.contains("if (!state.visible) return"));
        assertTrue(hide.contains("val app = context.applicationContext"));
        assertTrue(hide.contains("widget_hidden restore_on_app_open=true"));
        assertTrue(hide.contains("\"Віджет приховано — відкрийте BYD HUD, щоб повернути\""));
        assertTrue(hide.contains("\"Widget hidden — open BYD HUD to restore\""));
        assertFalse(hide.contains("повернути його"));
        assertFalse(hide.contains("bring it back"));
        int toast = hide.indexOf("Toast.makeText(app");
        int refresh = hide.indexOf("refresh(app)");
        assertTrue(toast >= 0 && refresh > toast);
        String activity = source("MainActivity.java");
        int shutdown = activity.indexOf("HudPrefs.setUserShutdownActive(this, true)");
        int invalidate = activity.indexOf("cancelWidgetModeForShutdown()", shutdown);
        int overlayStop = activity.indexOf("DashboardWidgetController.shutdown(this)", invalidate);
        int returnRequest = activity.indexOf("returnActiveDashboardToMain(safeReason)", overlayStop);
        assertTrue(shutdown >= 0 && invalidate > shutdown && overlayStop > invalidate && returnRequest > overlayStop);
        assertTrue(activity.contains("DashboardWidgetController.onAppOpened(this)"));
        assertTrue(source("HudRuntimeService.java").contains("DashboardWidgetController.onRuntimeStart(this)"));
    }
}
