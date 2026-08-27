package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public final class HudCheckTrafficLightTest {
    @Test
    public void samplesUseVerifiedColorsDirectionsAndNonNegativeCountdowns() {
        assertTrue(HudCheckTrafficLight.validSampleIndex(-1));
        assertTrue(HudCheckTrafficLight.validSampleIndex(11));
        assertFalse(HudCheckTrafficLight.validSampleIndex(12));
        assertFalse(HudCheckTrafficLight.validSampleIndex(-2));
        for (int index = 0; index <= 11; index++) {
            int[] values = HudCheckTrafficLight.valuesForSample(index);
            assertEquals(7, values.length);
            assertTrue(values[0] >= 3 && values[0] <= 5);
            assertTrue(values[1] >= 1 && values[1] <= 4);
            assertTrue(values[2] >= 1 && values[2] <= 4);
            assertTrue(values[4] >= 0);
        }
        assertEquals(8, HudCheckTrafficLight.valuesForSample(0)[4]);
        assertEquals(3, HudCheckTrafficLight.valuesForSample(4)[4]);
        assertEquals(0, HudCheckTrafficLight.valuesForSample(7)[4]);
        assertEquals(99, HudCheckTrafficLight.valuesForSample(8)[4]);
        assertEquals(HudCheckTrafficLight.DESCRIPTION_PASS,
                HudCheckTrafficLight.valuesForSample(9)[1]);
        assertEquals(HudCheckTrafficLight.DESCRIPTION_WAIT,
                HudCheckTrafficLight.valuesForSample(10)[1]);
        assertEquals(HudCheckTrafficLight.DESCRIPTION_CAUTION,
                HudCheckTrafficLight.valuesForSample(11)[1]);
    }

    @Test
    public void clearOwnsAllIntersectionsAndSeparateDistance() {
        assertEquals(77, HudCheckTrafficLight.DISTANCE_METERS);
        assertEquals(1_139_871_760, HudCheckTrafficLight.DISTANCE_FID);
        assertEquals(3, HudCheckTrafficLight.INTERSECTION_COUNT);
        assertEquals(7, HudCheckTrafficLight.clearValues().length);
        for (int intersection = 0; intersection < 3; intersection++) {
            assertEquals(7, HudCheckTrafficLight.selectors(intersection).length);
        }
    }

    @Test
    public void repeatsWriteOnlyTheActiveTupleAfterSuccessfulInitialization() throws Exception {
        String source = source("InstrumentNavigationProxyService.java");
        String send = source.substring(source.indexOf("public Bundle sendHudCheckTrafficLight("),
                source.indexOf("public void shutdown("));
        assertTrue(source.contains("private boolean trafficLightInitialized;"));
        assertTrue(send.contains("boolean fullState = !trafficLightInitialized\n"
                + "                        || sampleIndex == HudCheckTrafficLight.CLEAR;"));
        assertTrue(send.contains("intersection < (fullState ? HudCheckTrafficLight.INTERSECTION_COUNT : 1)"));
        assertTrue(send.contains("intersection, intersection == 0\n"
                + "                                        ? values : HudCheckTrafficLight.clearValues()"));
        assertTrue(send.contains("if (fullState) {\n"
                + "                    InstrumentApi currentInstrument = instrument();"));
        assertEquals(1, occurrences(send, "setInt(FID_DISTANCE_TO_TRAFFIC_LIGHT,"));
        assertTrue(send.contains("} else if (sampleIndex != HudCheckTrafficLight.CLEAR && allSucceeded) {\n"
                + "                    trafficLightInitialized = true;"));
        // A failed initial write retries setup; a failed active-only write must
        // not reset a completed setup. Ownership alone cannot prove setup succeeded.
        assertEquals(1, occurrences(source, "trafficLightInitialized = true;"));
        assertFalse(send.contains("trafficLightInitialized = allSucceeded"));
        assertFalse(send.contains("sampleIndex == last"));
    }

    @Test
    public void explicitAndDisconnectClearsInvalidateSetupBeforeAnyWrite() throws Exception {
        String source = source("InstrumentNavigationProxyService.java");
        String send = source.substring(source.indexOf("public Bundle sendHudCheckTrafficLight("),
                source.indexOf("public void shutdown("));
        assertTrue(send.contains("trafficLightOutputsOwned = true;\n"
                + "                } else {\n"
                + "                    trafficLightInitialized = false;"));
        assertTrue(send.indexOf("trafficLightInitialized = false;")
                < send.indexOf("setBodyworkTrafficLight("));
        assertTrue(send.contains("if (sampleIndex == HudCheckTrafficLight.CLEAR && allSucceeded) {\n"
                + "                    trafficLightOutputsOwned = false;"));
        String clear = source.substring(source.indexOf("private boolean clearTrafficLightOutputs()"),
                source.indexOf("private static boolean success(Object result)"));
        assertTrue(clear.indexOf("trafficLightInitialized = false;") >= 0);
        assertTrue(clear.indexOf("trafficLightInitialized = false;")
                < clear.indexOf("setBodyworkTrafficLight("));
        assertTrue(clear.contains("intersection < HudCheckTrafficLight.INTERSECTION_COUNT"));
        assertTrue(clear.contains("setInt(FID_DISTANCE_TO_TRAFFIC_LIGHT, 0)"));
        assertTrue(clear.contains("if (cleared) trafficLightOutputsOwned = false;"));
    }

    @Test
    public void heldSamplesKeepOneSecondRefreshAndUseTheExistingReconnectReplay() throws Exception {
        String publisher = source("VehicleTbtPublisher.java");
        String send = publisher.substring(publisher.indexOf("private void publishHudCheckLight("),
                publisher.indexOf("private static boolean successfulCheckResult("));
        assertTrue(send.contains("index == hudCheckLightIndex\n"
                + "                && now - hudCheckLightLastAttemptMs < 1000L"));
        assertTrue(send.contains("instrument.sendHudCheckTrafficLight(index, reason,"));
        assertTrue(publisher.contains("publishHudCheckLight(hudCheckLightIndex, \"hud-check-ready-replay\", true)"));
    }

    @Test
    public void serviceStopClearsOnlyAfterThisServiceOwnedTrafficLightOutput() throws Exception {
        String source = source("InstrumentNavigationProxyService.java");
        assertTrue(source.contains("if (trafficLightOutputsOwned) clearTrafficLightOutputs();"));
        assertTrue(source.contains("if (!connected)"));
        assertTrue(source.contains("if (sampleIndex != HudCheckTrafficLight.CLEAR)"));
        assertTrue(source.contains("if (cleared) trafficLightOutputsOwned = false;"));
        int stopStart = source.indexOf("private void stop(String reason)");
        int stopConnected = source.indexOf("connected = false;", stopStart);
        int stopClear = source.indexOf(
                "if (trafficLightOutputsOwned) clearTrafficLightOutputs();", stopStart);
        assertTrue(stopStart >= 0 && stopConnected >= 0 && stopClear >= 0);
        assertTrue(stopConnected < stopClear);
    }

    private static int occurrences(String source, String value) {
        return (source.length() - source.replace(value, "").length()) / value.length();
    }

    private static String source(String name) throws Exception {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path path = root.resolve("app/src/main/java/com/bydhud/app/" + name);
        if (!Files.isRegularFile(path)) path = root.resolve("src/main/java/com/bydhud/app/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
