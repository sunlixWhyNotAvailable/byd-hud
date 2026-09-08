package com.bydhud.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class LogcatStreamBoundaryTest {
    @Test
    public void splitUtf8ChunksRoundTripAndOnlyPersistedOccurrencesAreSkipped()
            throws Exception {
        String repeated = "09-08 15:45:20.226  100  200 I Tag: однакове\n";
        String next = "09-08 15:45:20.227  100  200 I Tag: кінець €\n";
        LogcatStreamBoundary tracker = new LogcatStreamBoundary();
        byte[] persisted = (repeated + repeated).getBytes(StandardCharsets.UTF_8);
        for (int offset = 0; offset < persisted.length; offset += 3) {
            tracker.observe(persisted, offset, Math.min(3, persisted.length - offset));
        }

        byte[] reread = (repeated + repeated + repeated + next).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream accepted = new ByteArrayOutputStream();
        LogcatStreamBoundary.Reconciler reconciler =
                new LogcatStreamBoundary.Reconciler(tracker.snapshot(), accepted);
        for (int offset = 0; offset < reread.length; offset += 2) {
            reconciler.write(reread, offset, Math.min(2, reread.length - offset));
        }

        assertArrayEquals((repeated + next).getBytes(StandardCharsets.UTF_8),
                accepted.toByteArray());
        assertFalse(reconciler.overflowed());
    }

    @Test
    public void distinctIdenticalRecordsOutsideTheBoundaryAreNeverGloballyDeduplicated()
            throws Exception {
        String boundaryLine = "09-08 15:45:20.226  100  200 I Tag: boundary\n";
        String identical = "09-08 15:45:20.227  100  200 I Tag: keep me\n";
        LogcatStreamBoundary tracker = new LogcatStreamBoundary();
        byte[] before = boundaryLine.getBytes(StandardCharsets.UTF_8);
        tracker.observe(before, 0, before.length);
        byte[] after = (boundaryLine + identical + identical).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream accepted = new ByteArrayOutputStream();
        LogcatStreamBoundary.Reconciler reconciler =
                new LogcatStreamBoundary.Reconciler(tracker.snapshot(), accepted);
        reconciler.write(after);
        assertArrayEquals((identical + identical).getBytes(StandardCharsets.UTF_8),
                accepted.toByteArray());
    }

    @Test
    public void transitionBookkeepingHasFixedLineAndOccurrenceBounds() {
        LogcatStreamBoundary tracker = new LogcatStreamBoundary();
        byte[] longPartial = new byte[2 * 1024 * 1024];
        tracker.observe(longPartial, 0, longPartial.length);
        assertTrue(tracker.bufferedBytesForTest() <= 64 * 1024);

        for (int index = 0; index < 10_000; index++) {
            byte[] line = String.format("09-08 15:45:20.226 %05d I Tag: value\n", index)
                    .getBytes(StandardCharsets.UTF_8);
            tracker.observe(line, 0, line.length);
        }
        assertTrue(tracker.trackedLinesForTest() <= 4_096);
        assertFalse(tracker.snapshot().reliable);
        assertEquals("09-08 15:45:20.226", tracker.snapshot().timestamp);
    }

    @Test
    public void incompleteFallbackRecordKeepsValidPrefixAndReportsOnlySplitUtf8Tail()
            throws Exception {
        String boundaryLine = "09-08 15:45:20.226  1  2 I Tag: prior\n";
        LogcatStreamBoundary tracker = new LogcatStreamBoundary();
        byte[] persisted = boundaryLine.getBytes(StandardCharsets.UTF_8);
        tracker.observe(persisted, 0, persisted.length);
        byte[] partial = "09-08 15:45:20.227  1  2 I Tag: обр"
                .getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream accepted = new ByteArrayOutputStream();
        LogcatStreamBoundary.Reconciler reconciler =
                new LogcatStreamBoundary.Reconciler(tracker.snapshot(), accepted);
        reconciler.write(partial, 0, partial.length - 1);

        LogcatStreamBoundary.Finish finish = reconciler.finish();
        assertEquals(1, finish.discardedBytes);
        assertArrayEquals(java.util.Arrays.copyOf(partial, partial.length - 2),
                accepted.toByteArray());
    }

    @Test
    public void adbPartialPrefixIsCompletedOnceByTheFallbackReread() throws Exception {
        String prior = "09-08 15:45:20.226  1  2 I Tag: prior\n";
        String next = "09-08 15:45:20.227  1  2 I Tag: новий €\n";
        LogcatStreamBoundary tracker = new LogcatStreamBoundary();
        byte[] priorBytes = prior.getBytes(StandardCharsets.UTF_8);
        tracker.observe(priorBytes, 0, priorBytes.length);
        byte[] nextBytes = next.getBytes(StandardCharsets.UTF_8);
        byte[] pending = java.util.Arrays.copyOf(nextBytes, nextBytes.length - 4);
        ByteArrayOutputStream accepted = new ByteArrayOutputStream();
        LogcatStreamBoundary.Reconciler reconciler =
                new LogcatStreamBoundary.Reconciler(tracker.snapshot(), accepted, pending);
        byte[] fallback = (prior + next).getBytes(StandardCharsets.UTF_8);
        reconciler.write(fallback);

        LogcatStreamBoundary.Finish finish = reconciler.finish();
        assertArrayEquals(nextBytes, accepted.toByteArray());
        assertEquals(0, finish.discardedBytes);
        assertFalse(finish.pendingMismatch);
    }

    @Test
    public void newPartialRecordAtBoundaryTimestampConsumesItsPrefixOnce() throws Exception {
        String prior = "09-08 15:45:20.226  1  2 I Tag: prior\n";
        String sameTimestamp = "09-08 15:45:20.226  1  2 I Tag: new same millisecond\n";
        String next = "09-08 15:45:20.227  1  2 I Tag: next\n";
        LogcatStreamBoundary tracker = new LogcatStreamBoundary();
        byte[] priorBytes = prior.getBytes(StandardCharsets.UTF_8);
        tracker.observe(priorBytes, 0, priorBytes.length);
        byte[] sameBytes = sameTimestamp.getBytes(StandardCharsets.UTF_8);
        byte[] pending = java.util.Arrays.copyOf(sameBytes, sameBytes.length - 5);
        ByteArrayOutputStream accepted = new ByteArrayOutputStream();
        LogcatStreamBoundary.Reconciler reconciler =
                new LogcatStreamBoundary.Reconciler(tracker.snapshot(), accepted, pending);

        reconciler.write((prior + sameTimestamp + next).getBytes(StandardCharsets.UTF_8));
        LogcatStreamBoundary.Finish finish = reconciler.finish();

        assertArrayEquals((sameTimestamp + next).getBytes(StandardCharsets.UTF_8),
                accepted.toByteArray());
        assertFalse(finish.pendingMismatch);
        assertEquals(0, finish.discardedBytes);
    }
}
