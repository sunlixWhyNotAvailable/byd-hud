package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NavAppTaskSelectionTest {
    @Test
    public void visibleTaskWinsRegardlessOfDumpsysOrder() {
        assertVisibleMainTask(hiddenDashboardTask() + visibleMainTask());
        assertVisibleMainTask(visibleMainTask() + hiddenDashboardTask());
    }

    @Test
    public void sharedSelectionRuleOnlyReplacesHiddenWithVisible() {
        assertTrue(NavAppTaskScanner.shouldReplaceTaskSelection(false, false, false));
        assertTrue(NavAppTaskScanner.shouldReplaceTaskSelection(true, false, true));
        assertFalse(NavAppTaskScanner.shouldReplaceTaskSelection(true, true, false));
        assertFalse(NavAppTaskScanner.shouldReplaceTaskSelection(true, true, true));
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
