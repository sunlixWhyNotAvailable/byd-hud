package com.bydhud.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PressFeedbackTrackerTest {
    @Test
    fun releaseKeepsVisualStateUntilCurrentGenerationClears() {
        val tracker = PressFeedbackTracker(90L)
        val press = Any()

        tracker.press(press)
        val generation = tracker.release(press)

        assertTrue(tracker.visualPressed)
        assertNotNull(generation)
        assertTrue(tracker.clearIfCurrent(requireNotNull(generation)))
        assertFalse(tracker.visualPressed)
    }

    @Test
    fun newerPressInvalidatesOlderReleaseTimer() {
        val tracker = PressFeedbackTracker(90L)
        val first = Any()
        val second = Any()

        tracker.press(first)
        val staleGeneration = requireNotNull(tracker.release(first))
        tracker.press(second)

        assertFalse(tracker.clearIfCurrent(staleGeneration))
        assertTrue(tracker.visualPressed)
        val currentGeneration = requireNotNull(tracker.release(second))
        assertTrue(tracker.clearIfCurrent(currentGeneration))
        assertFalse(tracker.visualPressed)
    }

    @Test
    fun cancelSharesHoldAndUnmatchedCompletionCannotFabricateFeedback() {
        val tracker = PressFeedbackTracker(90L)
        val press = Any()

        assertNull(tracker.release(Any()))
        assertFalse(tracker.visualPressed)
        tracker.press(press)
        val generation = requireNotNull(tracker.cancel(press))
        assertTrue(tracker.visualPressed)
        assertTrue(tracker.clearIfCurrent(generation))
        assertFalse(tracker.visualPressed)
    }

    @Test
    fun multiplePressesHoldUntilTheLastReleaseAndResetFencesLifecycleTimer() {
        val tracker = PressFeedbackTracker(90L)
        val first = Any()
        val second = Any()

        tracker.press(first)
        tracker.press(second)
        assertNull(tracker.release(first))
        assertTrue(tracker.visualPressed)
        val generation = requireNotNull(tracker.release(second))
        tracker.reset()

        assertFalse(tracker.visualPressed)
        assertFalse(tracker.clearIfCurrent(generation))
    }

    @Test
    fun zeroHoldRetainsLegacyImmediateReleaseState() {
        val tracker = PressFeedbackTracker(0L)
        val press = Any()

        tracker.press(press)
        assertNull(tracker.release(press))
        assertFalse(tracker.visualPressed)
    }
}
