package com.bydhud.app;

import android.content.Context;

/** User intent or recovery of an existing session; saved output choices do not start one. */
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

    static boolean allowsRuntime(Context context) {
        return PROCESS.allowsRuntime(
                HudPrefs.isBootEnabled(context), HudPrefs.isUserShutdownActive(context));
    }
}
