package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NavigatorRecoveryOwnershipTest {
    private static final String OWNER = "waze-direct-5.20.0.1";
    private static final String TRANSACTION = "asset-waze-direct-5.20.0.1-token";
    private static final String FINGERPRINT = "ABC123";

    @Test
    public void exactAssetTransactionAndFingerprintCanReenterRecovery() {
        assertTrue(matches(recovery(NavigatorPatchStore.Profile.WAZE),
                NavigatorPatchStore.Profile.WAZE,
                OWNER, TRANSACTION, FINGERPRINT));
    }

    @Test
    public void differentOwnerCannotReuseSameProfileRecovery() {
        assertFalse(matches(recovery(NavigatorPatchStore.Profile.WAZE),
                NavigatorPatchStore.Profile.WAZE,
                "waze-stock-4.95.0.3", TRANSACTION, FINGERPRINT));
    }

    @Test
    public void differentTransactionCannotReuseSameProfileRecovery() {
        assertFalse(matches(recovery(NavigatorPatchStore.Profile.WAZE),
                NavigatorPatchStore.Profile.WAZE,
                OWNER, "asset-other-token", FINGERPRINT));
    }

    @Test
    public void differentFingerprintCannotReuseSameProfileRecovery() {
        assertFalse(matches(recovery(NavigatorPatchStore.Profile.WAZE),
                NavigatorPatchStore.Profile.WAZE,
                OWNER, TRANSACTION, "DEF456"));
    }

    @Test
    public void differentProfileOrPhaseCannotReuseRecovery() {
        assertFalse(matches(recovery(NavigatorPatchStore.Profile.GMAPS),
                NavigatorPatchStore.Profile.WAZE,
                OWNER, TRANSACTION, FINGERPRINT));
        assertFalse(matches(new NavigatorPatchStore.OperationSnapshot(
                        NavigatorPatchStore.Profile.WAZE,
                        NavigatorPatchStore.OP_RECOVERY,
                        NavigatorPatchStore.FAILED,
                        "failed",
                        true),
                NavigatorPatchStore.Profile.WAZE,
                OWNER, TRANSACTION, FINGERPRINT));
    }

    private static NavigatorPatchStore.OperationSnapshot recovery(
            NavigatorPatchStore.Profile profile) {
        return new NavigatorPatchStore.OperationSnapshot(
                profile,
                NavigatorPatchStore.OP_RECOVERY,
                NavigatorPatchStore.RECOVERY_REQUIRED,
                "recovery",
                true);
    }

    private static boolean matches(
            NavigatorPatchStore.OperationSnapshot operation,
            NavigatorPatchStore.Profile profile,
            String owner,
            String transaction,
            String fingerprint) {
        return NavigatorPatchStore.matchesRecoveryTransaction(
                operation, profile,
                owner, transaction, fingerprint,
                OWNER, TRANSACTION, FINGERPRINT);
    }
}
