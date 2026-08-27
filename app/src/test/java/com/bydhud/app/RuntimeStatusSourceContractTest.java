package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicLong;

/** Focused contract for the local-only runtime status ownership boundary. */
public final class RuntimeStatusSourceContractTest {
    private static final String[] CACHE_FIELDS = {
            "cachedNavRuntimePermissionStatus", "cachedAdbAuthorizationKnown",
            "cachedAdbAuthorizationStatusAvailable"
    };
    private final Object[] savedCache = new Object[CACHE_FIELDS.length];
    private long savedRevision;

    @Before
    public void saveSharedState() throws Exception {
        for (int i = 0; i < CACHE_FIELDS.length; i++) {
            savedCache[i] = field(CACHE_FIELDS[i]).get(null);
        }
        savedRevision = uiRevision();
    }

    @After
    public void restoreSharedState() throws Exception {
        for (int i = 0; i < CACHE_FIELDS.length; i++) {
            field(CACHE_FIELDS[i]).set(null, savedCache[i]);
        }
        ((AtomicLong) field("UI_STATE_REVISION").get(null)).set(savedRevision);
    }

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
        assertTrue(status.contains("updateRuntimeStatusCache(refreshed, adbAuthorized)"));
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

    @Test
    public void grantDecisionsDoNotSilentlyReplaceTheSharedCache() throws IOException {
        String activity = source("MainActivity.java");
        String grant = between(activity, "private void requestAdbPermissionGrant(",
                "private void handleAdbGrantResult(");
        String result = between(activity, "private void handleAdbGrantResult(",
                "private void updateAdbBridgeStatus(");

        assertFalse(activity.contains("invalidateNavRuntimePermissionStatus"));
        assertFalse(grant.contains("= navRuntimePermissionStatus()"));
        assertFalse(result.contains("= navRuntimePermissionStatus()"));
        assertTrue(grant.contains("NavRuntimePermissionStatus.check(this)"));
        assertTrue(result.contains("NavRuntimePermissionStatus.check(this)"));
        assertTrue(result.contains("requestRuntimeStatusRefresh(this, true, \"adb-grant-result\")"));
    }

    @Test
    public void bothRepairPathsRefreshAfterReleasingTheRepairGate() throws IOException {
        String repair = source("NavRuntimePermissionRepair.java");
        assertEquals(2, repair.split("finally \\{\\s+finishRepair\\(appContext\\);", -1).length - 1);
        String finish = between(repair, "private static void finishRepair(",
                "private static void sleepQuietly(");
        assertTrue(finish.contains("MainActivity.requestRuntimeStatusRefresh(appContext, true,"));
        assertTrue(finish.indexOf("LOCK.notifyAll()")
                < finish.indexOf("MainActivity.requestRuntimeStatusRefresh"));
        assertFalse(finish.contains("runRuntimeShellCommand"));
        assertFalse(finish.contains("requestRuntimeUiStateRefresh"));
    }

    @Test
    public void repairedPermissionsPublishOnceWithoutAnotherUiAction() throws Exception {
        MainActivity.updateRuntimeStatusCache(permissionStatus(false, false), true);
        long before = uiRevision();

        //Repair completes after the visible snapshot was created with missing grants.
        MainActivity.updateRuntimeStatusCache(permissionStatus(true, true), true);
        assertEquals(before + 1, uiRevision());
        assertTrue(cachedPermissions().settingsGranted());

        //The Activity's later completion read must not publish the same result again.
        MainActivity.updateRuntimeStatusCache(permissionStatus(true, true), true);
        assertEquals(before + 1, uiRevision());
    }

    @Test
    public void grantedSettingsPublishWhileServicesRebindAndAdbStaysIndependent()
            throws Exception {
        MainActivity.updateRuntimeStatusCache(permissionStatus(false, false), true);
        long before = uiRevision();
        MainActivity.updateRuntimeStatusCache(permissionStatus(true, false), true);

        assertEquals(before + 1, uiRevision());
        assertTrue(cachedPermissions().settingsGranted());
        assertFalse(cachedPermissions().readyForCapture());

        MainActivity.updateRuntimeStatusCache(permissionStatus(true, false), false);
        assertEquals(before + 2, uiRevision());
        assertTrue(cachedPermissions().settingsGranted());
        assertFalse((boolean) field("cachedAdbAuthorizationKnown").get(null));

        MainActivity.updateRuntimeStatusCache(permissionStatus(false, false), false);
        assertEquals(before + 3, uiRevision());
        assertFalse(cachedPermissions().settingsGranted());
    }

    private static NavRuntimePermissionStatus permissionStatus(boolean granted, boolean connected) {
        NavPermissionStatus settings = NavPermissionStatus.forTest(
                true, granted, true, true, "notification", granted ? "accessibility" : "");
        return NavRuntimePermissionStatus.fromSettingsForTest(
                settings, true, connected, false, "notification", "accessibility");
    }

    private static long uiRevision() throws Exception {
        return ((AtomicLong) field("UI_STATE_REVISION").get(null)).get();
    }

    private static NavRuntimePermissionStatus cachedPermissions() throws Exception {
        return (NavRuntimePermissionStatus) field("cachedNavRuntimePermissionStatus").get(null);
    }

    private static Field field(String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
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
