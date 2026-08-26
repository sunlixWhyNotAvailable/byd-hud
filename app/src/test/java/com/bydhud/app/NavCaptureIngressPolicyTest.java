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
    private static final String OFFICIAL_GMAPS = "com.google.android.apps.maps";

    @Test
    public void selectedDirectPackagesNeverFrameworkIngest() {
        assertEquals(NavCaptureIngressPolicy.Mode.OFF,
                mode(WAZE, WAZE));
        assertEquals(NavCaptureIngressPolicy.Mode.OFF,
                mode(GMAPS, GMAPS));
    }

    @Test
    public void selectedUnsupportedPackageIsLogOnly() {
        assertEquals(NavCaptureIngressPolicy.Mode.OFF,
                mode(WAZE, WAZE));
        assertEquals(NavCaptureIngressPolicy.Mode.LOG_ONLY,
                mode(OFFICIAL_GMAPS, OFFICIAL_GMAPS));
        assertEquals(NavCaptureIngressPolicy.Mode.LOG_ONLY,
                NavCaptureIngressPolicy.resolveModeForTest(
                        "com.example.unsupported", "com.example.unsupported",
                        Collections.emptySet(), false));
    }

    @Test
    public void explicitLogOnlyPackagesRemainDiagnosticOnly() {
        assertEquals(NavCaptureIngressPolicy.Mode.LOG_ONLY,
                NavCaptureIngressPolicy.resolveModeForTest(
                        WAZE, "", Set.of(WAZE), false));
        assertEquals(NavCaptureIngressPolicy.Mode.LOG_ONLY,
                NavCaptureIngressPolicy.resolveModeForTest(
                        OFFICIAL_GMAPS, "", Set.of(OFFICIAL_GMAPS), false));
        assertEquals(NavCaptureIngressPolicy.Mode.OFF,
                NavCaptureIngressPolicy.resolveModeForTest(
                        WAZE, WAZE, Set.of(WAZE), false));
    }

    @Test
    public void policyNormalizesPackagesAndShutdownAlwaysDisarms() {
        assertEquals(NavCaptureIngressPolicy.Mode.LOG_ONLY,
                NavCaptureIngressPolicy.resolveModeForTest(
                        " COM.EXAMPLE.MAPS ", " com.example.maps ",
                        Collections.emptySet(), false));
        assertEquals(NavCaptureIngressPolicy.Mode.LOG_ONLY,
                NavCaptureIngressPolicy.resolveModeForTest(
                        "COM.WAZE", "", Set.of(" com.Waze "), false));
        assertEquals(NavCaptureIngressPolicy.Mode.OFF,
                NavCaptureIngressPolicy.resolveModeForTest(
                        OFFICIAL_GMAPS, OFFICIAL_GMAPS, Collections.emptySet(), true));
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
        assertFalse(accessibility.contains("NavHudLiveSender"));
        assertFalse(accessibility.contains("NavRouteStateStore"));
        assertFalse(accessibility.contains("WazeRouteTracker"));
        assertFalse(accessibility.contains("WazeRouteNode"));
        assertFalse(accessibility.contains("accessibility_waze_nodes"));
        assertFalse(accessibility.contains("DISCOVERY"));
        assertFalse(accessibility.contains("FALLBACK"));

        String postedCallback = between(notification,
                "public void onNotificationPosted", "private void postObservedPackage");
        assertFalse(postedCallback.contains("NavCapturePrefs."));
        assertFalse(postedCallback.contains("HudPrefs."));
        assertTrue(postedCallback.contains("NavCaptureIngressPolicy.mode"));
        assertFalse(notification.contains("NavHudLiveSender"));
        assertFalse(notification.contains("NavRouteStateStore"));
        assertFalse(notification.contains("WazeRouteTracker"));
        assertFalse(notification.contains("DISCOVERY"));
        assertFalse(notification.contains("FALLBACK"));
        assertTrue(notification.contains("pendingPosted.remove(postedToken(sbn))"));
        assertTrue(notification.contains("pendingPosted.clear()"));
        assertTrue(notification.contains("postedDrainScheduled = false"));
        assertTrue(notification.contains("Process.THREAD_PRIORITY_BACKGROUND"));
        assertTrue(accessibility.contains("Process.THREAD_PRIORITY_BACKGROUND"));
        assertTrue(accessibility.contains("postDelayed(this::drainLatestCapture"));
        assertTrue(receiver.contains("ScheduledExecutorService WATCHDOG"));
        assertFalse(receiver.contains("Looper.getMainLooper()"));
    }

    private static NavCaptureIngressPolicy.Mode mode(String packageName, String hudPackage) {
        return NavCaptureIngressPolicy.resolveModeForTest(
                packageName, hudPackage, Collections.emptySet(), false);
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
