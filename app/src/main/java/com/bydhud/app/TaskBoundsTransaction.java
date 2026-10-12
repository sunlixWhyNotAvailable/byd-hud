package com.bydhud.app;

import android.graphics.Rect;
import java.io.IOException;
import java.util.function.BooleanSupplier;

/** Confirms the real task rectangle and rolls a failed resize back under the same owner. */
final class TaskBoundsTransaction {
    interface Io {
        NavAppDisplayState query() throws IOException;
        boolean resize(Rect bounds) throws IOException;
        boolean move(int display) throws IOException;
    }

    static String apply(NavAppDisplayState before, int target, Rect expected,
            BooleanSupplier current, Io io) throws IOException {
        if (!current.getAsBoolean()) return "owner changed before resize";
        NavAppDisplayState observed = io.query();
        if (!sameTask(before, observed) || observed.displayId != target) {
            return "task/display changed before resize";
        }
        String failure;
        try {
            if (!expected.equals(observed.bounds) && !io.resize(expected)) {
                failure = "resize command rejected";
            } else {
                observed = io.query();
                if (current.getAsBoolean() && sameTask(before, observed)
                        && observed.displayId == target && expected.equals(observed.bounds)) return "";
                failure = "resize readback mismatch actual=" + describe(observed);
            }
        } catch (IOException error) { failure = "resize failed " + error; }
        if (!current.getAsBoolean()) return failure + "; rollback skipped: owner changed";
        observed = io.query();
        if (!sameTask(before, observed) || observed.displayId != target) {
            return failure + "; rollback skipped: task/display changed";
        }
        boolean moved = before.displayId == target || io.move(before.displayId);
        observed = io.query();
        boolean resized = moved && current.getAsBoolean() && sameTask(before, observed)
                && observed.displayId == before.displayId && before.bounds != null
                && (before.bounds.equals(observed.bounds) || io.resize(before.bounds));
        observed = io.query();
        boolean restored = resized && current.getAsBoolean() && sameTask(before, observed)
                && observed.displayId == before.displayId && before.bounds.equals(observed.bounds);
        return failure + "; rollback=" + restored + " actual=" + describe(observed);
    }

    private static boolean sameTask(NavAppDisplayState expected, NavAppDisplayState actual) {
        return actual != null && expected.taskId == actual.taskId
                && expected.packageName.equals(actual.packageName);
    }

    private static String describe(NavAppDisplayState state) {
        return state == null ? "missing" : "task=" + state.taskId + " display=" + state.displayId
                + " bounds=" + state.bounds;
    }
}
