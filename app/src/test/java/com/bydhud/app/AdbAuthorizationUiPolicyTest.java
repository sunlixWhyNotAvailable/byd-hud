package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AdbAuthorizationUiPolicyTest {
    @Test
    public void pendingAuthorizationStartsOnlyOnClearFocusedMainUi() {
        assertTrue(canStart(true, true, true, "", false));
        assertFalse(canStart(false, true, true, "", false));
        assertFalse(canStart(true, false, true, "", false));
        assertFalse(canStart(true, true, false, "", false));
        assertFalse(canStart(true, true, true, "update", false));
        assertFalse(canStart(true, true, true, "", true));
        assertFalse(AdbAuthorizationUiPolicy.canStart(
                AdbAuthorizationUiPolicy.AutoState.FAILED,
                true, true, true, "", false));
        assertFalse(AdbAuthorizationUiPolicy.canStart(
                AdbAuthorizationUiPolicy.AutoState.AUTHORIZED,
                true, true, true, "", false));
    }

    @Test
    public void onlyHardSystemFlowCancelsRunningAuthorization() {
        assertFalse(AdbAuthorizationUiPolicy.shouldCancelAuthorizationForFlow(
                AdbAuthorizationUiPolicy.AutoState.RUNNING, false, "update"));
        assertFalse(AdbAuthorizationUiPolicy.shouldCancelAuthorizationForFlow(
                AdbAuthorizationUiPolicy.AutoState.RUNNING, false, "storage-delete"));
        assertTrue(AdbAuthorizationUiPolicy.shouldCancelAuthorizationForFlow(
                AdbAuthorizationUiPolicy.AutoState.RUNNING, false, "setup"));
        assertTrue(AdbAuthorizationUiPolicy.shouldCancelAuthorizationForFlow(
                AdbAuthorizationUiPolicy.AutoState.AUTHORIZED, true, "runtime-permissions"));
        assertFalse(AdbAuthorizationUiPolicy.shouldCancelAuthorizationForFlow(
                AdbAuthorizationUiPolicy.AutoState.PENDING_UI, false, "setup"));
    }

    @Test
    public void autoAuthorizationCanPromptAgainUntilHandshakeSucceeds() {
        assertTrue(LocalAdbBridge.shouldSendPublicKeyForMode(
                LocalAdbBridge.AuthorizationPromptMode.AUTO_ONCE));
        assertTrue(LocalAdbBridge.shouldSendPublicKeyForMode(
                LocalAdbBridge.AuthorizationPromptMode.FORCE));
        assertFalse(LocalAdbBridge.shouldSendPublicKeyForMode(
                LocalAdbBridge.AuthorizationPromptMode.NEVER));
    }

    private static boolean canStart(
            boolean mainUiReady,
            boolean resumed,
            boolean focused,
            String blocker,
            boolean grantInProgress) {
        return AdbAuthorizationUiPolicy.canStart(
                AdbAuthorizationUiPolicy.AutoState.PENDING_UI,
                mainUiReady,
                resumed,
                focused,
                blocker,
                grantInProgress);
    }
}
