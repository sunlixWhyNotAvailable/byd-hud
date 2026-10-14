package com.bydhud.app

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.content.res.Resources
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
internal fun HudHelpImage(
    scene: HudHelpScene,
    language: Language,
    presentation: HudPresentationPreview,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val resources = LocalContext.current.resources
    val artworkCache = remember(resources) { HudHelpArtworkCache(resources) }
    val atlas = artworkCache.atlas
    val warningArtwork = artworkCache.warning(scene.warning != HudHelpWarning.None)
    val hasMap = scene.map == HudHelpMap.DenzaOn || scene.map == HudHelpMap.Sl07
    val mapArtwork = artworkCache.map(hasMap)

    Canvas(modifier.semantics { this.contentDescription = contentDescription }) {
        val fitScale = min(size.width / HUD_HELP_WIDTH, size.height / HUD_HELP_HEIGHT)
        val left = (size.width - HUD_HELP_WIDTH * fitScale) / 2f
        val top = (size.height - HUD_HELP_HEIGHT * fitScale) / 2f
        withTransform({ translate(left, top); scale(fitScale, fitScale, Offset.Zero) }) {
            drawHudHelpScene(scene, language, presentation, atlas, warningArtwork, mapArtwork)
        }
    }
}

private class HudHelpArtworkCache(private val resources: Resources) {
    val atlas: ImageBitmap by lazy(LazyThreadSafetyMode.NONE) {
        BitmapFactory.decodeResource(resources, R.drawable.hud_help_artwork).asImageBitmap()
    }

    private var warningArtwork: ImageBitmap? = null
    private var mapArtwork: ImageBitmap? = null

    fun warning(enabled: Boolean): ImageBitmap? = if (!enabled) null else warningArtwork ?:
        BitmapFactory.decodeResource(resources, R.drawable.hud_help_warning_artwork).asImageBitmap()
            .also { warningArtwork = it }

    fun map(enabled: Boolean): ImageBitmap? = if (!enabled) null else mapArtwork ?:
        BitmapFactory.decodeResource(resources, R.drawable.hud_help_map_denza).asImageBitmap()
            .also { mapArtwork = it }
}

private fun DrawScope.drawHudHelpScene(
    scene: HudHelpScene,
    language: Language,
    presentation: HudPresentationPreview,
    atlas: ImageBitmap,
    warningArtwork: ImageBitmap?,
    mapArtwork: ImageBitmap?
) {
    drawRect(Color(0xFF01060D), size = Size(HUD_HELP_WIDTH, HUD_HELP_HEIGHT))
    when (scene.map) {
        HudHelpMap.DenzaOff, HudHelpMap.DenzaOn -> drawDenzaHud(scene, atlas)
        else -> drawStandardHud(scene, atlas)
    }

    drawWarningSprite(scene.warning, warningArtwork)
    drawDistance(scene)
    drawStreet(scene, language)
    if (scene.warning != HudHelpWarning.Maneuver) drawEta(scene, language, presentation)
    drawSeparateWarning(scene, language, presentation)
    drawAddedSpeedSign(scene, atlas)
    drawMap(scene.map, mapArtwork)
}

private fun DrawScope.drawStandardHud(scene: HudHelpScene, atlas: ImageBitmap) {
    val warningOnManeuver = scene.warning == HudHelpWarning.Maneuver
    if (scene.maneuver && !warningOnManeuver && scene.speed != HudHelpSpeed.Maneuver) {
        drawSprite(atlas, 337, 333, 77, 192)
    }
    if (scene.nativeManeuver && !warningOnManeuver) drawSprite(atlas, 952, 184, 306, 130)
    drawSprite(atlas, 1040, 320, 105, 100)
    if (scene.map != HudHelpMap.Sl07 && scene.lanes && scene.speed != HudHelpSpeed.Lanes) {
        drawSprite(atlas, 810, 440, 700, 100)
    }
    drawSprite(atlas, 1700, 330, 190, 230)
    if (scene.nativeSignVisible) {
        drawSpeedSign(atlas, 1546f, 347f, 110f, if (scene.speed == HudHelpSpeed.Native) "115" else "40", targetHeight = 111f)
    }
}

private fun DrawScope.drawDenzaHud(scene: HudHelpScene, atlas: ImageBitmap) {
    val warningOnManeuver = scene.warning == HudHelpWarning.Maneuver
    if (scene.maneuver && !warningOnManeuver && scene.speed != HudHelpSpeed.Maneuver) {
        drawSprite(atlas, 337, 333, 77, 192)
    }
    if (scene.nativeManeuver && !warningOnManeuver) drawSprite(atlas, 940, 180, 330, 130)
    if (scene.lanes && scene.speed != HudHelpSpeed.Lanes) {
        drawSprite(atlas, 810, 440, 700, 100, 325, 535, 525, 75)
    }
    drawSprite(atlas, 1700, 330, 190, 230, 985, 350, 190, 230)
}

