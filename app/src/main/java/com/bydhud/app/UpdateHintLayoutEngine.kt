package com.bydhud.app

import kotlin.math.ceil

internal data class UpdateHintBounds(val left: Int, val top: Int, val width: Int, val height: Int)

internal data class UpdateHintLayoutItem(
    val key: String,
    val preferredSizePercent: Int,
    val preferredWidthPx: Int,
    val preferredHeightPx: Int
)

internal data class UpdateHintLayoutPlacement(
    val xPx: Int,
    val yPx: Int,
    val widthPx: Int,
    val heightPx: Int,
    val effectiveSizePercent: Float
)

internal object UpdateHintLayoutEngine {
    fun layout(
        items: List<UpdateHintLayoutItem>,
        bounds: UpdateHintBounds,
        outerMarginPx: Int,
        gapPx: Int
    ): Map<String, UpdateHintLayoutPlacement> {
        if (items.isEmpty()) return emptyMap()
        for (rows in items.size downTo 1) {
            build(items, bounds, outerMarginPx, gapPx, rows, Float.POSITIVE_INFINITY)?.let { return it }
        }
        var low = 0f
        var high = items.maxOf { it.preferredSizePercent }.toFloat()
        repeat(40) {
            val ceiling = (low + high) / 2f
            if ((items.size downTo 1).any {
                    build(items, bounds, outerMarginPx, gapPx, it, ceiling) != null
                }) low = ceiling else high = ceiling
        }
        for (rows in items.size downTo 1) {
            build(items, bounds, outerMarginPx, gapPx, rows, low)?.let { return it }
        }
        return emptyMap()
    }

    private fun build(
        items: List<UpdateHintLayoutItem>,
        bounds: UpdateHintBounds,
        margin: Int,
        gap: Int,
        rows: Int,
        ceiling: Float
    ): Map<String, UpdateHintLayoutPlacement>? {
        val columns = ceil(items.size / rows.toDouble()).toInt()
        val widths = IntArray(items.size)
        val heights = IntArray(items.size)
        val percents = FloatArray(items.size)
        items.forEachIndexed { index, item ->
            val percent = minOf(item.preferredSizePercent.toFloat(), ceiling)
            val ratio = percent / item.preferredSizePercent
            percents[index] = percent
            widths[index] = ceil(item.preferredWidthPx * ratio).toInt().coerceAtLeast(1)
            heights[index] = ceil(item.preferredHeightPx * ratio).toInt().coerceAtLeast(1)
        }
        val rowHeights = IntArray(rows)
        val columnWidths = IntArray(columns)
        items.indices.forEach { index ->
            val column = index / rows
            val row = index % rows
            rowHeights[row] = maxOf(rowHeights[row], heights[index])
            columnWidths[column] = maxOf(columnWidths[column], widths[index])
        }
        val usedWidth = margin * 2 + columnWidths.sum() + gap * (columns - 1)
        val usedHeight = margin * 2 + rowHeights.sum() + gap * (rows - 1)
        if (usedWidth > bounds.width || usedHeight > bounds.height) return null

        val columnX = IntArray(columns)
        val rowY = IntArray(rows)
        for (column in 1 until columns) {
            columnX[column] = columnX[column - 1] + columnWidths[column - 1] + gap
        }
        for (row in 1 until rows) rowY[row] = rowY[row - 1] + rowHeights[row - 1] + gap
        return items.indices.associate { index ->
            val column = index / rows
            val row = index % rows
            items[index].key to UpdateHintLayoutPlacement(
                bounds.left + margin + columnX[column],
                bounds.top + margin + rowY[row],
                widths[index],
                heights[index],
                percents[index]
            )
        }
    }
}
