package com.bydhud.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class AdbAuthorizationGenerationTest {
    @Test public void cancelledWaitCannotReplaceExitEvenAfterItsMarkerIsReleased() {
        LocalAdbBridge.AuthorizationCancellation wait = LocalAdbBridge.cancelPendingAuthorization();
        long cleanupGeneration = wait.generation;
        try {
            LocalAdbBridge.AuthorizationCancellation exit = LocalAdbBridge.cancelPendingAuthorization();
            cleanupGeneration = exit.generation;
            assertFalse(LocalAdbBridge.clearPendingAuthorizationCancellation(wait.generation));
            assertNull(LocalAdbBridge.cancelPendingAuthorization(wait.generation));
            assertEquals(exit.generation, LocalAdbBridge.authorizationCancellationGeneration());
            assertTrue(LocalAdbBridge.isAuthorizationCancellationPending());

            assertTrue(LocalAdbBridge.clearPendingAuthorizationCancellation(exit.generation));
            assertNull(LocalAdbBridge.cancelPendingAuthorization(wait.generation));
            assertFalse(LocalAdbBridge.isAuthorizationCancellationPending());
            LocalAdbBridge.AuthorizationCancellation retry =
                    LocalAdbBridge.cancelPendingAuthorization(exit.generation);
            assertNotNull(retry);
            cleanupGeneration = retry.generation;
        } finally {
            LocalAdbBridge.clearPendingAuthorizationCancellation(cleanupGeneration);
        }
    }
}
