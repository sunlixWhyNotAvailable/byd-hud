package com.bydhud.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public class WazeSurfaceTransferTargetTest {
    @Test public void recenterRequestsRejectDuplicatesAndCancelledIds() {
        WazeSurfacePanState state = new WazeSurfacePanState();
        long first = state.beginRecenter();
        assertTrue(first > 0);
        assertEquals(0, state.beginRecenter());
        assertEquals(first, state.recenterControlsRequest());
        assertTrue(state.sendRecenter(first)); // Admission, not an acknowledgment from Waze.
        assertFalse(state.sendRecenter(first));
        state.request(true);
        assertFalse(state.recenterPending());
        assertFalse(state.sendRecenter(first));
        long second = state.beginRecenter();
        assertTrue(second > first);
        assertEquals(0, state.recenterControlsRequest());
        assertFalse(state.sendRecenter(second));
        assertFalse(state.sendRecenter(first));
        assertEquals(second, state.pendingRecenter());
        state.rendererChanged();
        assertFalse(state.recenterPending());
        assertFalse(state.sendRecenter(second));
        assertTrue(state.isPanning());
        state.request(false);
        long third = state.beginRecenter();
        assertTrue(third > second);
        assertFalse(state.sendRecenter(first));
        assertFalse(state.sendRecenter(second));
        assertEquals(third, state.pendingRecenter());
        assertTrue(state.sendRecenter(third));
    }

    @Test public void mapsTouchFocusAndDeltasToTheActualSurfaceBuffer() {
        assertEquals(960f, WazeSurfaceActivity.surfaceCoordinate(480f, 960, 1920), 0.001f);
        assertEquals(270f, WazeSurfaceActivity.surfaceCoordinate(540f, 1080, 540), 0.001f);
        assertEquals(-40f, WazeSurfaceActivity.surfaceCoordinate(-20f, 960, 1920), 0.001f);
        assertEquals(75f, WazeSurfaceActivity.surfaceCoordinate(75f, 1920, 1920), 0.001f);
        assertEquals(75f, WazeSurfaceActivity.surfaceCoordinate(75f, 0, 0), 0.001f);
    }

    // Task-tree input: Routes and HUD settings on the tablet, Surface on the projection.
    private static final String TASKS = """
            Display #0
              * Task{aaa #41 type=standard A=10226:com.waze U=0 visible=true}
                realActivity=com.waze/.MainActivity
                packageName=com.waze processName=com.waze
              * Task{bbb #42 type=standard A=10228:com.bydhud.app U=0 visible=false}
                realActivity=com.bydhud.app/.MainActivity
                packageName=com.bydhud.app processName=com.bydhud.app
                intent={cmp=com.bydhud.app/.WazeSurfaceActivity}
            Display #27
              * Task{ccc #43 type=standard A=10228:com.bydhud.app.waze_surface U=0 visible=true}
                realActivity=com.bydhud.app/.WazeSurfaceActivity
                packageName=com.bydhud.app processName=com.bydhud.app
            """;

    @Test public void selectsSurfaceIndependentlyOfVisibleRoutesAndTracksItsReturn() {
        NavAppDisplayState surface = NavAppDisplayController.parseTask("com.waze", TASKS, true);
        assertEquals("com.waze", surface.packageName);
        assertEquals(43, surface.taskId);
        assertEquals(27, surface.displayId);
        NavAppDisplayState routes = NavAppDisplayController.parseTask("com.waze", TASKS, false);
        assertEquals(41, routes.taskId);
        assertEquals(0, routes.displayId);

        String returned = TASKS.replace("Display #27", "Display #0");
        assertEquals(0, NavAppDisplayController.parseTask("com.waze", returned, true).displayId);
        assertEquals(41, NavAppDisplayController.parseTask("com.waze", returned, false).taskId);
    }

    @Test public void rejectsMissingOrReusedSurfaceTaskWithoutSelectingWazeOrSettings() {
        assertNull(NavAppDisplayController.parseTaskId("com.waze", 41, TASKS, true));
        assertNull(NavAppDisplayController.parseTaskId("com.waze", 42, TASKS, true));
        assertNull(NavAppDisplayController.parseTaskId("com.waze", 43, TASKS, false));
        assertNull(NavAppDisplayController.parseTask("com.waze",
                TASKS.replace("realActivity=com.bydhud.app/.WazeSurfaceActivity",
                        "realActivity=com.bydhud.app/.MainActivity"), true));
        assertEquals(43, NavAppDisplayController.parseTask("com.waze",
                TASKS.replace("realActivity=com.bydhud.app/.WazeSurfaceActivity",
                        "* Hist #0: ActivityRecord{ccc u0 com.bydhud.app/com.bydhud.app.WazeSurfaceActivity t43}"),
                true).taskId);
    }
}
