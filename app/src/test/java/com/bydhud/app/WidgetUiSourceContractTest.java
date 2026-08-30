package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public final class WidgetUiSourceContractTest {
    @Test
    public void optionsExposeApprovedWidgetAndSteeringTransferControls() throws Exception {
        String source = source();
        String options = between(source, "private fun OptionsTab(", "private fun SetupReminderOverlay(");
        assertTrue(options.contains("dashboard-window-profile"));
        assertTrue(source.contains("dashboardWindowSize = \"Dashboard window profile\""));
        assertTrue(source.contains("dashboardWindowSize = \"Профіль вікна приборки\""));
        assertTrue(options.contains("dashboard-widget"));
        assertTrue(options.contains("Dashboard widget"));
        assertTrue(options.contains("Віджет приборки"));
        assertTrue(options.contains("Apply dashboard window profile"));
        assertTrue(options.contains("Застосовувати профіль вікна приборки"));
        assertTrue(options.contains("DashboardWidgetState.SIZE_RANGE"));
        assertTrue(options.contains("showTicks = false"));
        assertTrue(options.contains("widget-opening-direction"));
        assertTrue(options.contains("selectOpeningDirection"));
        assertTrue(options.contains("autoCollapseAfterInactivity"));
        assertTrue(options.contains("widget-corner-radius"));
        assertTrue(options.contains("dashboard-move"));
        assertTrue(options.contains("move-steering-button"));
        assertTrue(options.contains("HudTransferAppDropdown("));
        assertTrue(options.contains("move-window-profile"));
        assertTrue(options.contains("composeBeginSteeringButtonLearning"));
        assertFalse(options.contains("This section will be configured in the next step"));
        String colorLine = between(source, "private fun WidgetColorLine(", "private fun WidgetColorPicker(");
        assertTrue(colorLine.contains("R.drawable.ic_palette"));
        assertTrue(colorLine.contains("Modifier.width(106.dp)"));
        assertFalse(colorLine.contains("R.drawable.ic_options_settings"));
    }

    @Test
    public void overlayUsesPicturesAndDelegatesModeWithoutReadback() throws Exception {
        String source = source();
        String overlay = between(source, "internal fun DashboardWidgetOverlayContent(", "private fun SetupReminderOverlay(");
        assertTrue(overlay.contains("busy: Boolean"));
        assertTrue(overlay.contains("onMode: (DashboardWidgetMode) -> Unit"));
        assertTrue(overlay.contains("onPositionSettled: () -> Unit"));
        assertTrue(overlay.contains("Modifier.clickable(enabled = !busy"));
        assertTrue(overlay.contains("latestOnMode(mode)"));
        assertTrue(overlay.contains("R.drawable.ic_widget_ipc_off"));
        assertTrue(overlay.contains("R.drawable.ic_widget_tbt"));
        assertTrue(overlay.contains("R.drawable.ic_widget_mini"));
        assertTrue(overlay.contains("R.drawable.ic_widget_full"));
        assertEquals(1, occurrences(overlay, ".pointerInteropFilter"));
        assertTrue(overlay.contains("viewConfiguration.touchSlop"));
        assertTrue(overlay.contains("gesture.longPressJob?.cancel()"));
        assertTrue(overlay.contains("gesture.dragging"));
        assertFalse(overlay.contains("motionEventSpy"));
        assertFalse(overlay.contains("detectDragGestures"));
        assertFalse(overlay.contains("detectTapGestures"));
        assertFalse(overlay.contains("selectedMode"));
        assertFalse(overlay.contains("modeDot"));
    }

    private static String source() throws Exception {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve("app/src/main/java/com/bydhud/app/BydHudRuntimeCompose.kt");
        if (!Files.isRegularFile(file)) file = root.resolve("src/main/java/com/bydhud/app/BydHudRuntimeCompose.kt");
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to <= from) throw new AssertionError("missing source section");
        return source.substring(from, to);
    }

    private static int occurrences(String source, String marker) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(marker, offset)) >= 0) {
            count++;
            offset += marker.length();
        }
        return count;
    }
}
