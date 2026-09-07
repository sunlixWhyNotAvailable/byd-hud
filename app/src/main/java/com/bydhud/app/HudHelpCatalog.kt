package com.bydhud.app

internal enum class HudHelpTopicId {
    BasicPng,
    BasicNative,
    BasicLanes,
    BasicStreet,
    BasicTransliteration,
    BasicDistance,
    SmallDistanceClamp,
    EtaMode,
    EtaOutputField,
    EtaStreetFormat,
    TextColor,
    EtaOutput,
    RemainingTime,
    RemainingDistance,
    SpeedLimitMode,
    SpeedLimitFallback,
    SpeedLimitCompositeField,
    SpeedLimitCompositeManeuverSize,
    SpeedLimitCompositeLaneSize,
    WazeAlerts,
    WazeAlertField
}

internal data class HudHelpFrame(
    val labelUa: String,
    val labelEn: String,
    val captionUa: String,
    val captionEn: String,
    val imageRes: Int
) {
    fun label(ua: Boolean): String = if (ua) labelUa else labelEn
    fun caption(ua: Boolean): String = if (ua) captionUa else captionEn
}

internal data class HudHelpTopic(
    val id: HudHelpTopicId,
    val titleUa: String,
    val titleEn: String,
    val frames: List<HudHelpFrame>
) {
    fun title(ua: Boolean): String = if (ua) titleUa else titleEn
}

