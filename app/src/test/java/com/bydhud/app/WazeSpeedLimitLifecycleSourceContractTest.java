package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class WazeSpeedLimitLifecycleSourceContractTest {
    @Test
    public void navigationStartPreservesSpeedLimitUntilTerminalLifecycle() throws IOException {
        String source = source();
        String navigationStarted = between(source,
                "private void onWazeDirectNavigationStarted(",
                "void onWazeSurfaceActivityCreated(");
        String navigationEnded = between(source,
                "private void onWazeDirectNavigationEnded(",
                "private void onWazeRouteLifecycleEventOnMain(");
        String lifecycleTerminal = between(source,
                "if (terminal) {",
                "if (!routeActive) {");

        assertFalse(navigationStarted.contains("clearDirectSpeedLimit("));
        assertTrue(lifecycleTerminal.contains("onWazeDirectNavigationEnded("));
        assertTrue(navigationEnded.contains("clearDirectSpeedLimit(WAZE_PACKAGE)"));
    }

    private static String source() throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve("app/src/main/java/com/bydhud/app/NavHudLiveSender.java");
        if (!Files.isRegularFile(file)) {
            file = root.resolve("src/main/java/com/bydhud/app/NavHudLiveSender.java");
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
