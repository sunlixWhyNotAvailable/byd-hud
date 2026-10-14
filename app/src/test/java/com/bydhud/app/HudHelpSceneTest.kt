package com.bydhud.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HudHelpSceneTest {
    @Test fun etaSamplesRespectDestinationLanguageAndDisabledMetrics() {
        val native = HudHelpScene(etaMode = 1)
        val alternative = native.copy(etaMode = 2)
        assertEquals(listOf("18:45", "25 хв", "12 км"), native.etaLabels(Language.Ua))
        assertEquals(listOf("18:45", "25 min", "12 km"), native.etaLabels(Language.En))
        assertEquals(listOf("18:45", "25 мин", "12 км"), native.etaLabels(Language.Ru))
        assertEquals(listOf("18:45", "25 хв", "8,4 км"), alternative.etaLabels(Language.Ua))
        assertEquals(listOf("18:45", "25 min", "8.4 km"), alternative.etaLabels(Language.En))
        assertEquals(listOf("18:45", "25 мин", "8,4 км"), alternative.etaLabels(Language.Ru))
        assertEquals(listOf(null, "25 min", null), native.copy(etaMask = 2).etaLabels(Language.En))
        assertEquals(listOf("18:45", null, "8.4 km"), alternative.copy(etaMask = 5).etaLabels(Language.En))
        assertEquals(listOf(null, null, null), alternative.copy(etaMask = 0).etaLabels(Language.En))
        assertEquals(listOf(null, null, null), alternative.copy(etaMode = 0).etaLabels(Language.En))
    }

    @Test fun streetFormattingAndWarningsUseTheSceneWithoutChangingItsSource() {
        val source = HudHelpScene(etaMode = 2, etaMask = 1, etaStreet = true)
        assertEquals("[18:45] Dn", source.streetText(Language.En))
        val replacement = source.copy(streetFormat = EtaStreetFormat.Replace, etaMask = 6)
        assertEquals("25 min | 8.4 km", replacement.streetText(Language.En))
        assertEquals("25 мин | 8,4 км", replacement.streetText(Language.Ru))
        assertEquals("Dniprovske", replacement.copy(etaMask = 0).streetText(Language.En))
        assertEquals("Dniprovske", source.copy(etaMask = 0).streetText(Language.En))
        assertEquals("Dniprovske", source.copy(etaMode = 0).streetText(Language.En))
        assertEquals("Дніпровське", source.copy(etaMode = 1, street = HudHelpStreet.Cyrillic).streetText(Language.Ua))
        assertNull(source.copy(etaMode = 0, street = HudHelpStreet.Hidden).streetText(Language.En))
        assertEquals("Camera", source.copy(warning = HudHelpWarning.Maneuver).streetText(Language.En))
        assertEquals("Камера", source.copy(warning = HudHelpWarning.Maneuver).streetText(Language.Ru))
        assertEquals("[18:45] Dn", source.streetText(Language.En))
    }

    @Test fun alternativeColorsAreIndependentAndNativeColorsStayNative() {
        val presentation = HudPresentationPreview()
            .withColor(HudTextColorSlot.Arrival, 0x112233)
            .withColor(HudTextColorSlot.Duration, 0x445566)
            .withColor(HudTextColorSlot.Remaining, 0x778899)
        val alternative = HudHelpScene(etaMode = 2)
        assertEquals(0xFF112233.toInt(), alternative.etaColor(HudHelpEtaMetric.Arrival, presentation))
        assertEquals(0xFF445566.toInt(), alternative.etaColor(HudHelpEtaMetric.Duration, presentation))
        assertEquals(0xFF778899.toInt(), alternative.etaColor(HudHelpEtaMetric.Distance, presentation))
        assertEquals(0xFF00D8E7.toInt(), alternative.copy(etaMode = 1).etaColor(HudHelpEtaMetric.Arrival, presentation))
    }
}
