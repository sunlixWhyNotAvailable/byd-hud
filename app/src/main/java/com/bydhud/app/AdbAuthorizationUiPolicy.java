package com.bydhud.app;

final class AdbAuthorizationUiPolicy {
    enum AutoState {
        IDLE,
        PENDING_UI,
        RUNNING,
        AUTHORIZED,
        FAILED
    }

    private AdbAuthorizationUiPolicy() {
    }

    static boolean canStart(
            AutoState state,
            boolean mainUiReady,
            boolean activityResumed,
            boolean windowFocused,
            String blockingFlow,
            boolean grantInProgress) {
        return state == AutoState.PENDING_UI
                && mainUiReady
                && activityResumed
                && windowFocused
                && (blockingFlow == null || blockingFlow.isEmpty())
                && !grantInProgress;
    }

    static boolean isHardSystemFlow(String flow) {
        return "setup".equals(flow) || "runtime-permissions".equals(flow);
    }

    static boolean shouldCancelAuthorizationForFlow(
            AutoState state,
            boolean grantInProgress,
            String flow) {
        return isHardSystemFlow(flow)
                && (state == AutoState.RUNNING || grantInProgress);
    }
}
