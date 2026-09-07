package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure geometry and content-key tests; Android raster/device tests stay in the parent gate. */
public final class HudExperimentalCompositorTest {
    @Test
    public void usesApprovedZeroOffsetPlaneGeometry() {
        assertTrue(HudExperimentalLayout.isCanonicalF8(720, 48));
        assertTrue(HudExperimentalLayout.isCanonicalF7(960, 48));
        assertEquals(1_350f, HudExperimentalLayout.etaCenterX(720, 48), 0.001f);
        assertEquals(-2f, HudExperimentalLayout.effectiveDurationY(), 0.001f);
        assertEquals(24.166666f, HudExperimentalLayout.rowCenter(0), 0.001f);
        assertEquals(72.5f, HudExperimentalLayout.rowCenter(1), 0.001f);
        assertEquals(120.83333f, HudExperimentalLayout.rowCenter(2), 0.001f);
        assertEquals(480f, HudExperimentalLayout.laneCenterX(960), 0.001f);
        assertEquals(81.6f, HudExperimentalLayout.warningCenterX(960), 0.001f);
    }

    @Test
    public void eachPlaneOwnsCompleteEtaRows() {
        int rasterHeight = 580;
        assertEquals(0, HudExperimentalLayout.rowSourceTop(false, 0, rasterHeight));
        assertEquals(193, HudExperimentalLayout.rowSourceBottom(false, 0, rasterHeight));
        assertEquals(193, HudExperimentalLayout.rowSourceTop(false, 1, rasterHeight));
        assertEquals(386, HudExperimentalLayout.rowSourceBottom(false, 1, rasterHeight));
        assertEquals(0, HudExperimentalLayout.rowSourceTop(false, 2, rasterHeight));
        assertEquals(0, HudExperimentalLayout.rowSourceBottom(false, 2, rasterHeight));
        assertEquals(386, HudExperimentalLayout.rowSourceTop(true, 2, rasterHeight));
        assertEquals(580, HudExperimentalLayout.rowSourceBottom(true, 2, rasterHeight));
    }

    @Test
    public void contentKeyUsesBytesStringsAndRelevantColorsOnly() {
        byte[] maneuver = new byte[]{1, 2};
        HudExperimentalCompositor.Inputs first = input(
                "18:45", "25 min", "8.4 km", maneuver, new byte[]{3}, new byte[]{4}, null,
                new HudExperimentalCompositor.Colors(0x00112233, 0x00445566,
                        0x00778899, 0x00aabbcc));
        HudExperimentalCompositor.Inputs equal = input(
                "18:45", "25 min", "8.4 km", new byte[]{1, 2}, new byte[]{3}, new byte[]{4}, null,
                new HudExperimentalCompositor.Colors(0xff112233, 0xff445566,
                        0xff778899, 0xffaabbcc));
        assertTrue(HudExperimentalCompositor.sameContent(first, equal));
        maneuver[0] = 9;
        assertTrue(HudExperimentalCompositor.sameContent(first, equal));

        assertFalse(HudExperimentalCompositor.sameContent(first, input(
                "18:45", "25 min", "8.4 km", new byte[]{1, 9}, new byte[]{3}, new byte[]{4}, null,
                equal.colors)));
        assertFalse(HudExperimentalCompositor.sameContent(first, input(
                "18:45", "25 min", "8.4 km", new byte[]{1, 2}, new byte[]{3}, new byte[]{4}, null,
                new HudExperimentalCompositor.Colors(0xff112234, 0xff445566,
                        0xff778899, 0xffaabbcc))));
    }

    @Test
    public void noContentAndOptionalWarningDistanceAreDistinct() {
        HudExperimentalCompositor.Inputs noContent = input(
                "", "", "", null, null, null, null,
                new HudExperimentalCompositor.Colors(0xff000001, 0xff000002,
                        0xff000003, 0xff000004));
        HudExperimentalCompositor.Inputs noContentOtherColors = input(
                "", "", "", null, null, null, null,
                new HudExperimentalCompositor.Colors(0xff111111, 0xff222222,
                        0xff333333, 0xff444444));
        assertFalse(HudExperimentalCompositor.hasMeaningfulF8Content(noContent));
        assertFalse(HudExperimentalCompositor.hasMeaningfulF7Content(noContent));
        assertTrue(HudExperimentalCompositor.sameContent(noContent, noContentOtherColors));

        HudExperimentalCompositor.Inputs warningWithoutDistance = input(
                "", "", "", null, null, new byte[]{7}, null, noContent.colors);
        HudExperimentalCompositor.Inputs warningWithDistance = input(
                "", "", "", null, null, new byte[]{7}, "120 m", noContent.colors);
        assertTrue(HudExperimentalCompositor.hasMeaningfulF7Content(warningWithoutDistance));
        assertFalse(HudExperimentalCompositor.sameContent(warningWithoutDistance, warningWithDistance));
    }

    @Test
    public void colorsAlwaysBecomeOpaque() {
        HudExperimentalCompositor.Colors colors = new HudExperimentalCompositor.Colors(
                0x00112233, 0x00445566, 0x00778899, 0x00aabbcc);
        assertEquals(0xff112233, colors.arrival);
        assertEquals(0xff445566, colors.remainingTime);
        assertEquals(0xff778899, colors.remainingDistance);
        assertEquals(0xffaabbcc, colors.warningDistance);
    }

