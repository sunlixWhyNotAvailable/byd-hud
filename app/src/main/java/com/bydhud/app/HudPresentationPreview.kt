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
