package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Behavioral guards for route ownership, observer lifecycle and teardown tokens. */
public final class NavHudRuntimeContractTest {
    @Test
    public void GmapsProtocolAcceptsLegacyV3AndFencesTokenizedV3() {
        assertTrue(GMapsDirectChannel.acceptsProtocolMessageForTest(3, "new", ""));
        assertTrue(GMapsDirectChannel.acceptsProtocolMessageForTest(3, "new", "new"));
        assertFalse(GMapsDirectChannel.acceptsProtocolMessageForTest(3, "new", "old"));
        assertFalse(GMapsDirectChannel.acceptsProtocolMessageForTest(2, "new", "new"));
    }

    @Test
    public void TbtFramesDoNotStealOwnershipWithoutAStartOrHudPriority() {
        assertTrue(NavHudLiveSender.shouldClaimTbtOwnerForFrameForTest(
                false, false, false, false, false));
        assertTrue(NavHudLiveSender.shouldClaimTbtOwnerForFrameForTest(
                true, true, false, false, false));
        assertTrue(NavHudLiveSender.shouldClaimTbtOwnerForFrameForTest(
                true, false, true, false, false));
        assertTrue(NavHudLiveSender.shouldClaimTbtOwnerForFrameForTest(
                true, false, false, true, false));
        assertFalse(NavHudLiveSender.shouldClaimTbtOwnerForFrameForTest(
                true, false, false, false, false));
        assertFalse(NavHudLiveSender.shouldClaimTbtOwnerForFrameForTest(
                true, false, false, true, true));
    }

    @Test
    public void WazeObserverIsRouteGatedButRebindsAfterChannelLoss() {
        assertFalse(NavHudLiveSender.shouldStartTbtObserverForTest(
                true, true, false, false, false, false));
        assertTrue(NavHudLiveSender.shouldStartTbtObserverForTest(
                true, true, false, true, false, false));
        assertTrue(NavHudLiveSender.shouldStartTbtObserverForTest(
                true, true, false, true, true, false));
        assertFalse(NavHudLiveSender.shouldStartTbtObserverForTest(
                true, true, false, true, true, true));
    }

    @Test
    public void HudOwnershipTransitionPromotesObserverWithoutStoppingChannel() {
        assertTrue(NavHudLiveSender.shouldPromoteTbtObserverForHudForTest(true, true));
        assertFalse(NavHudLiveSender.shouldPromoteTbtObserverForHudForTest(true, false));
        assertFalse(NavHudLiveSender.shouldPromoteTbtObserverForHudForTest(false, true));
    }

    @Test
    public void HudSwitchRetainsOnlyAnActiveRouteWhenTbtWithoutHudIsEnabled() {
        assertTrue(NavHudLiveSender.shouldRetainTbtRouteOnHudSwitchForTest(
                true, true, true));
        assertFalse(NavHudLiveSender.shouldRetainTbtRouteOnHudSwitchForTest(
                true, true, false));
        assertFalse(NavHudLiveSender.shouldRetainTbtRouteOnHudSwitchForTest(
                true, false, true));
        assertFalse(NavHudLiveSender.shouldRetainTbtRouteOnHudSwitchForTest(
                false, true, true));
    }

    @Test
    public void NoHudTbtDoesNotRequireLogOnlySelection() {
        assertTrue(NavHudLiveSender.shouldObserveTbtWithoutHudForTest(
                true, false, true, false));
        assertTrue(NavHudLiveSender.shouldObserveTbtWithoutHudForTest(
                true, false, false, true));
        assertFalse(NavHudLiveSender.shouldObserveTbtWithoutHudForTest(
                true, false, false, false));
        assertFalse(NavHudLiveSender.shouldObserveTbtWithoutHudForTest(
                true, true, true, true));
    }

