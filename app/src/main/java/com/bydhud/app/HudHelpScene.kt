package com.bydhud.app

internal enum class HudHelpStreet { Latin, Cyrillic, Hidden }
internal enum class HudHelpDistance { Normal, Native, Clamped }
internal enum class HudHelpSpeed { None, Native, Maneuver, Lanes, CompositeManeuver, CompositeLanes }
internal enum class HudHelpWarning { None, Maneuver, Separate }
internal enum class HudHelpMap { None, DenzaOff, DenzaOn, Sl07 }

internal data class HudHelpScene(
    val maneuver: Boolean = true,
    val nativeManeuver: Boolean = true,
    val lanes: Boolean = true,
    val street: HudHelpStreet = HudHelpStreet.Latin,
    val distance: HudHelpDistance = HudHelpDistance.Normal,
    val etaMode: Int = 0,
    val etaMask: Int = 7,
    val etaStreet: Boolean = false,
    val streetFormat: EtaStreetFormat = EtaStreetFormat.Prefix,
    val speed: HudHelpSpeed = HudHelpSpeed.None,
    val nativeSignVisible: Boolean = true,
    val signSize: Float? = null,
    val warning: HudHelpWarning = HudHelpWarning.None,
    val map: HudHelpMap = HudHelpMap.None
)

internal enum class HudHelpEtaMetric { Arrival, Duration, Distance }

internal fun HudHelpScene.etaLabels(language: Language): List<String?> {
    if (etaMode !in 1..2) return listOf(null, null, null)
    val distance = when (etaMode) {
        1 -> language.choose("12 км", "12 km", "12 км")
        else -> language.choose("8,4 км", "8.4 km", "8,4 км")
    }
    val values = listOf(
        "18:45",
        language.choose("25 хв", "25 min", "25 мин"),
        distance
    )
    return values.mapIndexed { index, value -> value.takeIf { etaMask and (1 shl index) != 0 } }
}

internal fun HudHelpScene.streetText(language: Language): String? {
    if (warning == HudHelpWarning.Maneuver) return language.choose("Камера", "Camera", "Камера")
    val streetName = when (street) {
        HudHelpStreet.Latin -> "Dniprovske"
        HudHelpStreet.Cyrillic -> "Дніпровське"
        HudHelpStreet.Hidden -> null
    }
    if (etaMode != 2 || !etaStreet) return streetName

    val metrics = etaLabels(language).filterNotNull().joinToString(" | ")
    if (metrics.isEmpty()) return streetName
    if (streetFormat == EtaStreetFormat.Replace) return metrics
    val shortStreet = "Dn".takeIf { streetName != null }
    return when {
        shortStreet == null -> "[$metrics]"
        else -> "[$metrics] $shortStreet"
    }
}

internal fun HudHelpScene.etaColor(metric: HudHelpEtaMetric, presentation: HudPresentationPreview): Int =
    if (etaMode == 2) presentation.color(
        when (metric) {
            HudHelpEtaMetric.Arrival -> HudTextColorSlot.Arrival
            HudHelpEtaMetric.Duration -> HudTextColorSlot.Duration
            HudHelpEtaMetric.Distance -> HudTextColorSlot.Remaining
        }
    ) else HUD_HELP_NATIVE_ETA

private const val HUD_HELP_NATIVE_ETA = 0xFF00D8E7.toInt()
