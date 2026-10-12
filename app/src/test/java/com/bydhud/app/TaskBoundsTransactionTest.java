package com.bydhud.app;

import static org.junit.Assert.*;
import android.app.Application;
import android.graphics.Rect;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public final class TaskBoundsTransactionTest {
    @Test public void movedTaskMustMatchBufferAndFailedResizeRestoresOriginalDisplayAndBounds() throws Exception {
        Fake io = new Fake();
        NavAppDisplayState before = io.state(0, new Rect(0, 0, 2560, 1600));
        Rect target = new Rect(0, 0, 2560, 540);
        io.observed = io.state(7, before.bounds);
        assertEquals("", TaskBoundsTransaction.apply(before, 7, target, () -> true, io));
        assertEquals(target, io.observed.bounds);
        io.observed = io.state(7, before.bounds);
        io.reject = target;
        String failure = TaskBoundsTransaction.apply(before, 7, target, () -> true, io);
        assertTrue(failure, failure.contains("rollback=true"));
        assertEquals(0, io.observed.displayId);
        assertEquals(before.bounds, io.observed.bounds);
        io.reject = null;
        io.ignored = target;
        io.observed = io.state(7, before.bounds);
        failure = TaskBoundsTransaction.apply(before, 7, target, () -> true, io);
        assertTrue(failure, failure.contains("readback mismatch"));
        assertTrue(failure, failure.contains("rollback=true"));
        assertEquals(0, io.observed.displayId);
        assertEquals(before.bounds, io.observed.bounds);
        int mutations = io.mutations;
        assertTrue(TaskBoundsTransaction.apply(before, 7, target, () -> false, io).contains("owner changed"));
        assertEquals(mutations, io.mutations);
    }

    private static final class Fake implements TaskBoundsTransaction.Io {
        NavAppDisplayState observed;
        Rect reject;
        Rect ignored;
        int mutations;
        NavAppDisplayState state(int display, Rect bounds) {
            return new NavAppDisplayState("com.waze", 113, display, true, "", bounds);
        }
        @Override public NavAppDisplayState query() { return observed; }
        @Override public boolean resize(Rect bounds) {
            mutations++;
            if (bounds.equals(reject)) return false;
            if (bounds.equals(ignored)) return true;
            observed = state(observed.displayId, bounds);
            return true;
        }
        @Override public boolean move(int display) {
            mutations++;
            observed = state(display, observed.bounds);
            return true;
        }
    }
}
