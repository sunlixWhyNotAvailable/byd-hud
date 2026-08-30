package com.bydhud.app

import org.junit.Assert.*
import org.junit.Test

class DashboardWidgetStateTest {
    @Test fun defaultsAndPersistenceKeepAppearanceButNotExpansion() {
        val defaults = DashboardWidgetPreferences.decode(emptyMap<String, Any>())
        assertEquals(DashboardWidgetState(), defaults)
        assertFalse(defaults.visible)
        assertEquals(32, defaults.sizeDp)
        assertEquals(0, defaults.transparency)
        assertEquals(0, defaults.cornerRadiusDp)
        assertTrue(defaults.autoCollapse && defaults.autoCollapseAfterInactivity && defaults.applyWindowProfile)
        val customized = defaults.copy(shape = DashboardWidgetShape.Circle, sizeDp = 67,
            transparency = 31, fillArgb = 0xFF112233.toInt(), borderDp = 0,
            borderArgb = 0xFFABCDEF.toInt(), orientation = DashboardWidgetOrientation.Horizontal,
            expandForward = false, autoCollapse = false, autoCollapseAfterInactivity = false,
            applyWindowProfile = false, cornerRadiusDp = 17,
            xFraction = 0.84f, yFraction = 0.72f, hidden = true, expanded = true)
        assertEquals(customized.copy(expanded = false),
            DashboardWidgetPreferences.decode(DashboardWidgetPreferences.encode(customized)))
    }

    @Test fun invalidPersistedValuesUseDefaultsOrBounds() {
        val state = DashboardWidgetPreferences.decode(mapOf(
            "shape" to "bad", "orientation" to 5, "size" to -4,
            "transparency" to 200, "border" to 999, "fill" to 0x00112233,
            "x" to Float.NaN, "y" to 8f, "auto_collapse" to "no"
        ))
        assertEquals(DashboardWidgetShape.Off, state.shape)
        assertEquals(DashboardWidgetOrientation.Vertical, state.orientation)
        assertEquals(24, state.sizeDp)
        assertEquals(100, state.transparency)
        assertEquals(16, state.borderDp)
        assertEquals(0, state.cornerRadiusDp)
        assertEquals(0xFF112233.toInt(), state.fillArgb)
        assertEquals(0.03f, state.xFraction, 0f)
        assertEquals(1f, state.yFraction, 0f)
        assertTrue(state.autoCollapse)
        assertTrue(state.autoCollapseAfterInactivity)
    }

    @Test fun hideAndReopenNeverChangeShapeAndCollapseIsOptional() {
        val enabled = DashboardWidgetState(shape = DashboardWidgetShape.Square).toggleExpanded()
        assertEquals(5, enabled.fields().size)
        assertFalse(enabled.onModeClick().expanded)
        assertTrue(enabled.copy(autoCollapse = false).onModeClick().expanded)
        val hidden = enabled.hide()
        assertFalse(hidden.visible)
        assertFalse(hidden.expanded)
        val restored = DashboardWidgetPreferences.decode(DashboardWidgetPreferences.encode(hidden))
        assertFalse(restored.visible)
        assertTrue(restored.onAppOpened().visible)
        assertFalse(restored.onAppOpened().expanded)
        assertFalse(restored.selectShape(DashboardWidgetShape.Off).onAppOpened().visible)
        assertEquals(listOf(DashboardWidgetMode.Full, DashboardWidgetMode.Mini,
            DashboardWidgetMode.Tbt, DashboardWidgetMode.IpcOff, null),
            enabled.copy(expandForward = false).fields())
    }

    @Test fun settingsSnapshotDoesNotRewindCurrentGestureOrHiddenState() {
        val old = DashboardWidgetState(shape = DashboardWidgetShape.Circle)
        val current = old.copy(xFraction = 0.7f, yFraction = 0.8f, hidden = true)
        val edited = DashboardWidgetController.mergeSettings(current, old.copy(sizeDp = 52))
        assertEquals(52, edited.sizeDp)
        assertEquals(current.xFraction, edited.xFraction, 0f)
        assertEquals(current.yFraction, edited.yFraction, 0f)
        assertTrue(edited.hidden)
        assertFalse(DashboardWidgetController.mergeSettings(current,
            old.selectShape(DashboardWidgetShape.Square)).hidden)
    }

    @Test fun layoutsStayBoundedAndAnchorIsStableIncludingScreenEdges() {
        for (orientation in DashboardWidgetOrientation.entries) for (forward in listOf(false, true)) {
            val state = DashboardWidgetState(shape = DashboardWidgetShape.Circle,
                orientation = orientation, expandForward = forward, xFraction = 0.5f, yFraction = 0.5f)
            val collapsed = state.layout(1000f, 700f)
            val expanded = state.toggleExpanded().layout(1000f, 700f)
            assertEquals(collapsed.anchorX, expanded.anchorX, 0.01f)
            assertEquals(collapsed.anchorY, expanded.anchorY, 0.01f)
            for (x in listOf(0f, 1f)) for (y in listOf(0f, 1f)) {
                val edgeState = state.copy(sizeDp = 160, xFraction = x, yFraction = y)
                val edge = edgeState.toggleExpanded().layout(350f, 220f)
                val original = edgeState.layout(350f, 220f)
                assertTrue(edge.left >= 0 && edge.top >= 0)
                assertTrue(edge.left + edge.width <= 350.01f && edge.top + edge.height <= 220.01f)
                assertEquals(original.anchorX, edge.anchorX, 0.01f)
                assertEquals(original.anchorY, edge.anchorY, 0.01f)
                val actualAnchorLeft = edge.left + if (!edge.expandForward &&
                    orientation == DashboardWidgetOrientation.Horizontal) edge.width - edge.cellSize else 0f
                val actualAnchorTop = edge.top + if (!edge.expandForward &&
                    orientation == DashboardWidgetOrientation.Vertical) edge.height - edge.cellSize else 0f
                assertEquals(original.left, actualAnchorLeft, 0.01f)
                assertEquals(original.top, actualAnchorTop, 0.01f)
            }
        }
    }

    @Test fun openingDirectionAndCornerRadiusUsePreviewContract() {
        val base = DashboardWidgetState(shape = DashboardWidgetShape.Square)
        assertEquals(2, base.openingDirectionIndex)
        assertEquals(0, base.selectOpeningDirection(0).openingDirectionIndex)
        assertEquals(1, base.selectOpeningDirection(1).openingDirectionIndex)
        assertEquals(2, base.selectOpeningDirection(2).openingDirectionIndex)
        assertEquals(3, base.selectOpeningDirection(3).openingDirectionIndex)
        assertEquals(16, base.copy(sizeDp = 32).selectCornerRadius(99).cornerRadiusDp)
        assertEquals(12, base.copy(sizeDp = 24, cornerRadiusDp = 12).resize(24).cornerRadiusDp)
    }
}
