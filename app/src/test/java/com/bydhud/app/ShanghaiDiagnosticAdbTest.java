package com.bydhud.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public final class ShanghaiDiagnosticAdbTest {
    @Test public void captureCompletionRequiresReadyFinalizedLosslessOutput() {
        ShanghaiDiagnosticAdb.StreamState stream = new ShanghaiDiagnosticAdb.StreamState("adas");
        assertFalse(stream.json().optBoolean("captureComplete"));
        stream.ready.set(true);
        stream.status = "streaming";
        assertFalse(stream.json().optBoolean("captureComplete"));
        stream.status = "stopped";
        assertTrue(stream.json().optBoolean("captureComplete"));
        stream.stopError = "exit=66";
        assertFalse(stream.json().optBoolean("captureComplete"));
        stream.stopError = "";
        stream.droppedBytes.set(1L);
        assertFalse(stream.json().optBoolean("captureComplete"));
        stream.droppedBytes.set(0L);
        stream.helperError.set(true);
        assertFalse(stream.json().optBoolean("captureComplete"));

        ShanghaiDiagnosticAdb.StreamState pcap = new ShanghaiDiagnosticAdb.StreamState("pcap");
        pcap.status = "stopped";
        assertFalse(pcap.json().optBoolean("captureComplete"));
        pcap.bytes.set(24L);
        assertTrue(pcap.json().optBoolean("captureComplete"));
        pcap.error = "tcpdump unavailable: exit=127";
        assertFalse(pcap.json().optBoolean("captureComplete"));
    }

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
