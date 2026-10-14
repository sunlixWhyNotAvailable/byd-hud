package com.bydhud.app;

/** Selected native ETA inputs shared by RoadInfo and Instrument output. */
final class NativeEtaData {
    static final NativeEtaData EMPTY = new NativeEtaData(
            -1L, DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS,
            -1L, -1L, "");

    final long arrivalEpochMs;
    final int arrivalOffsetSeconds;
    final long remainingSeconds;
    final long remainingMeters;
    final String diagnostics;

    private NativeEtaData(long arrivalEpochMs, int arrivalOffsetSeconds,
            long remainingSeconds, long remainingMeters, String diagnostics) {
        this.arrivalEpochMs = arrivalEpochMs;
        this.arrivalOffsetSeconds = arrivalOffsetSeconds;
        this.remainingSeconds = remainingSeconds;
        this.remainingMeters = remainingMeters;
        this.diagnostics = diagnostics;
    }

    static NativeEtaData from(
            DirectTbtFrame frame, DirectTbtPayload.Options options, long nowWallMs) {
        if (frame == null || options == null
                || options.etaOutputMode != HudPrefs.ETA_OUTPUT_NATIVE
                || options.routeMetricsMode == HudPrefs.ROUTE_METRICS_OFF) {
            return EMPTY;
        }

        HudEtaResolver.Result resolved = HudEtaResolver.resolve(
                frame.getTripMetrics(), nowWallMs);
        DirectTbtFrame.TravelMetrics next = resolved.nextStop.effective;
        DirectTbtFrame.TravelMetrics whole = resolved.wholeRoute.effective;
        boolean preferWhole = options.wholeRouteMetrics;
        DirectTbtFrame.TravelMetrics arrival = HudEtaText.select(preferWhole,
                whole.getArrivalTimeEpochMs() > 0L,
                next.getArrivalTimeEpochMs() > 0L, whole, next);
        DirectTbtFrame.TravelMetrics time = HudEtaText.select(preferWhole,
                whole.getRemainingTimeSeconds() >= 0L,
                next.getRemainingTimeSeconds() >= 0L, whole, next);
        DirectTbtFrame.TravelMetrics distance = HudEtaText.select(preferWhole,
                whole.getRemainingDistanceMeters() >= 0L,
                next.getRemainingDistanceMeters() >= 0L, whole, next);

        long arrivalMs = options.showEta ? arrival.getArrivalTimeEpochMs() : -1L;
        int arrivalOffset = arrivalMs > 0L
                ? arrival.getArrivalZoneOffsetSeconds()
                : DirectTbtFrame.TravelMetrics.UNKNOWN_ZONE_OFFSET_SECONDS;
        return new NativeEtaData(arrivalMs, arrivalOffset,
                options.showRemainingTime ? time.getRemainingTimeSeconds() : -1L,
                options.showRemainingDistance ? distance.getRemainingDistanceMeters() : -1L,
                resolved.diagnostics());
    }
}
