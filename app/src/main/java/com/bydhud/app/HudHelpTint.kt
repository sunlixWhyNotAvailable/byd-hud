package com.bydhud.app

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Fixed text-only masks; route artwork and street pixels remain untouched. */
internal fun tintedHudHelp(resources: Resources, imageRes: Int, colors: HudPresentationPreview): ImageBitmap {
    val decoded = BitmapFactory.decodeResource(resources, imageRes)
    val result = decoded.copy(Bitmap.Config.ARGB_8888, true)
    decoded.recycle()
    val regions = listOf(
        intArrayOf(1996, 350, 127, 53) to colors.arrivalColor,
        intArrayOf(1939, 411, 184, 55) to colors.durationColor,
        intArrayOf(1984, 466, 139, 62) to colors.remainingColor,
        intArrayOf(174, 451, 140, 68) to colors.warningColor
    )
    for ((index, entry) in regions.withIndex()) {
        val (region, color) = entry
        // Default artwork already has these colors; preserve its pixels exactly.
        if (color == if (index == 3) 0xFFFFFF00.toInt() else -1) continue
        val (x, y, width, height) = region
        val pixels = IntArray(width * height)
        result.getPixels(pixels, 0, width, x, y, width, height)
        for (i in pixels.indices) pixels[i] = recolorHudTextPixel(pixels[i], color)
        result.setPixels(pixels, 0, width, x, y, width, height)
    }
    return result.asImageBitmap()
}
