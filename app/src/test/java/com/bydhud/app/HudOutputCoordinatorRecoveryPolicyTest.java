package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class HudOutputCoordinatorRecoveryPolicyTest {
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
