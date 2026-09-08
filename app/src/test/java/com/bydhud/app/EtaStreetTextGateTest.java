package com.bydhud.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class EtaStreetTextGateTest {
    private static final String LONG = "[18:45 | 25 min | 8.4 km] Dniprovske shose";
    private static final String LONGER =
            "[18:45 | 12 h 25 min | 8.4 km] vul. Symona Petliury, 27";
    private static final String ROAD = "Dniprovske shose";
    private static final String CONTEXT = "owner|street|prepend|wait";

    @Test
    public void widthAndTimingMatchAcceptedProbeFixtures() {
        assertEquals(398, EtaStreetTextGate.estimatedWidth(LONG), 0);
        assertEquals(182.5, EtaStreetTextGate.estimatedWidth("Dniprovske shose"), 0);
        assertEquals(253.5, EtaStreetTextGate.estimatedWidth("vul. Symona Petliury, 27"), 0);
        assertEquals(515, EtaStreetTextGate.estimatedWidth(LONGER), 0);
        assertEquals(118, EtaStreetTextGate.estimatedWidth("iIіІwWaA0 !"), 0);

        assertEquals(9_000, EtaStreetTextGate.holdMillis(LONG));
        assertEquals(5_000, EtaStreetTextGate.holdMillis("Dniprovske shose"));
        assertEquals(6_000, EtaStreetTextGate.holdMillis("vul. Symona Petliury, 27"));
        assertEquals(11_000, EtaStreetTextGate.holdMillis(LONGER));
        assertEquals(0, EtaStreetTextGate.holdMillis("...................."));
    }

    @Test
    public void currentLongTextHoldsIncomingShortAndCoalescesLatest() {
        EtaStreetTextGate gate = sent(LONG, ROAD, CONTEXT, 1_000);

        assertEquals(LONG, gate.select("18:46", ROAD, CONTEXT, true, 1_100));
        assertEquals(LONG, gate.select("18:47", ROAD, CONTEXT, true, 5_000));
        assertEquals("18:47", gate.select("18:47", ROAD, CONTEXT, true, 10_000));
        gate.onSent("18:47", 10_000);

        assertEquals(LONGER, gate.select(LONGER, ROAD, CONTEXT, true, 10_001));
    }

    @Test
    public void deadlinePollReleasesLatestWithoutExtendingOnIdenticalRefreshes() {
        EtaStreetTextGate gate = sent(LONG, ROAD, CONTEXT, 0);
        assertEquals("START initial_pass width=398.0 holdMs=9000", gate.drainEvent());

        assertEquals(LONG, gate.select("18:46", ROAD, CONTEXT, true, 100));
        assertEquals("", gate.drainEvent());
        assertEquals(LONG, gate.select("18:47", ROAD, CONTEXT, true, 1_000));
        gate.onSent(LONG, 1_000);
        assertEquals("", gate.drainEvent());
        assertEquals(LONG, gate.select("18:47", ROAD, CONTEXT, true, 8_999));
        assertEquals("", gate.drainEvent());
        assertEquals("18:47", gate.select("18:47", ROAD, CONTEXT, true, 9_000));
        assertEquals("RELEASE release_ready", gate.drainEvent());
        assertEquals("", gate.drainEvent());
    }

    @Test
    public void revertingCandidateDropsPendingUpdate() {
        EtaStreetTextGate gate = sent(LONG, ROAD, CONTEXT, 0);

        assertEquals(LONG, gate.select("18:46", ROAD, CONTEXT, true, 100));
        assertEquals(LONG, gate.select(LONG, ROAD, CONTEXT, true, 200));
        assertEquals(LONG, gate.select(LONG, ROAD, CONTEXT, true, 9_000));
        assertEquals("18:47", gate.select("18:47", ROAD, CONTEXT, true, 9_001));
    }

    @Test
    public void newSuccessfulLongTextGetsItsOwnInitialPass() {
        EtaStreetTextGate gate = sent(LONG, ROAD, CONTEXT, 0);
        assertEquals(LONGER, gate.select(LONGER, ROAD, CONTEXT, true, 9_000));
        gate.onSent(LONGER, 9_000);

        assertEquals(LONGER, gate.select("18:48", ROAD, CONTEXT, true, 19_999));
        assertEquals("18:48", gate.select("18:48", ROAD, CONTEXT, true, 20_000));
    }

    @Test
    public void changedRoadBypassesAndSameVisibleTextStartsNoPhantomCycle() {
        EtaStreetTextGate gate = sent(LONG, "Old road", CONTEXT, 0);
        assertEquals(LONG, gate.select("18:46", "Old road", CONTEXT, true, 100));

        assertEquals(LONG, gate.select(LONG, "New road", CONTEXT, true, 200));
        gate.onSent(LONG, 200);
        assertEquals("18:47", gate.select("18:47", "New road", CONTEXT, true, 201));

        gate = sent(LONG, "Old road", CONTEXT, 0);
        assertEquals("18:46", gate.select("18:46", "New road", CONTEXT, true, 100));
    }

    @Test
    public void failedFirstSendNeverStartsTimer() {
        EtaStreetTextGate gate = new EtaStreetTextGate();

        assertEquals(LONG, gate.select(LONG, ROAD, CONTEXT, true, 0));
        assertEquals("", gate.drainEvent());
        assertEquals("18:46", gate.select("18:46", ROAD, CONTEXT, true, 1));
        assertEquals("", gate.drainEvent());
    }

    @Test
    public void resetEventsNameTransitionAndStablePollsDoNotSpam() {
        EtaStreetTextGate gate = sent(LONG, "Old road", CONTEXT, 0);
        gate.drainEvent();

        assertEquals(LONG, gate.select(LONG, "New road", CONTEXT, true, 1));
        assertEquals("RESET reason=road", gate.drainEvent());
        assertEquals(LONG, gate.select(LONG, "New road", CONTEXT, true, 2));
        assertEquals("", gate.drainEvent());

        assertEquals(LONG, gate.select(LONG, "New road", "other-context", true, 3));
        assertEquals("RESET reason=context", gate.drainEvent());
        assertEquals("warning", gate.select("warning", "New road", "other-context", false, 4));
        assertEquals("RESET reason=eligibility", gate.drainEvent());
        assertEquals("warning", gate.select("warning", "New road", "other-context", false, 5));
        assertEquals("", gate.drainEvent());
        assertEquals(LONG, gate.select(LONG, "New road", "other-context", true, 6));
        assertEquals("RESET reason=eligibility", gate.drainEvent());

        gate.reset();
        assertEquals("RESET reason=explicit", gate.drainEvent());
        assertEquals("", gate.drainEvent());
    }

    @Test
    public void resetContextChangeAndDisableDiscardStaleState() {
        EtaStreetTextGate gate = sent(LONG, ROAD, CONTEXT, 0);
        assertEquals(LONG, gate.select("18:46", ROAD, CONTEXT, true, 100));
        gate.reset();
        assertEquals("18:46", gate.select("18:46", ROAD, CONTEXT, true, 101));

        gate = sent(LONG, ROAD, CONTEXT, 0);
        assertEquals(LONG, gate.select("18:46", ROAD, CONTEXT, true, 100));
        assertEquals("18:46", gate.select("18:46", ROAD, "other-context", true, 101));

        gate = sent(LONG, ROAD, CONTEXT, 0);
        assertEquals(LONG, gate.select("18:46", ROAD, CONTEXT, true, 100));
        assertEquals("warning", gate.select("warning", ROAD, CONTEXT, false, 101));
        gate.onSent("warning", 101);
        assertEquals("18:47", gate.select("18:47", ROAD, CONTEXT, true, 102));
    }

    private static EtaStreetTextGate sent(String text, String road, String context, long nowMs) {
        EtaStreetTextGate gate = new EtaStreetTextGate();
        assertEquals(text, gate.select(text, road, context, true, nowMs));
        gate.onSent(text, nowMs);
        return gate;
    }
}
