package com.bydhud.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Exercises the production async repair admission decision at its timing boundaries. */
public final class NavRuntimePermissionRepairCooldownTest {
    @Test
    public void firstAttemptAtThirtySecondsIsAllowedWithoutPriorStart() {
        assertEquals(
                NavRuntimePermissionRepair.AsyncRepairAdmission.START,
                NavRuntimePermissionRepair.asyncRepairAdmission(false, 30_000L, 0L, false));
    }

    @Test
    public void repeatBeforeSixtySecondsSkipsButAtSixtySecondsStarts() {
        assertEquals(
                NavRuntimePermissionRepair.AsyncRepairAdmission.COOLDOWN,
                NavRuntimePermissionRepair.asyncRepairAdmission(false, 89_999L, 30_000L, false));
        assertEquals(
                NavRuntimePermissionRepair.AsyncRepairAdmission.START,
                NavRuntimePermissionRepair.asyncRepairAdmission(false, 90_000L, 30_000L, false));
    }

    @Test
    public void activeRepairBlocksBothNormalAndForceAsyncRequests() {
        assertEquals(
                NavRuntimePermissionRepair.AsyncRepairAdmission.RUNNING,
                NavRuntimePermissionRepair.asyncRepairAdmission(true, 30_000L, 0L, false));
        assertEquals(
                NavRuntimePermissionRepair.AsyncRepairAdmission.RUNNING,
                NavRuntimePermissionRepair.asyncRepairAdmission(true, 30_000L, 30_000L, true));
    }

    @Test
    public void forceBypassesCooldownWhenNoRepairIsRunning() {
        assertEquals(
                NavRuntimePermissionRepair.AsyncRepairAdmission.COOLDOWN,
                NavRuntimePermissionRepair.asyncRepairAdmission(false, 89_999L, 30_000L, false));
        assertEquals(
                NavRuntimePermissionRepair.AsyncRepairAdmission.START,
                NavRuntimePermissionRepair.asyncRepairAdmission(false, 89_999L, 30_000L, true));
    }
}
