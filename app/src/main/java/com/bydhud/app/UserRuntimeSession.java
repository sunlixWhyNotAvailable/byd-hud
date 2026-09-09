package com.bydhud.app;

/** Process-local user intent; saved output choices alone never enable a cold runtime. */
final class UserRuntimeSession {
    static final UserRuntimeSession PROCESS = new UserRuntimeSession();

    private volatile boolean active;
    private long generation;

    synchronized void activate() {
        if (!active) generation++;
        active = true;
    }

    synchronized void shutdown() {
        generation++;
        active = false;
    }

    synchronized long shutdownToken() {
        return active ? -1L : generation;
    }

    synchronized boolean isCurrentShutdown(long token) {
        return token > 0L && !active && generation == token;
    }

    boolean allowsRuntime(boolean bootEnabled, boolean userShutdown) {
        return !userShutdown && (active || bootEnabled);
    }
}
