package com.bydhud.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateHintPresentationPolicyTest {
    private val available = AppUpdateManager.CheckResult.Available(
        AppUpdateManager.UpdateInfo("9.0.0", "https://github.com/fixture.apk", "notes")
    )

    @Test fun cachedResultRecreationMinimizeAndEnableToggleNeverReplay() {
        val policy = UpdateHintPresentationPolicy()
        val snapshot = AppUpdateManager.Snapshot(available, resultId = 1L)
        assertTrue(policy.onSnapshot(snapshot, true) is UpdateHintPresentationDecision.Show)
        assertTrue(policy.setOwnUiVisible(true) is UpdateHintPresentationDecision.Release)
        assertEquals(UpdateHintPresentationDecision.None, policy.onSnapshot(snapshot, true))
        assertEquals(UpdateHintPresentationDecision.None, policy.setOwnUiVisible(false))
        assertEquals(UpdateHintPresentationDecision.None, policy.onSnapshot(snapshot, false))
        assertEquals(UpdateHintPresentationDecision.None, policy.onSnapshot(snapshot, true))
    }

    @Test fun visibleUiConsumesEventButLaterCheckOfSameVersionMayNotify() {
        val policy = UpdateHintPresentationPolicy()
        policy.setOwnUiVisible(true)
        assertEquals(
            UpdateHintPresentationDecision.None,
            policy.onSnapshot(AppUpdateManager.Snapshot(available, dialogRequested = true, resultId = 4L), true)
        )
        policy.setOwnUiVisible(false)
        assertTrue(policy.onSnapshot(AppUpdateManager.Snapshot(available, resultId = 5L), true)
            is UpdateHintPresentationDecision.Show)
    }

    @Test fun closeTimeoutOrInvalidationReleasesOnlyTheMatchingActiveIdentity() {
        val policy = UpdateHintPresentationPolicy()
        policy.onSnapshot(AppUpdateManager.Snapshot(available, resultId = 7L), true)
        assertEquals(UpdateHintPresentationDecision.None, policy.invalidate(6L, "stale"))
        val release = policy.invalidate(7L, "offer-dismissed")
        assertTrue(release is UpdateHintPresentationDecision.Release)
        assertEquals(7L, (release as UpdateHintPresentationDecision.Release).resultId)
        assertEquals(UpdateHintPresentationDecision.None,
            policy.onSnapshot(AppUpdateManager.Snapshot(available, resultId = 7L), true))
    }

    @Test fun nonAvailableAndDisabledEventsAreHandledWithoutHistoricalReplay() {
        val policy = UpdateHintPresentationPolicy()
        assertEquals(UpdateHintPresentationDecision.None,
            policy.onSnapshot(AppUpdateManager.Snapshot(AppUpdateManager.CheckResult.UpToDate, resultId = 1L), true))
        assertEquals(UpdateHintPresentationDecision.None,
            policy.onSnapshot(AppUpdateManager.Snapshot(available, resultId = 2L), false))
        assertEquals(UpdateHintPresentationDecision.None,
            policy.onSnapshot(AppUpdateManager.Snapshot(available, resultId = 2L), true))
        assertTrue(policy.onSnapshot(AppUpdateManager.Snapshot(available, resultId = 3L), true)
            is UpdateHintPresentationDecision.Show)
    }

    @Test fun newerNonAvailableResultInvalidatesThePriorVisibleHint() {
        val policy = UpdateHintPresentationPolicy()
        assertTrue(policy.onSnapshot(AppUpdateManager.Snapshot(available, resultId = 10L), true)
            is UpdateHintPresentationDecision.Show)
        val release = policy.onSnapshot(
            AppUpdateManager.Snapshot(AppUpdateManager.CheckResult.UpToDate, resultId = 11L), true)
        assertTrue(release is UpdateHintPresentationDecision.Release)
        assertEquals(10L, (release as UpdateHintPresentationDecision.Release).resultId)
        assertEquals(UpdateHintPresentationDecision.None,
            policy.onSnapshot(AppUpdateManager.Snapshot(AppUpdateManager.CheckResult.UpToDate, resultId = 11L), true))
        assertTrue(policy.onSnapshot(AppUpdateManager.Snapshot(available, resultId = 12L), true)
            is UpdateHintPresentationDecision.Show)
    }
}
