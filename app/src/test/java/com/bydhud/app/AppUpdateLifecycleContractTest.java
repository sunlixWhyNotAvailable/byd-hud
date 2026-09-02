package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public final class AppUpdateLifecycleContractTest {
    @Test
    public void uiEntryArmsChecksAndOnlyShutdownResetsTheProcessSession() throws Exception {
        String activity = source("MainActivity.java");
        String resume = between(activity, "protected void onResume()", "protected void onPause()");
        assertTrue(resume.contains("if (!exitRequested) {"));
        assertTrue(resume.contains("AppUpdateManager.onSessionEntry(this)"));
        assertFalse(between(activity, "protected void onStop()", "protected void onDestroy()")
                .contains("AppUpdateManager"));
        assertFalse(between(activity, "private void exitAndFinish()", "private void shutdownAndExit(")
                .contains("AppUpdateManager"));
        String shutdown = between(activity, "private void shutdownAndExit(", "private void finishAfterStop()");
        assertTrue(shutdown.indexOf("AppUpdateManager.resetForShutdown()")
                > shutdown.indexOf("HudPrefs.setUserShutdownActive(this, true)"));
        assertTrue(shutdown.indexOf("AppUpdateManager.resetForShutdown()")
                < shutdown.indexOf("stopRecorderAsync("));
    }

    @Test
    public void backgroundEntryRunsOnlyAfterRuntimeAdmissionAndNeverFromHeartbeat() throws Exception {
        String runtime = source("HudRuntimeService.java");
        String start = between(runtime, "public int onStartCommand(", "public void onTaskRemoved(");
        int entry = start.indexOf("AppUpdateManager.onSessionEntry(this)");
        assertTrue(entry > start.indexOf("if (HudPrefs.isUserShutdownActive(this))"));
        assertTrue(entry > start.indexOf("if (!HudPrefs.isBootEnabled(this))"));
        assertTrue(entry > start.indexOf("if (HudRuntimeUpgradeGuard.hasPendingHardReset(this))"));
        assertTrue(entry > start.lastIndexOf("return START_NOT_STICKY;", entry));
        assertFalse(between(runtime, "public void onCreate()", "public int onStartCommand(")
                .contains("AppUpdateManager"));
        assertFalse(runtime.substring(0, runtime.indexOf("public void onCreate()"))
                .contains("AppUpdateManager"));
        assertTrue(runtime.indexOf("AppUpdateManager") == runtime.lastIndexOf("AppUpdateManager"));
    }

    @Test
    public void composeObservesRetainedResultsAndAcknowledgesCloseWithoutOwningRequests() throws Exception {
        String ui = source("BydHudRuntimeCompose.kt");
        assertTrue(ui.contains("AppUpdateManager.snapshot.collectAsState()"));
        assertTrue(ui.contains("when (val result = updateSnapshot.result)"));
        assertTrue(ui.contains("onManualUpdateCheck = { AppUpdateManager.requestManualCheck(activity) }"));
        assertTrue(ui.contains("AppUpdateManager.dismissResult()"));
        assertTrue(ui.contains("!updateSnapshot.dialogRequested || !appInForeground || showSetupDialog"));
        assertTrue(ui.contains("while (!activity.composeTryStartBlockingUiFlow(\"update\"))"));
        assertFalse(ui.contains("AppUpdateManager.checkForUpdate"));
        assertFalse(ui.contains("autoCheckDelayRemainingMs"));
        assertFalse(ui.contains("consumeAutoCheckReady"));
        assertFalse(ui.contains("resetForShutdown"));
        assertTrue(ui.contains("AppUpdateManager.downloadAndInstall(activity, available.info)"));
    }

    private static String source(String file) throws Exception {
        Path root = Paths.get("src/main/java/com/bydhud/app");
        if (!Files.exists(root)) root = Paths.get("app").resolve(root);
        return new String(Files.readAllBytes(root.resolve(file)), StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue("missing section: " + start, from >= 0 && to > from);
        return source.substring(from, to);
    }
}
