package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public final class ShanghaiDiagnosticAdbTest {
    @Test public void unconfirmedStopDoesNotReleaseAnAliveWriter() throws Exception {
        ShanghaiDiagnosticAdb.StreamState stream = new ShanghaiDiagnosticAdb.StreamState("adas");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        stream.thread = new Thread(() -> {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        stream.thread.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            stream.status = "stop_unconfirmed";
            assertFalse(stream.live());
            assertFalse(stream.isTerminated());
        } finally {
            release.countDown();
            stream.thread.join(2_000L);
        }
        assertTrue(stream.isTerminated());
    }
}