private fun DrawScope.drawWarningSprite(warning: HudHelpWarning, artwork: ImageBitmap?) {
    if (warning == HudHelpWarning.None || artwork == null) return
    if (warning == HudHelpWarning.Maneuver) {
        drawSprite(artwork, 326, 374, 98, 98)
    } else {
        drawSprite(artwork, 326, 374, 98, 98, 64, 420, 98, 98)
    }
}

private fun DrawScope.drawDistance(scene: HudHelpScene) {
    val text = if (scene.warning == HudHelpWarning.Maneuver) "120" else when (scene.distance) {
        HudHelpDistance.Normal -> "350"
        HudHelpDistance.Clamped -> "11"
        HudHelpDistance.Native -> null
    }
    if (scene.distance == HudHelpDistance.Native && scene.warning != HudHelpWarning.Maneuver) {
        drawHudText("现在", 500f, 391f, 73f, HUD_CYAN, fontName = "sans-serif")
        return
    }
    if (text == null) return
    val color = HUD_CYAN
    drawHudText(text, 676f, 414f, 91f, color, align = Paint.Align.RIGHT)
    drawHudText("m", 680f, 414f, 47f, color)
}

private fun DrawScope.drawStreet(scene: HudHelpScene, language: Language) {
    val text = scene.streetText(language) ?: return
    val paint = hudPaint(47f, HUD_CYAN)
    val size = if (scene.etaMode != 2 && scene.street == HudHelpStreet.Cyrillic) {
        min(47f, 239f / paint.measureText(text).coerceAtLeast(1f) * 47f)
    } else 47f
    drawHudText(text, 501f, 503f, size, HUD_CYAN, clip = RectF(492f, 457f, 744f, 524f))
}

