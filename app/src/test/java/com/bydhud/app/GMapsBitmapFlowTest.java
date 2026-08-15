package com.bydhud.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;

public final class GMapsBitmapFlowTest {
    @Test
    public void departSelectsCachedGoogleBitmapWithoutViewIdFilter() {
        byte[] google = png(36, 36, 1);
        byte[] fallback = png(72, 72, 2);
        GMapsDirectChannel.ManeuverBitmap candidate =
                new GMapsDirectChannel.ManeuverBitmap(
                        "DEPART", "any_future_view_id", google, 36, 36, 900L);

        GMapsDirectChannel.BitmapSelection selection =
                GMapsDirectChannel.BitmapSelection.select(
                        7L, "DEPART", candidate, fallback, 1_000L, "matched");

        assertEquals("google", selection.selected);
        assertEquals("any_future_view_id", selection.viewId);
        assertEquals(-100L, selection.frameDelayMs);
        assertTrue(selection.currentMatch);
        assertArrayEquals(google, selection.selectedPng);
        assertEquals(DirectTbtPayload.shortSha256(fallback), selection.fallbackSha);
    }

    @Test
    public void lateDepartMatchesImmediatelyAndDifferentManeuverDoesNot() {
        byte[] google = png(36, 36, 3);
        byte[] fallback = png(72, 72, 4);
        GMapsDirectChannel.ManeuverBitmap late =
                new GMapsDirectChannel.ManeuverBitmap(
                        "DEPART", "maneuver_image", google, 36, 36, 1_040L);
        GMapsDirectChannel.ManeuverBitmap stale =
                new GMapsDirectChannel.ManeuverBitmap(
                        "TURN_NORMAL_LEFT", "maneuver_image", google, 36, 36, 1_040L);

        GMapsDirectChannel.BitmapSelection selection =
                GMapsDirectChannel.BitmapSelection.google(
                        8L, late, fallback, 1_000L, "late-match");
        GMapsDirectChannel.BitmapSelection ignored =
                GMapsDirectChannel.BitmapSelection.select(
                        8L, "DEPART", stale, fallback, 1_000L, "matched");

        assertEquals("google", selection.selected);
        assertEquals(40L, selection.frameDelayMs);
        assertTrue(late.matches("DEPART"));
        assertFalse(stale.matches("DEPART"));
        assertEquals("fallback", ignored.selected);
        assertArrayEquals(fallback, ignored.selectedPng);
    }

    @Test
    public void sameManeuverBitmapUsesSourceTimestampWatermark() {
        GMapsDirectChannel.ManeuverBitmap current =
                new GMapsDirectChannel.ManeuverBitmap(
                        "DEPART", "maneuver_image", png(36, 36, 11), 36, 36, 1_100L);
        GMapsDirectChannel.ManeuverBitmap stale =
                new GMapsDirectChannel.ManeuverBitmap(
                        "DEPART", "maneuver_image", png(36, 36, 12), 36, 36, 1_099L);
        GMapsDirectChannel.ManeuverBitmap newer =
                new GMapsDirectChannel.ManeuverBitmap(
                        "DEPART", "maneuver_image", png(36, 36, 13), 36, 36, 1_101L);

        assertFalse(stale.isNewerThan(current));
        assertFalse(current.isNewerThan(current));
        assertTrue(newer.isNewerThan(current));
    }

    @Test
    public void renderGenerationRejectsStaleProducerFramesEvenWithNewerClock() {
        byte[] oldPng = png(36, 36, 21);
        byte[] newPng = png(36, 36, 22);
        GMapsDirectChannel.ManeuverBitmap current =
                new GMapsDirectChannel.ManeuverBitmap(
                        "DEPART", "maneuver_image", oldPng, 36, 36, 1_001L,
                        7L, 12L);
        GMapsDirectChannel.ManeuverBitmap stale =
                new GMapsDirectChannel.ManeuverBitmap(
                        "DEPART", "maneuver_image", newPng, 36, 36, 9_999L,
                        7L, 11L);
        GMapsDirectChannel.ManeuverBitmap restart =
                new GMapsDirectChannel.ManeuverBitmap(
                        "DEPART", "maneuver_image", newPng, 36, 36, 1L,
                        8L, 1L);

        assertFalse(stale.isNewerThan(current));
        assertTrue(restart.isNewerThan(current));
    }

