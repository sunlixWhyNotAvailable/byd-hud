package com.bydhud.app;

/** Bounded merge state for Waze Trip and RoutingInfo updates before icon rendering. */
final class WazeTripStateQueue<S, M> {
    private static final long UNSET = -1L;
    static final int LANES_PRESERVE = 0;
    static final int LANES_CLEAR = 1;
    static final int LANES_FROM_UPDATE = 2;

    private S step;
    private long stepSequence = UNSET;
    private long stepElapsedMs = UNSET;
    private int distanceMeters;
    private boolean distanceKnown;
    private boolean authoritativeLanes;
    private M metrics;
    private long metricsSequence = UNSET;
    private long metricsElapsedMs = UNSET;
    private long processedStepSequence = UNSET;
    private long processedMetricsSequence = UNSET;
    private long discardedThroughSequence = UNSET;
    private int maneuverType = -1;
    private long maneuverSequence = UNSET;
    // Latest observed incompatible maneuver: lanes at/before this point cannot survive.
    private long laneBarrierSequence = UNSET;
    private long laneSequence = UNSET;
    private int laneManeuverType = -1;
    private int laneDisposition = LANES_PRESERVE;
    private S laneSource;
    private boolean laneDirty;
    private long laneChangeSequence = UNSET;
    private long laneChangeElapsedMs = UNSET;
    private int coalescedCount;
    private boolean processing;

    synchronized boolean merge(Update<S, M> update) {
        if (update == null || update.sequence <= discardedThroughSequence) return false;
        if (hasPending() || processing) coalescedCount++;
        boolean accepted = false;
        if (update.stepUpdated && update.step != null) {
            if (update.sequence > stepSequence) {
                step = update.step;
                stepSequence = update.sequence;
                stepElapsedMs = update.elapsedMs;
                distanceMeters = Math.max(0, update.distanceMeters);
                distanceKnown = update.distanceKnown;
                authoritativeLanes = update.authoritativeLanes;
                accepted = true;
            }
            if (update.maneuverType >= 0) {
                if (update.sequence > maneuverSequence) {
                    if (maneuverType >= 0 && update.maneuverType != maneuverType) {
                        laneBarrierSequence = Math.max(laneBarrierSequence, maneuverSequence);
                    }
                    maneuverType = update.maneuverType;
                    maneuverSequence = update.sequence;
                } else if (update.maneuverType != maneuverType) {
                    laneBarrierSequence = Math.max(laneBarrierSequence, update.sequence);
                }
                if (laneSequence <= laneBarrierSequence
                        || laneManeuverType >= 0 && laneManeuverType != maneuverType) {
                    if (clearLanes(laneBarrierSequence)) {
                        laneChangeSequence = update.sequence;
                        laneChangeElapsedMs = update.elapsedMs;
                        accepted = true;
                    }
                }
            }
            boolean compatibleManeuver = update.maneuverType < 0
                    || maneuverType < 0 || update.maneuverType == maneuverType;
            if ((update.authoritativeLanes || update.hasLaneGuidance)
                    && compatibleManeuver && update.sequence > laneBarrierSequence
                    && update.sequence > laneSequence) {
                laneSequence = update.sequence;
                laneManeuverType = maneuverType;
                laneDisposition = update.hasLaneGuidance ? LANES_FROM_UPDATE : LANES_CLEAR;
                laneSource = update.hasLaneGuidance ? update.step : null;
                laneDirty = true;
                laneChangeSequence = update.sequence;
                laneChangeElapsedMs = update.elapsedMs;
                accepted = true;
            }
        }
        if (update.metricsUpdated && update.sequence > metricsSequence) {
            metrics = update.metrics;
            metricsSequence = update.sequence;
            metricsElapsedMs = update.elapsedMs;
            accepted = true;
        }
        return accepted;
    }

    private boolean clearLanes(long barrier) {
        if (barrier < 0L) return false;
        boolean changed = laneDisposition != LANES_CLEAR;
        laneSequence = Math.max(laneSequence, barrier);
        laneManeuverType = maneuverType;
        laneDisposition = LANES_CLEAR;
        laneSource = null;
        laneDirty |= changed;
        return changed;
    }

    synchronized Snapshot<S, M> take() {
        boolean stepUpdated = step != null && stepSequence > processedStepSequence;
        boolean metricsUpdated = metrics != null && metricsSequence > processedMetricsSequence;
        boolean laneUpdated = step != null && laneDirty;
        if (!stepUpdated && !metricsUpdated && !laneUpdated) return null;
        if (stepUpdated) processedStepSequence = stepSequence;
        if (metricsUpdated || (stepUpdated || laneUpdated) && metricsSequence >= 0L) {
            processedMetricsSequence = metricsSequence;
        }
        long newestSequence = stepUpdated ? stepSequence : UNSET;
        long newestElapsedMs = stepUpdated ? stepElapsedMs : UNSET;
        if (metricsUpdated && metricsSequence > newestSequence) {
            newestSequence = metricsSequence;
            newestElapsedMs = metricsElapsedMs;
        }
        if (laneUpdated && laneChangeSequence > newestSequence) {
            newestSequence = laneChangeSequence;
            newestElapsedMs = laneChangeElapsedMs;
        }
        Snapshot<S, M> snapshot = new Snapshot<>(stepUpdated, metricsUpdated, laneUpdated, step,
                distanceKnown, distanceMeters, authoritativeLanes, metrics,
                laneUpdated ? laneDisposition : LANES_PRESERVE,
                laneUpdated ? laneSource : null, newestSequence, newestElapsedMs, coalescedCount);
        laneDirty = false;
        coalescedCount = 0;
        processing = true;
        return snapshot;
    }

