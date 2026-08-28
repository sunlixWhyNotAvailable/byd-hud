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
import java.util.Arrays;

public final class InstrumentProxyContractTest {
    @Test
    public void acceptsOnlyKnownNavigationStatuses() {
        assertTrue(InstrumentProxyContract.validStatus(1));
        assertTrue(InstrumentProxyContract.validStatus(2));
        assertTrue(InstrumentProxyContract.validStatus(4));
        assertFalse(InstrumentProxyContract.validStatus(0));
        assertFalse(InstrumentProxyContract.validStatus(3));
        assertFalse(InstrumentProxyContract.validStatus(5));
    }

    @Test
    public void guidanceContractIsBounded() {
        assertTrue(InstrumentProxyContract.validGuidance(0, -1, ""));
        assertTrue(InstrumentProxyContract.validGuidance(0, -1, " "));
        assertTrue(InstrumentProxyContract.validGuidance(49, 2_000_000, "Road"));
        assertFalse(InstrumentProxyContract.validGuidance(-1, 10, "Road"));
        assertFalse(InstrumentProxyContract.validGuidance(50, 10, "Road"));
        assertFalse(InstrumentProxyContract.validGuidance(12, -2, "Road"));
        assertFalse(InstrumentProxyContract.validGuidance(
                12, 10, new String(new char[513]).replace('\0', 'x')));
        StringBuilder oversizedWhitespace = new StringBuilder(513);
        for (int index = 0; index < 513; index++) oversizedWhitespace.append(' ');
        assertFalse(InstrumentProxyContract.validGuidance(
                12, 10, oversizedWhitespace.toString()));
        assertTrue(InstrumentProxyContract.validGuidance(
                12, 10, "Road", new int[]{4, 0, 3}, new int[]{255, 0, 255}));
        assertTrue(InstrumentProxyContract.validGuidance(
                0, -1, "", new int[0], new int[0]));
        assertFalse(InstrumentProxyContract.validGuidance(
                12, 10, "Road", new int[]{4, 0}, new int[]{255}));
        assertFalse(InstrumentProxyContract.validGuidance(
                12, 10, "Road", new int[]{255}, new int[]{255}));
        assertEquals(5, InstrumentNavigationProxyService.laneGuideValueForTest(4, 255));
        assertEquals(5, InstrumentNavigationProxyService.laneGuideValueForTest(4, 4));
        assertEquals(26, InstrumentNavigationProxyService.laneGuideValueForTest(0, 0));
        assertEquals(53, InstrumentNavigationProxyService.laneGuideValueForTest(4, 0));
    }

    @Test
    public void activeRoadWhitespaceSurvivesManagerAndServiceHandoff() throws IOException {
        String manager = source(
                "app/src/main/java/com/bydhud/app/InstrumentProxyManager.java");
        String service = source(
                "app/src/main/java/com/bydhud/app/InstrumentNavigationProxyService.java");

        assertTrue(manager.contains("icon, distanceMeters, preserveText(road),\n"
                + "                    laneDirections, laneRecommendations, callback"));
        assertTrue(service.contains("String roadText = road == null ? \"\" : road;"));
        assertTrue(service.contains("roadText.getBytes(StandardCharsets.UTF_16LE)"));
        assertTrue(service.contains("current == null ? null : current.next, roadText"));
        assertTrue(service.contains("setting_fid:"));
        assertTrue(service.contains("instrument_lane:sendLaneGuidanceInfo"));
        assertTrue(service.contains("intArrayValue"));
        assertFalse(service.contains("optionalMethod(\n"
                + "                        instrumentClass, \"sendLaneGuidanceInfo\""));
        assertTrue(service.contains("directions.length == 0 ? -1"));
    }

    @Test
    public void helperIdentityAndProcStartTimeAreStrict() {
        String token = "0123456789abcdef";
        assertTrue(InstrumentProxyContract.validLaunchToken(token));
        assertFalse(InstrumentProxyContract.validLaunchToken("bad"));
        assertTrue(InstrumentProxyContract.processName(10_123, token)
                .startsWith("bh10123_"));
        assertTrue(InstrumentProxyContract.processName(10_123, token).length() <= 15);
        assertTrue(InstrumentProxyContract.processStartTimeTicks(
                "123 (main helper) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 4242")
                == 4242L);
        assertTrue(InstrumentProxyContract.processStartTimeTicks("malformed") < 0L);
    }

    @Test
    public void capabilityModesPreserveSupportedSubsets() {
        assertTrue(InstrumentProxyManager.capabilityMode(true, true)
                == InstrumentProxyManager.CapabilityMode.FULL);
        assertTrue(InstrumentProxyManager.capabilityMode(true, false)
                == InstrumentProxyManager.CapabilityMode.FID_ONLY);
        assertTrue(InstrumentProxyManager.capabilityMode(false, true)
                == InstrumentProxyManager.CapabilityMode.SDK_ONLY);
        assertTrue(InstrumentProxyManager.capabilityMode(false, false)
                == InstrumentProxyManager.CapabilityMode.NONE);
    }

