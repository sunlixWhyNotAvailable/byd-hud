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
    public void serviceStopClearsOnlyAfterThisServiceOwnedTrafficLightOutput() throws Exception {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path service = root.resolve(
                "app/src/main/java/com/bydhud/app/InstrumentNavigationProxyService.java");
        if (!Files.isRegularFile(service)) {
            service = root.resolve(
                    "src/main/java/com/bydhud/app/InstrumentNavigationProxyService.java");
        }
        String source = new String(Files.readAllBytes(service), StandardCharsets.UTF_8);
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
}
