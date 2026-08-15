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
        assertFalse(legacy.contains("TRUST_TIMEOUT_MS"));
        assertTrue(legacy.contains("Executors.newSingleThreadExecutor"));
        assertTrue(legacy.contains("enqueue(appContext, goAsync(), \"v1\""));
        assertTrue(v2.contains("enqueue(appContext, goAsync(), \"v2\""));
        assertTrue(v2.contains("getApplicationInfo("));
        assertTrue(v2.contains("matchesIdentityMetadata"));
        assertFalse(v2.contains("getLongVersionCode()"));
        assertFalse(v2.contains("lastUpdateTime"));
        int capabilityIndex = v2.indexOf("noteV2BridgeObserved(appContext)");
        int enqueueIndex = v2.indexOf("enqueue(appContext, goAsync(), \"v2\"");
        assertTrue(capabilityIndex >= 0 && enqueueIndex > capabilityIndex);
        assertTrue(v2.contains("receiverEntryElapsedMs"));
        assertTrue(v2.contains("new WazeRouteTiming"));
        assertTrue(legacy.contains("EVENT_PENDING"));
        assertFalse(legacy.contains("markTrustStart"));
        assertFalse(legacy.contains("awaitTrust"));
        assertTrue(v2.contains("trustedIdentity(appContext, identity)"));
        assertTrue(legacy.contains("timing.markDeliveryStart"));
        assertFalse(v2.contains("NavigatorPatchStore.isInstalledWazeLifecycleV2"));
        assertTrue(v2.contains("getApplicationInfo("));
    }

    @Test
    public void activeRouteRecoveryUsesAuthenticatedRegisteredOnlyHandshake()
            throws IOException {
        String bridge = source("../../waze/bydhud/RouteStateBridgeV2.java");
        String receiver = source("WazeRouteLifecycleV2Receiver.java");
        String sender = source("NavHudLiveSender.java");

        assertTrue(bridge.contains("extends BroadcastReceiver"));
        assertTrue(bridge.contains("Context.RECEIVER_EXPORTED"));
        assertTrue(bridge.contains("matchesStateRequest(identity.getCreatorPackage(), protocol)"));
        assertTrue(bridge.contains("state_snapshot"));
        assertTrue(bridge.contains("if (context == null || !statePublished)"));
        assertFalse(receiver.contains("NavigatorPatchStore.isInstalledWazeLifecycleV2"));
        assertTrue(receiver.contains("Intent.FLAG_RECEIVER_REGISTERED_ONLY"));
        assertTrue(receiver.contains(".setPackage(WazeRouteLifecycleStore.WAZE_PACKAGE)"));
        assertTrue(sender.contains(
                "requestWazeRouteStateSnapshot(\"wait-route:\" + safeReason(reason), false)"));
        assertTrue(sender.contains(
                "requestWazeRouteStateSnapshot(\"app-foreground\", true)"));
        assertTrue(sender.contains(
                "requestWazeRouteStateSnapshot(\"tbt-observer\", false)"));
    }

    private static String source(String name) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve("app/src/main/java/com/bydhud/app/").resolve(name).normalize();
        if (!Files.isRegularFile(file)) {
            file = root.resolve("src/main/java/com/bydhud/app/").resolve(name).normalize();
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