    @Test
    public void legacyV3BitmapUsesBoundedTimestampWatermark() {
        GMapsDirectChannel.ManeuverBitmap current =
                new GMapsDirectChannel.ManeuverBitmap(
                        "DEPART", "maneuver_image", png(36, 36, 31), 36, 36, 2_000L,
                        40L, 9L, 7L);
        GMapsDirectChannel.ManeuverBitmap newerClock =
                new GMapsDirectChannel.ManeuverBitmap(
                        "DEPART", "maneuver_image", png(36, 36, 32), 36, 36, 2_001L,
                        1L, 1L, -1L);
        GMapsDirectChannel.ManeuverBitmap staleClock =
                new GMapsDirectChannel.ManeuverBitmap(
                        "DEPART", "maneuver_image", png(36, 36, 33), 36, 36, 1_999L,
                        99L, 99L, -1L);
        assertTrue(newerClock.isNewerThan(current, false));
        assertFalse(staleClock.isNewerThan(current, false));
    }

    @Test
    public void txDiagnosticUsesFinalPreparedBitmapAndRequiresSuccessfulSend() {
        byte[] google = png(36, 36, 5);
        byte[] fallback = png(72, 72, 6);
        GMapsDirectChannel.BitmapSelection selection =
                GMapsDirectChannel.BitmapSelection.google(
                        9L,
                        new GMapsDirectChannel.ManeuverBitmap(
                                "DEPART", "maneuver_image", google, 36, 36, 1_040L),
                        fallback,
                        1_000L,
                        "late-match");
        byte[] finalPayloadPng = png(48, 48, 14);
        DirectTbtPayload.Prepared prepared = DirectTbtPayload.prepare(
                frame(finalPayloadPng), DirectTbtPayload.Options.ALL);

        String key = HudOutputCoordinator.bitmapTxDiagnosticKey(0, selection, prepared);
        String message = selection.txMessage(prepared, 1_050L);

        assertFalse(key.isEmpty());
        assertEquals(key, HudOutputCoordinator.bitmapTxDiagnosticKey(0, selection, prepared));
        assertTrue(HudOutputCoordinator.shouldEmitBitmapTx("", key));
        assertFalse(HudOutputCoordinator.shouldEmitBitmapTx(key, key));
        assertEquals("", HudOutputCoordinator.bitmapTxDiagnosticKey(1, selection, prepared));
        assertTrue(message.contains("sequence=9"));
        assertTrue(message.contains("pngSha=" + prepared.maneuverPngSha()));
        assertTrue(message.contains("pngSha="
                + DirectTbtPayload.shortSha256(finalPayloadPng)));
        assertTrue(message.contains("pngBytes=" + finalPayloadPng.length));
        assertTrue(message.contains("width=48 height=48"));
        assertTrue(message.contains("native=99"));
    }

    @Test
    public void pngDisabledProducesNoTxDiagnostic() {
        byte[] google = png(36, 36, 7);
        GMapsDirectChannel.BitmapSelection selection =
                GMapsDirectChannel.BitmapSelection.google(
                        10L,
                        new GMapsDirectChannel.ManeuverBitmap(
                                "DEPART", "maneuver_image", google, 36, 36, 1_000L),
                        png(72, 72, 8),
                        1_000L,
                        "matched");
        DirectTbtPayload.Options pngDisabled = new DirectTbtPayload.Options(
                false, true, true, true, true, true, false);
        DirectTbtPayload.Prepared prepared = DirectTbtPayload.prepare(
                frame(google), pngDisabled);

        assertEquals(0, prepared.maneuverPngBytes());
        assertEquals("", HudOutputCoordinator.bitmapTxDiagnosticKey(
                0, selection, prepared));
    }

    private static DirectTbtFrame frame(byte[] maneuverPng) {
        return new DirectTbtFrame(
                1, 0, 99, 0, "Head northeast", "", "Head northeast",
                maneuverPng, null, Collections.emptyList(),
                DirectTbtFrame.AlertOverlay.inactive());
    }

    private static byte[] png(int width, int height, int marker) {
        byte[] png = new byte[25];
        png[0] = (byte) 0x89;
        png[1] = 0x50;
        png[2] = 0x4e;
        png[3] = 0x47;
        png[12] = 0x49;
        png[13] = 0x48;
        png[14] = 0x44;
        png[15] = 0x52;
        writeInt(png, 16, width);
        writeInt(png, 20, height);
        png[24] = (byte) marker;
        return png;
    }

    private static void writeInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }
}
