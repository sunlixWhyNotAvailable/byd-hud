package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
    public void successfulInitializationMakesRepeatsActiveOnlyAndClearRestoresFullSetup() {
        HudCheckTrafficLight.Output output = new HudCheckTrafficLight.Output();
        List<Boolean> full = new ArrayList<>();
        for (int sample : new int[] {0, 1, HudCheckTrafficLight.CLEAR, 2}) {
            assertTrue(output.write(sample, (setup, values) -> {
                full.add(setup);
                org.junit.Assert.assertArrayEquals(sample == HudCheckTrafficLight.CLEAR
                        ? HudCheckTrafficLight.clearValues() : HudCheckTrafficLight.valuesForSample(sample), values);
                return true;
            }));
        }
        assertEquals(Arrays.asList(true, false, true, true), full);
    }

    @Test
    public void failedFirstWriteRetriesSetupButFailedRepeatKeepsSuccessfulInitialization() {
        HudCheckTrafficLight.Output output = new HudCheckTrafficLight.Output();
        List<Boolean> full = new ArrayList<>();
        for (boolean success : new boolean[] {false, true, false, true}) {
            assertEquals(success, output.write(0, (setup, values) -> {
                full.add(setup);
                return success;
            }));
        }
        assertEquals(Arrays.asList(true, true, false, false), full);
    }

    @Test
    public void cleanupDoesNotWriteWithoutOwnershipAndRetriesFailureBeforeReleasingIt() {
        HudCheckTrafficLight.Output output = new HudCheckTrafficLight.Output();
        AtomicInteger clears = new AtomicInteger();
        assertTrue(output.clearIfOwned(false, () -> { clears.incrementAndGet(); return true; }));
        assertEquals(0, clears.get());
        assertFalse(output.write(0, (full, values) -> false));
        assertFalse(output.clearIfOwned(false, () -> { clears.incrementAndGet(); return false; }));
        assertTrue(output.clearIfOwned(false, () -> { clears.incrementAndGet(); return true; }));
        assertTrue(output.clearIfOwned(false, () -> { clears.incrementAndGet(); return true; }));
        assertEquals(2, clears.get());
        output.write(1, (full, values) -> { assertTrue(full); return true; });
    }

    @Test
    public void exceptionalWriteStillRequiresCleanupAndSuspendedStopDoesNotClear() {
        HudCheckTrafficLight.Output output = new HudCheckTrafficLight.Output();
        org.junit.Assert.assertThrows(IllegalStateException.class,
                () -> output.write(0, (full, values) -> { throw new IllegalStateException("vendor"); }));
        AtomicInteger clears = new AtomicInteger();
        assertTrue(output.clearIfOwned(true, () -> { clears.incrementAndGet(); return true; }));
        assertEquals(0, clears.get());
        assertTrue(output.clearIfOwned(false, () -> { clears.incrementAndGet(); return true; }));
        assertEquals(1, clears.get());
    }

    @Test
    public void failedExplicitClearInvalidatesSetupAndRetainsCleanupResponsibility() {
        HudCheckTrafficLight.Output output = new HudCheckTrafficLight.Output();
        output.write(0, (full, values) -> true);
        assertFalse(output.write(HudCheckTrafficLight.CLEAR, (full, values) -> false));
        AtomicInteger clears = new AtomicInteger();
        output.clearIfOwned(false, () -> { clears.incrementAndGet(); return false; });
        assertEquals(1, clears.get());
        output.write(1, (full, values) -> { assertTrue(full); return true; });
        output.write(HudCheckTrafficLight.CLEAR, (full, values) -> true);
        output.clearIfOwned(false, () -> { clears.incrementAndGet(); return true; });
        assertEquals(1, clears.get());
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

    private static String source(String name) throws Exception {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path path = root.resolve("app/src/main/java/com/bydhud/app/" + name);
        if (!Files.isRegularFile(path)) path = root.resolve("src/main/java/com/bydhud/app/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
