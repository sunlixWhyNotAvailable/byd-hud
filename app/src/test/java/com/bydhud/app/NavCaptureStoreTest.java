package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

public final class NavCaptureStoreTest {
    @Test
    public void directArtifactNameIsStableAndContentAddressed() {
        byte[] png = new byte[]{1, 2, 3};

        String first = NavCaptureStore.directArtifactFileName("lanes", png);
        String second = NavCaptureStore.directArtifactFileName("lanes", png.clone());
        String changed = NavCaptureStore.directArtifactFileName(
                "lanes", new byte[]{1, 2, 4});

        assertEquals(first, second);
        assertNotEquals(first, changed);
        assertEquals(
                "lanes-039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81.png",
                first);
    }

    @Test
    public void directArtifactNameRejectsEmptyBytesAndSanitizesKind() {
        assertEquals("", NavCaptureStore.directArtifactFileName("alert", new byte[0]));
        assertEquals(
                "maneuver_raw-039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81.png",
                NavCaptureStore.directArtifactFileName(
                        "maneuver/raw", new byte[]{1, 2, 3}));
    }

    @Test
    public void capturedWallClockSelectsTheLogDay() {
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            Calendar captured = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            captured.clear();
            captured.set(2026, Calendar.JULY, 21, 23, 59, 59);

            assertEquals("20260721", NavCaptureStore.todayDir(captured.getTimeInMillis()));
        } finally {
            TimeZone.setDefault(previous);
        }
    }
}
