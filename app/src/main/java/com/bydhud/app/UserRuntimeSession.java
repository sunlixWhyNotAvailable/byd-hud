package com.bydhud.app;

/** Process-local user intent; saved output choices alone never enable a cold runtime. */
final class UserRuntimeSession {
    static final UserRuntimeSession PROCESS = new UserRuntimeSession();

    private volatile boolean active;

    void activate() {
        active = true;
    }

    void shutdown() {
        active = false;
    }

    boolean allowsRuntime(boolean bootEnabled, boolean userShutdown) {
        return !userShutdown && (active || bootEnabled);
    }
}
