package com.bydhud.app;

/** Pure transitions for the dashboard overlay lifecycle. */
final class DashboardWidgetLifecyclePolicy {
    enum Permission { UNKNOWN, GRANTED, DENIED }
    enum ServiceStart { WAIT_FOR_PERMISSION, SHOW, STOP }
    enum Action { NONE, START, RENDER, CLOSE }

    static final class State {
        final boolean startRequested;
        final long activeInstance;
        final long pendingRestartInstance;
        final boolean restartBlocked;

        State(boolean startRequested, long activeInstance,
                long pendingRestartInstance, boolean restartBlocked) {
            this.startRequested = startRequested;
            this.activeInstance = activeInstance;
            this.pendingRestartInstance = pendingRestartInstance;
            this.restartBlocked = restartBlocked;
        }
    }

    static final class Transition {
        final State state;
        final Action action;

        Transition(State state, Action action) {
            this.state = state;
            this.action = action;
        }
    }

    private DashboardWidgetLifecyclePolicy() {}

    static State initialState() {
        return new State(false, 0L, 0L, false);
    }

    static State allowRetry(State state) {
        return new State(state.startRequested, state.activeInstance,
                state.pendingRestartInstance, false);
    }

    static State serviceCreated(State state, long instance) {
        return new State(false, instance, 0L, false);
    }

    static State startFailed(State state) {
        return new State(false, state.activeInstance,
                state.pendingRestartInstance, state.restartBlocked);
    }

    static Transition reconcile(State state, boolean visible, Permission permission,
            boolean shutdown, boolean permissionRefreshFailed, boolean activeClosing,
            boolean allowPendingRestart) {
        if (!visible || shutdown || permission == Permission.DENIED
                || (permission == Permission.UNKNOWN && permissionRefreshFailed)) {
            return cancel(state);
        }
        if (permission == Permission.UNKNOWN) {
            return new Transition(state, Action.NONE);
        }
        if (state.activeInstance != 0L) {
            if (activeClosing) {
                State queued = allowPendingRestart && !state.restartBlocked
                        ? new State(state.startRequested, state.activeInstance,
                                state.activeInstance, state.restartBlocked)
                        : state;
                return new Transition(queued, Action.NONE);
            }
            return new Transition(state, Action.RENDER);
        }
        if (state.startRequested) {
            return new Transition(state, Action.NONE);
        }
        return new Transition(new State(true, 0L, 0L, state.restartBlocked), Action.START);
    }

    static Transition cancel(State state) {
        return new Transition(new State(false, state.activeInstance, 0L, state.restartBlocked),
                state.activeInstance == 0L && !state.startRequested ? Action.NONE : Action.CLOSE);
    }

    static Transition attachmentFailed(State state, long instance) {
        if (state.activeInstance != instance) {
            return new Transition(state, Action.NONE);
        }
        return new Transition(new State(false, state.activeInstance, 0L, true), Action.CLOSE);
    }

    static Transition serviceDestroyed(State state, long instance,
            boolean visible, Permission permission, boolean shutdown) {
        if (state.activeInstance != instance) {
            return new Transition(state, Action.NONE);
        }
        boolean restart = state.pendingRestartInstance == instance
                && !state.restartBlocked
                && wantsOverlay(visible, permission, shutdown);
        return new Transition(new State(restart, 0L, 0L, state.restartBlocked),
                restart ? Action.START : Action.NONE);
    }

    static boolean wantsOverlay(boolean visible, Permission permission, boolean shutdown) {
        return visible && permission == Permission.GRANTED && !shutdown;
    }

    static ServiceStart serviceStart(
            boolean visible, Permission permission, boolean shutdown,
            boolean permissionRefreshFailed, boolean closing) {
        if (!visible || shutdown || permission == Permission.DENIED
                || (permission == Permission.UNKNOWN && permissionRefreshFailed) || closing) {
            return ServiceStart.STOP;
        }
        return permission == Permission.UNKNOWN ? ServiceStart.WAIT_FOR_PERMISSION : ServiceStart.SHOW;
    }
}
