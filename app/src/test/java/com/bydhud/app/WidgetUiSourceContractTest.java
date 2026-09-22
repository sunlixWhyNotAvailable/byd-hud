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
    public void steeringCaptureCancelsOnlyTheDisposedVisibleDialog() throws Exception {
        String source = source();
        String options = between(source, "private fun OptionsTab(", "private fun WidgetNumberLine(");
        String effect = between(options, "DisposableEffect(showSteeringButtonCapture)", "val routeMetricModes");
        assertTrue(effect.contains("val wasVisible = showSteeringButtonCapture"));
        assertTrue(effect.contains("if (wasVisible)"));
        assertFalse(effect.contains("if (showSteeringButtonCapture)"));
        assertTrue(options.contains("steeringLearningRevision > steeringCaptureLearningRevision"));
        assertTrue(options.contains("transferDraft = transferDraft?.copy(keyCode = snapshot.steeringCapturedKeyCode)"));
        assertFalse(options.contains("composeSetSteeringTransferKeyCode"));
    }

    @Test
    public void anchorOwnsGesturesWhileMenuOnlyDelegatesModePictures() throws Exception {
        String source = source();
        String anchor = between(source, "internal fun DashboardWidgetAnchorContent(", "internal fun DashboardWidgetMenuContent(");
        String menu = between(source, "internal fun DashboardWidgetMenuContent(", "private fun WidgetCloseIcon(");

        assertTrue(anchor.contains("onPositionSettled: () -> Unit"));
        assertTrue(anchor.contains("onInteraction: () -> Unit"));
        assertTrue(anchor.contains("viewConfiguration.touchSlop"));
        assertTrue(anchor.contains("gesture.longPressJob?.cancel()"));
        assertTrue(anchor.contains("gesture.dragging"));
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
        assertFalse(menu.contains(".pointerInteropFilter"));
        assertFalse(menu.contains("selectedMode"));
        assertFalse(menu.contains("modeDot"));
        String sample = between(source, "private fun DashboardWidgetSample(", "private class DashboardWidgetPointerGesture");
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
