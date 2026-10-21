package com.bydhud.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public class WazeSurfaceTransferTargetTest {
    @Test public void restoresPanForNewRendererAndIgnoresOldAcknowledgments() {
        WazeSurfacePanState state = new WazeSurfacePanState();
        state.request(true);
        WazeSurfacePanState.Request old = state.next();
        assertTrue(old.enabled);
        assertNull(state.next());
        state.rendererChanged();
        WazeSurfacePanState.Request restored = state.next();
        assertTrue(restored.enabled);
        assertFalse(state.complete(old, false));
        assertNull(state.next());
        assertTrue(state.complete(restored, true));
        state.request(false);
        WazeSurfacePanState.Request centered = state.next();
        assertFalse(centered.enabled);
        assertTrue(state.complete(centered, false));
        WazeSurfacePanState.Request retry = state.next();
        assertFalse(retry.enabled);
        assertFalse(state.complete(centered, true));
        assertTrue(state.complete(retry, true));
        assertNull(state.next());
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
