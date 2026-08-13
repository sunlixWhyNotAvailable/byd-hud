package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class GMapsTimingDiagnosticsTest {
    @Test
    public void fastNonFirstFramesStayQuietButFirstAndDetailedFramesLog() {
        GMapsTimingDiagnostics.Frame fast = frame(1_000L, 1_001L, 1_002L, false);
        GMapsTimingDiagnostics.Frame first = frame(1_000L, 1_001L, 1_002L, true);

        assertFalse(fast.shouldLog(false, 1_003L, 1_004L, 1_005L));
        assertTrue(fast.shouldLog(true, 1_003L, 1_004L, 1_005L));
        assertTrue(first.shouldLog(false, 1_003L, 1_004L, 1_005L));
    }

    @Test
    public void slowPathIsDetectedAcrossDispatchBoundary() {
        GMapsTimingDiagnostics.Frame frame = frame(1_000L, 1_001L, 1_002L, false);

        assertTrue(frame.shouldLog(false, 1_003L, 1_253L, -1L));
    }

    @Test
    public void slowParseIsLoggedAtListenerHandoff() {
        GMapsTimingDiagnostics.Frame frame = GMapsTimingDiagnostics.frame(
                3, "channel", 3, 1L, 1L, 1L,
                1_000L, 1_000L, 1_001L)
                .withProtocolValidated(1_002L, true)
                .withParse(1_003L, 1_253L)
                .withListenerHandoff(1_254L, false);

        assertTrue(frame.shouldLogAtHandoff(false));
    }

    @Test
    public void dispatchLineCarriesProtocolCorrelationFields() {
        GMapsTimingDiagnostics.Frame frame = GMapsTimingDiagnostics.frame(
                3, "gmaps-channel", 3, 8L, 7L, 41L,
                1_000L, 990L, 1_001L)
                .withProtocolValidated(1_002L, true)
                .withParse(1_003L, 1_004L)
                .withListenerHandoff(1_005L, true);

        String line = frame.dispatchLine(1_010L, 1_012L, 1_014L, true, true);

        assertTrue(line.contains("protocol=3"));
        assertTrue(line.contains("channelId=gmaps-channel"));
        assertTrue(line.contains("message=3"));
        assertTrue(line.contains("session=8"));
        assertTrue(line.contains("messageSession=7"));
        assertTrue(line.contains("sequence=41"));
        assertTrue(line.contains("bridgeElapsedMs=1000"));
        assertTrue(line.contains("sourceElapsedMs=990"));
        assertTrue(line.contains("firstFrame=true"));
        assertTrue(line.contains("stage=dispatch"));
        assertTrue(line.contains("tbtDispatched=true"));
    }

    @Test
    public void registrationLineIncludesBeforeAndAfter() {
        String line = GMapsTimingDiagnostics.registrationLine(
                "ACTION_START_CHANNEL", "channel", 3L, 100L, 104L, true);

        assertTrue(line.contains("beforeElapsedMs=100"));
        assertTrue(line.contains("afterElapsedMs=104"));
        assertTrue(line.contains("durationMs=4"));
        assertTrue(line.contains("sent=true"));
    }

    @Test
    public void startTimingUsesTheGenerationCreatedByTheStartMessage() {
        assertTrue(GMapsDirectChannel.timingSessionGenerationForTest(2, 7L) == 8L);
        assertTrue(GMapsDirectChannel.timingSessionGenerationForTest(3, 7L) == 7L);
    }

    private static GMapsTimingDiagnostics.Frame frame(
            long handlerEntry, long listenerHandoff, long ignored, boolean first) {
        return GMapsTimingDiagnostics.frame(
                3, "channel", 3, 1L, 1L, 1L,
                1_000L, 1_000L, handlerEntry)
                .withProtocolValidated(handlerEntry + 1L, true)
                .withParse(handlerEntry + 1L, handlerEntry + 2L)
                .withListenerHandoff(listenerHandoff, first);
    }
}
