package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class WazeRouteLifecycleV2ReceiverTest {
    @Test
    public void identityRequiresWazePackageAndInstalledUid() {
        assertTrue(WazeRouteLifecycleV2Receiver.matchesIdentityMetadata(
                "com.waze", 10123, 10123));
        assertFalse(WazeRouteLifecycleV2Receiver.matchesIdentityMetadata(
                "com.waze", 10123, 10456));
        assertFalse(WazeRouteLifecycleV2Receiver.matchesIdentityMetadata(
                "other.package", 10123, 10123));
    }

    @Test
    public void bothReceiversShareBoundedOrderedAsyncDispatch() throws IOException {
        String legacy = source("WazeRouteLifecycleReceiver.java");
        String v2 = source("WazeRouteLifecycleV2Receiver.java");

        assertTrue(legacy.contains("RECEIVER_TIMEOUT_MS = 8_000L"));
        assertTrue(legacy.contains("TRUST_TIMEOUT_MS = 2_000L"));
        assertTrue(legacy.contains("Executors.newSingleThreadExecutor"));
        assertTrue(legacy.contains("enqueue(appContext, goAsync(), \"v1\""));
        assertTrue(v2.contains("enqueue(appContext, goAsync(), \"v2\""));
        assertTrue(v2.contains("packageInfo.getLongVersionCode()"));
        assertTrue(v2.contains("packageInfo.lastUpdateTime"));
    }

    private static String source(String name) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve("app/src/main/java/com/bydhud/app/" + name);
        if (!Files.isRegularFile(file)) {
            file = root.resolve("src/main/java/com/bydhud/app/" + name);
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
