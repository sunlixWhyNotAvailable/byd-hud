package com.bydhud.app;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public final class BootCleanupPolicyTest {
    @Test public void firstObservationSeedsWithoutCleanupThenTrustedBootCleansOnce() {
        FakeStore store = new FakeStore(-1, -1);
        FakeClears clears = new FakeClears();

        assertEquals(BootCleanupPolicy.Outcome.SEEDED, execute(
                BootCleanupPolicy.Trigger.INITIALIZE, 14, 100L, store, clears));
        assertEquals(Arrays.asList("seed:14"), store.events);
        assertEquals(List.of(), clears.events);

        assertEquals(BootCleanupPolicy.Outcome.RECOVERY_ONLY, execute(
                BootCleanupPolicy.Trigger.QUICKBOOT, 14, 110L, store, clears));
        assertEquals(BootCleanupPolicy.Outcome.RESET_COMPLETE, execute(
                BootCleanupPolicy.Trigger.BOOT_COMPLETED, 14, 120L, store, clears));
        assertEquals(Arrays.asList("projection", "waze"), clears.events);
        assertEquals(Arrays.asList("seed:14", "complete:14"), store.events);

        clears.events.clear();
        assertEquals(BootCleanupPolicy.Outcome.RECOVERY_ONLY, execute(
                BootCleanupPolicy.Trigger.BOOT_COMPLETED, 14, 130L, store, clears));
        assertEquals(List.of(), clears.events);
    }

    @Test public void changedCountAdmitsSerializedCleanupAndSameQuickbootRecoversOnly() {
        FakeStore store = new FakeStore(14, 14);
        FakeClears clears = new FakeClears();

        assertEquals(BootCleanupPolicy.Outcome.RESET_COMPLETE, execute(
                BootCleanupPolicy.Trigger.INITIALIZE, 15, 10L, store, clears));
        assertEquals(Arrays.asList("projection", "waze"), clears.events);
        assertEquals(15, store.state.observedBootCount);
        assertEquals(15, store.state.completedBootCount);

        clears.events.clear();
        assertEquals(BootCleanupPolicy.Outcome.RECOVERY_ONLY, execute(
                BootCleanupPolicy.Trigger.QUICKBOOT, 15, 20L, store, clears));
        assertEquals(List.of(), clears.events);
    }

    @Test public void trustedBootWithNoBaselineCleansInsteadOfOnlySeeding() {
        FakeStore store = new FakeStore(-1, -1);
        FakeClears clears = new FakeClears();

        assertEquals(BootCleanupPolicy.Outcome.RESET_COMPLETE, execute(
                BootCleanupPolicy.Trigger.BOOT_COMPLETED, 3, 10L, store, clears));
        assertEquals(Arrays.asList("projection", "waze"), clears.events);
        assertEquals(Arrays.asList("complete:3"), store.events);
        assertEquals(3, store.state.observedBootCount);
        assertEquals(3, store.state.completedBootCount);
    }

    @Test public void unknownQuickbootNeverCleansAndEveryTrustedFallbackCleans() {
        FakeStore store = new FakeStore(15, 15);
        FakeClears clears = new FakeClears();

        assertEquals(BootCleanupPolicy.Outcome.RECOVERY_ONLY, execute(
                BootCleanupPolicy.Trigger.QUICKBOOT, -1, 900L, store, clears));
        assertEquals(BootCleanupPolicy.Outcome.RESET_COMPLETE, execute(
                BootCleanupPolicy.Trigger.BOOT_COMPLETED, -1, 910L, store, clears));
        assertEquals(-1, store.state.observedBootCount);
        assertEquals(-1, store.state.completedBootCount);
        clears.events.clear();
        assertEquals(BootCleanupPolicy.Outcome.RESET_COMPLETE, execute(
                BootCleanupPolicy.Trigger.BOOT_COMPLETED, -1, 920L, store, clears));
        assertEquals(Arrays.asList("projection", "waze"), clears.events);

        assertEquals(BootCleanupPolicy.Outcome.SEEDED, execute(
                BootCleanupPolicy.Trigger.INITIALIZE, 16, 930L, store, clears));
        assertEquals(16, store.state.observedBootCount);
        assertEquals(-1, store.state.completedBootCount);
    }

    @Test public void failedOrInterruptedStepLeavesMarkersAndRetryRepeatsFromProjection() {
        FakeStore store = new FakeStore(14, 14);
        FakeClears clears = new FakeClears();
        clears.wazeResult = false;

        assertEquals(BootCleanupPolicy.Outcome.WAZE_CLEAR_FAILED, execute(
                BootCleanupPolicy.Trigger.QUICKBOOT, 15, 1L, store, clears));
        assertEquals(Arrays.asList("projection", "waze"), clears.events);
        assertEquals(14, store.state.observedBootCount);
        assertEquals(14, store.state.completedBootCount);

        clears.wazeResult = true;
        assertEquals(BootCleanupPolicy.Outcome.RESET_COMPLETE, execute(
                BootCleanupPolicy.Trigger.QUICKBOOT, 15, 2L, store, clears));
        assertEquals(Arrays.asList("projection", "waze", "projection", "waze"), clears.events);

        FakeStore markerFailure = new FakeStore(15, 15);
        markerFailure.completeResult = false;
        FakeClears nextBoot = new FakeClears();
        assertEquals(BootCleanupPolicy.Outcome.MARKER_WRITE_FAILED, execute(
                BootCleanupPolicy.Trigger.INITIALIZE, 16, 3L, markerFailure, nextBoot));
        assertEquals(15, markerFailure.state.observedBootCount);
        assertEquals(15, markerFailure.state.completedBootCount);
    }

    @Test public void projectionFailureNeverTouchesWazeOrMarkers() {
        FakeStore store = new FakeStore(8, 8);
        FakeClears clears = new FakeClears();
        clears.projectionResult = false;

        assertEquals(BootCleanupPolicy.Outcome.PROJECTION_CLEAR_FAILED, execute(
                BootCleanupPolicy.Trigger.INITIALIZE, 9, 1L, store, clears));
        assertEquals(List.of("projection"), clears.events);
        assertEquals(List.of(), store.events);
    }

    @Test public void completedMarkerIsWrittenAfterBothDurableClears() {
        List<String> order = new ArrayList<>();
        FakeStore store = new FakeStore(4, 4, order);
        FakeClears clears = new FakeClears(order);

        assertEquals(BootCleanupPolicy.Outcome.RESET_COMPLETE, execute(
                BootCleanupPolicy.Trigger.INITIALIZE, 5, 1L, store, clears));
        assertEquals(Arrays.asList("projection", "waze", "complete:5"), order);
    }

    @Test public void failedMarkerDiskCommitRestoresThePriorInMemoryValues() {
        MemoryFirstBackend backend = new MemoryFirstBackend(
                new BootCleanupPolicy.State(6, 6), false, true);

        assertEquals(false, BootCleanupPolicy.writeMarkersDurably(
                backend, new BootCleanupPolicy.State(7, 7)));
        assertEquals(6, backend.state.observedBootCount);
        assertEquals(6, backend.state.completedBootCount);
        assertEquals(Arrays.asList("write:7/7:false", "write:6/6:true"), backend.events);
    }

    private static BootCleanupPolicy.Outcome execute(BootCleanupPolicy.Trigger trigger,
            int count, long elapsed, FakeStore store, FakeClears clears) {
        return BootCleanupPolicy.execute(trigger, count, store, clears);
    }

    private static final class FakeStore implements BootCleanupPolicy.Store {
        BootCleanupPolicy.State state;
        boolean seedResult = true;
        boolean completeResult = true;
        final List<String> events = new ArrayList<>();
        final List<String> order;

        FakeStore(int observed, int completed) {
            this(observed, completed, null);
        }

        FakeStore(int observed, int completed, List<String> order) {
            state = new BootCleanupPolicy.State(observed, completed);
            this.order = order;
        }

        @Override public BootCleanupPolicy.State read() {
            return state;
        }

        @Override public boolean seedObserved(int bootCount) {
            events.add("seed:" + bootCount);
            if (order != null) order.add("seed:" + bootCount);
            if (seedResult) {
                state = new BootCleanupPolicy.State(bootCount, state.completedBootCount);
            }
            return seedResult;
        }

        @Override public boolean completeValidReset(int bootCount) {
            events.add("complete:" + bootCount);
            if (order != null) order.add("complete:" + bootCount);
            if (completeResult) state = new BootCleanupPolicy.State(bootCount, bootCount);
            return completeResult;
        }

        @Override public boolean completeUnknownReset() {
            events.add("complete-unknown");
            if (order != null) order.add("complete-unknown");
            if (completeResult) state = new BootCleanupPolicy.State(-1, -1);
            return completeResult;
        }
    }

    private static final class FakeClears implements BootCleanupPolicy.Clears {
        boolean projectionResult = true;
        boolean wazeResult = true;
        final List<String> events = new ArrayList<>();
        final List<String> order;

        FakeClears() {
            this(null);
        }

        FakeClears(List<String> order) {
            this.order = order;
        }

        @Override public boolean clearProjection() {
            events.add("projection");
            if (order != null) order.add("projection");
            return projectionResult;
        }

        @Override public boolean clearWaze() {
            events.add("waze");
            if (order != null) order.add("waze");
            return wazeResult;
        }
    }

    private static final class MemoryFirstBackend implements BootCleanupPolicy.MarkerBackend {
        BootCleanupPolicy.State state;
        final boolean[] diskResults;
        int writes;
        final List<String> events = new ArrayList<>();

        MemoryFirstBackend(BootCleanupPolicy.State state, boolean... diskResults) {
            this.state = state;
            this.diskResults = diskResults;
        }

        @Override public BootCleanupPolicy.State read() {
            return state;
        }

        @Override public boolean write(BootCleanupPolicy.State next) {
            state = next;
            boolean diskResult = diskResults[writes++];
            events.add("write:" + next.observedBootCount + "/"
                    + next.completedBootCount + ":" + diskResult);
            return diskResult;
        }
    }
}