private fun DrawScope.drawEta(scene: HudHelpScene, language: Language, presentation: HudPresentationPreview) {
    val labels = scene.etaLabels(language)
    when (scene.etaMode) {
        1 -> {
            // Approved v2 row sits below the street, with a gap above its glyphs.
            val lefts = floatArrayOf(456f, 574f, 687f)
            labels.forEachIndexed { index, label ->
                if (label != null) drawHudText(label, lefts[index], 555f, 34f, Color(scene.etaColor(HudHelpEtaMetric.entries[index], presentation)))
            }
        }
        2 -> if (!scene.etaStreet) {
            val boxes = arrayOf(
                RectF(1996f, 350f, 2123f, 403f),
                RectF(1939f, 411f, 2123f, 466f),
                RectF(1984f, 466f, 2123f, 528f)
            )
            labels.forEachIndexed { index, label ->
                if (label != null) {
                    val box = boxes[index]
                    drawCenteredBoxText(
                        label, box, 42f,
                        Color(scene.etaColor(HudHelpEtaMetric.entries[index], presentation)),
                        bold = true, align = Paint.Align.RIGHT
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawSeparateWarning(
    scene: HudHelpScene,
    language: Language,
    presentation: HudPresentationPreview
) {
    if (scene.warning != HudHelpWarning.Separate) return
    val text = language.choose("120 м", "120 m", "120 м")
    drawCenteredBoxText(
        text, RectF(174f, 451f, 314f, 519f), 48f,
        Color(presentation.warningColor), bold = true
    )
}

private fun DrawScope.drawAddedSpeedSign(scene: HudHelpScene, atlas: ImageBitmap) {
    val value = "40"
    when (scene.speed) {
        HudHelpSpeed.Maneuver -> drawSpeedSign(atlas, 320f, 372f, 111f, value)
        HudHelpSpeed.Lanes -> {
            if (scene.map == HudHelpMap.DenzaOff || scene.map == HudHelpMap.DenzaOn) {
                drawSpeedSign(atlas, 562f, 537f, 54f, value)
            } else drawSpeedSign(atlas, 1126f, 443f, 72f, value)
        }
        HudHelpSpeed.CompositeManeuver -> {
            val diameter = scene.signSize?.takeIf(Float::isFinite)?.coerceAtLeast(1f) ?: 68f
            drawSpeedSign(atlas, 410f, 512f - diameter, diameter, value)
        }
        HudHelpSpeed.CompositeLanes -> {
            val diameter = scene.signSize?.takeIf(Float::isFinite)?.coerceAtLeast(1f) ?: 68f
            if (scene.map == HudHelpMap.DenzaOff || scene.map == HudHelpMap.DenzaOn) {
                val scale = 525f / 700f
                drawSpeedSign(atlas, 325f + (1360f - 810f) * scale, 535f + (447f - 440f) * scale, diameter * scale, value)
            } else drawSpeedSign(atlas, 1360f, 515f - diameter, diameter, value)
        }
        else -> Unit
    }
}

private fun DrawScope.drawSpeedSign(
    atlas: ImageBitmap,
    left: Float,
    top: Float,
    diameter: Float,
    value: String,
    targetHeight: Float = diameter
) {
    val xScale = diameter / 110f
    val yScale = targetHeight / 111f
    val ringBounds = RectF(left, top, left + 110f * xScale, top + 111f * yScale)
    val interiorBounds = RectF(
        left + 15f * xScale,
        top + 16f * yScale,
        left + 95f * xScale,
        top + 96f * yScale
    )
    val canvas = drawContext.canvas.nativeCanvas
    val save = canvas.save()
    val ring = Path().apply {
        fillType = Path.FillType.EVEN_ODD
        addOval(ringBounds, Path.Direction.CW)
        addOval(interiorBounds, Path.Direction.CW)
    }
    canvas.clipPath(ring)
    drawSprite(
        atlas, 1546, 347, 110, 111,
        left.roundToInt(), top.roundToInt(),
        diameter.roundToInt().coerceAtLeast(1), targetHeight.roundToInt().coerceAtLeast(1)
    )
    canvas.restoreToCount(save)
    drawHudCenteredText(value, left + diameter / 2f, top + targetHeight / 2f, 42f * diameter / 110f, HUD_CYAN)
}

private fun DrawScope.drawMap(map: HudHelpMap, artwork: ImageBitmap?) {
    if (artwork == null) return
    when (map) {
        HudHelpMap.DenzaOn -> drawSprite(artwork, 0, 0, artwork.width, artwork.height, 1450, 300, 450, 270)
        HudHelpMap.Sl07 -> drawSprite(artwork, 0, 0, artwork.width, artwork.height, 1190, 320, 330, 198)
        else -> Unit
    }
}

private fun DrawScope.drawSprite(
    image: ImageBitmap,
    sourceX: Int,
    sourceY: Int,
    sourceWidth: Int,
    sourceHeight: Int,
    destinationX: Int = sourceX,
    destinationY: Int = sourceY,
    destinationWidth: Int = sourceWidth,
    destinationHeight: Int = sourceHeight
) {
    drawImage(
        image = image,
        srcOffset = IntOffset(sourceX, sourceY),
        srcSize = IntSize(sourceWidth, sourceHeight),
        dstOffset = IntOffset(destinationX, destinationY),
        dstSize = IntSize(destinationWidth, destinationHeight),
        filterQuality = FilterQuality.Medium
    )
}

private fun DrawScope.drawCenteredBoxText(
    text: String,
    box: RectF,
    size: Float,
    color: Color,
    bold: Boolean = false,
    align: Paint.Align = Paint.Align.CENTER
) {
    val paint = hudPaint(size, color, bold, align)
    val x = when (align) {
        Paint.Align.LEFT -> box.left
        Paint.Align.RIGHT -> box.right
        else -> box.centerX()
    }
    val metrics = paint.fontMetrics
    val baseline = box.centerY() - (metrics.ascent + metrics.descent) / 2f
    drawHudText(text, x, baseline, size, color, bold, align, box)
}

private fun DrawScope.drawHudCenteredText(text: String, x: Float, centerY: Float, size: Float, color: Color) {
    val paint = hudPaint(size, color, align = Paint.Align.CENTER)
    val metrics = paint.fontMetrics
    drawHudText(text, x, centerY - (metrics.ascent + metrics.descent) / 2f, size, color, align = Paint.Align.CENTER)
}

private fun DrawScope.drawHudText(
    text: String,
    x: Float,
    baseline: Float,
    size: Float,
    color: Color,
    bold: Boolean = false,
    align: Paint.Align = Paint.Align.LEFT,
    clip: RectF? = null,
    fontName: String = "Arial"
) {
    val canvas = drawContext.canvas.nativeCanvas
    val paint = hudPaint(size, color, bold, align, fontName)
    val save = canvas.save()
    if (clip != null) canvas.clipRect(clip)
    canvas.drawText(text, x, baseline, paint)
    canvas.restoreToCount(save)
}

private fun hudPaint(
    size: Float,
    color: Color,
    bold: Boolean = false,
    align: Paint.Align = Paint.Align.LEFT,
    fontName: String = "Arial"
) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color.toArgb()
    textSize = size
    textAlign = align
    typeface = Typeface.create(fontName, if (bold) Typeface.BOLD else Typeface.NORMAL)
}

private const val HUD_HELP_WIDTH = 2172f
private const val HUD_HELP_HEIGHT = 724f
private val HUD_CYAN = Color(0xFF00D3E2)
