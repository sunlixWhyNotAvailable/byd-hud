package com.bydhud.app

internal enum class DashboardWidgetShape { Off, Square, Circle }
internal enum class DashboardWidgetOrientation { Vertical, Horizontal }
internal enum class DashboardWidgetMode(val label: String) { IpcOff("IPC OFF"), Tbt("TBT"), Mini("MINI"), Full("FULL") }

internal data class DashboardWidgetLayout(
    val cellSize: Float,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val anchorX: Float,
    val anchorY: Float,
    val expandForward: Boolean
)

/** Widget appearance and gesture state; vehicle mode is never inferred from a tap. */
internal data class DashboardWidgetState(
    val shape: DashboardWidgetShape = DashboardWidgetShape.Off,
    val sizeDp: Int = 32,
    val transparency: Int = 0,
    val fillArgb: Int = 0xFF2F86F6.toInt(),
    val borderDp: Int = 2,
    val borderArgb: Int = 0xFF000000.toInt(),
    val cornerRadiusDp: Int = 0,
    val orientation: DashboardWidgetOrientation = DashboardWidgetOrientation.Vertical,
    val expandForward: Boolean = true,
    val autoCollapse: Boolean = true,
    val autoCollapseAfterInactivity: Boolean = true,
    val applyWindowProfile: Boolean = true,
    val expanded: Boolean = false,
    val xFraction: Float = 0.03f,
    val yFraction: Float = 0.45f,
    val hidden: Boolean = false
) {
    val enabled get() = shape != DashboardWidgetShape.Off
    val visible get() = enabled && !hidden
    val alpha get() = 1f - transparency.coerceIn(0, 100) / 100f
    val openingDirectionIndex: Int get() = when {
        orientation == DashboardWidgetOrientation.Vertical && !expandForward -> 0
        orientation == DashboardWidgetOrientation.Horizontal && expandForward -> 1
        orientation == DashboardWidgetOrientation.Vertical -> 2
        else -> 3
    }
    val cornerRadiusRange get() = 0..(sizeDp.coerceIn(SIZE_RANGE) / 2)

    fun normalized() = copy(
        sizeDp = sizeDp.coerceIn(SIZE_RANGE),
        transparency = transparency.coerceIn(0, 100),
        borderDp = borderDp.coerceIn(BORDER_RANGE),
        cornerRadiusDp = cornerRadiusDp.coerceIn(0, sizeDp.coerceIn(SIZE_RANGE) / 2),
        fillArgb = fillArgb or 0xFF000000.toInt(),
        borderArgb = borderArgb or 0xFF000000.toInt(),
        xFraction = if (xFraction.isFinite()) xFraction.coerceIn(0f, 1f) else 0.03f,
        yFraction = if (yFraction.isFinite()) yFraction.coerceIn(0f, 1f) else 0.45f
    )

    fun selectShape(value: DashboardWidgetShape) = copy(shape = value, hidden = false, expanded = false)
    fun selectOrientation(value: DashboardWidgetOrientation) = copy(orientation = value, expanded = false)
    fun selectDirection(forward: Boolean) = copy(expandForward = forward, expanded = false)
    fun selectOpeningDirection(index: Int) = when (index.coerceIn(0, 3)) {
        0 -> copy(orientation = DashboardWidgetOrientation.Vertical, expandForward = false, expanded = false)
        1 -> copy(orientation = DashboardWidgetOrientation.Horizontal, expandForward = true, expanded = false)
        2 -> copy(orientation = DashboardWidgetOrientation.Vertical, expandForward = true, expanded = false)
        else -> copy(orientation = DashboardWidgetOrientation.Horizontal, expandForward = false, expanded = false)
    }
    fun resize(value: Int): DashboardWidgetState {
        val size = value.coerceIn(SIZE_RANGE)
        return copy(sizeDp = size, cornerRadiusDp = cornerRadiusDp.coerceIn(0, size / 2))
    }
    fun selectCornerRadius(value: Int) = copy(cornerRadiusDp = value.coerceIn(cornerRadiusRange))
    fun toggleExpanded() = if (visible) copy(expanded = !expanded) else this
    fun onModeClick() = if (visible && expanded) copy(expanded = !autoCollapse) else this
    fun hide() = copy(hidden = true, expanded = false)
    fun onAppOpened() = copy(hidden = false, expanded = false)

    /** Null is the anchor/collapse field; modes are ordered outwards from it. */
    fun fields(forward: Boolean = expandForward): List<DashboardWidgetMode?> {
        val values = if (expanded) listOf(null) + DashboardWidgetMode.entries else listOf(null)
        return if (forward) values else values.reversed()
    }

    fun layout(windowWidth: Float, windowHeight: Float): DashboardWidgetLayout {
        val vertical = orientation == DashboardWidgetOrientation.Vertical
        val baseCell = minOf(sizeDp.coerceIn(SIZE_RANGE).toFloat(), windowWidth, windowHeight).coerceAtLeast(1f)
        // Fractions always locate the collapsed anchor, independently of menu dimensions.
        val anchorLeft = xFraction * (windowWidth - baseCell).coerceAtLeast(0f)
        val anchorTop = yFraction * (windowHeight - baseCell).coerceAtLeast(0f)
        val axisLength = if (vertical) windowHeight else windowWidth
        val anchorAxis = if (vertical) anchorTop else anchorLeft
        val before = anchorAxis
        val after = (axisLength - anchorAxis - baseCell).coerceAtLeast(0f)
        val extra = 4 * (baseCell + GAP)
        val forward = when {
            !expanded -> expandForward
            (if (expandForward) after else before) >= extra -> expandForward
            (if (expandForward) before else after) >= extra -> !expandForward
            else -> after >= before
        }
        // Like an anchored popup, open toward available space instead of moving the anchor.
        val cell = if (!expanded) baseCell else minOf(baseCell,
            if (forward) (axisLength - anchorAxis - GAP * 4) / 5
            else (anchorAxis - GAP * 4) / 4).coerceAtLeast(1f)
        val count = if (expanded) 5 else 1
        val length = cell * count + GAP * (count - 1)
        val width = if (vertical) cell else length
        val height = if (vertical) length else cell
        val anchorOffset = if (forward) 0f else length - cell
        val left = (anchorLeft - if (vertical) 0f else anchorOffset).coerceAtLeast(0f)
        val top = (anchorTop - if (vertical) anchorOffset else 0f).coerceAtLeast(0f)
        return DashboardWidgetLayout(cell, left, top, width, height,
            anchorLeft, anchorTop, forward)
    }

    fun dragBy(dx: Float, dy: Float, availableWidth: Float, availableHeight: Float) = copy(
        xFraction = if (availableWidth > 0f) (xFraction + dx / availableWidth).coerceIn(0f, 1f) else 0f,
        yFraction = if (availableHeight > 0f) (yFraction + dy / availableHeight).coerceIn(0f, 1f) else 0f
    )

    companion object {
        const val GAP = 2f
        val SIZE_RANGE = 24..160
        val BORDER_RANGE = 0..16
    }
}