internal object HudHelpCatalog {
    val topics: List<HudHelpTopic> = listOf(
        topic(HudHelpTopicId.EtaStreetFormat, "Формат ЕТА у полі вулиці", "ETA format in street field",
            frame("Приставити", "Prepend", "", "", R.drawable.hud_help_eta_street_1),
            frame("Замінити", "Replace", "", "", R.drawable.hud_help_eta_replace_1)),
        topic(HudHelpTopicId.TextColor, "Колір тексту", "Text color",
            frame("Колір", "Color", "", "", R.drawable.hud_help_eta_all)),
        topic(HudHelpTopicId.BasicPng, "PNG", "PNG output", frame("Вимкнено", "Off", "OFF: PNG-вивід вимкнено.", "OFF: PNG output is disabled.", R.drawable.hud_help_no_maneuver), frame("Увімкнено", "On", "ON: надсилати PNG маневру, коли зображення доступне.", "ON: send the maneuver PNG when an image is available.")),
        topic(HudHelpTopicId.BasicNative, "Штатний маневр", "Native output", frame("Вимкнено", "Off", "OFF: штатний динамічний вивід вимкнено.", "OFF: native dynamic output is disabled.", R.drawable.hud_help_no_dynamic), frame("Увімкнено", "On", "ON: надсилати штатне динамічне зображення, коли воно доступне.", "ON: send the native dynamic image when available.")),
        topic(HudHelpTopicId.BasicLanes, "Смуги", "Lane output", frame("Вимкнено", "Off", "OFF: вивід смуг вимкнено.", "OFF: lane output is disabled.", R.drawable.hud_help_no_lanes), frame("Увімкнено", "On", "ON: надсилати смуги, коли навігація їх надала.", "ON: send lanes when navigation provides them.")),
        topic(HudHelpTopicId.BasicStreet, "Вулиця", "Street output", frame("Вимкнено", "Off", "OFF: текст вулиці не надсилається.", "OFF: street text is not sent.", R.drawable.hud_help_no_street), frame("Увімкнено", "On", "ON: надсилати наступну дорогу або назву вулиці, коли доступна.", "ON: send the next road or street name when available.")),
        topic(HudHelpTopicId.BasicTransliteration, "Транслітерація", "Text transliteration", frame("Вимкнено", "Off", "OFF: джерельний текст залишається кириличним.", "OFF: source text remains Cyrillic.", R.drawable.hud_help_street_cyrillic), frame("Українська", "Ukrainian", "Українська: приклад кириличного тексту, переданого латиницею.", "Ukrainian: example of Cyrillic text rendered in Latin characters.", R.drawable.hud_help_baseline), frame("Універсальна", "Universal", "Універсальна: для цього джерела використовується та сама латинська версія.", "Universal: this source uses the same Latin version.", R.drawable.hud_help_baseline)),
        topic(HudHelpTopicId.BasicDistance, "Дистанція", "Distance output", frame("Вимкнено", "Off", "OFF: надсилається нульова дистанція, тому HUD показує штатний маркер «现在».", "OFF: zero distance is sent, so the HUD shows the native “现在” marker.", R.drawable.hud_help_no_distance), frame("Увімкнено", "On", "ON: надсилати дистанцію до маневру, коли доступна.", "ON: send distance to the maneuver when available.")),
        topic(HudHelpTopicId.SmallDistanceClamp, "Мала дистанція", "Small distance clamp", frame("Вимкнено", "Off", "OFF: для raw 0–10 м залишається штатний маркер «现在».", "OFF: raw 0–10 m remains the native “现在” marker.", R.drawable.hud_help_distance_native), frame("Увімкнено", "On", "ON: ті самі raw 0–10 м надсилаються як 11 м.", "ON: the same raw 0–10 m is sent as 11 m.", R.drawable.hud_help_distance_clamped)),
        topic(HudHelpTopicId.EtaMode, "Режим виводу ЕТА (час/дистанція)", "ETA output mode (time/distance)", frame("Вимкнено", "Off", "OFF: поля ETA/часу/дистанції не формуються.", "OFF: ETA/time/distance fields are not formed.", R.drawable.hud_help_baseline), frame("До зупинки", "Next stop", "Показувати доступні значення до наступної проміжної або кінцевої точки; Waze надає лише значення до наступної зупинки.", "Show available values to the next intermediate or final stop; Waze provides only next-stop values.", R.drawable.hud_help_eta_all), frame("Весь маршрут", "Entire route", "Показувати доступні значення до кінцевої точки; якщо джерело їх не дає, використовується значення до наступної зупинки.", "Show available values to the final destination; when unavailable at source, use the next-stop value.", R.drawable.hud_help_eta_all)),
        topic(HudHelpTopicId.EtaOutputField, "Поле виводу ЕТА", "ETA output field", frame("Вулиці", "Street", "Поля ETA показані у вуличному полі.", "ETA fields are shown in the street field.", R.drawable.hud_help_eta_street_7), frame("Експериментальне", "Experimental", "Поля ETA показані в окремому блоці праворуч.", "ETA fields are shown in a separate block on the right.", R.drawable.hud_help_eta_all)),
        topic(HudHelpTopicId.EtaOutput, "Показ ETA", "Show ETA", frame("Вимкнено", "Off", "OFF: час прибуття не показується ні у вуличному полі, ні в окремому блоці.", "OFF: arrival time is hidden in both the street field and separate block.", R.drawable.hud_help_baseline), frame("Увімкнено", "On", "ON: час прибуття показано у вуличному та експериментальному блоках.", "ON: arrival time is shown in both street and experimental blocks.", R.drawable.hud_help_eta_both_1)),
        topic(HudHelpTopicId.RemainingTime, "Залишок часу", "Remaining time", frame("Вимкнено", "Off", "OFF: залишок часу не показується ні у вуличному полі, ні в окремому блоці.", "OFF: remaining time is hidden in both the street field and separate block.", R.drawable.hud_help_baseline), frame("Увімкнено", "On", "ON: залишок часу показано у вуличному та експериментальному блоках.", "ON: remaining time is shown in both street and experimental blocks.", R.drawable.hud_help_eta_both_2)),
        topic(HudHelpTopicId.RemainingDistance, "Залишок дистанції", "Remaining distance", frame("Вимкнено", "Off", "OFF: залишок дистанції не показується ні у вуличному полі, ні в окремому блоці.", "OFF: remaining distance is hidden in both the street field and separate block.", R.drawable.hud_help_baseline), frame("Увімкнено", "On", "ON: залишок дистанції показано у вуличному та експериментальному блоках.", "ON: remaining distance is shown in both street and experimental blocks.", R.drawable.hud_help_eta_both_4)),
        topic(HudHelpTopicId.SpeedLimitMode, "Режим обмеження швидкості", "Speed limit output mode", frame("Вимкнено", "Off", "OFF: доданий знак обмеження не надсилається; штатний знак біля швидкості залишається на місці.", "OFF: the added speed sign is not sent; the native speed-limit glyph remains separate and fixed.", R.drawable.hud_help_baseline), frame("Поле маневру", "Maneuver field", "Знак обмеження розміщено в полі маневру; штатний знак біля швидкості не змінюється.", "The added speed sign is placed in the maneuver field; the native speed-limit glyph is separate.", R.drawable.hud_help_speed_maneuver), frame("Поле смуг", "Lane field", "Знак обмеження розміщено в полі смуг; штатний знак біля швидкості не змінюється.", "The added speed sign is placed in the lane field; the native speed-limit glyph is separate.", R.drawable.hud_help_speed_lanes), frame("Вільне → маневр", "Free → maneuver", "Якщо поле маневру вільне, знак займає його. Штатний знак біля швидкості не змінюється.", "If the maneuver field is empty, the sign uses it. The factory sign beside vehicle speed stays unchanged.", R.drawable.hud_help_speed_maneuver), frame("Вільне → смуги", "Free → lanes", "Якщо поле смуг вільне, знак займає його. Штатний знак біля швидкості не змінюється.", "If the lane field is empty, the sign uses it. The factory sign beside vehicle speed stays unchanged.", R.drawable.hud_help_speed_lanes), frame("Композитний маневр", "Composite maneuver", "Композитний доданий знак у полі маневру; позиція залежить від bitmap-вмісту, v4 — лише ілюстрація. Штатний знак біля швидкості не змінюється.", "Composite added sign in the maneuver field; position depends on bitmap content, and v4 is illustrative only. The native speed-limit glyph is separate.", R.drawable.hud_help_speed_composite_maneuver), frame("Композитні смуги", "Composite lanes", "Композитний доданий знак у полі смуг; позиція залежить від bitmap-вмісту, v4 — лише ілюстрація. Штатний знак біля швидкості не змінюється.", "Composite added sign in the lane field; position depends on bitmap content, and v4 is illustrative only. The native speed-limit glyph is separate.", R.drawable.hud_help_speed_composite_lanes)),
        topic(HudHelpTopicId.SpeedLimitFallback, "Накладання обмеження", "Speed limit fallback", frame("Без накладання", "No overlay", "Обидва поля зайняті, накладання вимкнене: маневр і смуги залишаються без змін.", "Both fields are occupied and overlay is off: maneuver and lanes remain unchanged.", R.drawable.hud_help_baseline), frame("Маневр", "Maneuver", "Коли обидва поля зайняті, знак тимчасово замінює маневр.", "When both fields are occupied, the sign temporarily replaces the maneuver.", R.drawable.hud_help_speed_maneuver), frame("Смуги", "Lanes", "Коли обидва поля зайняті, знак тимчасово замінює смуги.", "When both fields are occupied, the sign temporarily replaces the lanes.", R.drawable.hud_help_speed_lanes)),
        topic(HudHelpTopicId.SpeedLimitCompositeField, "Поле композитного знаку", "Composite output field", frame("Маневр", "Maneuver", "Композитний знак у полі маневру; позиція залежить від bitmap-вмісту, v4 — лише ілюстрація.", "Composite sign in the maneuver field; position depends on bitmap content, and v4 is illustrative only.", R.drawable.hud_help_speed_composite_maneuver), frame("Смуги", "Lanes", "Композитний знак у полі смуг; позиція залежить від bitmap-вмісту, v4 — лише ілюстрація.", "Composite sign in the lane field; position depends on bitmap content, and v4 is illustrative only.", R.drawable.hud_help_speed_composite_lanes), frame("Вільне або маневру", "Free or maneuver", "Вільне поле має пріоритет; якщо обидва зайняті, знак додається до обраного зображення. Позиція залежить від bitmap-вмісту, v4 — лише ілюстрація.", "A free field has priority; if both are occupied, the sign is composed into the selected image. Position depends on bitmap content, and v4 is illustrative only.", R.drawable.hud_help_speed_composite_maneuver), frame("Вільне або смуг", "Free or lanes", "Вільне поле має пріоритет; якщо обидва зайняті, знак додається до обраного зображення. Позиція залежить від bitmap-вмісту, v4 — лише ілюстрація.", "A free field has priority; if both are occupied, the sign is composed into the selected image. Position depends on bitmap content, and v4 is illustrative only.", R.drawable.hud_help_speed_composite_lanes)),
        topic(HudHelpTopicId.SpeedLimitCompositeManeuverSize, "Розмір знаку в маневрі", "Maneuver sign size", frame("Звичайний", "Normal", "Звичайний розмір; позиція залежить від bitmap-вмісту, v4 — лише ілюстрація.", "Normal size; position depends on bitmap content, and v4 is illustrative only.", R.drawable.hud_help_speed_composite_maneuver), frame("Малий", "Small", "Малий розмір; позиція залежить від bitmap-вмісту, v4 — лише ілюстрація.", "Small size; position depends on bitmap content, and v4 is illustrative only.", R.drawable.hud_help_speed_composite_maneuver_small)),
        topic(HudHelpTopicId.SpeedLimitCompositeLaneSize, "Розмір знаку в смугах", "Lane sign size", frame("Звичайний", "Normal", "Звичайний розмір; позиція залежить від bitmap-вмісту, v4 — лише ілюстрація.", "Normal size; position depends on bitmap content, and v4 is illustrative only.", R.drawable.hud_help_speed_composite_lanes), frame("Малий", "Small", "Малий розмір; позиція залежить від bitmap-вмісту, v4 — лише ілюстрація.", "Small size; position depends on bitmap content, and v4 is illustrative only.", R.drawable.hud_help_speed_composite_lanes_small)),
        topic(HudHelpTopicId.WazeAlerts, "Попередження Waze", "Waze alerts", frame("Вимкнено", "Off", "OFF: попередження не надсилаються.", "OFF: warnings are not sent.", R.drawable.hud_help_baseline), frame("Увімкнено", "On", "ON: попередження займають вибране поле.", "ON: warnings use the selected field.", R.drawable.hud_help_warning_maneuver)),
        topic(HudHelpTopicId.WazeAlertField, "Поле виводу попередження Waze", "Waze alert output field", frame("Маневру", "Maneuver", "Попередження у полі маневру; маршрутна дистанція та вулиця тимчасово замінюються, смуги залишаються.", "Warnings use the maneuver field; route distance and street are temporarily replaced while lanes remain.", R.drawable.hud_help_warning_maneuver), frame("Експериментальне", "Experimental", "Попередження показано окремо ліворуч від смуг; маршрутний маневр, дистанція та вулиця залишаються.", "The warning is shown separately to the left of the lanes; route maneuver, distance, and street remain.", R.drawable.hud_help_warning_separate))
    )

