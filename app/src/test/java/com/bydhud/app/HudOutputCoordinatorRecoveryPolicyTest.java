package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class HudOutputCoordinatorRecoveryPolicyTest {
    @Test
    public void nativeEndClearRequiresAnEndingOwnerAndExplicitNativeEndPolicy() {
        for (HudOutputCoordinator.Source previous : HudOutputCoordinator.Source.values()) {
            for (HudOutputCoordinator.Source target : HudOutputCoordinator.Source.values()) {
                for (int primary = 0; primary <= 5; primary++) {
                    for (int clearing = 0; clearing <= 3; clearing++) {
                        boolean expected = previous != HudOutputCoordinator.Source.NONE
                                && target == HudOutputCoordinator.Source.NONE
                                && primary == 5 && clearing == 3;
                        assertEquals(expected, HudOutputCoordinator.shouldClearNativeAtEnd(
                                previous, target, true, primary, clearing));
                        // Handoff/recovery callers never admit this operation.
                        assertFalse(HudOutputCoordinator.shouldClearNativeAtEnd(
                                previous, target, false, primary, clearing));
                    }
                }
            }
        }
    }

    @Test
    public void classifiesResultsAndCapsProtocolBackoff() {
        assertTrue(HudOutputCoordinator.isStartReadyResult(0));
        assertTrue(HudOutputCoordinator.isStartReadyResult(13));
        assertFalse(HudOutputCoordinator.isStartReadyResult(11));
        assertTrue(HudOutputCoordinator.resultMarksServiceUnstarted(11));
        assertFalse(HudOutputCoordinator.resultMarksServiceUnstarted(13));
        assertTrue(HudOutputCoordinator.isPayloadSuccessResult(0));
        assertFalse(HudOutputCoordinator.isPayloadSuccessResult(13));

        assertEquals(1_000L, HudOutputCoordinator.protocolRetryDelayMs(1));
        assertEquals(2_000L, HudOutputCoordinator.protocolRetryDelayMs(2));
        assertEquals(5_000L, HudOutputCoordinator.protocolRetryDelayMs(3));
        assertEquals(5_000L, HudOutputCoordinator.protocolRetryDelayMs(99));
    }

    @Test
    public void bindDeadlineIsBoundedAndDoesNotDependOnRetryCount() {
        assertFalse(HudOutputCoordinator.bindDeadlineReachedForTest(0L, 10_000L));
        assertFalse(HudOutputCoordinator.bindDeadlineReachedForTest(7_000L, 6_999L));
        assertTrue(HudOutputCoordinator.bindDeadlineReachedForTest(7_000L, 7_000L));
    }
}
