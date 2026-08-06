package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class ShareShutdownSourceContractTest {
    @Test
    public void shareSummaryDoesNotUpgradeTopologyLock() throws IOException {
        String source = source("LogShareZip.java");
        String method = between(source,
                "static SelectionSummary summarize(",
                "static synchronized Result create(");

        assertFalse(method.contains("lockTopologyRead"));
        assertFalse(method.contains("lockTopologyWrite"));
    }

    @Test
    public void shareUsesBoundedCheckpointAndStableStaging() throws IOException {
        String source = source("LogShareZip.java");
        String create = between(source,
                "static synchronized Result create(",
                "//Removes completed and partial archives");
        String writer = source("WazeCaptureDebugWriter.java");

        assertTrue(source.contains("WRITER_CHECKPOINT_TIMEOUT_MS = 2_000L"));
        assertTrue(writer.contains("boolean awaitCheckpoint(long timeoutMs)"));
        assertTrue(writer.contains("TimeUnit.MILLISECONDS"));
        assertTrue(writer.contains("boolean awaitIdle()"));
        assertTrue(create.contains("awaitCheckpoint(WRITER_CHECKPOINT_TIMEOUT_MS)"));
        assertTrue(create.contains("copySnapshotToStaging"));
        assertFalse(create.contains("lockTopologyRead"));
        assertTrue(create.indexOf("unlockTopologyWrite")
                < create.indexOf("writeZip(part, snapshot)"));
        assertTrue(create.contains("deleteTree(staging)"));
        assertTrue(source.contains("checkCancelled();"));
    }

    @Test
    public void shareUiIsCancellableAndCopyIsCompact() throws IOException {
        String source = sourcePath("app/src/main/java/com/bydhud/app/BydHudRuntimeCompose.kt");
        String begin = between(source,
                "fun beginStorageShare(days: List<String>)",
                "fun runLogcatAction(");
        String shareEffect = between(source,
                "LaunchedEffect(storageShareBusy, storageShareDays, storageShareDestination)",
                "LaunchedEffect(configurationShareBusy, configurationShareDestination)");
        String dropdown = between(source,
                "private fun HudDropdown(",
                "private fun HudIntegerStepper(");

        assertFalse(begin.contains("composeTryStartBlockingUiFlow(\"storage-share\")"));
        assertFalse(shareEffect.contains("NonCancellable"));
        assertTrue(shareEffect.contains("runInterruptible(Dispatchers.IO)"));
        assertTrue(source.contains("StorageShareProgressOverlay("));
        assertTrue(source.contains("waitingForWrites = \"Очікування записів\""));
        assertTrue(source.contains("archiving = \"Archiving\""));
        assertFalse(dropdown.contains("drawBehind"));
        assertTrue(dropdown.contains("if (index == safeIndex) selectedBackground"));
        assertTrue(source.contains("patchWazeAlerts = \"Попередження\""));
        assertTrue(source.contains("patchWazeAlerts = \"Alerts\""));
        assertTrue(source.contains("patchNotChecked = \"перевірити\""));
        assertTrue(source.contains("patchNotChecked = \"check\""));
        assertTrue(source.contains("\"Стабільність\" else \"Stability\""));
    }

    @Test
    public void persistentRuntimeStopDoesNotStartService() throws IOException {
        String source = source("HudRuntimeService.java");
        String method = between(source,
                "static void stopPersistent(",
                "@Override");

        assertTrue(method.contains("stopService("));
        assertFalse(method.contains("startService("));
        assertFalse(method.contains("startForegroundService("));
    }

    @Test
    public void shutdownSuspendsBothNavigationCollectors() throws IOException {
        String source = source("MainActivity.java");
        String method = between(source,
                "private void shutdownAndExit(",
                "private void finishAfterStop(");

        assertTrue(method.contains("NavNotificationListenerService.suspendForUserShutdown"));
        assertTrue(method.contains("NavAccessibilityService.suspendForUserShutdown"));
    }

    @Test
    public void wazeRouteGenerationDoesNotInvalidateBoundCarHost() throws IOException {
        String source = source("WazeDirectChannel.java");
        String terminal = between(source,
                "private void endNavigation(String reason, boolean notifyListener)",
                "private void publishCurrentStep(");
        String resume = between(source,
                "private void resumeOnChannel(",
                "private void prepareRouteStart(");

        assertTrue(terminal.contains("++sessionGeneration"));
        assertFalse(terminal.contains("++generation"));
        assertTrue(resume.contains("sessionGeneration++"));
        assertFalse(resume.contains("generation++"));
    }

    private static String source(String fileName) throws IOException {
        return sourcePath("app/src/main/java/com/bydhud/app/" + fileName);
    }

    private static String sourcePath(String relativePath) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve(relativePath);
        if (!Files.isRegularFile(file)) {
            String withoutApp = relativePath.startsWith("app/")
                    ? relativePath.substring("app/".length())
                    : relativePath;
            file = root.resolve(withoutApp);
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