    private val byId = topics.associateBy(HudHelpTopic::id)

    fun topic(id: HudHelpTopicId): HudHelpTopic = requireNotNull(byId[id])

    // The preview's language switch is independent of Android's resource locale.
    // Native distance/street names are shared; ETA units and the alert sample follow ua.
    fun localizedImage(imageRes: Int, ua: Boolean): Int = if (ua) imageRes else when (imageRes) {
        R.drawable.hud_help_eta_all -> R.drawable.hud_help_eta_all_en
        R.drawable.hud_help_eta_no_arrival -> R.drawable.hud_help_eta_no_arrival_en
        R.drawable.hud_help_eta_no_duration -> R.drawable.hud_help_eta_no_duration_en
        R.drawable.hud_help_eta_no_distance -> R.drawable.hud_help_eta_no_distance_en
        R.drawable.hud_help_eta_duration -> R.drawable.hud_help_eta_duration_en
        R.drawable.hud_help_eta_distance -> R.drawable.hud_help_eta_distance_en
        R.drawable.hud_help_eta_both_2 -> R.drawable.hud_help_eta_both_2_en
        R.drawable.hud_help_eta_both_4 -> R.drawable.hud_help_eta_both_4_en
        R.drawable.hud_help_warning_separate -> R.drawable.hud_help_warning_separate_en
        R.drawable.hud_help_warning_maneuver -> R.drawable.hud_help_warning_maneuver_en
        R.drawable.hud_help_eta_street_2 -> R.drawable.hud_help_eta_street_2_en
        R.drawable.hud_help_eta_street_3 -> R.drawable.hud_help_eta_street_3_en
        R.drawable.hud_help_eta_street_4 -> R.drawable.hud_help_eta_street_4_en
        R.drawable.hud_help_eta_street_5 -> R.drawable.hud_help_eta_street_5_en
        R.drawable.hud_help_eta_street_6 -> R.drawable.hud_help_eta_street_6_en
        R.drawable.hud_help_eta_street_7 -> R.drawable.hud_help_eta_street_7_en
        R.drawable.hud_help_eta_replace_2 -> R.drawable.hud_help_eta_replace_2_en
        R.drawable.hud_help_eta_replace_3 -> R.drawable.hud_help_eta_replace_3_en
        R.drawable.hud_help_eta_replace_4 -> R.drawable.hud_help_eta_replace_4_en
        R.drawable.hud_help_eta_replace_5 -> R.drawable.hud_help_eta_replace_5_en
        R.drawable.hud_help_eta_replace_6 -> R.drawable.hud_help_eta_replace_6_en
        R.drawable.hud_help_eta_replace_7 -> R.drawable.hud_help_eta_replace_7_en
        R.drawable.hud_help_eta_both_replace_2 -> R.drawable.hud_help_eta_both_replace_2_en
        R.drawable.hud_help_eta_both_replace_4 -> R.drawable.hud_help_eta_both_replace_4_en
        else -> imageRes
    }

