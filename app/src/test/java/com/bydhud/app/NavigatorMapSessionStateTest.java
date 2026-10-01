package com.bydhud.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NavigatorMapSessionStateTest {
    @Test public void cadenceUsesFreshCropsAndResetsOnChangeErrorsProfilesAndExpiry() {
        NavigatorMapSessionState state = new NavigatorMapSessionState();
        state.activate("com.waze", 1, "s");
        assertEquals(200, state.requestIntervalMs());
        for (long now = 0; now <= 2000; now += 200) {
            // The surface changes every time, while the final crop remains identical.
            state.publish("com.waze", 1, "s", "surface-" + now, "crop-a",
                    new byte[]{1}, HudMapProfile.Source.WAZE, 1, now, now);
            assertEquals(now < 2000 ? 200 : 1000, state.requestIntervalMs());
        }
        state.refreshSameInput("com.waze", 1, "s", "surface-2000",
                HudMapProfile.Source.WAZE, 1, 3000, 3000);
        assertEquals(1000, state.requestIntervalMs());
        state.publish("com.waze", 1, "s", "surface-new", "crop-b",
                new byte[]{2}, HudMapProfile.Source.WAZE, 1, 4000, 4000);
        assertEquals(200, state.requestIntervalMs());
        state.refreshSameInput("com.waze", 1, "s", "surface-new",
                HudMapProfile.Source.WAZE, 1, 6000, 6000);
        assertEquals(1000, state.requestIntervalMs());
        state.resetCadence(); // Failed or timed-out request: silence is not a stable crop.
        state.refreshSameInput("com.waze", 1, "s", "surface-new",
                HudMapProfile.Source.WAZE, 1, 6200, 6200);
        assertEquals(200, state.requestIntervalMs());
        state.publish("com.waze", 1, "s", "surface-new", "crop-b",
                new byte[]{2}, HudMapProfile.Source.WAZE, 2, 8200, 8200);
        assertEquals(200, state.requestIntervalMs());
        state.refreshSameInput("com.waze", 1, "s", "surface-new",
                HudMapProfile.Source.WAZE, 2, 10200, 10200);
        assertEquals(1000, state.requestIntervalMs());
        assertTrue(state.expireIfStale(13700));
        assertEquals(200, state.requestIntervalMs());
        state.activate("com.waze", 2, "replacement");
        assertEquals(NavigatorMapSessionState.FrameUpdate.REJECTED,
                state.publish("com.waze", 1, "s", "late", "crop-b", new byte[]{2}, 14000, 14000));
        assertEquals(200, state.requestIntervalMs());
    }
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

    @Test
    public void samePixelsAreReusableOnlyForTheSameSourceAndProfileRevision() {
        NavigatorMapSessionState state = new NavigatorMapSessionState();
        state.activate("com.waze", 3L, "session");
        HudMapProfile.Source source = HudMapProfile.Source.WAZE;
        assertEquals(NavigatorMapSessionState.FrameUpdate.CHANGED,
                state.publish("com.waze", 3L, "session", "pixels", "crop-1",
                        new byte[]{1}, source, 4L, 1000L, 1000L));
        long revision = state.revision();

        assertTrue(state.hasSameInputPixels("com.waze", 3L, "session", "pixels", source, 4L));
        assertFalse(state.hasSameInputPixels("com.waze", 3L, "session", "pixels",
                HudMapProfile.Source.WAZE_SURFACE, 4L));
        assertFalse(state.hasSameInputPixels("com.waze", 3L, "session", "pixels", source, 5L));
        assertEquals(NavigatorMapSessionState.FrameUpdate.CHANGED,
                state.publish("com.waze", 3L, "session", "pixels", "crop-2",
                        new byte[]{2}, source, 5L, 1000L, 1100L));
        assertEquals(revision + 1L, state.revision());
        assertEquals(source, state.source());
        assertEquals(5L, state.profileRevision());
    }

    @Test
    public void recropKeepsOriginalReceiptAndExpiryDeadline() {
        NavigatorMapSessionState state = new NavigatorMapSessionState();
        state.activate("com.waze", 8L, "session");
        HudMapProfile.Source source = HudMapProfile.Source.WAZE;
        assertEquals(NavigatorMapSessionState.FrameUpdate.CHANGED,
                state.publish("com.waze", 8L, "session", "pixels", "crop-1",
                        new byte[]{1}, source, 1L, 1000L, 1000L));
        assertEquals(NavigatorMapSessionState.FrameUpdate.CHANGED,
                state.publish("com.waze", 8L, "session", "pixels", "crop-2",
                        new byte[]{2}, source, 2L, 1000L, 1200L));
        assertEquals(1000L, state.receivedAtElapsedMs());
        assertTrue(state.expireIfStale(4500L));
        assertFalse(state.hasFrame());
    }
}
