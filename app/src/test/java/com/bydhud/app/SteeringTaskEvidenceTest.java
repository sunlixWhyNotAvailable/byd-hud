package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Executable evidence-policy checks; Android publication wiring is tested separately. */
public final class SteeringTaskEvidenceTest {
    @Test
    public void busyAndSelectedMoveScansKeepAcceptedEvidence() {
        for (boolean authoritative : new boolean[] {false, true}) {
            assertEquals(SteeringTransferPolicy.TaskScanAction.KEEP,
                    SteeringTransferPolicy.taskScanAction(
                            true, false, true, authoritative, 4L, 4L, false));
            assertEquals(SteeringTransferPolicy.TaskScanAction.KEEP,
                    SteeringTransferPolicy.taskScanAction(
                            true, false, false, authoritative, 4L, 4L, true));
        }
    }

    @Test
    public void staleSuccessAndFailureCannotReplaceOrEraseNewerEvidence() {
        for (boolean authoritative : new boolean[] {false, true}) {
            assertEquals(SteeringTransferPolicy.TaskScanAction.KEEP,
                    SteeringTransferPolicy.taskScanAction(
                            true, false, false, authoritative, 4L, 5L, false));
        }
    }

    @Test
    public void onlyCurrentSuccessfulScanPublishesAndCurrentFailureClears() {
        assertEquals(SteeringTransferPolicy.TaskScanAction.PUBLISH,
                SteeringTransferPolicy.taskScanAction(
                        true, false, false, true, 4L, 4L, false));
        assertEquals(SteeringTransferPolicy.TaskScanAction.CLEAR,
                SteeringTransferPolicy.taskScanAction(
                        true, false, false, false, 4L, 4L, false));
    }

    @Test
    public void currentShutdownOrInactiveServiceClearsEvenWhileBusy() {
        for (boolean busy : new boolean[] {false, true}) {
            assertEquals(SteeringTransferPolicy.TaskScanAction.CLEAR,
                    SteeringTransferPolicy.taskScanAction(
                            true, true, busy, true, 4L, 4L, busy));
            assertEquals(SteeringTransferPolicy.TaskScanAction.CLEAR,
                    SteeringTransferPolicy.taskScanAction(
                            false, false, busy, true, 4L, 4L, busy));
        }
    }

    @Test
    public void retainedEvidenceStillExpiresAtItsOriginalAcceptanceTime() {
        long acceptedAt = 1_000L;
        assertTrue(SteeringTransferPolicy.hasFreshTaskEvidence(true, acceptedAt, 5_000L, 4_000L));
        assertEquals(SteeringTransferPolicy.TaskScanAction.KEEP,
                SteeringTransferPolicy.taskScanAction(
                        true, false, true, false, 4L, 4L, false));
        assertEquals(SteeringTransferPolicy.TaskScanAction.KEEP,
                SteeringTransferPolicy.taskScanAction(
                        true, false, false, true, 4L, 5L, false));
        assertEquals(SteeringTransferPolicy.TaskScanAction.KEEP,
                SteeringTransferPolicy.taskScanAction(
                        true, false, false, true, 5L, 5L, true));
        assertFalse(SteeringTransferPolicy.hasFreshTaskEvidence(true, acceptedAt, 5_001L, 4_000L));
    }

    @Test
    public void selectedMoveRequiresTheExactNonemptyPackageNotWidgetOwnership() {
        assertTrue(SteeringTransferPolicy.isSelectedMove(true, "com.waze", "com.waze"));
        assertTrue(SteeringTransferPolicy.isSelectedMove(
                true, "com.example.player", "com.example.player"));
        assertFalse(SteeringTransferPolicy.isSelectedMove(false, "com.waze", "com.waze"));
        assertFalse(SteeringTransferPolicy.isSelectedMove(
                true, GMapsDirectChannel.PACKAGE_NAME, "com.waze"));
        assertFalse(SteeringTransferPolicy.isSelectedMove(true, "com.Waze", "com.waze"));
        assertFalse(SteeringTransferPolicy.isSelectedMove(true, " com.waze ", "com.waze"));
        assertFalse(SteeringTransferPolicy.isSelectedMove(true, "", "com.waze"));
        assertFalse(SteeringTransferPolicy.isSelectedMove(true, "", ""));
        assertFalse(SteeringTransferPolicy.isSelectedMove(true, null, "com.waze"));
        assertFalse(SteeringTransferPolicy.isSelectedMove(true, "com.waze", null));
    }

    @Test
    public void missingOrUnknownConfirmationCannotBecomeTaskEvidence() {
        assertNull(SteeringTransferPolicy.confirmedTaskSnapshot(null, 9_000L));
        assertNull(SteeringTransferPolicy.confirmedTaskSnapshot(
                new NavAppDisplayState(null, 42, 0, true, ""), 9_000L));
        assertNull(SteeringTransferPolicy.confirmedTaskSnapshot(
                new NavAppDisplayState("", 42, 0, true, ""), 9_000L));
        assertNull(SteeringTransferPolicy.confirmedTaskSnapshot(
                new NavAppDisplayState("com.waze", -1, 0, true, "task missing"), 9_000L));
        assertNull(SteeringTransferPolicy.confirmedTaskSnapshot(
                new NavAppDisplayState("com.waze", 42,
                        NavAppDisplayState.DISPLAY_UNKNOWN, true, "unknown"), 9_000L));
    }

    @Test
    public void confirmedMainAndDashboardTasksProduceOneExactTaskOnlyRow() {
        for (String packageName : new String[] {
                "com.waze", GMapsDirectChannel.PACKAGE_NAME, "com.example.player"
        }) {
            for (int displayId : new int[] {0, 7}) {
                boolean visible = displayId == 0;
                NavAppTaskScanner.Snapshot snapshot = SteeringTransferPolicy.confirmedTaskSnapshot(
                        new NavAppDisplayState(packageName, 42, displayId, visible, "confirmed"), 9_000L);

                assertNotNull(snapshot);
                assertTrue(snapshot.hasAuthoritativeTaskState());
                assertEquals(9_000L, snapshot.scannedAtMs);
                assertEquals(1, snapshot.rows.size());
                NavAppTaskScanner.Row row = snapshot.rows.get(0);
                assertEquals(packageName, row.packageName);
                assertEquals(42, row.taskId);
                assertEquals(displayId, row.displayId);
                assertEquals(visible, row.visible);
                assertTrue(row.hasTask);
                assertFalse(row.hasProcess);
                assertEquals("", row.processName);
            }
        }
    }
}
