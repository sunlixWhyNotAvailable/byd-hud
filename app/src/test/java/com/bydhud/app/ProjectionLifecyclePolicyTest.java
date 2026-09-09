package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ProjectionLifecyclePolicyTest {
    @Test
    public void reuseRequiresEveryRetainedResourceToRemainValidAndAttached() {
        assertTrue(ProjectionLifecyclePolicy.canReuseResources(true, true, true, true));
        assertFalse(ProjectionLifecyclePolicy.canReuseResources(false, true, true, true));
        assertFalse(ProjectionLifecyclePolicy.canReuseResources(true, false, true, true));
        assertFalse(ProjectionLifecyclePolicy.canReuseResources(true, true, false, true));
        assertFalse(ProjectionLifecyclePolicy.canReuseResources(true, true, true, false));
    }

    @Test
    public void returnCanIdleOnlyTheMatchingActiveGeneration() {
        assertTrue(ProjectionLifecyclePolicy.canCompleteReturn(
                true, "com.waze", 7, "com.waze", 7));
        assertFalse(ProjectionLifecyclePolicy.canCompleteReturn(
                true, "com.waze", 8, "com.waze", 7));
        assertFalse(ProjectionLifecyclePolicy.canCompleteReturn(
                true, "com.waze", 7, "com.google.android.apps.maps", 7));
        assertFalse(ProjectionLifecyclePolicy.canCompleteReturn(
                false, "", 7, "com.waze", 7));
    }

    @Test
    public void revealRequiresMatchingOwnerTokenPackageAndVirtualDisplay() {
        assertTrue(ProjectionLifecyclePolicy.canReveal(
                true, true, true, "com.waze", 8, 41L, "com.waze", 8, 41L));
        assertFalse(ProjectionLifecyclePolicy.canReveal(
                true, true, true, "com.waze", 8, 42L, "com.waze", 8, 41L));
        assertFalse(ProjectionLifecyclePolicy.canReveal(
                true, true, true, "com.waze", 9, 41L, "com.waze", 8, 41L));
        assertFalse(ProjectionLifecyclePolicy.canReveal(
                true, true, true, "com.waze", 8, 41L,
                "com.google.android.apps.maps", 8, 41L));
        assertFalse(ProjectionLifecyclePolicy.canReveal(
                true, true, false, "com.waze", 8, 41L, "com.waze", 8, 41L));
        assertFalse(ProjectionLifecyclePolicy.canReveal(
                true, false, true, "com.waze", 8, 41L, "com.waze", 8, 41L));
    }

    @Test
    public void shutdownReleaseRequiresUnchangedIdleState() {
        assertTrue(ProjectionLifecyclePolicy.canReleaseIdleForShutdown(
                true, false, "", "", 0L));
        assertFalse(ProjectionLifecyclePolicy.canReleaseIdleForShutdown(
                false, false, "", "", 0L));
        assertFalse(ProjectionLifecyclePolicy.canReleaseIdleForShutdown(
                true, true, "com.waze", "com.waze", 41L));
        assertFalse(ProjectionLifecyclePolicy.canReleaseIdleForShutdown(
                true, false, "", "com.waze", 0L));
        assertFalse(ProjectionLifecyclePolicy.canReleaseIdleForShutdown(
                true, false, "", "", 41L));
    }

    @Test
    public void requestedResizeRejectsRolledBackOldGeometry() {
        assertTrue(ProjectionLifecyclePolicy.requestedGeometrySucceeded(
                true, true,
                576, 540, 1152, 1080, 1330, 90,
                576, 540, 1152, 1080, 1330, 90));
        assertFalse(ProjectionLifecyclePolicy.requestedGeometrySucceeded(
                false, true,
                1920, 540, 1920, 540, 0, 90,
                576, 540, 1152, 1080, 1330, 90));
        assertFalse(ProjectionLifecyclePolicy.requestedGeometrySucceeded(
                true, false,
                576, 540, 1152, 1080, 1330, 90,
                576, 540, 1152, 1080, 1330, 90));
    }

    @Test
    public void failedResizeRetainsIdleUnlessAVisibleOwnerOrRecoveryMustWin() {
        assertTrue(ProjectionLifecyclePolicy.failedRequestTransition(true, false)
                == ProjectionLifecyclePolicy.FailedRequestTransition.RETAIN_IDLE);
        assertTrue(ProjectionLifecyclePolicy.failedRequestTransition(true, true)
                == ProjectionLifecyclePolicy.FailedRequestTransition.KEEP_VISIBLE_OWNER);
        assertTrue(ProjectionLifecyclePolicy.failedRequestTransition(false, true)
                == ProjectionLifecyclePolicy.FailedRequestTransition.RECOVER_INVALID_GEOMETRY);
    }

    @Test
    public void invalidGeometryRecoveryRequiresExactOwnerAndBothGenerations() {
        assertTrue(ProjectionLifecyclePolicy.matchesInvalidRecoveryOwner(
                true, false, "com.waze", 12, 44L, "com.waze", 12, 44L));
        assertFalse(ProjectionLifecyclePolicy.matchesInvalidRecoveryOwner(
                true, true, "com.waze", 12, 44L, "com.waze", 12, 44L));
        assertFalse(ProjectionLifecyclePolicy.matchesInvalidRecoveryOwner(
                true, false, "com.waze", 13, 44L, "com.waze", 12, 44L));
        assertFalse(ProjectionLifecyclePolicy.matchesInvalidRecoveryOwner(
                true, false, "com.waze", 12, 45L, "com.waze", 12, 44L));
    }
}