    fun etaImage(street: Boolean, mask: Int, format: EtaStreetFormat = EtaStreetFormat.Prefix): Int =
        streetFormatImage(etaBaseImage(street, mask), format)

    fun streetFormatImage(imageRes: Int, format: EtaStreetFormat): Int =
        if (format == EtaStreetFormat.Prefix) imageRes else when (imageRes) {
            R.drawable.hud_help_eta_street_1 -> R.drawable.hud_help_eta_replace_1
            R.drawable.hud_help_eta_street_2 -> R.drawable.hud_help_eta_replace_2
            R.drawable.hud_help_eta_street_3 -> R.drawable.hud_help_eta_replace_3
            R.drawable.hud_help_eta_street_4 -> R.drawable.hud_help_eta_replace_4
            R.drawable.hud_help_eta_street_5 -> R.drawable.hud_help_eta_replace_5
            R.drawable.hud_help_eta_street_6 -> R.drawable.hud_help_eta_replace_6
            R.drawable.hud_help_eta_street_7 -> R.drawable.hud_help_eta_replace_7
            R.drawable.hud_help_eta_both_1 -> R.drawable.hud_help_eta_both_replace_1
            R.drawable.hud_help_eta_both_2 -> R.drawable.hud_help_eta_both_replace_2
            R.drawable.hud_help_eta_both_4 -> R.drawable.hud_help_eta_both_replace_4
            else -> imageRes
        }

