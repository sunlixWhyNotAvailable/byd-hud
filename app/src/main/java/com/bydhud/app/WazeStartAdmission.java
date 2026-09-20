package com.bydhud.app;

/** Process-local permission to attach a Waze host; saved route state is not permission. */
final class WazeStartAdmission {
    static final WazeStartAdmission PROCESS = new WazeStartAdmission();
    static final long FRESH_MS = 5_000L;

    enum Source { LIVE_ROUTE, LEGACY_PROCESS, ACTIVE_RECOVERY }

    static final class Permit {
        final long epoch;
        final Source source;

        Permit(long epoch, Source source) {
            this.epoch = epoch;
            this.source = source;
        }
    }

    private long epoch;
    private long packageUpdateMs = Long.MIN_VALUE;
    private boolean enabled;
    private boolean bridgeSupported;
    private boolean routeActive;
    private boolean establishedRoute;
    private long routeEvidenceMs = -1L;
    private long legacyEvidenceMs = -1L;

    synchronized long epoch() { return epoch; }

    synchronized void updateRuntime(boolean enabled, long packageUpdateMs,
            boolean bridgeSupported) {
        if (this.packageUpdateMs != Long.MIN_VALUE
                && this.packageUpdateMs != packageUpdateMs) invalidate();
        this.packageUpdateMs = packageUpdateMs;
        if (this.enabled && !enabled) invalidate();
        this.enabled = enabled;
        this.bridgeSupported = bridgeSupported;
        if (bridgeSupported) legacyEvidenceMs = -1L;
    }

    synchronized void acceptedRoute(boolean active, boolean navigating,
            boolean terminal, long eventElapsedMs) {
        if (terminal || !active) {
            invalidate();
            return;
        }
        routeActive = true;
        if (navigating) routeEvidenceMs = eventElapsedMs;
    }

    synchronized boolean observedProcess(long expectedEpoch, boolean running,
            long observedElapsedMs) {
        if (expectedEpoch != epoch || !enabled || bridgeSupported) return false;
        legacyEvidenceMs = running ? observedElapsedMs : -1L;
        return running;
    }

    synchronized Permit acquire(long nowElapsedMs) {
        if (!enabled) return null;
        if (routeActive && establishedRoute) return new Permit(epoch, Source.ACTIVE_RECOVERY);
        if (routeActive && fresh(routeEvidenceMs, nowElapsedMs)) {
            return new Permit(epoch, Source.LIVE_ROUTE);
        }
        if (!bridgeSupported && fresh(legacyEvidenceMs, nowElapsedMs)) {
            return new Permit(epoch, Source.LEGACY_PROCESS);
        }
        return null;
    }

    synchronized boolean isCurrent(Permit permit) {
        return permit != null && enabled && permit.epoch == epoch
                && (permit.source != Source.LEGACY_PROCESS || !bridgeSupported || routeActive);
    }

    synchronized void established(Permit permit, boolean navigationStarted) {
        if (!isCurrent(permit)) return;
        if (navigationStarted || routeActive) {
            routeActive = true;
            establishedRoute = true;
        }
    }

    synchronized void invalidate() {
        epoch++;
        routeActive = false;
        establishedRoute = false;
        routeEvidenceMs = -1L;
        legacyEvidenceMs = -1L;
    }

    private static boolean fresh(long evidenceMs, long nowMs) {
        return evidenceMs >= 0L && nowMs >= evidenceMs && nowMs - evidenceMs <= FRESH_MS;
    }
}
