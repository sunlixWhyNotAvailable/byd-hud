package com.bydhud.app;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.bydhud.app.NavigatorPatchStore.*;

public class NavigatorComponentStateTest {
    @Test public void routeExportRequiresExactlyOneCompatibleOwnerIn525() {
        assertEquals(UNSUPPORTED, NavigatorPatchPipeline.routeExportState("5.25.0.1", 0, NOT_CHECKED));
        assertEquals(UNSUPPORTED, NavigatorPatchPipeline.routeExportState("5.25.0.1", 2, PATCHED));
        assertEquals(UNSUPPORTED, NavigatorPatchPipeline.routeExportState("5.25.0.1", 1, UNSUPPORTED));
        assertEquals(PATCHABLE, NavigatorPatchPipeline.routeExportState("5.25.0.1", 1, PATCHABLE));
        assertEquals(PATCHED, NavigatorPatchPipeline.routeExportState("5.25.0.1", 1, PATCHED));
        assertEquals(NOT_CHECKED, NavigatorPatchPipeline.routeExportState("5.20.0.1", 0, NOT_CHECKED));
    }

    @Test public void missingAudioOrExportRemainsPatchableWithCurrentStabilityAndMap() {
        assertTrue(isPatchEnabled(Profile.WAZE, PATCHED, PATCHED, PATCHED, PATCHED, PATCHED, PATCHABLE, PATCHED));
        assertTrue(isPatchEnabled(Profile.WAZE, PATCHED, PATCHED, PATCHED, PATCHED, PATCHED, PATCHED, PATCHABLE));
        assertFalse(isPatchEnabled(Profile.WAZE, PATCHED, PATCHED, PATCHED, PATCHED, PATCHED, PATCHED, PATCHED));
        assertFalse(isPatchEnabled(Profile.WAZE, UNSUPPORTED, PATCHED, PATCHED, PATCHED, PATCHED, PATCHABLE, PATCHABLE));
    }

    @Test public void mapResultPreservesIndependentComponentsAndDetectsTheirRegression() {
        var initial = result(PATCHED, PATCHABLE);
        var withMap = NavigatorPatchPipeline.withMap(initial, PATCHED, "current", "revision");
        assertEquals(PATCHED, withMap.audioState);
        assertEquals(PATCHABLE, withMap.exportState);
        assertTrue(NavigatorPatchPipeline.navigationComponentsUnchanged(initial, withMap));
        assertFalse(NavigatorPatchPipeline.navigationComponentsUnchanged(initial, result(PATCHABLE, PATCHABLE)));
        assertFalse(NavigatorPatchPipeline.navigationComponentsUnchanged(initial, result(PATCHED, UNSUPPORTED)));
    }

    private static NavigatorPatchPipeline.ScanResult result(String audio, String export) {
        return new NavigatorPatchPipeline.ScanResult(Profile.WAZE, "", "5.25.0.1", 1030736, "",
                PATCHED, PATCHED, PATCHED, PATCHED, "", PATCHABLE, "", "", audio, export);
    }
}
