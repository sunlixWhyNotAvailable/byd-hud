package com.bydhud.app

internal enum class EtaStreetFormat { Prefix, Replace }

internal enum class HudTextColorSlot(val titleUa: String, val titleEn: String) {
    Arrival("Колір часу прибуття", "Arrival time color"),
    Duration("Колір залишку часу", "Remaining time color"),
    Remaining("Колір залишку дистанції", "Remaining distance color"),
    Warning("Колір дистанції до попередження", "Warning distance color");

    fun title(ua: Boolean) = if (ua) titleUa else titleEn
}

/** Preview state used by the production help modal; it never owns persisted settings. */
internal data class HudPresentationPreview(
    val streetFormat: EtaStreetFormat = EtaStreetFormat.Prefix,
    val waitForFullText: Boolean = true,
    val arrivalColor: Int = -1,
    val durationColor: Int = -1,
    val remainingColor: Int = -1,
    val warningColor: Int = 0xFFFFFF00.toInt()
) {
    fun color(slot: HudTextColorSlot): Int = when (slot) {
        HudTextColorSlot.Arrival -> arrivalColor
        HudTextColorSlot.Duration -> durationColor
        HudTextColorSlot.Remaining -> remainingColor
        HudTextColorSlot.Warning -> warningColor
    }

    fun withColor(slot: HudTextColorSlot, argb: Int): HudPresentationPreview {
        val opaque = argb or 0xFF000000.toInt()
        return when (slot) {
            HudTextColorSlot.Arrival -> copy(arrivalColor = opaque)
            HudTextColorSlot.Duration -> copy(durationColor = opaque)
            HudTextColorSlot.Remaining -> copy(remainingColor = opaque)
            HudTextColorSlot.Warning -> copy(warningColor = opaque)
        }
    }

    fun waitApplies(etaStreetEnabled: Boolean) = etaStreetEnabled

}

/** Recolor only text coverage against the approved (1,6,13) schematic background. */
internal fun recolorHudTextPixel(pixel: Int, color: Int): Int {
    val coverage = (((pixel ushr 8) and 255) - 6).coerceAtLeast(0) / 249f
    if (coverage == 0f) return pixel
    fun channel(shift: Int, background: Int) =
        (background + (((color ushr shift) and 255) - background) * coverage).toInt().coerceIn(0, 255)
    return (pixel and 0xFF000000.toInt()) or (channel(16, 1) shl 16) or
        (channel(8, 6) shl 8) or channel(0, 13)
}
