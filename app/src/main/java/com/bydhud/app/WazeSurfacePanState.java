package com.bydhud.app;

/** Desired pan mode survives renderer replacement; an old acknowledgment does not. */
final class WazeSurfacePanState {
    private boolean desired;
    private Boolean sent;
    private long revision;
    private long recenterSequence;
    private long recenter;
    private boolean waitingForPanExit;
    private boolean recenterSent;

    static final class Request {
        final long revision;
        final boolean enabled;
        Request(long revision, boolean enabled) {
            this.revision = revision;
            this.enabled = enabled;
        }
    }

    void request(boolean enabled) {
        desired = enabled;
        if (enabled) finishRecenter(recenter);
    }

    boolean isPanning() { return desired; }
    boolean recenterPending() { return recenter != 0; }

    long beginRecenter() {
        if (recenterPending()) return 0;
        recenter = ++recenterSequence;
        waitingForPanExit = desired;
        recenterSent = false;
        return recenter;
    }

    boolean recenterPanExited() {
        if (!recenterPending() || !waitingForPanExit) return false;
        waitingForPanExit = false;
        return true;
    }

    long recenterControlsRequest() {
        return waitingForPanExit || recenterSent ? 0 : recenter;
    }

    boolean sendRecenter(long operation) {
        if (operation == 0 || operation != recenterControlsRequest()) return false;
        recenterSent = true;
        return true;
    }

    long pendingRecenter() { return recenter; }

    boolean finishRecenter(long operation) {
        if (operation == 0 || operation != recenter) return false;
        if (waitingForPanExit) {
            desired = true;
            sent = null;
            revision++;
        }
        recenter = 0;
        waitingForPanExit = false;
        recenterSent = false;
        return true;
    }

    void rendererChanged() {
        finishRecenter(recenter);
        sent = null;
        revision++;
    }

    Request next() {
        if (sent != null && sent == desired) return null;
        sent = desired;
        return new Request(++revision, desired);
    }

    boolean complete(Request request, boolean success) {
        if (request.revision != revision) return false;
        if (!success) sent = null;
        return true;
    }
}
