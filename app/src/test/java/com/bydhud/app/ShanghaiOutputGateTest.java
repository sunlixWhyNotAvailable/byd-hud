package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class ShanghaiOutputGateTest {
    private ExecutorService executor;

    @Before
    public void setUp() {
        ShanghaiOutputGate.resume();
        executor = Executors.newCachedThreadPool();
    }

    @After
    public void tearDown() throws InterruptedException {
        executor.shutdownNow();
        try {
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        } finally {
            ShanghaiOutputGate.resume();
        }
    }

    @Test
    public void suspendWaitsForAnAlreadyAdmittedWriteToFinish() throws Exception {
        CountDownLatch writeEntered = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        Future<?> writer = executor.submit(() -> {
            assertTrue(ShanghaiOutputGate.enterWrite());
            writeEntered.countDown();
            try {
                assertTrue(releaseWrite.await(2, TimeUnit.SECONDS));
            } finally {
                ShanghaiOutputGate.leaveWrite();
            }
            return null;
        });
        assertTrue(writeEntered.await(2, TimeUnit.SECONDS));

        Future<?> suspender = executor.submit(() -> {
            ShanghaiOutputGate.suspend();
            return null;
        });
        try {
            suspender.get(150, TimeUnit.MILLISECONDS);
            fail("suspend returned before the admitted write completed");
        } catch (TimeoutException expected) {
            assertFalse(ShanghaiOutputGate.isSuspended());
        }

        releaseWrite.countDown();
        writer.get(2, TimeUnit.SECONDS);
        suspender.get(2, TimeUnit.SECONDS);
        assertTrue(ShanghaiOutputGate.isSuspended());
    }

    @Test
    public void suspendedGateRejectsNewWritesWithoutAcquiringCallerOwnership() throws Exception {
        ShanghaiOutputGate.suspend();

        assertFalse(ShanghaiOutputGate.enterWrite());

        Future<?> resume = executor.submit(() -> {
            ShanghaiOutputGate.resume();
            return null;
        });
        resume.get(2, TimeUnit.SECONDS);
        assertFalse(ShanghaiOutputGate.isSuspended());
    }

    @Test
    public void resumeReadmitsWrites() throws Exception {
        ShanghaiOutputGate.suspend();
        assertFalse(ShanghaiOutputGate.enterWrite());

        ShanghaiOutputGate.resume();

        boolean entered = ShanghaiOutputGate.enterWrite();
        try {
            assertTrue(entered);
        } finally {
            if (entered) ShanghaiOutputGate.leaveWrite();
        }
    }

    @Test
    public void exceptionalWriteReleasesOwnershipAndDoesNotPoisonLaterSuspend() throws Exception {
        Future<?> failedWrite = executor.submit(() -> {
            assertTrue(ShanghaiOutputGate.enterWrite());
            try {
                throw new IOException("simulated transport failure");
            } finally {
                ShanghaiOutputGate.leaveWrite();
            }
        });

        try {
            failedWrite.get(2, TimeUnit.SECONDS);
            fail("write failure was not propagated");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof IOException);
        }

        ShanghaiOutputGate.suspend();
        assertTrue(ShanghaiOutputGate.isSuspended());
    }
}
