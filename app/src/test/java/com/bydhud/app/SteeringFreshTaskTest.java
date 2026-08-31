package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Executes fresh-task and lifecycle decisions, with no Android or cached task admission. */
public final class SteeringFreshTaskTest {
    @Test
    public void freshTaskOnMainOrOwnedDashboardCanToggleForAnySelectedApp() {
        for (String packageName : new String[] {"com.waze", GMapsDirectChannel.PACKAGE_NAME,
                "com.example.player"}) {
            for (int displayId : new int[] {0, 7}) {
                NavAppDisplayState task = new NavAppDisplayState(packageName, 42, displayId, false, "ok");
                assertTrue(SteeringTransferPolicy.canToggleTask(task,
                        DashboardProjectionPolicy.classifyObservedDisplay(
                                packageName, task, packageName, 7)));
            }
        }
    }

    @Test
    public void missingTaskFailedQueryAndTimeoutCannotMoveButConfiguredKeyStillMatches() {
        for (String status : new String[] {"task missing", "check failed", "timeout"}) {
            NavAppDisplayState task = new NavAppDisplayState(
                    "com.waze", -1, NavAppDisplayState.DISPLAY_UNKNOWN, false, status);
            assertFalse(canToggle(task, "com.waze", 7));
            assertTrue(SteeringTransferPolicy.isMappedKey(305, 305));
        }
        assertFalse(SteeringTransferPolicy.canToggleTask(null,
                DashboardProjectionPolicy.ObservedDisplay.MAIN));
    }

    @Test
    public void unknownForeignOrNoLongerOwnedDisplayCannotMove() {
        assertFalse(canToggle(new NavAppDisplayState("com.waze", 42, -1, true, "unknown"),
                "com.waze", 7));
        assertFalse(canToggle(new NavAppDisplayState("com.waze", 42, 8, true, "foreign"),
                "com.waze", 7));
        assertFalse(canToggle(new NavAppDisplayState("com.waze", 42, 7, true, "owner gone"),
                "", -1));
        assertFalse(canToggle(new NavAppDisplayState("com.waze", 42, 7, true, "foreign owner"),
                GMapsDirectChannel.PACKAGE_NAME, 7));
    }

    @Test
    public void latestUiMoveOrReturnObservationDeterminesTheNextSteeringDirection() {
        NavAppDisplayState onMain = new NavAppDisplayState("com.waze", 42, 0, true, "returned");
        NavAppDisplayState onDashboard = new NavAppDisplayState("com.waze", 42, 7, false, "moved");
        assertTrue(DashboardProjectionPolicy.classifyObservedDisplay("com.waze", onMain, "", -1)
                == DashboardProjectionPolicy.ObservedDisplay.MAIN);
        assertTrue(DashboardProjectionPolicy.classifyObservedDisplay("com.waze", onDashboard, "com.waze", 7)
                == DashboardProjectionPolicy.ObservedDisplay.DASHBOARD);
        assertTrue(DashboardProjectionPolicy.classifyObservedDisplay("com.waze", onMain, "", -1)
                == DashboardProjectionPolicy.ObservedDisplay.MAIN);
    }

    @Test
    public void currentBindingAndActiveServiceCanDispatch() {
        assertTrue(SteeringTransferPolicy.isRequestCurrent(true, false, 4L, 4L, 9L, 9L));
    }

    @Test
    public void shutdownOrDisconnectedServiceBlocksMoveWithoutChangingKeyConsumption() {
        assertFalse(SteeringTransferPolicy.isRequestCurrent(true, true, 4L, 4L, 9L, 9L));
        assertFalse(SteeringTransferPolicy.isRequestCurrent(false, false, 4L, 4L, 9L, 9L));
        assertTrue(SteeringTransferPolicy.isMappedKey(305, 305));
    }

    @Test
    public void interruptShutdownResumeOrReconnectDuringQueryInvalidatesOldRequest() {
        assertFalse(SteeringTransferPolicy.isRequestCurrent(true, false, 4L, 5L, 9L, 9L));
        assertTrue(SteeringTransferPolicy.isRequestCurrent(true, false, 5L, 5L, 9L, 9L));
    }

    @Test
    public void keyResetAppResetAndSamePackageProfileChangeInvalidateOldRequest() {
        assertFalse(SteeringTransferPolicy.isRequestCurrent(true, false, 4L, 4L, 9L, 10L));
        assertTrue(SteeringTransferPolicy.isRequestCurrent(true, false, 4L, 4L, 10L, 10L));
    }

    @Test
    public void noSelectedAppCannotMoveButDoesNotUnassignTheKey() {
        NavAppDisplayState missing = new NavAppDisplayState("", -1, -1, false, "empty package");
        assertFalse(SteeringTransferPolicy.canToggleTask(missing,
                DashboardProjectionPolicy.classifyObservedDisplay("", missing, "", -1)));
        assertTrue(SteeringTransferPolicy.isMappedKey(305, 305));
    }

    private static boolean canToggle(NavAppDisplayState task, String owner, int ownedDisplay) {
        return SteeringTransferPolicy.canToggleTask(task,
                DashboardProjectionPolicy.classifyObservedDisplay("com.waze", task, owner, ownedDisplay));
    }
}
