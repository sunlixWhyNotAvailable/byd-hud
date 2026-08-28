package com.bydhud.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class HudCheckStateTest {
    @Test
    public void defaultsAndNoOpsAreStable() {
        HudCheckState state = new HudCheckState();
        assertEquals(HudCheckState.Mode.BASIC, state.mode);
        assertFalse(state.running);
        assertTrue(state.automatic);
        assertEquals("Straight", state.maneuverLabel(false));
        assertEquals("Прямо", state.maneuverLabel(true));
        assertSame(state, state.stop());
        assertSame(state, state.step(null, 1));
        assertSame(state, state.step(HudCheckState.Field.DISTANCE, 0));
        assertSame(state, state.withAutomatic(true));
        assertSame(state, state.withManeuverBitmap(false));
        assertSame(state, state.withLaneBitmap(false));
        assertSame(state, state.withTransliterate(false));
    }

    @Test
    public void selectorsWrapAndUkrainianStreetProjectionUsesProductionRules() {
        HudCheckState state = new HudCheckState();
        assertEquals(15555, state.step(HudCheckState.Field.DISTANCE, -1).distance());
        assertEquals("Kyiv", state.step(HudCheckState.Field.STREET, 2).withTransliterate(true)
                .effectiveStreet());
        assertEquals("ТЕСТ", state.sourceStreet());
        assertEquals("TEST", state.withTransliterate(true).effectiveStreet());
        assertEquals(state, state.step(HudCheckState.Field.MANEUVER, 11));
        assertEquals(state, state.step(HudCheckState.Field.LANES, 8));

        HudState semanticChoice = state.step(HudCheckState.Field.MANEUVER, 2).toHudState();
        assertEquals(3, semanticChoice.turnBitmapId);
        assertEquals(2, semanticChoice.maneuverId);
    }

    @Test
    public void extendedCycleIsPositionPreservingAndBaselineIsIndependent() {
        HudCheckState selected = new HudCheckState()
                .step(HudCheckState.Field.MANEUVER, 2)
                .step(HudCheckState.Field.LANES, 3)
                .withManeuverBitmap(true)
                .withLaneBitmap(true)
                .selectMode(HudCheckState.Mode.EXTENDED);
        assertEquals("Right", selected.toHudState().hudCheck.maneuverLabel(false));
        assertEquals(11, selected.toHudState().maneuverId);
        assertEquals(77, selected.toHudState().distanceToIntersection);
        assertEquals(5, selected.toHudState().numOfLanes);
        assertEquals("Continue straight", selected.toHudState().roadName);
        assertFalse(selected.toHudState().includeLaneBitmap);

        HudCheckState started = selected.toggleRun();
        HudCheckState held = started.withAutomatic(false).tick();
        assertEquals(started.extendedIndex, held.extendedIndex);
        HudCheckState advanced = held.stepExtended(1);
        assertEquals(1, advanced.extendedIndex);
        assertEquals(1, advanced.stop().extendedIndex);
        assertEquals(1, advanced.stop().toggleRun().extendedIndex);
        assertEquals(2, advanced.stop().stepExtended(1).extendedIndex);
        assertEquals(0, advanced.stop().stepExtended(24).extendedIndex);
        assertEquals(25, HudCheckPayload.extendedCount());
    }

    @Test
    public void allExtendedLabelsAndValuesArePresent() {
        HudCheckState state = new HudCheckState().selectMode(HudCheckState.Mode.EXTENDED);
        for (int i = 0; i < HudCheckPayload.extendedCount(); i++) {
            HudCheckState selected = state.withAutomatic(false).stepExtended(i);
            assertTrue(selected.extendedLabel(false).length() > 0);
            assertTrue(selected.extendedField().length() > 0);
            assertTrue(selected.extendedValue().length() > 0);
        }
        assertTrue(state.withAutomatic(false).stepExtended(20).extendedValue().contains("77 m"));
        assertTrue(state.withAutomatic(false).stepExtended(21).lanes().contains("S* | S"));
    }

    @Test
    public void hudStateCarriesSnapshotAndClearsItOnOutputClear() {
        HudState state = new HudCheckState().toHudState();
        assertSame(state.hudCheck, state.copy().hudCheck);
        assertTrue(state.copyForClear().hudCheck == null);
    }
}
