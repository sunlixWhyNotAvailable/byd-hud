package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Regression contract for the manual Nxx to vehicle TBT mapping.
 *
 * The production implementation is expected to expose the package-private
 * VehicleTbtPublisher.manualMappingForTest(int) helper and ManualMapping
 * fields used below: instrumentId, amapManeuver, roundaboutExit and
 * amapSupported.
 */
public final class ManualTbtMappingTest {
    @Test
    public void blankNativeClearsBothInstrumentAndAmap() {
        VehicleTbtPublisher.ManualMapping mapping =
                VehicleTbtPublisher.manualMappingForTest(99);

        assertEquals(0, mapping.instrumentId);
        assertEquals(-1, mapping.amapManeuver);
        assertEquals(0, mapping.roundaboutExit);
        assertTrue(mapping.amapSupported);
    }

    @Test
    public void verifiedNativeIdsPreserveInstrumentIdAndHaveCanonicalVehicleMeaning() {
        for (int nativeId = 1; nativeId <= 49; nativeId++) {
            VehicleTbtPublisher.ManualMapping mapping =
                    VehicleTbtPublisher.manualMappingForTest(nativeId);
            assertEquals("Instrument ID must be preserved", nativeId, mapping.instrumentId);
            if (nativeId == 4 || nativeId == 6) {
                assertFalse("N" + nativeId + " has no exact AMap equivalent",
                        mapping.amapSupported);
            } else {
                assertTrue("N" + nativeId + " must have a canonical AMap meaning",
                        mapping.amapSupported);
                assertTrue("N" + nativeId + " must map to an icon or roundabout exit",
                        mapping.amapManeuver > 0 || mapping.roundaboutExit > 0);
            }
        }
    }

    @Test
    public void numberedRoundaboutsUseVerifiedAmapDirectionAndExitFields() {
        for (int exit = 1; exit <= 10; exit++) {
            VehicleTbtPublisher.ManualMapping ccw =
                    VehicleTbtPublisher.manualMappingForTest(24 + exit);
            VehicleTbtPublisher.ManualMapping cw =
                    VehicleTbtPublisher.manualMappingForTest(34 + exit);
            assertEquals(11, ccw.amapManeuver);
            assertEquals(exit, ccw.roundaboutExit);
            assertEquals(17, cw.amapManeuver);
            assertEquals(exit, cw.roundaboutExit);
        }
    }

    @Test
    public void unknownNativeIdsKeepInstrumentValueButDoNotInventAmapMeaning() {
        for (int nativeId : new int[]{50, 69, 80, 98}) {
            VehicleTbtPublisher.ManualMapping mapping =
                    VehicleTbtPublisher.manualMappingForTest(nativeId);
            assertEquals(nativeId, mapping.instrumentId);
            assertEquals(0, mapping.amapManeuver);
            assertEquals(0, mapping.roundaboutExit);
            assertFalse(mapping.amapSupported);
        }
    }
}
