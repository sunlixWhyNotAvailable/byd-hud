package com.bydhud.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateHintLayoutEngineTest {
    @Test
    fun `unequal cards use row maxima in one column`() {
        val layout = UpdateHintLayoutEngine.layout(
            listOf(item("a", 100, 100), item("b", 120, 80), item("c", 80, 140)),
            UpdateHintBounds(0, 0, 500, 1000), 18, 8
        )
        assertPlacement(layout.getValue("a"), 18, 18, 100, 100, 100f)
        assertPlacement(layout.getValue("b"), 18, 126, 120, 80, 100f)
        assertPlacement(layout.getValue("c"), 18, 214, 80, 140, 100f)
    }

    @Test
    fun `five cards choose first fitting column-major row count`() {
        val layout = UpdateHintLayoutEngine.layout(
            (1..5).map { item("$it", 100, 100) },
            UpdateHintBounds(0, 0, 400, 260), 10, 5
        )
        assertEquals(listOf(10 to 10, 10 to 115, 115 to 10, 115 to 115, 220 to 10),
            (1..5).map { layout.getValue("$it").let { value -> value.xPx to value.yPx } })
    }

    @Test
    fun `common ceiling can shrink below fifty percent`() {
        val layout = UpdateHintLayoutEngine.layout(
            listOf(item("large", 200, 200)),
            UpdateHintBounds(0, 0, 100, 100), 10, 5
        ).getValue("large")
        assertEquals(10, layout.xPx)
        assertEquals(10, layout.yPx)
        assertTrue(layout.widthPx in 79..80)
        assertEquals(layout.widthPx, layout.heightPx)
        assertTrue(layout.effectiveSizePercent in 39.9f..40.01f)
    }

    @Test
    fun `largest preferred percentages shrink first under shared ceiling`() {
        val layout = UpdateHintLayoutEngine.layout(
            listOf(
                UpdateHintLayoutItem("small", 50, 50, 50),
                UpdateHintLayoutItem("large", 100, 100, 100)
            ),
            UpdateHintBounds(0, 0, 140, 130), 10, 5
        )
        assertEquals(50f, layout.getValue("small").effectiveSizePercent, 0.01f)
        // Two columns fit at65%; a single column would unnecessarily cap it at55%.
        assertTrue(layout.getValue("large").effectiveSizePercent in 64.9f..65.01f)
        assertEquals(65, layout.getValue("large").xPx)
        assertEquals(10, layout.getValue("large").yPx)
    }

    @Test
    fun `removal and larger bounds restore original geometry`() {
        val items = listOf(item("a", 200, 200), item("b", 200, 200))
        val constrained = UpdateHintLayoutEngine.layout(items,
            UpdateHintBounds(0, 0, 160, 250), 10, 10)
        assertTrue(constrained.getValue("a").effectiveSizePercent < 100f)

        val afterRemoval = UpdateHintLayoutEngine.layout(items.take(1),
            UpdateHintBounds(0, 0, 240, 240), 10, 10).getValue("a")
        assertPlacement(afterRemoval, 10, 10, 200, 200, 100f)

        val afterResize = UpdateHintLayoutEngine.layout(items,
            UpdateHintBounds(0, 0, 500, 500), 10, 10)
        assertEquals(100f, afterResize.getValue("a").effectiveSizePercent, 0f)
        assertEquals(100f, afterResize.getValue("b").effectiveSizePercent, 0f)
    }

    private fun item(key: String, width: Int, height: Int) =
        UpdateHintLayoutItem(key, 100, width, height)

    private fun assertPlacement(
        actual: UpdateHintLayoutPlacement,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        percent: Float
    ) {
        assertEquals(x, actual.xPx)
        assertEquals(y, actual.yPx)
        assertEquals(width, actual.widthPx)
        assertEquals(height, actual.heightPx)
        assertEquals(percent, actual.effectiveSizePercent, 0f)
    }
}
