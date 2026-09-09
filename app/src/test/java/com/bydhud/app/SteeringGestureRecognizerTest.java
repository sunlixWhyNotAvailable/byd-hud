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
        return recognizer.onKey(key, action, 0, false, time, time, false, profile -> matches.add(profile));
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
        recognizer.advance(370, profile -> matches.add(profile));
        modes();
        recognizer.advance(371, profile -> matches.add(profile));
        modes(PRESS_SINGLE);
        assertEquals(Long.MAX_VALUE, recognizer.nextDeadline());
        assertFalse(event(320, 0, 400));
    }

    @Test public void doubleAcceptsExactFirstUpToSecondDownBoundary() {
        configure(294, PRESS_SINGLE, PRESS_DOUBLE);
        event(294, 0, 0); event(294, 1, 50);
        recognizer.advance(350, profile -> matches.add(profile));
        modes();
        event(294, 0, 350);
        recognizer.advance(351, profile -> matches.add(profile));
        modes();
        event(294, 1, 380);
        recognizer.advance(1000, profile -> matches.add(profile));
        modes(PRESS_DOUBLE);
    }

    @Test public void oneMillisecondOutsideDoubleWindowEmitsTwoSingles() {
        configure(294, PRESS_SINGLE, PRESS_DOUBLE);
        event(294, 0, 0); event(294, 1, 50);
        recognizer.advance(350, profile -> matches.add(profile));
        modes();
        event(294, 0, 351); event(294, 1, 390);
        modes(PRESS_SINGLE);
        recognizer.advance(690, profile -> matches.add(profile));
        modes(PRESS_SINGLE);
        recognizer.advance(691, profile -> matches.add(profile));
        modes(PRESS_SINGLE, PRESS_SINGLE);
    }

    @Test public void holdHasOneActionAndNeverFallsBackToSingle() {
        configure(294, PRESS_SINGLE, PRESS_HOLD, PRESS_DOUBLE);
        event(294, 0, 0);
        recognizer.advance(400, profile -> matches.add(profile));
        assertTrue(recognizer.onKey(294, 0, 3, false, 600, 600, false, profile -> matches.add(profile)));
        event(294, 1, 800);
        recognizer.advance(2000, profile -> matches.add(profile));
        modes(PRESS_HOLD);
    }

    @Test public void assignedStreamsAreConsumedEvenWhenClassifiedGestureHasNoAction() {
        configure(294, PRESS_SINGLE);
        assertTrue(event(294, 0, 0)); assertTrue(event(294, 1, 50));
        assertTrue(event(294, 0, 150)); assertTrue(event(294, 1, 200));
        modes(); // Double on a Single-only button has no action.

        configure(294, PRESS_HOLD);
        assertTrue(event(294, 0, 300)); assertTrue(event(294, 1, 350));
        recognizer.advance(651, profile -> matches.add(profile));
        modes(); // Single on a Hold-only button has no action.

        configure(294, PRESS_DOUBLE);
        assertTrue(event(294, 0, 700));
        recognizer.advance(1100, profile -> matches.add(profile));
        assertTrue(event(294, 1, 1150));
        recognizer.advance(2000, profile -> matches.add(profile));
        modes();
    }

    @Test public void unassignedStreamPassesThroughWithoutArmingDeadlines() {
        assertFalse(event(294, 0, 0));
        assertFalse(event(294, 1, 50));
        assertEquals(Long.MAX_VALUE, recognizer.nextDeadline());
        recognizer.advance(1000, profile -> matches.add(profile));
        modes();
    }

    @Test public void shortThenHeldWithinWindowEmitsOnlyHold() {
        configure(294, PRESS_SINGLE, PRESS_HOLD, PRESS_DOUBLE);
        event(294, 0, 0); event(294, 1, 50);
        event(294, 0, 150); event(294, 1, 600);
        recognizer.advance(1000, profile -> matches.add(profile));
        modes(PRESS_HOLD);
    }

    @Test public void tripleShortPressesEmitDoubleThenPendingSingle() {
        configure(294, PRESS_SINGLE, PRESS_HOLD, PRESS_DOUBLE);
        event(294, 0, 0); event(294, 1, 50);
        event(294, 0, 150); event(294, 1, 180);
        event(294, 0, 250); event(294, 1, 280);
        modes(PRESS_DOUBLE);
        recognizer.advance(580, profile -> matches.add(profile));
        modes(PRESS_DOUBLE);
        recognizer.advance(581, profile -> matches.add(profile));
        modes(PRESS_DOUBLE, PRESS_SINGLE);
    }

    @Test public void everyOrdinaryKeyUsesTheSameHoldDeadlineWithoutNativeFeedback() {
        for (int key : new int[] {294, 304, 305, 87, 88, 353, 313, 1000}) {
            matches.clear();
            configure(key, PRESS_SINGLE, PRESS_HOLD, PRESS_DOUBLE);
            event(key, 0, 1000);
            assertEquals(1400, recognizer.nextDeadline());
            recognizer.advance(1399, profile -> matches.add(profile));
            modes();
            recognizer.advance(1400, profile -> matches.add(profile));
            modes(PRESS_HOLD);
            event(key, 0, 1450); // Duplicate DOWN cannot restart or emit again.
            recognizer.onKey(key, 0, 1, false, 1500, 1500, false, profile -> matches.add(profile));
            event(key, 1, 1600);
            recognizer.advance(2000, profile -> matches.add(profile));
            modes(PRESS_HOLD);
            assertEquals(Long.MAX_VALUE, recognizer.nextDeadline());
        }
    }

    @Test public void knownFamiliesAlsoRecognizeHoldOnUpWhenTheTimerIsDelayed() {
        for (int key : new int[] {304, 305, 87, 88}) {
            matches.clear();
            configure(key, PRESS_SINGLE, PRESS_HOLD, PRESS_DOUBLE);
            event(key, 0, 0); event(key, 1, 400);
            recognizer.advance(1000, profile -> matches.add(profile));
            modes(PRESS_HOLD);
        }
    }

    @Test public void nativeAliasDownEmitsImmediateHoldAndCompleteCyclesRepeat() {
        for (int[] pair : new int[][] {{305, 306}, {304, 312}, {88, 303}, {87, 302}}) {
            matches.clear();
            configure(pair[0], PRESS_SINGLE, PRESS_HOLD, PRESS_DOUBLE);
            assertTrue(event(pair[1], 0, 8_074_946));
            modes(PRESS_HOLD);
            assertTrue(recognizer.onKey(pair[1], 0, 3, false,
                    8_074_946, 8_074_946, false, profile -> matches.add(profile)));
            assertTrue(event(pair[1], 1, 8_074_947));
            assertTrue(event(pair[1], 0, 8_075_000));
            assertTrue(event(pair[1], 1, 8_075_001));
            modes(PRESS_HOLD, PRESS_HOLD);
            assertEquals(Long.MAX_VALUE, recognizer.nextDeadline());
        }
    }

    @Test public void nativeBeforeTimerSharesTheOrdinaryPressAndEmitsOnce() {
        for (int[] pair : new int[][] {{305, 306}, {304, 312}, {88, 303}, {87, 302}}) {
            matches.clear();
            configure(pair[0], PRESS_SINGLE, PRESS_HOLD, PRESS_DOUBLE);
            event(pair[0], 0, 0);
            assertTrue(event(pair[1], 0, 100));
            modes(PRESS_HOLD);
            assertTrue(event(pair[1], 1, 101));
            recognizer.advance(400, profile -> matches.add(profile));
            modes(PRESS_HOLD);
            event(pair[0], 1, 500);
            recognizer.advance(2000, profile -> matches.add(profile));
            modes(PRESS_HOLD);
        }
    }

    @Test public void timerBeforeNativeKeepsOneHoldAndTheNextOrdinaryPressStartsNormally() {
        for (int[] pair : new int[][] {{305, 306}, {304, 312}, {88, 303}, {87, 302}}) {
            matches.clear();
            configure(pair[0], PRESS_SINGLE, PRESS_HOLD, PRESS_DOUBLE);
            event(pair[0], 0, 0);
            recognizer.advance(400, profile -> matches.add(profile));
            modes(PRESS_HOLD);
            event(pair[0], 1, 500);
            event(pair[1], 0, 550); event(pair[1], 1, 551); // One late native packet.
            event(pair[0], 0, 600);
            event(pair[1], 0, 650); event(pair[1], 1, 651); // Belongs to the new press.
            recognizer.advance(1000, profile -> matches.add(profile));
            modes(PRESS_HOLD, PRESS_HOLD);
            event(pair[0], 1, 1100);
        }
    }

    @Test public void nativeAfterTimerBeforeReleaseCannotDuplicateAndDoesNotArmACooldown() {
        configure(305, PRESS_SINGLE, PRESS_HOLD, PRESS_DOUBLE);
        event(305, 0, 0);
        recognizer.advance(400, profile -> matches.add(profile));
        event(306, 0, 450); event(306, 1, 451);
        event(305, 1, 500);
        event(306, 0, 600); event(306, 1, 601);
        modes(PRESS_HOLD, PRESS_HOLD);
    }

    @Test public void lateNativeGuardExpiresAfterTheExistingDoubleWindow() {
        configure(305, PRESS_HOLD);
        event(305, 0, 0);
        recognizer.advance(400, profile -> matches.add(profile));
        event(305, 1, 500); // Guard is inclusive through 800.
        event(306, 0, 801); event(306, 1, 802);
        modes(PRESS_HOLD, PRESS_HOLD);
    }

    @Test public void expiredTimerHoldSuppressesOneNativeTailThenNativeCyclesResume() {
        configure(305, PRESS_HOLD);
        event(305, 0, 0);
        recognizer.advance(400, profile -> matches.add(profile));
        recognizer.advance(3000, profile -> matches.add(profile));
        assertEquals(Long.MAX_VALUE, recognizer.nextDeadline());

        event(306, 0, 3100); event(306, 1, 3101);
        modes(PRESS_HOLD);
        event(306, 0, 3200); event(306, 1, 3201);
        modes(PRESS_HOLD, PRESS_HOLD);
    }

    @Test public void ordinaryUpAfterExpiredTimerHoldUsesOnlyTheDoubleWindowTailGuard() {
        configure(305, PRESS_SINGLE, PRESS_HOLD, PRESS_DOUBLE);
        event(305, 0, 0);
        recognizer.advance(400, profile -> matches.add(profile));
        recognizer.advance(3000, profile -> matches.add(profile));
        event(305, 1, 3100);
        event(306, 0, 3200); event(306, 1, 3201);
        modes(PRESS_HOLD);
        event(306, 0, 3401); event(306, 1, 3402);
        modes(PRESS_HOLD, PRESS_HOLD);
    }

    @Test public void freshOrdinaryDownReplacesAnExpiredTimerHoldMarker() {
        configure(305, PRESS_HOLD);
        event(305, 0, 0);
        recognizer.advance(400, profile -> matches.add(profile));
        recognizer.advance(3000, profile -> matches.add(profile));
        event(305, 0, 3100);
        event(306, 1, 3150); // Late UP from the previous physical cycle.
        recognizer.advance(3500, profile -> matches.add(profile));
        event(305, 1, 3600);
        modes(PRESS_HOLD, PRESS_HOLD);
    }

    @Test public void lateNativeUpCannotCancelANewerOrdinaryPress() {
        configure(305, PRESS_SINGLE, PRESS_HOLD, PRESS_DOUBLE);
        event(305, 0, 0);
        recognizer.advance(400, profile -> matches.add(profile));
        event(305, 1, 500);
        event(305, 0, 600);
        event(306, 1, 650);
        recognizer.advance(1000, profile -> matches.add(profile));
        event(305, 1, 1100);
        modes(PRESS_HOLD, PRESS_HOLD);
    }

    @Test public void ordinaryDoublesRemainUniversalForAllNativeFamilies() {
        for (int key : new int[] {305, 304, 88, 87}) {
            matches.clear();
            configure(key, PRESS_SINGLE, PRESS_HOLD, PRESS_DOUBLE);
            event(key, 0, 0); event(key, 1, 50);
            event(key, 0, 350); event(key, 1, 390);
            recognizer.advance(1000, profile -> matches.add(profile));
            modes(PRESS_DOUBLE);
        }
    }

    @Test public void nativeClassificationNeverDegradesToAnUnmatchedSingleOrDouble() {
        configure(305, PRESS_SINGLE, PRESS_DOUBLE);
        event(305, 0, 0);
        event(306, 0, 100); event(306, 1, 101);
        event(305, 1, 200);
        recognizer.advance(1000, profile -> matches.add(profile));
        modes();
    }

    @Test public void nativeHoldLogsItsRecognitionOrigin() {
        configure(305, PRESS_HOLD);
        List<String> origins = new ArrayList<>();
        recognizer.onKey(306, 0, 0, false, 8_074_946, 8_074_946, false,
                (profile, origin) -> { matches.add(profile); origins.add(origin); });
        event(306, 1, 8_074_947);
        recognizer.onKey(305, 0, 0, false, 8_075_000, 8_075_000, false,
                (profile, origin) -> { matches.add(profile); origins.add(origin); });
        recognizer.advance(8_075_400,
                (profile, origin) -> { matches.add(profile); origins.add(origin); });
        assertEquals(Arrays.asList(SteeringGestureRecognizer.ORIGIN_NATIVE,
                SteeringGestureRecognizer.ORIGIN_TIMER), origins);
        modes(PRESS_HOLD, PRESS_HOLD);
    }

    @Test public void cancelledRepeatedBusyAndRevisionChangedNativeInputCannotAct() {
        configure(305, PRESS_HOLD);
        assertTrue(recognizer.onKey(306, 0, 1, false, 0, 0, false, profile -> matches.add(profile)));
        assertTrue(recognizer.onKey(306, 0, 0, true, 10, 10, false, profile -> matches.add(profile)));
        assertTrue(recognizer.onKey(306, 1, 0, true, 11, 11, false, profile -> matches.add(profile)));
        assertTrue(recognizer.onKey(306, 0, 0, false, 20, 20, true, profile -> matches.add(profile)));
        assertTrue(event(306, 1, 21));
        event(305, 0, 30);
        recognizer.configure(Collections.singletonList(profile(305, PRESS_HOLD)), 2);
        assertTrue(event(306, 0, 40));
        assertTrue(event(306, 1, 41));
        assertTrue(event(305, 1, 42));
        recognizer.advance(1000, profile -> matches.add(profile));
        modes();
    }

    @Test public void cancelledNativeDownCancelsTheActiveOrdinaryTimer() {
        configure(305, PRESS_HOLD);
        event(305, 0, 0);
        assertTrue(recognizer.onKey(306, 0, 0, true, 100, 100, false, profile -> matches.add(profile)));
        recognizer.advance(400, profile -> matches.add(profile));
        event(305, 1, 500);
        modes();
    }

    @Test public void nativeFamiliesKeepIndependentPressState() {
        recognizer.configure(Arrays.asList(profile(305, PRESS_HOLD), profile(304, PRESS_HOLD),
                profile(88, PRESS_HOLD), profile(87, PRESS_HOLD)), 1);
        for (int alias : new int[] {306, 312, 303, 302}) event(alias, 0, 100 + alias);
        assertEquals(Arrays.asList(305, 304, 88, 87), Arrays.asList(
                matches.get(0).keyCode, matches.get(1).keyCode,
                matches.get(2).keyCode, matches.get(3).keyCode));
        for (int alias : new int[] {306, 312, 303, 302}) event(alias, 1, 200 + alias);
        modes(PRESS_HOLD, PRESS_HOLD, PRESS_HOLD, PRESS_HOLD);
    }

    @Test public void revisionChangeCancelsPendingAndHeldActionsButConsumesTheirTails() {
        configure(294, PRESS_SINGLE, PRESS_HOLD, PRESS_DOUBLE);
        event(294, 0, 0); event(294, 1, 50);
        recognizer.configure(Collections.singletonList(profile(294, PRESS_SINGLE)), 2);
        recognizer.advance(500, profile -> matches.add(profile));
        event(294, 0, 600);
        recognizer.configure(Collections.emptyList(), 3);
        assertTrue(event(294, 1, 650));
        assertFalse(event(294, 0, 700));
        modes();
    }

    @Test public void canceledUpAndLifecycleCancelPendingSingleCannotTriggerAnAction() {
        configure(294, PRESS_SINGLE);
        event(294, 0, 0);
        recognizer.onKey(294, 1, 0, true, 50, 50, false, profile -> matches.add(profile));
        event(294, 0, 100); event(294, 1, 150);
        recognizer.cancel();
        recognizer.advance(1000, profile -> matches.add(profile));
        modes();
    }

    @Test public void busyPressIsNotReplayedWhenWorkerBecomesFree() {
        configure(294, PRESS_SINGLE, PRESS_HOLD, PRESS_DOUBLE);
        assertTrue(recognizer.onKey(294, 0, 0, false, 0, 0, true, profile -> matches.add(profile)));
        event(294, 1, 50);
        recognizer.advance(1000, profile -> matches.add(profile));
        modes();
    }

    @Test public void differentButtonsKeepIndependentDoubleWindows() {
        recognizer.configure(Arrays.asList(profile(294, PRESS_SINGLE), profile(294, PRESS_DOUBLE),
                profile(305, PRESS_SINGLE), profile(305, PRESS_DOUBLE)), 1);
        event(294, 0, 0); event(294, 1, 50);
        event(305, 0, 100); event(305, 1, 150);
        event(294, 0, 200); event(294, 1, 250);
        recognizer.advance(451, profile -> matches.add(profile));
        modes(PRESS_DOUBLE, PRESS_SINGLE);
        assertEquals(294, matches.get(0).keyCode);
        assertEquals(305, matches.get(1).keyCode);
    }

    @Test public void orphanRepeatsAndLostUpHaveBoundedRecovery() {
        configure(294, PRESS_SINGLE);
        assertTrue(recognizer.onKey(294, 0, 1, false, 0, 0, false, profile -> matches.add(profile)));
        event(294, 1, 50);
        event(294, 0, 100);
        recognizer.advance(3100, profile -> matches.add(profile));
        event(294, 1, 3200);
        event(294, 0, 3300); event(294, 1, 3350);
        modes();
        recognizer.advance(3650, profile -> matches.add(profile));
        modes();
        recognizer.advance(3651, profile -> matches.add(profile));
        modes(PRESS_SINGLE);
    }
}
