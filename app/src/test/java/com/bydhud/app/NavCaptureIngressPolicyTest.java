package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Set;

public final class NavCaptureIngressPolicyTest {
    private static final String WAZE = "com.waze";
    private static final String GMAPS = "app.revanced.android.apps.maps";

    @Test
    public void directDisarmsFallbackCapture() {
        assertEquals(NavCaptureIngressPolicy.Mode.OFF,
                mode(WAZE, WAZE, true, true, false, false, false));
        assertEquals(NavCaptureIngressPolicy.Mode.OFF,
                mode(GMAPS, GMAPS, true, false, false, true, false));
    }

    @Test
    public void wazeRequiresExplicitLegacyFallback() {
        assertEquals(NavCaptureIngressPolicy.Mode.OFF,
                mode(WAZE, WAZE, false, false, false, false, false));
        assertEquals(NavCaptureIngressPolicy.Mode.DISCOVERY,
                mode(WAZE, WAZE, true, false, false, false, false));
        assertEquals(NavCaptureIngressPolicy.Mode.FALLBACK,
                mode(WAZE, WAZE, true, false, true, false, false));
    }

    @Test
    public void gmapsUsesDiscoveryUntilFallbackIsSelected() {
        assertEquals(NavCaptureIngressPolicy.Mode.DISCOVERY,
                mode(GMAPS, GMAPS, true, false, false, false, false));
        assertEquals(NavCaptureIngressPolicy.Mode.FALLBACK,
                mode(GMAPS, GMAPS, true, false, false, false, true));
        assertEquals(NavCaptureIngressPolicy.Mode.FALLBACK,
                NavCaptureIngressPolicy.resolveModeForTest(
                        "com.google.android.apps.maps",
                        "com.google.android.apps.maps", Collections.emptySet(),
                        false, true, "com.google.android.apps.maps",
                        false, false, false, false, false));
    }

    @Test
    public void logOnlyCaptureRemainsAvailableButNeverOverridesDirect() {
        assertEquals(NavCaptureIngressPolicy.Mode.FALLBACK,
                NavCaptureIngressPolicy.resolveModeForTest(
                        WAZE, "", Set.of(WAZE), false, false, "",
                        false, false, false, false, false));
        assertEquals(NavCaptureIngressPolicy.Mode.OFF,
                NavCaptureIngressPolicy.resolveModeForTest(
                        WAZE, "", Set.of(WAZE), false, false, "",
                        true, true, false, false, false));
    }

    @Test
    public void frameworkCallbacksOnlyGateAndEnqueue() throws IOException {
        String accessibility = source(
                "app/src/main/java/com/bydhud/app/NavAccessibilityService.java");
        String notification = source(
                "app/src/main/java/com/bydhud/app/NavNotificationListenerService.java");
        String receiver = source(
                "app/src/main/java/com/bydhud/app/WazeRouteLifecycleReceiver.java");

        String accessibilityCallback = between(accessibility,
                "public void onAccessibilityEvent", "public void onDestroy");
        assertFalse(accessibilityCallback.contains("NavCapturePrefs."));
        assertFalse(accessibilityCallback.contains("HudPrefs."));
        assertTrue(accessibilityCallback.contains("NavCaptureIngressPolicy.mode"));
        assertTrue(accessibilityCallback.contains("postCaptureActiveWindow"));

        String postedCallback = between(notification,
                "public void onNotificationPosted", "private void postObservedPackage");
        assertFalse(postedCallback.contains("NavCapturePrefs."));
        assertFalse(postedCallback.contains("HudPrefs."));
        assertTrue(postedCallback.contains("NavCaptureIngressPolicy.mode"));
        assertTrue(notification.contains("pendingPosted.remove(postedToken(sbn))"));
        assertTrue(notification.contains("pendingPosted.clear()"));
        assertTrue(notification.contains("postedDrainScheduled = false"));
        assertTrue(notification.contains("Process.THREAD_PRIORITY_BACKGROUND"));
        assertTrue(accessibility.contains("Process.THREAD_PRIORITY_BACKGROUND"));
        assertTrue(accessibility.contains("postDelayed(this::drainLatestCapture"));
        assertTrue(receiver.contains("ScheduledExecutorService WATCHDOG"));
        assertFalse(receiver.contains("Looper.getMainLooper()"));
    }

    private static NavCaptureIngressPolicy.Mode mode(String packageName,
            String hudPackage, boolean wazeLegacy, boolean wazeDirect,
            boolean wazeFallback, boolean gmapsDirect, boolean gmapsFallback) {
        return NavCaptureIngressPolicy.resolveModeForTest(
                packageName, hudPackage, Collections.emptySet(), false,
                true, hudPackage, wazeLegacy, wazeDirect, wazeFallback,
                gmapsDirect, gmapsFallback);
    }

    private static String between(String source, String start, String end) {
        int first = source.indexOf(start);
        int last = source.indexOf(end, first + start.length());
        return first >= 0 && last > first ? source.substring(first, last) : "";
    }

    private static String source(String relativePath) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve(relativePath);
        if (!Files.isRegularFile(file) && relativePath.startsWith("app/")) {
            file = root.resolve(relativePath.substring("app/".length()));
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .replace("\r\n", "\n").replace('\r', '\n');
    }
}