    @Test
    public void SurfaceFramesRequireBothSourceAndPublisherGenerations() {
        assertTrue(NavHudLiveSender.shouldAcceptWazeFrameForTest(
                true, true, true, true, true, true));
        assertFalse(NavHudLiveSender.shouldAcceptWazeFrameForTest(
                true, true, false, true, true, true));
        assertFalse(NavHudLiveSender.shouldAcceptWazeFrameForTest(
                true, true, true, false, true, true));
        assertFalse(NavHudLiveSender.shouldAcceptWazeFrameForTest(
                true, false, true, true, true, true));
    }

    @Test
    public void GMapsEndCallbackMustBeCurrentEvenForObserver() {
        assertTrue(NavHudLiveSender.acceptsGMapsTeardownForTest(true, 8L, 9L));
        assertFalse(NavHudLiveSender.acceptsGMapsTeardownForTest(false, 8L, 9L));
        assertFalse(NavHudLiveSender.acceptsGMapsTeardownForTest(true, 8L, 8L));
    }

    @Test
    public void WazeAcceptsDirectFenceOrAuthoritativeLifecycleTerminal() {
        assertTrue(NavHudLiveSender.acceptsWazeTeardownForTest(8, 9));
        assertTrue(NavHudLiveSender.acceptsWazeTeardownForTest(8, 8));
        assertFalse(NavHudLiveSender.acceptsWazeTeardownForTest(8, 10));
    }

    @Test
    public void DelayedHudClearMustBelongToTheCurrentSenderGeneration() {
        assertTrue(NavHudLiveSender.acceptsHudStopCallbackForTest(8L, 8L));
        assertFalse(NavHudLiveSender.acceptsHudStopCallbackForTest(8L, 9L));
        assertEquals(9L, NavHudLiveSender.nextObserverLifecycleTokenForTest(8L));
        assertFalse(NavHudLiveSender.acceptsHudStopCallbackForTest(
                8L, NavHudLiveSender.nextObserverLifecycleTokenForTest(8L)));
    }

    @Test
    public void UnsupportedManualTbtMappingIsBlankButVerifiedMappingIsPreserved() {
        assertEquals(11, NavHudLiveSender.manualTbtManeuverForTest(11));
        assertEquals(99, NavHudLiveSender.manualTbtManeuverForTest(4));
        assertEquals(99, NavHudLiveSender.manualTbtManeuverForTest(50));
    }

    @Test
    public void OldTeardownWorkerCannotPublishStatusFourForNewRoute() {
        assertTrue(NavHudLiveSender.shouldApplyTeardownForTest(
                8L, 8L, false, true, true));
        assertFalse(NavHudLiveSender.shouldApplyTeardownForTest(
                8L, 9L, false, true, true));
        assertFalse(NavHudLiveSender.shouldApplyTeardownForTest(
                8L, 8L, true, true, true));
        assertFalse(NavHudLiveSender.shouldApplyTeardownForTest(
                8L, 8L, false, true, false));
    }

    @Test
    public void TeardownRetriesOnlyWhileAuthorizedIdleLifecycleIsCurrent() {
        assertTrue(NavHudLiveSender.shouldRetryTeardownForTest(
                8L, 8L, false, true, true, false, false));
        assertFalse(NavHudLiveSender.shouldRetryTeardownForTest(
                8L, 9L, false, true, true, false, false));
        assertFalse(NavHudLiveSender.shouldRetryTeardownForTest(
                8L, 8L, true, true, true, false, false));
        assertFalse(NavHudLiveSender.shouldRetryTeardownForTest(
                8L, 8L, false, false, false, false, false));
        assertFalse(NavHudLiveSender.shouldRetryTeardownForTest(
                8L, 8L, false, true, true, true, true));
    }

    @Test
    public void FirstFrameStartsAReplacementLifecycleWhenStartCallbackWasMissing() {
        assertTrue(NavHudLiveSender.shouldAdvanceTbtLifecycleForTest(false));
        assertFalse(NavHudLiveSender.shouldAdvanceTbtLifecycleForTest(true));
    }

