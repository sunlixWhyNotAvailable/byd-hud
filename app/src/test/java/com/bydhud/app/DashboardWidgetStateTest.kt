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
            assertEquals(collapsed.anchor, expanded.anchor)
            assertNull(collapsed.menu)
            for (x in listOf(0f, 1f)) for (y in listOf(0f, 1f)) {
                val edgeState = state.copy(sizeDp = 160, xFraction = x, yFraction = y)
                val edge = edgeState.toggleExpanded().layout(350f, 220f)
                val original = edgeState.layout(350f, 220f)
                assertNotNull(edge.menu)
                val menu = edge.menu!!
                assertEquals(original.anchor, edge.anchor)
                assertEquals(160f, edge.anchor.width, 0.01f)
                assertEquals(160f, edge.anchor.height, 0.01f)
                assertTrue(menu.left >= 0 && menu.top >= 0)
                assertTrue(menu.left + menu.width <= 350.01f && menu.top + menu.height <= 220.01f)
                assertTrue(menu.cellSize <= edge.anchor.width)
                if (orientation == DashboardWidgetOrientation.Vertical) {
                    assertTrue(menu.top + menu.height <= edge.anchor.top - DashboardWidgetState.GAP + 0.01f ||
                        menu.top >= edge.anchor.top + edge.anchor.height + DashboardWidgetState.GAP - 0.01f)
                } else {
                    assertTrue(menu.left + menu.width <= edge.anchor.left - DashboardWidgetState.GAP + 0.01f ||
                        menu.left >= edge.anchor.left + edge.anchor.width + DashboardWidgetState.GAP - 0.01f)
                }
            }
        }
    }

    @Test fun menuFallsBackWithoutChangingPreferenceAndOrdersModesOutward() {
        val preferredUpAtTop = DashboardWidgetState(shape = DashboardWidgetShape.Square,
            orientation = DashboardWidgetOrientation.Vertical, expandForward = false,
            xFraction = 0.4f, yFraction = 0f, expanded = true)
        val menu = preferredUpAtTop.layout(800f, 480f).menu!!
        assertTrue(menu.expandForward)
        assertFalse(preferredUpAtTop.expandForward)
        assertEquals(listOf(DashboardWidgetMode.IpcOff, DashboardWidgetMode.Tbt,
            DashboardWidgetMode.Mini, DashboardWidgetMode.Full),
            preferredUpAtTop.fields(menu.expandForward).filterNotNull())

        val preferredLeftAtRight = preferredUpAtTop.copy(
            orientation = DashboardWidgetOrientation.Horizontal, expandForward = false,
            xFraction = 1f, yFraction = 0.4f)
        val leftMenu = preferredLeftAtRight.layout(800f, 480f).menu!!
        assertFalse(leftMenu.expandForward)
        assertEquals(listOf(DashboardWidgetMode.Full, DashboardWidgetMode.Mini,
            DashboardWidgetMode.Tbt, DashboardWidgetMode.IpcOff),
            preferredLeftAtRight.fields(leftMenu.expandForward).filterNotNull())
    }

    @Test fun expandedMenuIsGapSeparatedFromPermanentAnchor() {
        for (orientation in DashboardWidgetOrientation.entries) for (forward in listOf(false, true)) {
            val layout = DashboardWidgetState(shape = DashboardWidgetShape.Circle,
                orientation = orientation, expandForward = forward, expanded = true,
                xFraction = 0.5f, yFraction = 0.5f).layout(1000f, 700f)
            val menu = layout.menu!!
            if (orientation == DashboardWidgetOrientation.Vertical) {
                if (menu.expandForward) assertEquals(layout.anchor.top + layout.anchor.height + DashboardWidgetState.GAP,
                    menu.top, 0.01f)
                else assertEquals(menu.top + menu.height + DashboardWidgetState.GAP, layout.anchor.top, 0.01f)
            } else {
                if (menu.expandForward) assertEquals(layout.anchor.left + layout.anchor.width + DashboardWidgetState.GAP,
                    menu.left, 0.01f)
                else assertEquals(menu.left + menu.width + DashboardWidgetState.GAP, layout.anchor.left, 0.01f)
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
