package com.bydhud.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class NativeEtaTransportPolicyTest {
    @Test public void nativeModeChoosesExactlyOneWriterFromReceiverState() {
        assertEquals(NativeEtaTransportPolicy.Writer.AMAP,
                NativeEtaTransportPolicy.writerFor(HudPrefs.ETA_OUTPUT_NATIVE,
                        NativeEtaTransportPolicy.ReceiverState.AVAILABLE));
        assertEquals(NativeEtaTransportPolicy.Writer.INSTRUMENT,
                NativeEtaTransportPolicy.writerFor(HudPrefs.ETA_OUTPUT_NATIVE,
                        NativeEtaTransportPolicy.ReceiverState.UNAVAILABLE));
        assertEquals(NativeEtaTransportPolicy.Writer.UNRESOLVED,
                NativeEtaTransportPolicy.writerFor(HudPrefs.ETA_OUTPUT_NATIVE,
                        NativeEtaTransportPolicy.ReceiverState.UNKNOWN));
        assertEquals(NativeEtaTransportPolicy.Writer.NONE,
                NativeEtaTransportPolicy.writerFor(HudPrefs.ETA_OUTPUT_OFF,
                        NativeEtaTransportPolicy.ReceiverState.AVAILABLE));
        assertEquals(NativeEtaTransportPolicy.Writer.NONE,
                NativeEtaTransportPolicy.writerFor(HudPrefs.ETA_OUTPUT_ALTERNATIVE,
                        NativeEtaTransportPolicy.ReceiverState.UNAVAILABLE));
    }

    @Test public void directCacheOnlyRepeatsKnownValuesAfterInterval() {
        assertFalse(NativeEtaTransportPolicy.shouldDispatchDirectValue(
                false, 12, 1_000L, -1, 10_000L));
        assertTrue(NativeEtaTransportPolicy.shouldDispatchDirectValue(
                false, -1, -1L, 12, 1_000L));
        assertTrue(NativeEtaTransportPolicy.shouldDispatchDirectValue(
                true, 11, 5_000L, 12, 5_001L));
        assertFalse(NativeEtaTransportPolicy.shouldDispatchDirectValue(
                true, 12, 5_000L, 12, 8_499L));
        assertTrue(NativeEtaTransportPolicy.shouldDispatchDirectValue(
                true, 12, 5_000L, 12, 8_500L));
        assertFalse(NativeEtaTransportPolicy.shouldDispatchDirectValue(
                true, 12, 5_000L, 12, 4_999L));
    }

    @Test public void typedInstrumentValuesAcceptIndependentMissingMarkers() {
        assertTrue(NativeEtaTransportPolicy.validInstrumentValues(new int[] {
                -1, -1, 1, 1, -1, 1, 12, 30, 0, 2
        }));
        assertTrue(NativeEtaTransportPolicy.validInstrumentValues(new int[] {
                1, 0, -1, 0, Integer.MAX_VALUE, -1, -1, -1, -1, -1
        }));
        assertFalse(NativeEtaTransportPolicy.validInstrumentValues(new int[] {
                11, 24, -1, 0, 0, -1, -1, -1, -1, -1
        }));
        assertFalse(NativeEtaTransportPolicy.validInstrumentValues(new int[] {
                1, 0, 0, 0, 0, -1, -1, -1, -1, 3
        }));
        assertFalse(NativeEtaTransportPolicy.validInstrumentValues(new int[] {
                1, 0, -1, 0, 0, -1, -1, -1, -1
        }));
    }

    @Test public void instrumentValuesPreserveIndependentOemPresenceRules() {
        NativeEtaFormatter.Formatted formatted = new NativeEtaFormatter.Formatted(
                "08:15", "", "11天", "预计后天 8:15到达",
                11, 2, 0, 0, Integer.MAX_VALUE,
                3, 8, 15, 0, 0);
        int[] values = NativeEtaTransportPolicy.instrumentValues(formatted);
        assertEquals(-1, values[0]);
        assertEquals(2, values[1]);
        assertEquals(-1, values[2]);
        assertEquals(0, values[3]);
        assertEquals(Integer.MAX_VALUE, values[4]);
        assertEquals(3, values[5]);
        assertTrue(NativeEtaTransportPolicy.validInstrumentValues(values));
    }
}
