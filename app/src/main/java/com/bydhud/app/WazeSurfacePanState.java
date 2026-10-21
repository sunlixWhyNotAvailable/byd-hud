package com.bydhud.app;

/** Desired pan mode survives renderer replacement; an old acknowledgment does not. */
final class WazeSurfacePanState {
    private boolean desired;
    private Boolean sent;
    private long revision;

    static final class Request {
        final long revision;
        final boolean enabled;
        Request(long revision, boolean enabled) {
            this.revision = revision;
            this.enabled = enabled;
        }
    }

    void request(boolean enabled) { desired = enabled; }

    void rendererChanged() {
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