    private fun etaBaseImage(street: Boolean, mask: Int): Int = if (street) {
        when (mask.coerceIn(0, 7)) {
            1 -> R.drawable.hud_help_eta_street_1
            2 -> R.drawable.hud_help_eta_street_2
            3 -> R.drawable.hud_help_eta_street_3
            4 -> R.drawable.hud_help_eta_street_4
            5 -> R.drawable.hud_help_eta_street_5
            6 -> R.drawable.hud_help_eta_street_6
            7 -> R.drawable.hud_help_eta_street_7
            else -> R.drawable.hud_help_baseline
        }
    } else {
        when (mask.coerceIn(0, 7)) {
            1 -> R.drawable.hud_help_eta_arrival
            2 -> R.drawable.hud_help_eta_duration
            3 -> R.drawable.hud_help_eta_no_distance
            4 -> R.drawable.hud_help_eta_distance
            5 -> R.drawable.hud_help_eta_no_duration
            6 -> R.drawable.hud_help_eta_no_arrival
            7 -> R.drawable.hud_help_eta_all
            else -> R.drawable.hud_help_baseline
        }
    }

    private fun topic(id: HudHelpTopicId, titleUa: String, titleEn: String, vararg frames: HudHelpFrame) =
        HudHelpTopic(id, titleUa, titleEn, frames.toList())

    private fun frame(
        labelUa: String,
        labelEn: String,
        captionUa: String,
        captionEn: String,
        imageRes: Int = R.drawable.hud_help_baseline
    ) = HudHelpFrame(labelUa, labelEn, captionUa, captionEn, imageRes)
}
