package com.bydhud.app

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateHintAppearanceTest {
    @Test fun normalizationKeepsApprovedRangesAndOpaqueBorderColor() {
        val normalized = UpdateHintAppearance(-4, 99, -2, 0x00123456, 20).normalized()
        assertEquals(0, normalized.transparencyPercent)
        assertEquals(40, normalized.cornerRadiusDp)
        assertEquals(0, normalized.borderWidthDp)
        assertEquals(0xFF123456.toInt(), normalized.borderArgb)
        assertEquals(50, normalized.sizePercent)
    }

    @Test fun fullTransparencyHasZeroAlphaAndMaximumPreferredScaleIsRetained() {
        val appearance = UpdateHintAppearance(transparencyPercent = 100, sizePercent = 150)
        assertEquals(0f, appearance.alpha, 0f)
        assertEquals(1.5f, appearance.scale, 0f)
        assertEquals(UpdateHintAppearance.DEFAULT_COLOR, UpdateHintAppearance().borderArgb)
    }
}
