package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Focused contract for the local-only runtime status ownership boundary. */
public final class RuntimeStatusSourceContractTest {
    @Test
    public void heartbeatUsesFiveMinuteLocalStatusWithoutStartingAdbWork() throws IOException {
        String service = source("HudRuntimeService.java");
        String heartbeat = between(service, "private final Runnable heartbeatRunnable",
                "private boolean runtimeStartInitialized");

        assertTrue(service.contains("HEARTBEAT_INTERVAL_MS = 5L * 60L * 1000L"));
        assertTrue(heartbeat.contains("requestRuntimeUiRefresh(false, \"runtime-heartbeat\")"));
        assertFalse(heartbeat.contains("InstrumentProxyManager.get"));
        assertFalse(heartbeat.contains("forceScanIfIdle"));
        String periodic = between(service, "private void requestRuntimeUiRefresh(",
                "//keeps this HUD step isolated");
        assertTrue(periodic.contains("MainActivity.requestRuntimeStatusRefresh"));
        assertFalse(periodic.contains("requestRuntimeUiStateRefresh"));
    }

    @Test
    public void localStatusReadsKeyEvidenceAndCurrentSettingsOnly() throws IOException {
        String activity = source("MainActivity.java");
        String status = between(activity, "static void requestRuntimeStatusRefresh(",
                "private static boolean sameRuntimePermissionStatus");

        assertTrue(status.contains("NavRuntimePermissionStatus.check(appContext)"));
        assertTrue(status.contains("LocalAdbBridge.isCurrentKeyKnownAuthorized(appContext)"));
        assertFalse(status.contains("LocalAdbBridge.runRuntimeShellCommand"));
        assertFalse(status.contains("NavAppTaskScanner"));
        assertFalse(status.contains("Connection.open"));
        assertFalse(status.contains("forceScanIfIdle"));
    }

    @Test
    public void activityVisibilitySharesImmediateStatusPathAndKeepsScanSeparate()
            throws IOException {
        String activity = source("MainActivity.java");
        String create = between(activity, "protected void onCreate(Bundle savedInstanceState)",
                "protected void onStart()");
        String resume = between(activity, "protected void onResume()", "protected void onPause()");

        assertTrue(create.contains("requestActivityLocalStatusRefresh(\"activity-create\")"));
        assertTrue(resume.contains("requestActivityLocalStatusRefresh(\"activity-resume\")"));
        assertTrue(resume.contains("requestRuntimeUiStateRefresh(this, true, \"activity-resume\")"));
        assertFalse(create.contains("requestRuntimeStatusRefresh(this, false"));
    }

    @Test
    public void runtimeTransportRetiresOnlyAfterThirtyMinutesAndRetriesIoOnce()
            throws IOException {
        String bridge = source("LocalAdbBridge.java");
        String command = between(bridge, "private static ShellResult runTrustedRuntimeShellCommand(",
                "private static Result rebindAccessibilityRuntimeIfNeeded(");

        assertTrue(bridge.contains("RUNTIME_IDLE_CLOSE_MS = 30L * 60L * 1000L"));
        assertTrue(command.contains("catch (IOException e)"));
        assertTrue(command.contains("closeRuntimeConnectionLocked(appContext, \"io_exception\")"));
        assertTrue(command.contains("closeRuntimeConnectionLocked(appContext, \"retry_io_exception\")"));
        assertTrue(command.contains("connection.shellWithExit(safeCommand, maxOutputBytes)"));
        assertFalse(command.contains("NavRuntimePermissionStatus"));
    }

    @Test
    public void shutdownClosesTransportWithoutClearingPersistedAuthorization() throws IOException {
        String bridge = source("LocalAdbBridge.java");
        String close = between(bridge, "private static void closeRuntimeConnectionLocked(",
                "private static ShellResult unauthorizedRuntimeShellResult(");

        assertTrue(close.contains("runtimeConnection.close()"));
        assertTrue(close.contains("runtimeConnection = null"));
        assertFalse(close.contains("clearAuthorizedFingerprint"));
    }

    @Test
    public void authorizationEvidenceChangesNotifyTheLocalStatusCache() throws IOException {
        String bridge = source("LocalAdbBridge.java");
        String mark = between(bridge, "private static void markAuthorizedFingerprint(",
                "private static void clearAuthorizedFingerprint(");
        String clear = between(bridge, "private static void clearAuthorizedFingerprint(",
                "//tracks only the socket blocked on RSA consent");

        assertTrue(mark.contains(
                "MainActivity.requestRuntimeStatusRefresh(context, true, \"adb-authorization-verified\")"));
        assertTrue(clear.contains(
                "MainActivity.requestRuntimeStatusRefresh(context, true, \"adb-authorization-rejected\")"));
        assertFalse(clear.contains("runRuntimeShellCommand"));
    }

    @Test
    public void snapshotCarriesIndependentAdbAndSettingsEvidence() throws IOException {
        String activity = source("MainActivity.java");
        String snapshot = between(activity, "public static final class ComposeSnapshot",
                "public static final class ComposeStorageDay");

        assertTrue(snapshot.contains("public final boolean adbAuthorized"));
        assertTrue(snapshot.contains("public final boolean settingsPermissionsGranted"));
        assertTrue(activity.contains("permissionStatus.settingsGranted(),\n                adbAuthorized(),"));
    }

    private static String source(String fileName) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve("app/src/main/java/com/bydhud/app/" + fileName);
        if (!Files.isRegularFile(file)) {
            file = root.resolve("src/main/java/com/bydhud/app/" + fileName);
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue("missing start marker " + start, from >= 0);
        assertTrue("missing end marker " + end, to > from);
        return source.substring(from, to);
    }
}
