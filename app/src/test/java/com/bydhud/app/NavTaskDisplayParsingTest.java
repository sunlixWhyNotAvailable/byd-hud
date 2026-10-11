package com.bydhud.app;

import static org.junit.Assert.*;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public final class NavTaskDisplayParsingTest {
    // Fixture: minimized Oct 11 DiLink dump, waze-5.25.0.1/evidence/20261011-return-to-tablet/activities.txt.
    private Context context;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        PackageInfo info = new PackageInfo();
        info.packageName = "com.waze";
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = info.packageName;
        Shadows.shadowOf(context.getPackageManager()).installPackage(info);
    }

    @Test public void capturedHistoryDoesNotInventAnActiveWazeTask() throws Exception {
        String dump = capturedDump();
        assertNull(NavAppDisplayController.parseTaskForTest("com.waze", dump));
        assertNull(NavAppDisplayController.parseTaskIdForTest("com.waze", 117, dump));
        assertNull(scanWaze(dump));
        assertPlacement(NavAppDisplayController.parseTaskIdForTest("com.waze", 5, dump), 5, 0);
    }

    @Test public void navigatorAndSurfaceKeepTheirOwnDisplayDespiteLaunchAndHistoryReferences()
            throws Exception {
        String tree = "Display #0 (activities from top to bottom):\n"
                + "  * Task{a #112 A=10230:com.bydhud.app visible=true}\n"
                + "    packageName=com.bydhud.app processName=com.bydhud.app\n"
                + "    launchedFromPackage=com.waze\n"
                + "    Intent { cmp=com.bydhud.app/.MainActivity (has extras) }\n"
                + "Display #21 (activities from top to bottom):\n"
                + "  * Task{b #116 A=10230:com.bydhud.app.waze.surface visible=true}\n"
                + "    topResumedActivity=ActivityRecord{c u0 com.bydhud.app/.WazeSurfaceActivity t116}\n"
                + "    packageName=com.bydhud.app processName=com.bydhud.app\n"
                + "    launchedFromPackage=com.waze\n"
                + "  * Task{d #113 A=10231:com.waze visible=false}\n"
                + "    packageName=com.waze processName=com.waze\n";
        String history = capturedDump().substring(capturedDump().indexOf("ActivityTaskSupervisor"));
        String dump = tree + history;
        NavAppDisplayState waze = NavAppDisplayController.parseTaskForTest("com.waze", dump);
        assertPlacement(waze, 113, 21);
        assertFalse(waze.isOnMainDisplay());
        assertFalse(waze.visible);
        DashboardProjectionPolicy.ObservedDisplay observed =
                DashboardProjectionPolicy.classifyObservedDisplay("com.waze", waze, "com.waze", 21);
        assertFalse(SteeringTransferPolicy.toggleMovesToDashboard(observed));
        assertTrue(SteeringTransferPolicy.canReturnToMain(observed));
        assertPlacement(NavAppDisplayController.parseTaskIdForTest("com.waze", 116, dump), 116, 21);
        assertEquals(113, scanWaze(dump).taskId);
        assertEquals(21, scanWaze(dump).displayId);

        String returned = tree.replace("Display #21", "Display #0") + history;
        assertPlacement(NavAppDisplayController.parseTaskForTest("com.waze", returned), 113, 0);
        assertPlacement(NavAppDisplayController.parseTaskIdForTest("com.waze", 116, returned), 116, 0);
        assertEquals(0, scanWaze(returned).displayId);
    }

    @Test public void compactTaskHeadersPreserveExplicitOrUnknownDisplay() {
        String task = "  * Task{d #113 A=10231:com.waze visible=true}\n";
        NavAppDisplayState unknown = NavAppDisplayController.parseTaskForTest("com.waze", task);
        assertPlacement(unknown, 113, -1);
        assertFalse(SteeringTransferPolicy.canToggleTask(unknown,
                DashboardProjectionPolicy.classifyObservedDisplay("com.waze", unknown, "com.waze", 21)));
        assertPlacement(NavAppDisplayController.parseTaskIdForTest("com.waze", 113, task), 113, -1);
        assertEquals(-1, scanWaze(task).displayId);
        String explicit = "RootTask id=113 displayId=21 A=10231:com.waze visible=true\n";
        assertPlacement(NavAppDisplayController.parseTaskForTest("com.waze", explicit), 113, 21);
        assertEquals(21, scanWaze(explicit).displayId);
    }

    private NavAppTaskScanner.Row scanWaze(String dump) {
        NavAppTaskScanner scanner = ReflectionHelpers.callConstructor(
                NavAppTaskScanner.class, from(Context.class, context));
        Map<String, Object> rows = new HashMap<>();
        ReflectionHelpers.callInstanceMethod(scanner, "parseTaskRows",
                from(String.class, dump), from(Map.class, rows));
        NavAppTaskScanner.Snapshot result = ReflectionHelpers.callInstanceMethod(scanner,
                "buildSnapshot", from(Map.class, rows), from(long.class, 1L),
                from(String.class, "task"), from(String.class, "ok"), from(boolean.class, true));
        for (NavAppTaskScanner.Row row : result.rows) {
            if ("com.waze".equals(row.packageName)) return row;
        }
        return null;
    }

    private static void assertPlacement(NavAppDisplayState state, int task, int display) {
        assertNotNull(state);
        assertEquals(task, state.taskId);
        assertEquals(display, state.displayId);
    }

    private static String capturedDump() throws Exception {
        try (InputStream input = NavTaskDisplayParsingTest.class.getResourceAsStream(
                "/dilink-task-history.txt")) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
