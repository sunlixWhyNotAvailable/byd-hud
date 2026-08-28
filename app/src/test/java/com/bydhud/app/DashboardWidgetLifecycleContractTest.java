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
        assertTrue(service.contains("windows.removeViewImmediate(view)"));
        assertTrue(service.contains("closing = true"));
        assertTrue(service.contains("!closing && DashboardWidgetController.state.visible"));
        assertTrue(service.contains("SideEffect { updateWindow(widgetState) }"));
        assertTrue(service.contains("windowAnimations = 0"));
        assertTrue(service.contains("setViewTreeLifecycleOwner"));
        assertTrue(service.contains("setViewTreeSavedStateRegistryOwner"));
        assertTrue(service.contains("view.disposeComposition()"));
        assertTrue(service.contains("Display.DEFAULT_DISPLAY"));
        assertTrue(service.contains("return START_STICKY"));
        assertTrue(service.contains("!HudPrefs.isUserShutdownActive(this)"));
        assertTrue(service.contains("DashboardWidgetController.hasOverlayPermission()"));
        assertFalse(service.contains("Settings.canDrawOverlays"));
        assertFalse(service.contains("NavHudLiveSender"));
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