    synchronized void finishProcessing() { processing = false; }

    synchronized boolean hasPending() {
        return step != null && (stepSequence > processedStepSequence || laneDirty)
                || metrics != null && metricsSequence > processedMetricsSequence;
    }

    synchronized long newestPendingSequence() {
        long newest = UNSET;
        if (step != null && (stepSequence > processedStepSequence || laneDirty)) newest = stepSequence;
        if (metrics != null && metricsSequence > processedMetricsSequence) {
            newest = Math.max(newest, metricsSequence);
        }
        return newest;
    }

    synchronized int discardThrough(long sequence) {
        int discarded = hasPending() ? 1 : 0;
        discardedThroughSequence = Math.max(discardedThroughSequence, sequence);
        if (stepSequence <= sequence) {
            step = null;
            stepSequence = UNSET;
            stepElapsedMs = UNSET;
            processedStepSequence = UNSET;
            distanceMeters = 0;
            distanceKnown = false;
            authoritativeLanes = false;
        }
        if (maneuverSequence <= sequence) {
            maneuverType = -1;
            maneuverSequence = UNSET;
        }
        laneBarrierSequence = Math.max(laneBarrierSequence, sequence);
        if (laneSequence <= sequence && clearLanes(sequence)) {
            laneChangeSequence = sequence;
            laneChangeElapsedMs = UNSET;
        }
        if (metricsSequence <= sequence) {
            metrics = null;
            metricsSequence = UNSET;
            metricsElapsedMs = UNSET;
            processedMetricsSequence = UNSET;
        }
        if (discarded > 0) coalescedCount++;
        return discarded;
    }

    synchronized void reset() {
        step = null;
        stepSequence = UNSET;
        stepElapsedMs = UNSET;
        distanceMeters = 0;
        distanceKnown = false;
        authoritativeLanes = false;
        metrics = null;
        metricsSequence = UNSET;
        metricsElapsedMs = UNSET;
        processedStepSequence = UNSET;
        processedMetricsSequence = UNSET;
        maneuverType = -1;
        maneuverSequence = UNSET;
        laneBarrierSequence = UNSET;
        laneSequence = UNSET;
        laneManeuverType = -1;
        laneDisposition = LANES_PRESERVE;
        laneSource = null;
        laneDirty = false;
        laneChangeSequence = UNSET;
        laneChangeElapsedMs = UNSET;
        coalescedCount = 0;
        processing = false;
    }

    synchronized void resetThrough(long sequence) {
        reset();
        discardedThroughSequence = Math.max(discardedThroughSequence, sequence);
    }

    static final class Update<S, M> {
        final long sequence;
        final long elapsedMs;
        final boolean stepUpdated;
        final S step;
        final boolean distanceKnown;
        final int distanceMeters;
        final boolean authoritativeLanes;
        final int maneuverType;
        final boolean hasLaneGuidance;
        final boolean metricsUpdated;
        final M metrics;

        Update(long sequence, long elapsedMs, boolean stepUpdated, S step,
                boolean distanceKnown, int distanceMeters, boolean authoritativeLanes,
                boolean metricsUpdated, M metrics) {
            this(sequence, elapsedMs, stepUpdated, step, distanceKnown, distanceMeters,
                    authoritativeLanes, -1, false, metricsUpdated, metrics);
        }

        Update(long sequence, long elapsedMs, boolean stepUpdated, S step,
                boolean distanceKnown, int distanceMeters, boolean authoritativeLanes,
                int maneuverType, boolean hasLaneGuidance,
                boolean metricsUpdated, M metrics) {
            this.sequence = sequence;
            this.elapsedMs = elapsedMs;
            this.stepUpdated = stepUpdated;
            this.step = step;
            this.distanceKnown = distanceKnown;
            this.distanceMeters = distanceMeters;
            this.authoritativeLanes = authoritativeLanes;
            this.maneuverType = maneuverType;
            this.hasLaneGuidance = hasLaneGuidance;
            this.metricsUpdated = metricsUpdated;
            this.metrics = metrics;
        }
    }

    static final class Snapshot<S, M> {
        final boolean stepUpdated;
        final boolean metricsUpdated;
        final boolean laneUpdated;
        final S step;
        final boolean distanceKnown;
        final int distanceMeters;
        final boolean authoritativeLanes;
        final int laneDisposition;
        final S laneSource;
        final M metrics;
        final long sequence;
        final long ingressElapsedMs;
        final int coalescedCount;

        Snapshot(boolean stepUpdated, boolean metricsUpdated, boolean laneUpdated, S step,
                boolean distanceKnown, int distanceMeters, boolean authoritativeLanes,
                M metrics, int laneDisposition, S laneSource,
                long sequence, long ingressElapsedMs,
                int coalescedCount) {
            this.stepUpdated = stepUpdated;
            this.metricsUpdated = metricsUpdated;
            this.laneUpdated = laneUpdated;
            this.step = step;
            this.distanceKnown = distanceKnown;
            this.distanceMeters = distanceMeters;
            this.authoritativeLanes = authoritativeLanes;
            this.laneDisposition = laneDisposition;
            this.laneSource = laneSource;
            this.metrics = metrics;
            this.sequence = sequence;
            this.ingressElapsedMs = ingressElapsedMs;
            this.coalescedCount = coalescedCount;
        }
    }
}
