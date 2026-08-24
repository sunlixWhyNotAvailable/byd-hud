package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class BackendPatchShareSourceContractTest {
    @Test
    public void resumeForcesRuntimeRefreshAndAssetRefreshPublishesOnlyOnChange() throws IOException {
        String activity = source("MainActivity.java");
        String compose = source("BydHudRuntimeCompose.kt");
        String onResume = between(activity, "protected void onResume()", "protected void onPause()");
        String asset = between(activity, "static void requestNavigatorAssetUiStateRefresh(",
                "private static Map<String, String> scanInstalledAppVersions");
        String tabRefresh = between(compose, "fun requestTabStateRefresh(",
                "fun runAction(");

        assertTrue(onResume.contains("requestRuntimeUiStateRefresh(this, true"));
        assertTrue(tabRefresh.contains(
                "RuntimeTab.Apps -> activity.composeRequestRuntimeUiStateRefresh(false, reason)"));
        assertTrue(tabRefresh.contains("RuntimeTab.Logs,"));
        assertFalse(tabRefresh.contains("composeRequestRuntimeUiStateRefresh(true, reason)"));
        assertTrue(asset.contains("if (!next.equals(navigatorAssetSnapshots))"));
        assertTrue(asset.contains("publishSharedUiStateChange();"));
        assertTrue(asset.indexOf("publishSharedUiStateChange();")
                < asset.indexOf("} catch (RuntimeException e)"));
        assertTrue(count(asset, "publishSharedUiStateChange();") == 1);
    }

    @Test
    public void checkAndPatchUsesWorkerBoundaryAndRestoresPriority() throws IOException {
        String activity = source("MainActivity.java");
        String pipeline = source("NavigatorPatchPipeline.java");

        assertTrue(activity.contains("Process.THREAD_PRIORITY_BACKGROUND"));
        assertTrue(activity.contains("restoreCurrentThreadPriority(previousPriority)"));
        assertTrue(pipeline.contains("Process.THREAD_PRIORITY_BACKGROUND"));
        String scan = between(pipeline, "static ScanResult scan(",
                "static PreparedPatch prepare(");
        String prepare = between(pipeline, "static PreparedPatch prepare(",
                "static ScanResult inspectInstalled(");
        assertTrue(scan.contains("return scanViaWorker(context, profile);"));
        assertTrue(prepare.contains("return prepareViaWorker(context, profile);"));
        String workerScan = between(pipeline, "static ScanResult scanViaWorker(",
                "static PreparedPatch prepareViaWorker(");
        String workerPrepare = between(pipeline, "static PreparedPatch prepareViaWorker(",
                "/** Heavy read-only work executed by NavigatorPatchWorkerService. */");
        assertTrue(workerScan.contains("lowerCurrentThreadPriority()"));
        assertTrue(workerScan.contains("restoreCurrentThreadPriority(previousPriority)"));
        assertTrue(workerPrepare.contains("lowerCurrentThreadPriority()"));
        assertTrue(workerPrepare.contains("restoreCurrentThreadPriority(previousPriority)"));
    }

    @Test
    public void shareLaunchCarriesImmutableDaySnapshotAndOnlyCommitsAfterChooser() throws IOException {
        String source = source("MainActivity.java");
        String compose = source("BydHudRuntimeCompose.kt");
        String deliver = between(source, "private void deliverPendingShare()",
                "private static void notifyPendingShare()");
        String snapshot = between(source, "public static final class ComposeSnapshot",
                "public static final class ComposeStorageDay");

        assertTrue(source.contains("AtomicReference<PendingShare> PENDING_SHARE"));
        assertTrue(source.contains("SHARE_LAUNCH_SEQUENCE.incrementAndGet()"));
        assertTrue(source.contains("public final long shareLaunchId"));
        assertTrue(source.contains("public final List<String> shareLaunchDays"));
        assertTrue(deliver.indexOf("startActivity(Intent.createChooser(send, null))")
                < deliver.indexOf("SHARE_LAUNCH_EVENT.set(new ShareLaunchEvent"));
        assertTrue(deliver.contains("publishSharedUiStateChange();"));
        assertTrue(deliver.contains("share_chooser_failed"));
        assertTrue(source.contains("queuePendingShare(result.file, Collections.emptyList())"));
        assertTrue(snapshot.contains("shareLaunchId"));
        assertTrue(snapshot.contains("shareLaunchDays"));
        assertTrue(compose.contains(
                "LaunchedEffect(snapshot.shareLaunchId, snapshot.shareLaunchDays)"));
        assertTrue(compose.contains(
                "selectedStorageDays.filterNot { it in sharedDays }"));
        assertTrue(compose.contains("activity.composeAcknowledgeShareLaunch(launchId)"));
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

    private static int count(String source, String token) {
        int count = 0;
        for (int from = 0; ; ) {
            from = source.indexOf(token, from);
            if (from < 0) return count;
            count++;
            from += token.length();
        }
    }
}
