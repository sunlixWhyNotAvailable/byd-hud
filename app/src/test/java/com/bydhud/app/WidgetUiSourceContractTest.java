package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public final class WidgetUiSourceContractTest {
    @Test
    public void optionsExposeApprovedWidgetControlsAndPlaceholder() throws Exception {
        String source = source();
        String options = between(source, "private fun OptionsTab(", "private fun SetupReminderOverlay(");
        assertTrue(options.contains("dashboard-window-size"));
        assertTrue(source.contains("dashboardWindowSize = \"Dashboard window size\""));
        assertTrue(source.contains("dashboardWindowSize = \"Розміри вікна на приборці\""));
        assertTrue(options.contains("dashboard-widget"));
        assertTrue(options.contains("Dashboard widget"));
        assertTrue(options.contains("Віджет приборки"));
        assertTrue(options.contains("Apply dashboard window profile"));
        assertTrue(options.contains("Застосовувати профіль вікна приборки"));
        assertTrue(options.contains("DashboardWidgetState.SIZE_RANGE"));
        assertTrue(options.contains("showTicks = false"));
        assertTrue(options.contains("widgetDirectionLabels"));
        assertTrue(options.contains("dashboard-move"));
        assertTrue(options.contains("This section will be configured in the next step"));
        String colorLine = between(source, "private fun WidgetColorLine(", "private fun WidgetColorPicker(");
        assertTrue(colorLine.contains("R.drawable.ic_palette"));
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
}
