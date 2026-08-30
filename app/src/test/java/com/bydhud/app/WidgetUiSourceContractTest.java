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
    public void steeringCaptureCancelsOnlyTheDisposedVisibleDialog() throws Exception {
        String source = source();
        String options = between(source, "private fun OptionsTab(", "private fun WidgetNumberLine(");
        String effect = between(options, "DisposableEffect(showSteeringButtonCapture)", "val routeMetricModes");
        assertTrue(effect.contains("val wasVisible = showSteeringButtonCapture"));
        assertTrue(effect.contains("if (wasVisible)"));
        assertFalse(effect.contains("if (showSteeringButtonCapture)"));
        assertTrue(options.contains("snapshot.steeringTransferRevision > steeringCaptureRevision"));
        assertTrue(options.contains("steeringLearningRevision > steeringCaptureLearningRevision"));
    }

    @Test
    public void anchorOwnsGesturesWhileMenuOnlyDelegatesModePictures() throws Exception {
        String source = source();
        String anchor = between(source, "internal fun DashboardWidgetAnchorContent(", "internal fun DashboardWidgetMenuContent(");
        String menu = between(source, "internal fun DashboardWidgetMenuContent(", "private fun WidgetCloseIcon(");

        assertTrue(anchor.contains("onPositionSettled: () -> Unit"));
        assertTrue(anchor.contains("onInteraction: () -> Unit"));
        assertEquals(1, occurrences(anchor, ".pointerInteropFilter"));
        assertTrue(anchor.contains("viewConfiguration.touchSlop"));
        assertTrue(anchor.contains("gesture.longPressJob?.cancel()"));
        assertTrue(anchor.contains("gesture.dragging"));
        assertTrue(anchor.contains("WidgetCloseIcon("));
        assertTrue(anchor.contains("R.drawable.ic_widget_toggle"));
        assertTrue(anchor.contains("modifier = Modifier.size(graphicSize)"));
        assertFalse(anchor.contains("R.drawable.ic_widget_full"));
        assertFalse(anchor.contains("onMode: (DashboardWidgetMode) -> Unit"));
        assertFalse(anchor.contains("motionEventSpy"));
        assertFalse(anchor.contains("detectDragGestures"));
        assertFalse(anchor.contains("detectTapGestures"));

        assertTrue(menu.contains("layout: DashboardWidgetMenuLayout"));
        assertTrue(menu.contains("busy: Boolean"));
        assertTrue(menu.contains("onMode: (DashboardWidgetMode) -> Unit"));
        assertTrue(menu.contains("clickable("));
        assertTrue(menu.contains("enabled = !busy"));
        assertTrue(menu.contains("latestOnMode(mode)"));
        assertTrue(menu.contains("R.drawable.ic_widget_ipc_off"));
        assertTrue(menu.contains("R.drawable.ic_widget_tbt"));
        assertTrue(menu.contains("R.drawable.ic_widget_mini"));
        assertTrue(menu.contains("R.drawable.ic_widget_full"));
        assertFalse(menu.contains(".pointerInteropFilter"));
        assertFalse(menu.contains("R.drawable.ic_widget_toggle"));
        assertFalse(menu.contains("WidgetCloseIcon("));
        assertFalse(menu.contains("selectedMode"));
        assertFalse(menu.contains("modeDot"));
        assertEquals(2, occurrences(source, "painterResource(R.drawable.ic_widget_toggle)"));
        String sample = between(source, "private fun DashboardWidgetSample(", "private class DashboardWidgetPointerGesture");
        assertTrue(sample.contains("modifier = Modifier.size(state.sizeDp.dp)"));
    }

    @Test
    public void disabledWidgetSliderKeepsVisibleThumbAndTracks() throws Exception {
        String source = source();
        String slider = between(source, "private fun WidgetNumberLine(", "private fun WidgetColorLine(");
        assertTrue(slider.contains("disabledThumbColor = palette.muted.copy(alpha = 0.72f)"));
        assertTrue(slider.contains("disabledActiveTrackColor = palette.borderStrong"));
        assertTrue(slider.contains("disabledInactiveTrackColor = palette.disabled.copy(alpha = 0.72f)"));
        assertTrue(slider.contains("enabled = enabled"));
    }

    @Test
    public void anchorAndMenuShareOneInactivityScheduler() throws Exception {
        String service = source("DashboardWidgetOverlayService.kt");
        assertEquals(1, occurrences(service, "private fun markInteraction()"));
        assertEquals(1, occurrences(service, "main.postDelayed(inactivityCallback, INACTIVITY_TIMEOUT_MS)"));
        assertEquals(2, occurrences(service, "onInteraction = { markInteraction() }"));
        assertTrue(service.contains("main.removeCallbacks(inactivityCallback)"));
        assertTrue(service.contains("state.expanded && state.autoCollapseAfterInactivity"));
    }

    private static String source() throws Exception {
        return source("BydHudRuntimeCompose.kt");
    }

    private static String source(String name) throws Exception {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve("app/src/main/java/com/bydhud/app/").resolve(name);
        if (!Files.isRegularFile(file)) file = root.resolve("src/main/java/com/bydhud/app/").resolve(name);
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