    @Test
    public void cacheRendersOnlyWhenMeaningfulContentChanges() {
        final int[] renderCount = new int[]{0};
        HudExperimentalCompositor compositor = new HudExperimentalCompositor(input -> {
            renderCount[0]++;
            return new HudExperimentalCompositor.Result(
                    HudExperimentalCompositor.hasMeaningfulF8Content(input)
                            ? new byte[]{8} : null,
                    HudExperimentalCompositor.hasMeaningfulF7Content(input)
                            ? new byte[]{7} : null);
        });
        HudExperimentalCompositor.Inputs first = input(
                "18:45", "25 min", "", null, null, null, null,
                HudExperimentalCompositor.Colors.defaults());
        HudExperimentalCompositor.Inputs same = input(
                "18:45", "25 min", "", null, null, null, null,
                new HudExperimentalCompositor.Colors(0xffffffff, 0xffffffff,
                        0xffbbbbbb, 0xffff0000));
        assertTrue(compositor.compose(first).hasF8());
        assertTrue(compositor.compose(same).hasF8());
        assertEquals(1, renderCount[0]);

        HudExperimentalCompositor.Inputs changed = input(
                "18:46", "25 min", "", null, null, null, null,
                same.colors);
        assertTrue(compositor.compose(changed).hasF8());
        assertEquals(2, renderCount[0]);

        HudExperimentalCompositor.Inputs empty = HudExperimentalCompositor.Inputs.empty();
        assertTrue(compositor.compose(empty).isEmpty());
        assertTrue(compositor.compose(HudExperimentalCompositor.Inputs.empty()).isEmpty());
        assertEquals(2, renderCount[0]); // Empty content clears cached planes without rasterization.
    }

    @Test
    public void colorChangesArePlaneLocalAndFailureCanRetrySameContent() {
        final int[] upper = {0};
        final int[] lower = {0};
        final boolean[] failUpper = {true};
        HudExperimentalCompositor compositor = new HudExperimentalCompositor(input -> {
            if (HudExperimentalCompositor.hasMeaningfulF8Content(input)) {
                upper[0]++;
                if (failUpper[0]) return HudExperimentalCompositor.Result.empty();
                return new HudExperimentalCompositor.Result(new byte[]{8}, null);
            }
            lower[0]++;
            return new HudExperimentalCompositor.Result(null, new byte[]{7});
        });
        HudExperimentalCompositor.Inputs first = input("18:45", "25 min", "8 km",
                null, null, new byte[]{9}, "100 m", HudExperimentalCompositor.Colors.defaults());
        assertFalse(compositor.compose(first).hasF8());
        failUpper[0] = false;
        assertTrue(compositor.compose(first).hasF8());
        assertEquals(2, upper[0]);
        assertEquals(1, lower[0]);
        compositor.compose(input("18:45", "25 min", "8 km", null, null, new byte[]{9}, "100 m",
                new HudExperimentalCompositor.Colors(-1, -1, -1, 0xffff0000)));
        assertEquals(2, upper[0]);
        assertEquals(2, lower[0]);
        compositor.compose(input("18:45", "25 min", "8 km", null, null, new byte[]{9}, "100 m",
                new HudExperimentalCompositor.Colors(0xff00ff00, -1, -1, 0xffff0000)));
        assertEquals(3, upper[0]);
        assertEquals(2, lower[0]);
    }

    @Test
    public void failedEtaCannotCachePrimaryOnlyPlane() throws Exception {
        final boolean[] etaAvailable = {false};
        final int[] renders = {0};
        HudExperimentalCompositor compositor = new HudExperimentalCompositor(input -> {
            renders[0]++;
            if (HudExperimentalCompositor.isEtaRasterMissing(input, etaAvailable[0])) {
                return HudExperimentalCompositor.Result.empty();
            }
            return new HudExperimentalCompositor.Result(new byte[]{8}, null);
        });
        HudExperimentalCompositor.Inputs mixed = input("18:45", "25 min", "",
                new byte[]{1}, null, null, "", HudExperimentalCompositor.Colors.defaults());
        assertTrue(compositor.compose(mixed).isEmpty());
        assertTrue(compositor.compose(mixed).isEmpty());
        assertEquals(2, renders[0]);
        etaAvailable[0] = true;
        assertTrue(compositor.compose(mixed).hasF8());
        assertTrue(compositor.compose(mixed).hasF8());
        assertEquals(3, renders[0]);
        assertFalse(HudExperimentalCompositor.isEtaRasterMissing(input("", "", "",
                new byte[]{1}, null, null, "", mixed.colors), false));

        // Bind the pure failure seam to the Android renderer: it must reject the
        // result before primary art can turn a failed ETA raster into a cache hit.
        java.nio.file.Path path = java.nio.file.Paths.get(
                "src/main/java/com/bydhud/app/HudExperimentalCompositor.java");
        if (!java.nio.file.Files.exists(path)) path = java.nio.file.Paths.get(
                "app/src/main/java/com/bydhud/app/HudExperimentalCompositor.java");
        String source = new String(java.nio.file.Files.readAllBytes(path),
                java.nio.charset.StandardCharsets.UTF_8);
        int guard = source.indexOf("if (isEtaRasterMissing(input, eta != null)) return Result.empty();");
        assertTrue(guard > source.indexOf("eta = renderEta(input);"));
        assertTrue(guard < source.indexOf("f8 = renderF8(input, eta);"));
    }

    private static HudExperimentalCompositor.Inputs input(
            String arrival, String duration, String remaining,
            byte[] maneuver, byte[] lane, byte[] warning, String warningDistance,
            HudExperimentalCompositor.Colors colors) {
        return new HudExperimentalCompositor.Inputs(arrival, duration, remaining,
                maneuver, lane, warning, warningDistance, colors);
    }
}
