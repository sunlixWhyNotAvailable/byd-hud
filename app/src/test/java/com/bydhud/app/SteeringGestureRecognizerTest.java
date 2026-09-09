package com.bydhud.app;

import static com.bydhud.app.SteeringTransferPreferences.*;
import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class SteeringGestureRecognizerTest {
    private final SteeringGestureRecognizer recognizer = new SteeringGestureRecognizer(400, 300, 3000);
    private final List<SteeringTransferProfile> matches = new ArrayList<>();

    private SteeringTransferProfile profile(int key, String mode) {
        return new SteeringTransferProfile(key + mode, key, mode, "com.waze", PROFILE_SELECTED);
    }

    private void configure(int key, String... modes) {
        List<SteeringTransferProfile> profiles = new ArrayList<>();
        for (String mode : modes) profiles.add(profile(key, mode));
        recognizer.configure(profiles, recognizer.revision() + 1);
    }

    private boolean event(int key, int action, long time) {
        return recognizer.onKey(key, action, 0, false, time, time, false, matches::add);
    }

    private void modes(String... expected) {
        List<String> actual = new ArrayList<>();
        for (SteeringTransferProfile match : matches) actual.add(match.pressMode);
        assertEquals(Arrays.asList(expected), actual);
    }

    @Test public void singleOnlyStillWaitsForUniversalDoubleWindow() {
        configure(294, PRESS_SINGLE);
        assertTrue(event(294, 0, 0));
        modes();
        assertTrue(event(294, 1, 70));
        modes();
        assertEquals(371, recognizer.nextDeadline());
        recognizer.advance(370, matches::add);
        modes();
        recognizer.advance(371, matches::add);
        modes(PRESS_SINGLE);
        assertEquals(Long.MAX_VALUE, recognizer.nextDeadline());
        assertFalse(event(320, 0, 400));
    }

    @Test public void doubleAcceptsExactFirstUpToSecondDownBoundary() {
        configure(294, PRESS_SINGLE, PRESS_DOUBLE);
        event(294, 0, 0); event(294, 1, 50);
        recognizer.advance(350, matches::add);
        modes();
        event(294, 0, 350);
        recognizer.advance(351, matches::add);
        modes();
        event(294, 1, 380);
        recognizer.advance(1000, matches::add);
        modes(PRESS_DOUBLE);
    }

    @Test public void oneMillisecondOutsideDoubleWindowEmitsTwoSingles() {
        configure(294, PRESS_SINGLE, PRESS_DOUBLE);
        event(294, 0, 0); event(294, 1, 50);
        recognizer.advance(350, matches::add);
        modes();
        event(294, 0, 351); event(294, 1, 390);
        modes(PRESS_SINGLE);
        recognizer.advance(690, matches::add);
        modes(PRESS_SINGLE);
        recognizer.advance(691, matches::add);
        modes(PRESS_SINGLE, PRESS_SINGLE);
    }

    @Test public void holdHasOneActionAndNeverFallsBackToSingle() {
        configure(294, PRESS_SINGLE, PRESS_HOLD, PRESS_DOUBLE);
        event(294, 0, 0);
        recognizer.advance(400, matches::add);
        assertTrue(recognizer.onKey(294, 0, 3, false, 600, 600, false, matches::add));
        event(294, 1, 800);
        recognizer.advance(2000, matches::add);
        modes(PRESS_HOLD);
    }

    @Test public void assignedStreamsAreConsumedEvenWhenClassifiedGestureHasNoAction() {
        configure(294, PRESS_SINGLE);
        assertTrue(event(294, 0, 0)); assertTrue(event(294, 1, 50));
        assertTrue(event(294, 0, 150)); assertTrue(event(294, 1, 200));
        modes(); // Double on a Single-only button has no action.

        configure(294, PRESS_HOLD);
        assertTrue(event(294, 0, 300)); assertTrue(event(294, 1, 350));
        recognizer.advance(651, matches::add);
        modes(); // Single on a Hold-only button has no action.

        configure(294, PRESS_DOUBLE);
        assertTrue(event(294, 0, 700));
        recognizer.advance(1100, matches::add);
        assertTrue(event(294, 1, 1150));
        recognizer.advance(2000, matches::add);
        modes();
    }

    @Test public void unassignedStreamPassesThroughWithoutArmingDeadlines() {
        assertFalse(event(294, 0, 0));
        assertFalse(event(294, 1, 50));
        assertEquals(Long.MAX_VALUE, recognizer.nextDeadline());
        recognizer.advance(1000, matches::add);
        modes();
    }

    @Test public void shortThenHeldWithinWindowEmitsOnlyHold() {
        configure(294, PRESS_SINGLE, PRESS_HOLD, PRESS_DOUBLE);
        event(294, 0, 0); event(294, 1, 50);
        event(294, 0, 150); event(294, 1, 600);
        recognizer.advance(1000, matches::add);
        modes(PRESS_HOLD);
    }

    @Test public void tripleShortPressesEmitDoubleThenPendingSingle() {
        configure(294, PRESS_SINGLE, PRESS_HOLD, PRESS_DOUBLE);
        event(294, 0, 0); event(294, 1, 50);
        event(294, 0, 150); event(294, 1, 180);
        event(294, 0, 250); event(294, 1, 280);
        modes(PRESS_DOUBLE);
        recognizer.advance(580, matches::add);
        modes(PRESS_DOUBLE);
        recognizer.advance(581, matches::add);
        modes(PRESS_DOUBLE, PRESS_SINGLE);
    }

    @Test public void everyOrdinaryKeyUsesTheSameHoldDeadlineWithoutNativeFeedback() {
        for (int key : new int[] {294, 304, 305, 87, 88, 353, 313, 1000}) {
            matches.clear();
            configure(key, PRESS_SINGLE, PRESS_HOLD, PRESS_DOUBLE);
            event(key, 0, 1000);
            assertEquals(1400, recognizer.nextDeadline());
            recognizer.advance(1399, matches::add);
            modes();
            recognizer.advance(1400, matches::add);
            modes(PRESS_HOLD);
            event(key, 0, 1450); // Duplicate DOWN cannot restart or emit again.
            recognizer.onKey(key, 0, 1, false, 1500, 1500, false, matches::add);
            event(key, 1, 1600);
            recognizer.advance(2000, matches::add);
            modes(PRESS_HOLD);
            assertEquals(Long.MAX_VALUE, recognizer.nextDeadline());
        }
    }

    @Test public void knownFamiliesAlsoRecognizeHoldOnUpWhenTheTimerIsDelayed() {
        for (int key : new int[] {304, 305}) {
            matches.clear();
            configure(key, PRESS_SINGLE, PRESS_HOLD, PRESS_DOUBLE);
            event(key, 0, 0); event(key, 1, 400);
            recognizer.advance(1000, matches::add);
            modes(PRESS_HOLD);
        }
    }

    @Test public void nativeAliasAloneNeverBecomesAnyGestureOrArmsATimer() {
        for (int alias : new int[] {306, 312}) {
            matches.clear();
            configure(alias, PRESS_SINGLE, PRESS_HOLD, PRESS_DOUBLE);
            assertTrue(event(alias, 0, 0));
            assertTrue(event(alias, 1, 56));
            assertTrue(event(alias, 0, 100));
            recognizer.advance(500, matches::add);
            assertTrue(event(alias, 1, 600));
            recognizer.advance(1000, matches::add);
            modes();
            assertEquals(Long.MAX_VALUE, recognizer.nextDeadline());
        }
    }

    @Test public void nativeAliasesCannotReleaseCancelOrDuplicateAnOrdinaryHold() {
        for (int[] pair : new int[][] {{305, 306}, {304, 312}}) {
            matches.clear();
            configure(pair[0], PRESS_SINGLE, PRESS_HOLD, PRESS_DOUBLE);
            event(pair[0], 0, 0);
            assertTrue(event(pair[1], 0, 100));
            assertTrue(recognizer.onKey(pair[1], 1, 0, true, 150, 150, false, matches::add));
            assertEquals(400, recognizer.nextDeadline());
            recognizer.advance(400, matches::add);
            modes(PRESS_HOLD);
            event(pair[0], 1, 500);
            event(pair[1], 0, 510); event(pair[1], 1, 520); // Late semantic tail.
            event(pair[0], 0, 600);
            event(pair[1], 1, 650); // Old alias UP cannot end the new press.
            recognizer.advance(1000, matches::add);
            event(pair[0], 1, 1100);
            recognizer.advance(2000, matches::add);
            modes(PRESS_HOLD, PRESS_HOLD);
        }
    }

    @Test public void doubleStillUsesTwoOrdinaryClicksWhenNativeFeedbackAppearsOrDisappears() {
        for (int[] pair : new int[][] {{305, 306}, {304, 312}}) {
            for (boolean nativeFeedback : new boolean[] {false, true}) {
                matches.clear();
                configure(pair[0], PRESS_SINGLE, PRESS_HOLD, PRESS_DOUBLE);
                event(pair[0], 0, 0); event(pair[0], 1, 50);
                if (nativeFeedback) {
                    event(pair[1], 0, 100); event(pair[1], 1, 120);
                }
                recognizer.advance(350, matches::add);
                modes();
                event(pair[0], 0, 350);
                if (nativeFeedback) event(pair[1], 1, 360);
                event(pair[0], 1, 390);
                recognizer.advance(1000, matches::add);
                modes(PRESS_DOUBLE);
            }
        }
    }

    @Test public void revisionChangeCancelsPendingAndHeldActionsButConsumesTheirTails() {
        configure(294, PRESS_SINGLE, PRESS_HOLD, PRESS_DOUBLE);
        event(294, 0, 0); event(294, 1, 50);
        recognizer.configure(Collections.singletonList(profile(294, PRESS_SINGLE)), 2);
        recognizer.advance(500, matches::add);
        event(294, 0, 600);
        recognizer.configure(Collections.emptyList(), 3);
        assertTrue(event(294, 1, 650));
        assertFalse(event(294, 0, 700));
        modes();
    }

    @Test public void canceledUpAndLifecycleCancelPendingSingleCannotTriggerAnAction() {
        configure(294, PRESS_SINGLE);
        event(294, 0, 0);
        recognizer.onKey(294, 1, 0, true, 50, 50, false, matches::add);
        event(294, 0, 100); event(294, 1, 150);
        recognizer.cancel();
        recognizer.advance(1000, matches::add);
        modes();
    }

    @Test public void busyPressIsNotReplayedWhenWorkerBecomesFree() {
        configure(294, PRESS_SINGLE, PRESS_HOLD, PRESS_DOUBLE);
        assertTrue(recognizer.onKey(294, 0, 0, false, 0, 0, true, matches::add));
        event(294, 1, 50);
        recognizer.advance(1000, matches::add);
        modes();
    }

    @Test public void differentButtonsKeepIndependentDoubleWindows() {
        recognizer.configure(Arrays.asList(profile(294, PRESS_SINGLE), profile(294, PRESS_DOUBLE),
                profile(305, PRESS_SINGLE), profile(305, PRESS_DOUBLE)), 1);
        event(294, 0, 0); event(294, 1, 50);
        event(305, 0, 100); event(305, 1, 150);
        event(294, 0, 200); event(294, 1, 250);
        recognizer.advance(451, matches::add);
        modes(PRESS_DOUBLE, PRESS_SINGLE);
        assertEquals(294, matches.get(0).keyCode);
        assertEquals(305, matches.get(1).keyCode);
    }

    @Test public void orphanRepeatsAndLostUpHaveBoundedRecovery() {
        configure(294, PRESS_SINGLE);
        assertTrue(recognizer.onKey(294, 0, 1, false, 0, 0, false, matches::add));
        event(294, 1, 50);
        event(294, 0, 100);
        recognizer.advance(3100, matches::add);
        event(294, 1, 3200);
        event(294, 0, 3300); event(294, 1, 3350);
        modes();
        recognizer.advance(3650, matches::add);
        modes();
        recognizer.advance(3651, matches::add);
        modes(PRESS_SINGLE);
    }
}
