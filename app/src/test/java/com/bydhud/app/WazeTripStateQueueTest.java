package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class WazeTripStateQueueTest {
    @Test
    public void slowRenderBurstKeepsNewestStepAndPartialMetricsBounded() {
        WazeTripStateQueue<String, String> queue = new WazeTripStateQueue<>();
        queue.merge(route(1L, "A", "eta-A"));
        WazeTripStateQueue.Snapshot<String, String> processing = queue.take();
        assertEquals("A", processing.step);

        queue.merge(metrics(2L, "eta-B"));
        queue.merge(route(3L, "C", null));
        queue.merge(metrics(4L, "eta-D"));
        queue.finishProcessing();

        WazeTripStateQueue.Snapshot<String, String> latest = queue.take();
        assertNotNull(latest);
        assertTrue(latest.stepUpdated);
        assertEquals("C", latest.step);
        assertEquals("eta-D", latest.metrics);
        assertEquals(4L, latest.sequence);
        assertEquals(3, latest.coalescedCount);
        assertNull(queue.take());
    }

    @Test
    public void outOfOrderDecodeCannotReplaceNewerComponentOrInvalidState() {
        WazeTripStateQueue<String, String> queue = new WazeTripStateQueue<>();
        assertTrue(queue.merge(route(5L, "new-step", null)));
        assertTrue(queue.merge(metrics(4L, "older-eta")));
        assertTrue(queue.merge(metrics(6L, "new-eta")));
        assertFalse(queue.merge(route(3L, "old-step", "old-eta")));
        assertFalse(queue.merge(metrics(4L, "older-eta")));

        WazeTripStateQueue.Snapshot<String, String> latest = queue.take();
        assertEquals("new-step", latest.step);
        assertEquals("new-eta", latest.metrics);
        assertFalse(queue.merge(null));
    }

    @Test
    public void terminalFenceDropsOldRouteButKeepsOnlyPostFenceIngress() {
        WazeTripStateQueue<String, String> queue = new WazeTripStateQueue<>();
        queue.merge(route(1L, "old-route", "old-eta"));
        queue.merge(metrics(4L, "new-route-eta"));

        queue.discardThrough(2L);
        WazeTripStateQueue.Snapshot<String, String> afterTerminal = queue.take();
        assertNotNull(afterTerminal);
        assertFalse(afterTerminal.stepUpdated);
        assertEquals("new-route-eta", afterTerminal.metrics);

        queue.discardThrough(5L);
        assertNull(queue.take());
        queue.reset();
        assertNull(queue.take());
    }

    @Test
    public void sameManeuverTripCanRemainNonAuthoritativeAfterRoutingInfo() {
        WazeTripStateQueue<String, String> queue = new WazeTripStateQueue<>();
        queue.merge(new WazeTripStateQueue.Update<>(
                7L, 70L, true, "routing-step", true, 120, true,
                9, true, false, null));
        WazeTripStateQueue.Snapshot<String, String> routing = queue.take();
        assertEquals(WazeTripStateQueue.LANES_FROM_UPDATE, routing.laneDisposition);
        assertEquals("routing-step", routing.laneSource);
        queue.finishProcessing();
        queue.merge(new WazeTripStateQueue.Update<>(
                8L, 80L, true, "trip-step", false, 0, false,
                9, false, false, null));

        WazeTripStateQueue.Snapshot<String, String> latest = queue.take();
        assertEquals("trip-step", latest.step);
        assertFalse(latest.distanceKnown);
        assertFalse(latest.authoritativeLanes);
        assertEquals(WazeTripStateQueue.LANES_PRESERVE, latest.laneDisposition);
    }

    @Test
    public void lateIntermediateManeuverInvalidatesPublishedLanes() {
        WazeTripStateQueue<String, String> queue = new WazeTripStateQueue<>();
        queue.merge(lanedRoute(1L, "published-A", 10, true));
        queue.take();
        queue.finishProcessing();

        // C decodes first and appears to match A. The older B still proves that
        // the route left A before returning, so A's lanes must stay cleared.
        queue.merge(lanedRoute(3L, "pending-C", 10, false));
        queue.merge(lanedRoute(2L, "late-B", 11, false));

        WazeTripStateQueue.Snapshot<String, String> latest = queue.take();
        assertEquals("pending-C", latest.step);
        assertEquals(WazeTripStateQueue.LANES_CLEAR, latest.laneDisposition);
        assertNull(latest.laneSource);
    }

    @Test
    public void lateAuthoritativeLaneComponentSurvivesNewerLaneOmission() {
        WazeTripStateQueue<String, String> queue = new WazeTripStateQueue<>();
        queue.merge(lanedRoute(1L, "published-A", 10, true));
        queue.take();
        queue.finishProcessing();

        queue.merge(lanedRoute(3L, "trip-C", 10, false));
        queue.merge(new WazeTripStateQueue.Update<>(
                2L, 20L, true, "routing-B", true, 70, true,
                10, true, false, null));

        WazeTripStateQueue.Snapshot<String, String> latest = queue.take();
        assertEquals("trip-C", latest.step);
        assertEquals(WazeTripStateQueue.LANES_FROM_UPDATE, latest.laneDisposition);
        assertEquals("routing-B", latest.laneSource);
    }

    @Test
    public void lateLaneComponentForRetainedManeuverReplacesInferredClear() {
        WazeTripStateQueue<String, String> queue = new WazeTripStateQueue<>();
        queue.merge(lanedRoute(1L, "published-A", 10, true));
        queue.take();
        queue.finishProcessing();

        queue.merge(lanedRoute(3L, "trip-C", 11, false));
        assertEquals(WazeTripStateQueue.LANES_CLEAR,
                queue.take().laneDisposition);
        queue.finishProcessing();

        // B updates the lane component of C's maneuver, even after C was taken.
        // Its older road/distance must not replace C's newer values.
        queue.merge(new WazeTripStateQueue.Update<>(
                2L, 20L, true, "late-B", true, 70, true,
                11, true, false, null));
        WazeTripStateQueue.Snapshot<String, String> late = queue.take();
        assertNotNull(late);
        assertFalse(late.stepUpdated);
        assertTrue(late.laneUpdated);
        assertEquals(2L, late.sequence);
        assertEquals(20L, late.ingressElapsedMs);
        assertEquals("trip-C", late.step);
        assertEquals(100, late.distanceMeters);
        assertEquals(WazeTripStateQueue.LANES_FROM_UPDATE, late.laneDisposition);
        assertEquals("late-B", late.laneSource);

        WazeTripStateQueue<String, String> coalesced = new WazeTripStateQueue<>();
        coalesced.merge(lanedRoute(1L, "published-A", 10, true));
        coalesced.take();
        coalesced.finishProcessing();
        coalesced.merge(lanedRoute(3L, "trip-C", 11, false));
        coalesced.merge(new WazeTripStateQueue.Update<>(
                2L, 20L, true, "late-B", true, 70, true,
                11, true, false, null));

        WazeTripStateQueue.Snapshot<String, String> latest = coalesced.take();
        assertEquals("trip-C", latest.step);
        assertEquals(WazeTripStateQueue.LANES_FROM_UPDATE, latest.laneDisposition);
        assertEquals("late-B", latest.laneSource);
    }

    @Test
    public void newerExplicitLaneClearOrUpdateWinsOverLateLaneData() {
        WazeTripStateQueue<String, String> updated = new WazeTripStateQueue<>();
        updated.merge(lanedRoute(3L, "newer-with-lanes", 10, true));
        updated.merge(new WazeTripStateQueue.Update<>(
                2L, 20L, true, "late-lanes", true, 70, true,
                10, true, false, null));
        WazeTripStateQueue.Snapshot<String, String> update = updated.take();
        assertEquals(WazeTripStateQueue.LANES_FROM_UPDATE, update.laneDisposition);
        assertEquals("newer-with-lanes", update.laneSource);

        WazeTripStateQueue<String, String> cleared = new WazeTripStateQueue<>();
        cleared.merge(new WazeTripStateQueue.Update<>(
                3L, 30L, true, "newer-clear", true, 80, true,
                10, false, false, null));
        cleared.merge(new WazeTripStateQueue.Update<>(
                2L, 20L, true, "late-lanes", true, 70, true,
                10, true, false, null));
        WazeTripStateQueue.Snapshot<String, String> clear = cleared.take();
        assertEquals(WazeTripStateQueue.LANES_CLEAR, clear.laneDisposition);
        assertNull(clear.laneSource);
        cleared.finishProcessing();
        assertFalse(cleared.merge(lanedRoute(1L, "even-later-old-lanes", 10, true)));
        assertNull(cleared.take());
    }

    @Test
    public void lateDifferentManeuverClearsAlreadyTakenLanesWithoutRestoringItsStep() {
        WazeTripStateQueue<String, String> queue = new WazeTripStateQueue<>();
        queue.merge(lanedRoute(1L, "published-A", 10, true));
        queue.take();
        queue.finishProcessing();
        queue.merge(lanedRoute(3L, "current-A", 10, false));
        queue.take();
        queue.finishProcessing();
        queue.merge(lanedRoute(2L, "late-B-with-lanes", 11, true));
        WazeTripStateQueue.Snapshot<String, String> latest = queue.take();
        assertEquals("current-A", latest.step);
        assertFalse(latest.stepUpdated);
        assertTrue(latest.laneUpdated);
        assertEquals(WazeTripStateQueue.LANES_CLEAR, latest.laneDisposition);
        assertNull(latest.laneSource);
    }

    @Test
    public void intermediateTransitionDoesNotClearNewerExplicitLanes() {
        WazeTripStateQueue<String, String> queue = new WazeTripStateQueue<>();
        queue.merge(lanedRoute(1L, "old-A", 10, true));
        queue.take();
        queue.finishProcessing();
        queue.merge(lanedRoute(4L, "current-A-lanes", 10, true));
        queue.take();
        queue.finishProcessing();
        assertFalse(queue.merge(lanedRoute(2L, "old-B", 11, true)));
        assertFalse(queue.merge(lanedRoute(3L, "older-A-lanes", 10, true)));
        assertNull(queue.take());
    }

    @Test
    public void terminalDoesNotLetOldLanesJoinRetainedPostTerminalStep() {
        WazeTripStateQueue<String, String> queue = new WazeTripStateQueue<>();
        queue.merge(lanedRoute(1L, "old-route-lanes", 10, true));
        queue.take();
        queue.finishProcessing();
        queue.merge(lanedRoute(4L, "new-route", 10, false));
        queue.discardThrough(3L);
        assertFalse(queue.merge(lanedRoute(2L, "old-late-lanes", 10, true)));
        WazeTripStateQueue.Snapshot<String, String> latest = queue.take();
        assertEquals("new-route", latest.step);
        assertEquals(WazeTripStateQueue.LANES_CLEAR, latest.laneDisposition);
        assertNull(latest.laneSource);
    }

    private static WazeTripStateQueue.Update<String, String> route(
            long sequence, String step, String metrics) {
        return new WazeTripStateQueue.Update<>(sequence, sequence * 10L,
                true, step, true, 100 + (int) sequence, false,
                metrics != null, metrics);
    }

    private static WazeTripStateQueue.Update<String, String> metrics(
            long sequence, String value) {
        return new WazeTripStateQueue.Update<>(sequence, sequence * 10L,
                false, null, false, 0, false, true, value);
    }

    private static WazeTripStateQueue.Update<String, String> lanedRoute(
            long sequence, String step, int maneuver, boolean hasLanes) {
        return new WazeTripStateQueue.Update<>(sequence, sequence * 10L,
                true, step, true, 100, false, maneuver, hasLanes, false, null);
    }
}