    @Test
    public void recoveryAllowsOnlyOneImmediateRestart() {
        assertTrue(InstrumentProxyManager.shouldRetryStart(true, true, 1));
        assertFalse(InstrumentProxyManager.shouldRetryStart(true, true, 2));
        assertFalse(InstrumentProxyManager.shouldRetryStart(true, false, 1));
        assertTrue(InstrumentProxyManager.shouldRetryBinder(true, 1));
        assertFalse(InstrumentProxyManager.shouldRetryBinder(true, 2));
        assertFalse(InstrumentProxyManager.shouldRetryBinder(false, 1));
    }

    @Test
    public void requiredInstrumentOperationsCannotBeMaskedByOptionalSuccess() {
        InstrumentProxyContract.Operation success =
                new InstrumentProxyContract.Operation("required", 0, 1L, "");
        InstrumentProxyContract.Operation failure =
                new InstrumentProxyContract.Operation("required", -1, 1L, "denied");
        InstrumentProxyContract.Operation optionalSuccess =
                new InstrumentProxyContract.Operation("optional", 0, 1L, "");

        assertTrue(InstrumentProxyContract.requiredOperationsSucceeded(
                Arrays.asList(success, failure, optionalSuccess), 1));
        assertFalse(InstrumentProxyContract.requiredOperationsSucceeded(
                Arrays.asList(success, failure, optionalSuccess), 2));
        assertFalse(InstrumentProxyContract.requiredOperationsSucceeded(
                Arrays.asList(failure, optionalSuccess), 1));
    }

    @Test
    public void lifecycleBinderCallsAreOneWayAndVendorApiGatesReady() throws IOException {
        String proxyAidl = source(
                "app/src/main/aidl/com/bydhud/app/IInstrumentNavigationProxy.aidl");
        String clientAidl = source(
                "app/src/main/aidl/com/bydhud/app/IInstrumentNavigationClient.aidl");
        String service = source(
                "app/src/main/java/com/bydhud/app/InstrumentNavigationProxyService.java");

        assertTrue(proxyAidl.contains("oneway void connect"));
        assertTrue(proxyAidl.contains("oneway void ping"));
        assertTrue(proxyAidl.contains("oneway void shutdown"));
        assertTrue(clientAidl.contains("onProxyConnected"));
        assertTrue(clientAidl.contains("onProxyPong"));
        assertTrue(service.contains("InstrumentApi current = instrument()"));
        assertTrue(service.contains("Instrument API unavailable"));
        assertTrue(service.contains("probeFidReadiness()"));
        assertTrue(service.contains("reader.invoke"));
        assertTrue(service.contains("writer.set.invoke"));
        assertFalse(service.contains("getInstrumentScreenType"));
        assertTrue(service.contains("BYDAUTO_INSTRUMENT_COMMON"));
        assertTrue(service.contains("BYDAUTO_INSTRUMENT_GET"));
        assertTrue(service.contains("BYDAUTO_INSTRUMENT_SET"));
        assertTrue(service.contains("super.checkPermission"));
        assertTrue(service.contains("all available Instrument planes failed"));
        assertFalse(service.contains("repeated vendor operation failure"));
    }

    @Test
    public void handoffReceiverIsRuntimeScopedInsteadOfManifestExported() throws IOException {
        String manifest = source("app/src/main/AndroidManifest.xml");
        String manager = source(
                "app/src/main/java/com/bydhud/app/InstrumentProxyManager.java");
        String entry = source(
                "app/src/main/java/com/bydhud/app/InstrumentProxyEntryPoint.java");

        assertFalse(manifest.contains("android:name=\".InstrumentProxyReceiver\""));
        assertTrue(manager.contains("registerHandoffReceiver()"));
        assertTrue(manager.contains("unregisterHandoffReceiver()"));
        assertTrue(entry.contains("connected.setPackage(\"com.bydhud.app\")"));
    }

    @Test
    public void staleBinderFailuresCannotTearDownReplacementGeneration() throws IOException {
        String manager = source(
                "app/src/main/java/com/bydhud/app/InstrumentProxyManager.java");

        assertTrue(manager.contains("resetCallWorker(\"replace-stale\")"));
        assertTrue(manager.contains("requestBinder != proxyBinder"));
        assertTrue(manager.contains("requestBinder != connectingBinder"));
        int launch = manager.indexOf("private void launch(");
        int cleanup = manager.indexOf("LocalAdbBridge.stopInstrumentProxy", launch);
        int replacement = manager.indexOf("LocalAdbBridge.launchInstrumentProxy", launch);
        assertTrue(launch >= 0 && cleanup > launch && replacement > cleanup);
    }

    @Test
    public void navigationFramesNeverLaunchTheHelper() throws IOException {
        String manager = source(
                "app/src/main/java/com/bydhud/app/InstrumentProxyManager.java");
        String executeCall = manager.substring(
                manager.indexOf("private void executeCall("),
                manager.indexOf("private void handleCallTimeout("));
        assertFalse(executeCall.contains("ensureStarted("));
        assertTrue(manager.contains("state == State.BLOCKED"));
        assertTrue(manager.contains("InstrumentProxyStore.load(context)"));
        assertTrue(manager.contains("capability circuit open"));
    }

    private static String source(String relativePath) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path file = root.resolve(relativePath);
        if (!Files.isRegularFile(file) && relativePath.startsWith("app/")) {
            file = root.resolve(relativePath.substring("app/".length()));
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }
}
