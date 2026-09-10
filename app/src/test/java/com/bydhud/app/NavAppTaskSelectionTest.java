package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;

public final class NavAppTaskSelectionTest {
    @Test
    public void visibleTaskWinsRegardlessOfDumpsysOrder() {
        assertVisibleMainTask(hiddenDashboardTask() + visibleMainTask());
        assertVisibleMainTask(visibleMainTask() + hiddenDashboardTask());
    }

    @Test
    public void globalFocusedSummaryCannotReplaceHiddenOwnedTaskOrInheritLastDisplay() {
        String dumpsys = "Display #8 (activities from top to bottom):\n"
                + "  * Task{abc #28 type=standard A=101:com.waze U=0 visible=false}\n"
                + "    mResumedActivity: ActivityRecord{abc com.waze/.MainActivity visible=false}\n"
                + "Display #4 (activities from top to bottom):\n"
                + "ActivityTaskSupervisor state:\n"
                + "  topDisplayFocusedRootTask=Task{def #28 type=standard A=101:com.waze"
                + " U=0 visible=true}\n";

        NavAppDisplayState byPackage =
                NavAppDisplayController.parseTaskForTest("com.waze", dumpsys);
        NavAppDisplayState byTask =
                NavAppDisplayController.parseTaskIdForTest("com.waze", 28, dumpsys);

        assertEquals(28, byPackage.taskId);
        assertEquals(8, byPackage.displayId);
        assertFalse(byPackage.visible);
        assertEquals(8, byTask.displayId);
        assertFalse(byTask.visible);
        assertTrue(NavAppTaskScanner.isGlobalTaskSummary(
                "  topDisplayFocusedRootTask=Task{def #28 com.waze visible=true}"));
        assertTrue(NavAppTaskScanner.parseTaskHeader(
                "  topDisplayFocusedRootTask=Task{def #28 com.waze visible=true}", 4) == null);
    }

    @Test
    public void multipleDisplaysAndSummaryTrailerKeepRealVisibleTaskAuthoritative() {
        String dumpsys = "Display #0 (activities from top to bottom):\n"
                + "  * Task{aaa #11 A=101:com.example.other visible=true}\n"
                + "Display #6 (activities from top to bottom):\n"
                + "  * Task{bbb #12 A=101:com.waze visible=true}\n"
                + "    ActivityRecord{bbb com.waze/.MainActivity visible=true}\n"
                + "Display #9 (activities from top to bottom):\n"
                + "  * Task{ccc #19 A=101:com.waze visible=false}\n"
                + "ActivityTaskSupervisor state:\n"
                + "  topDisplayFocusedRootTask=Task{ddd #12 A=101:com.waze visible=false}\n";

        NavAppDisplayState state =
                NavAppDisplayController.parseTaskForTest("com.waze", dumpsys);

        assertEquals(12, state.taskId);
        assertEquals(6, state.displayId);
        assertTrue(state.visible);
    }

    @Test
    public void sharedSelectionRuleOnlyReplacesHiddenWithVisible() {
        assertTrue(NavAppTaskScanner.shouldReplaceTaskSelection(false, false, false));
        assertTrue(NavAppTaskScanner.shouldReplaceTaskSelection(true, false, true));
        assertFalse(NavAppTaskScanner.shouldReplaceTaskSelection(true, true, false));
        assertFalse(NavAppTaskScanner.shouldReplaceTaskSelection(true, true, true));
    }

    @Test
    public void runtimeStatusKeepsLastSuccessfulTaskScanDuringRefresh() {
        assertTrue(snapshot("task", "ok").hasAuthoritativeTaskState());
        assertFalse(snapshot("process", "initial").hasAuthoritativeTaskState());
        assertFalse(snapshot("process", "adb unavailable").hasAuthoritativeTaskState());
        assertFalse(snapshot("task", "error").hasAuthoritativeTaskState());
    }

    @Test
    public void failedRefreshDoesNotReplaceLastAuthoritativeTaskState() {
        NavAppTaskScanner.Snapshot current = snapshot("task", "ok");
        NavAppTaskScanner.Snapshot failed = snapshot("process", "adb unavailable");

        assertTrue(current == NavAppTaskScanner.preferredSnapshotForTest(current, failed));
        assertTrue(failed == NavAppTaskScanner.preferredSnapshotForTest(null, failed));
    }

    @Test
    public void completedBackgroundScanReplacesStaleActivityErrorBeforeUiOpens() {
        assertEquals("", MainActivity.appScanStatusForTest(
                false, "failed: stale", snapshot("task", "ok")));
        assertEquals("adb unavailable", MainActivity.appScanStatusForTest(
                false, "failed: stale", snapshot("process", "adb unavailable")));
        assertEquals("scanning", MainActivity.appScanStatusForTest(
                true, "scanning", snapshot("task", "ok")));
    }

    private static NavAppTaskScanner.Snapshot snapshot(String source, String status) {
        return new NavAppTaskScanner.Snapshot(
                Collections.<NavAppTaskScanner.Row>emptyList(),
                1L,
                "00:00:01",
                source,
                status);
    }

    private static void assertVisibleMainTask(String dumpsys) {
        NavAppDisplayState state = NavAppDisplayController.parseTaskForTest("com.waze", dumpsys);

        assertEquals(42, state.taskId);
        assertEquals(0, state.displayId);
        assertTrue(state.visible);
    }

    private static String hiddenDashboardTask() {
        return "  * Task{abc #19 type=standard A=101:com.waze U=0 visible=false displayId=7}\n"
                + "    mResumedActivity: ActivityRecord{abc com.waze/.MainActivity visible=false}\n";
    }

    private static String visibleMainTask() {
        return "  * Task{def #42 type=standard A=101:com.waze U=0 visible=true displayId=0}\n"
                + "    mResumedActivity: ActivityRecord{def com.waze/.MainActivity visible=true}\n";
    }
}
