package com.bydhud.app;

import java.util.Locale;

/** Completes missing ETA counterparts without mutating navigator-supplied metrics. */
final class HudEtaResolver {
    static final long MINUTE_MS = 60_000L;

    enum Provenance {
        UNAVAILABLE,
        SOURCE,
        DERIVED
    }

    private HudEtaResolver() {
    }

    static Result resolve(DirectTbtFrame.TripMetrics raw, long nowWallTimeMs) {
        DirectTbtFrame.TripMetrics safe = raw == null
                ? DirectTbtFrame.TripMetrics.empty() : raw;
        Target next = resolveTarget(safe.getNextStop(), nowWallTimeMs);
        Target whole = resolveTarget(safe.getWholeRoute(), nowWallTimeMs);
        return new Result(next, whole);
    }

    private static Target resolveTarget(
            DirectTbtFrame.TravelMetrics raw, long nowWallTimeMs) {
        DirectTbtFrame.TravelMetrics source = raw == null
                ? DirectTbtFrame.TravelMetrics.unavailable() : raw;
        long arrivalMs = source.getArrivalTimeEpochMs();
        int arrivalOffset = source.getArrivalZoneOffsetSeconds();
        long durationSeconds = source.getRemainingTimeSeconds();
        Provenance arrivalProvenance = arrivalMs > 0L
                ? Provenance.SOURCE : Provenance.UNAVAILABLE;
        Provenance durationProvenance = durationSeconds >= 0L
                ? Provenance.SOURCE : Provenance.UNAVAILABLE;

        if (arrivalMs > 0L && durationSeconds < 0L && nowWallTimeMs >= 0L) {
            long minutes = epochMinute(arrivalMs) - epochMinute(nowWallTimeMs);
            if (minutes >= 0L && minutes <= Long.MAX_VALUE / 60L) {
                durationSeconds = minutes * 60L;
                durationProvenance = Provenance.DERIVED;
            }
        } else if (arrivalMs <= 0L && durationSeconds >= 0L) {
            long sampleMs = source.getSampleWallTimeEpochMs();
            long minutes = roundedUpMinutes(durationSeconds);
            if (sampleMs > 0L && minutes <= Long.MAX_VALUE - epochMinute(sampleMs)) {
                long arrivalMinute = epochMinute(sampleMs) + minutes;
                if (arrivalMinute > 0L && arrivalMinute <= Long.MAX_VALUE / MINUTE_MS) {
                    arrivalMs = arrivalMinute * MINUTE_MS;
                    arrivalProvenance = Provenance.DERIVED;
                }
            }
        }

        DirectTbtFrame.TravelMetrics effective = new DirectTbtFrame.TravelMetrics(
                arrivalMs, arrivalOffset, durationSeconds,
                source.getRemainingDistanceMeters(), source.getSampleWallTimeEpochMs());
        return new Target(source, effective, arrivalProvenance, durationProvenance);
    }

    static long epochMinute(long epochMs) {
        return Math.floorDiv(epochMs, MINUTE_MS);
    }

    static long roundedUpMinutes(long seconds) {
        if (seconds < 0L) return -1L;
        return seconds / 60L + (seconds % 60L == 0L ? 0L : 1L);
    }

    static final class Result {
        final Target nextStop;
        final Target wholeRoute;

        Result(Target nextStop, Target wholeRoute) {
            this.nextStop = nextStop;
            this.wholeRoute = wholeRoute;
        }

        DirectTbtFrame.TripMetrics effectiveMetrics() {
            return new DirectTbtFrame.TripMetrics(nextStop.effective, wholeRoute.effective);
        }

        String diagnostics() {
            return nextStop.diagnostics("next") + " " + wholeRoute.diagnostics("whole");
        }
    }

    static final class Target {
        final DirectTbtFrame.TravelMetrics source;
        final DirectTbtFrame.TravelMetrics effective;
        final Provenance arrivalProvenance;
        final Provenance durationProvenance;

        Target(DirectTbtFrame.TravelMetrics source,
                DirectTbtFrame.TravelMetrics effective,
                Provenance arrivalProvenance,
                Provenance durationProvenance) {
            this.source = source;
            this.effective = effective;
            this.arrivalProvenance = arrivalProvenance;
            this.durationProvenance = durationProvenance;
        }

        String diagnostics(String prefix) {
            return prefix + "Arrival=" + metric(
                    source.getArrivalTimeEpochMs(), effective.getArrivalTimeEpochMs(),
                    arrivalProvenance)
                    + " " + prefix + "Duration=" + metric(
                    source.getRemainingTimeSeconds(), effective.getRemainingTimeSeconds(),
                    durationProvenance)
                    + " " + prefix + "SampleWallMs=" + source.getSampleWallTimeEpochMs();
        }

        private static String metric(long source, long effective, Provenance provenance) {
            return provenance.name().toLowerCase(Locale.US)
                    + ":" + effective + "(raw=" + source + ")";
        }
    }
}
