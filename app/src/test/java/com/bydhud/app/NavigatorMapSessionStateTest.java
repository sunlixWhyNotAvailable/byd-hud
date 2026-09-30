package com.bydhud.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NavigatorMapSessionStateTest {
    @Test public void monotonicZeroIsAValidReceiptAndStillExpiresAtTheDeadline() {
        NavigatorMapSessionState state = new NavigatorMapSessionState();
        state.activate("com.waze", 1L, "session");
        assertEquals(NavigatorMapSessionState.FrameUpdate.CHANGED,
                state.publish("com.waze", 1L, "session", "pixels", "png",
                        new byte[]{1}, 0L, 0L));
        assertFalse(state.expireIfStale(3499L));
        assertTrue(state.expireIfStale(3500L));
    }
    @Test
    public void changedOwnerFencesOldWorkAndClearsTheImage() {
        NavigatorMapSessionState state = new NavigatorMapSessionState();
        assertTrue(state.activate("com.waze", 4L, "session-a"));
        assertEquals(NavigatorMapSessionState.FrameUpdate.CHANGED,
                state.publish("com.waze", 4L, "session-a", "input-a", "png-a",
                        new byte[]{1, 2}, 1000L, 1000L));
        long revision = state.revision();

        assertTrue(state.activate("app.revanced.android.apps.maps", 5L, "session-b"));
        assertEquals(revision + 1L, state.revision());
        assertNull(state.pngCopy());
        assertEquals(NavigatorMapSessionState.FrameUpdate.REJECTED,
                state.publish("com.waze", 4L, "session-a", "input-b", "png-b",
                        new byte[]{3}, 1100L, 1100L));
        assertTrue(state.stop());
        assertFalse(state.isCurrent("app.revanced.android.apps.maps", 5L, "session-b"));
    }

    @Test
    public void duplicateInputRefreshesAgeWithoutChangingPixelsOrRevision() {
        NavigatorMapSessionState state = new NavigatorMapSessionState();
        state.activate("com.waze", 1L, "session");
        assertEquals(NavigatorMapSessionState.FrameUpdate.CHANGED,
                state.publish("com.waze", 1L, "session", "input", "png",
                        new byte[]{7, 8, 9}, 1000L, 1000L));
        long revision = state.revision();
        long firstSequence = state.frameSequence();

        assertTrue(state.hasSameInputPixels("com.waze", 1L, "session", "input"));
        assertEquals(NavigatorMapSessionState.FrameUpdate.SAME,
                state.refreshSameInput("com.waze", 1L, "session", "input", 2000L, 2000L));
        assertEquals(revision, state.revision());
        assertTrue(state.frameSequence() > firstSequence);
        assertFalse(state.expireIfDue("session", firstSequence, 5500L));
        assertEquals(2000L, state.receivedAtElapsedMs());

        assertTrue(state.expireIfDue("session", state.frameSequence(), 5500L));
        assertEquals(revision + 1L, state.revision());
        assertNull(state.pngCopy());
    }

    @Test
    public void encodedImageChangeRevisesPixelsAndRejectsExpiredCrop() {
        NavigatorMapSessionState state = new NavigatorMapSessionState();
        state.activate("com.waze", 9L, "session");
        assertEquals(NavigatorMapSessionState.FrameUpdate.CHANGED,
                state.publish("com.waze", 9L, "session", "input-a", "png-a",
                        new byte[]{1}, 1000L, 1000L));
        long revision = state.revision();

        assertEquals(NavigatorMapSessionState.FrameUpdate.CHANGED,
                state.publish("com.waze", 9L, "session", "input-b", "png-b",
                        new byte[]{2}, 2000L, 2001L));
        assertEquals(revision + 1L, state.revision());
        assertArrayEquals(new byte[]{2}, state.pngCopy());
        assertEquals(NavigatorMapSessionState.FrameUpdate.REJECTED,
                state.publish("com.waze", 9L, "session", "input-c", "png-c",
                        new byte[]{3}, 3000L, 6500L));
        assertArrayEquals(new byte[]{2}, state.pngCopy());
    }

    @Test
    public void snapshotPngIsDefensiveAndEmptyWhenNoImageExists() {
        NavigatorMapCapture.Snapshot empty = new NavigatorMapCapture.Snapshot(null, 3L, 0L);
        assertEquals(0, empty.png().length);

        byte[] source = new byte[]{4, 5};
        NavigatorMapCapture.Snapshot snapshot = new NavigatorMapCapture.Snapshot(source, 4L, 100L);
        source[0] = 0;
        byte[] copy = snapshot.png();
        assertArrayEquals(new byte[]{4, 5}, copy);
        copy[1] = 0;
        assertArrayEquals(new byte[]{4, 5}, snapshot.png());
    }
}