    @Test
    public void TbtRuntimeKeepsGMapsRegistrationAliveWithoutWazeEarlyBind() {
        assertTrue(HudRuntimeSupervisor.shouldKeepTbtRuntimeForTest(
                true, true, false, false, false));
        assertFalse(HudRuntimeSupervisor.shouldKeepTbtRuntimeForTest(
                true, false, false, false, false));
        assertTrue(HudRuntimeSupervisor.shouldKeepTbtRuntimeForTest(
                true, false, false, true, false));
        assertFalse(HudRuntimeSupervisor.shouldKeepTbtRuntimeForTest(
                true, true, true, false, false));
    }

    @Test
    public void RemainingActiveRouteTakesTbtAfterOwnerEnds() {
        assertTrue(NavHudLiveSender.selectRemainingTbtOwnerForTest(
                GMapsDirectChannel.PACKAGE_NAME, true, false, "com.waze", 10L, 20L)
                .equals("com.waze"));
        assertTrue(NavHudLiveSender.selectRemainingTbtOwnerForTest(
                "com.waze", false, true, GMapsDirectChannel.PACKAGE_NAME, 10L, 20L)
                .equals(GMapsDirectChannel.PACKAGE_NAME));
        assertTrue(NavHudLiveSender.selectRemainingTbtOwnerForTest(
                "com.waze", false, false, GMapsDirectChannel.PACKAGE_NAME,
                10L, 20L).isEmpty());
        assertTrue(NavHudLiveSender.selectRemainingTbtOwnerForTest(
                "", true, true, "", 10L, 20L)
                .equals(GMapsDirectChannel.PACKAGE_NAME));
    }

    @Test
    public void ManualStopRestoresTheCurrentSurfaceFrameBeforeClusterFallback() {
        DirectTbtFrame surface = DirectTbtFrame.empty();
        DirectTbtFrame cluster = DirectTbtFrame.empty();

        assertTrue(NavHudLiveSender.selectWazeRestoreFrameForTest(
                true, surface, true, cluster, true) == surface);
        assertTrue(NavHudLiveSender.selectWazeRestoreFrameForTest(
                true, surface, false, cluster, true) == cluster);
        assertTrue(NavHudLiveSender.selectWazeRestoreFrameForTest(
                false, surface, true, cluster, true) == cluster);
        assertTrue(NavHudLiveSender.selectWazeRestoreFrameForTest(
                true, surface, false, cluster, false) == null);
    }

    @Test
    public void AdministrativeTbtStopUsesPublisherOwnerGeneration() {
        assertTrue(NavHudLiveSender.tbtOwnerGenerationForTest(
                "com.waze", "com.waze", 17L, 19L) == 17L);
        assertTrue(NavHudLiveSender.tbtOwnerGenerationForTest(
                "com.waze", GMapsDirectChannel.PACKAGE_NAME, 17L, 19L) == 19L);
    }

    @Test
    public void FinalClearRecoveryHasABoundedTimeout() {
        assertFalse(HudOutputCoordinator.finalClearTimedOutForTest(0L, 10_000L));
        assertFalse(HudOutputCoordinator.finalClearTimedOutForTest(1_000L, 6_999L));
        assertTrue(HudOutputCoordinator.finalClearTimedOutForTest(1_000L, 7_000L));
    }

    @Test
    public void AdministrativeStopCannotClearAnotherDirectOwner() {
        assertTrue(HudOutputCoordinator.shouldClearForAdministrativeStopForTest(
                "com.waze", "com.waze"));
        assertTrue(HudOutputCoordinator.shouldClearForAdministrativeStopForTest(
                "", "com.waze"));
        assertFalse(HudOutputCoordinator.shouldClearForAdministrativeStopForTest(
                GMapsDirectChannel.PACKAGE_NAME, "com.waze"));
    }
}
