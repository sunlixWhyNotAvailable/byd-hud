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
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve("app/src/main/java/com/bydhud/app").resolve(fileName);
        if (!Files.isRegularFile(file)) {
            file = root.resolve("src/main/java/com/bydhud/app").resolve(fileName);
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
