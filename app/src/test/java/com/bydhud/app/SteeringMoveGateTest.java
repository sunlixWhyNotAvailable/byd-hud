package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/** Executes the controller's real lock/ownership boundary without invoking Android or ADB. */
public final class SteeringMoveGateTest {
    @Test
    public void reservedPrecheckRejectsAnotherPressBeforeAnyWorkerRuns() throws Exception {
        NavAppDisplayController controller = controller();
        assertTrue(controller.reserveMove());
        assertTrue(controller.isMoveInProgress());
        assertFalse(controller.reserveMove());
        assertFalse(controller.reserveMove());
    }

    @Test
    public void busyWidgetOrUiReservationIsVisibleToNewUiListeners() throws Exception {
        NavAppDisplayController controller = controller();
        assertFalse(controller.setListener(null));
        assertTrue(controller.reserveMove());
        assertTrue(controller.isMoveInProgress());
        assertTrue(controller.setListener(null));
        assertFalse(controller.reserveMove());
    }

    @Test
    public void concurrentUiAndSteeringRequestsHaveOneWinner() throws Exception {
        NavAppDisplayController controller = controller();
        ExecutorService threads = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> attempts = new ArrayList<>();
        try {
            for (int index = 0; index < 8; index++) {
                attempts.add(threads.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return controller.reserveMove();
                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> attempt : attempts) {
                if (attempt.get(5, TimeUnit.SECONDS)) winners++;
            }
            assertEquals(1, winners);
            assertTrue(controller.isMoveInProgress());
        } finally {
            threads.shutdownNow();
        }
    }

    private static NavAppDisplayController controller() throws Exception {
        Constructor<NavAppDisplayController> constructor =
                NavAppDisplayController.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        Context context = new ContextWrapper(null) {
            @Override
            public Context getApplicationContext() {
                return this;
            }
        };
        return constructor.newInstance(context);
    }
}
