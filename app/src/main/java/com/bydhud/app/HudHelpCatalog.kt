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
    SpeedLimitNativeClearMode,
    SpeedLimitNativeFallbackMode,
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
    val labelRu: String,
    val captionUa: String,
    val captionEn: String,
    val captionRu: String,
    val imageRes: Int
) {
    fun label(language: Language): String = language.choose(labelUa, labelEn, labelRu)
    fun caption(language: Language): String = language.choose(captionUa, captionEn, captionRu)
}

internal data class HudHelpTopic(
    val id: HudHelpTopicId,
    val titleUa: String,
    val titleEn: String,
    val titleRu: String,
    val frames: List<HudHelpFrame>
) {
    fun title(language: Language): String = language.choose(titleUa, titleEn, titleRu)
}

internal object HudHelpCatalog {
    val topics: List<HudHelpTopic> = listOf(
        topic(HudHelpTopicId.EtaStreetFormat, "Формат ЕТА у полі вулиці", "ETA format in street field", "Формат ETA в поле улицы",
            frame("Приставити", "Prepend", "Дополнять", "", "", "", R.drawable.hud_help_eta_street_1),
            frame("Замінити", "Replace", "Заменять", "", "", "", R.drawable.hud_help_eta_replace_1)),
        topic(HudHelpTopicId.TextColor, "Колір тексту", "Text color", "Цвет текста", frame("Колір", "Color", "Цвет", "", "", "", R.drawable.hud_help_eta_all)),
        topic(HudHelpTopicId.BasicPng, "PNG", "PNG output", "Вывод PNG", frame("Вимкнено", "Off", "Выкл.", "OFF: PNG-вивід вимкнено.", "OFF: PNG output is disabled.", "OFF: вывод PNG отключён.", R.drawable.hud_help_no_maneuver), frame("Увімкнено", "On", "Вкл.", "ON: надсилати PNG маневру, коли зображення доступне.", "ON: send the maneuver PNG when an image is available.", "ON: передавать PNG манёвра, когда изображение доступно.")),
        topic(HudHelpTopicId.BasicNative, "Штатний маневр", "Native output", "Штатный вывод", frame("Вимкнено", "Off", "Выкл.", "OFF: штатний динамічний вивід вимкнено.", "OFF: native dynamic output is disabled.", "OFF: штатный динамический вывод отключён.", R.drawable.hud_help_no_dynamic), frame("Увімкнено", "On", "Вкл.", "ON: надсилати штатне динамічне зображення, коли воно доступне.", "ON: send the native dynamic image when available.", "ON: передавать штатное динамическое изображение, когда оно доступно.")),
        topic(HudHelpTopicId.BasicLanes, "Смуги", "Lane output", "Вывод полос", frame("Вимкнено", "Off", "Выкл.", "OFF: вивід смуг вимкнено.", "OFF: lane output is disabled.", "OFF: вывод полос отключён.", R.drawable.hud_help_no_lanes), frame("Увімкнено", "On", "Вкл.", "ON: надсилати смуги, коли навігація їх надала.", "ON: send lanes when navigation provides them.", "ON: передавать полосы, когда их предоставляет навигация.")),
        topic(HudHelpTopicId.BasicStreet, "Вулиця", "Street output", "Вывод улицы", frame("Вимкнено", "Off", "Выкл.", "OFF: текст вулиці не надсилається.", "OFF: street text is not sent.", "OFF: текст улицы не передаётся.", R.drawable.hud_help_no_street), frame("Увімкнено", "On", "Вкл.", "ON: надсилати наступну дорогу або назву вулиці, коли доступна.", "ON: send the next road or street name when available.", "ON: передавать следующую дорогу или название улицы, когда они доступны.")),
        topic(HudHelpTopicId.BasicTransliteration, "Транслітерація", "Text transliteration", "Транслитерация текста", frame("Вимкнено", "Off", "Выкл.", "OFF: джерельний текст залишається кириличним.", "OFF: source text remains Cyrillic.", "OFF: исходный текст остаётся кириллическим.", R.drawable.hud_help_street_cyrillic), frame("Українська", "Ukrainian", "Украинская", "Українська: приклад кириличного тексту, переданого латиницею.", "Ukrainian: example of Cyrillic text rendered in Latin characters.", "Украинская: пример передачи кириллического текста латиницей.", R.drawable.hud_help_baseline), frame("Універсальна", "Universal", "Универсальная", "Універсальна: для цього джерела використовується та сама латинська версія.", "Universal: this source uses the same Latin version.", "Универсальная: для этого источника используется та же латинская версия.", R.drawable.hud_help_baseline)),
        topic(HudHelpTopicId.BasicDistance, "Дистанція", "Distance output", "Вывод дистанции", frame("Вимкнено", "Off", "Выкл.", "OFF: надсилається нульова дистанція, тому HUD показує штатний маркер «现在».", "OFF: zero distance is sent, so the HUD shows the native “现在” marker.", "OFF: передаётся нулевая дистанция, поэтому HUD показывает штатный маркер «现在».", R.drawable.hud_help_no_distance), frame("Увімкнено", "On", "Вкл.", "ON: надсилати дистанцію до маневру, коли доступна.", "ON: send distance to the maneuver when available.", "ON: передавать дистанцию до манёвра, когда она доступна.")),
        topic(HudHelpTopicId.SmallDistanceClamp, "Мала дистанція", "Small distance clamp", "Малая дистанция", frame("Вимкнено", "Off", "Выкл.", "OFF: для raw 0–10 м залишається штатний маркер «现在».", "OFF: raw 0–10 m remains the native “现在” marker.", "OFF: для исходных 0–10 м остаётся штатный маркер «现在».", R.drawable.hud_help_distance_native), frame("Увімкнено", "On", "Вкл.", "ON: ті самі raw 0–10 м надсилаються як 11 м.", "ON: the same raw 0–10 m is sent as 11 m.", "ON: те же исходные 0–10 м передаются как 11 м.", R.drawable.hud_help_distance_clamped)),
        topic(HudHelpTopicId.EtaMode, "Режим виводу ЕТА (час/дистанція)", "ETA output mode (time/distance)", "Режим вывода ETA (время/расстояние)", frame("Вимкнено", "Off", "Выкл.", "OFF: поля ETA/часу/дистанції не формуються.", "OFF: ETA/time/distance fields are not formed.", "OFF: поля ETA/времени/расстояния не формируются.", R.drawable.hud_help_baseline), frame("До зупинки", "Next stop", "До остановки", "Показувати час і дистанцію до наступної зупинки маршруту.", "Show time and distance to the next route stop.", "Показывать время и расстояние до следующей остановки маршрута.", R.drawable.hud_help_eta_all), frame("Весь маршрут", "Entire route", "Весь маршрут", "Показувати значення всього маршруту; Waze використовує доступне значення до зупинки, якщо окремого значення всього маршруту немає.", "Show whole-route values; Waze falls back to an available next-stop value when no separate whole-route value exists.", "Показывать значения всего маршрута; Waze использует доступное значение до остановки, если отдельного значения всего маршрута нет.", R.drawable.hud_help_eta_all)),
        topic(HudHelpTopicId.EtaOutputField, "Поле виводу ЕТА", "ETA output field", "Поле вывода ETA", frame("Вулиці", "Street", "Улица", "Поля ETA показані у вуличному полі.", "ETA fields are shown in the street field.", "Поля ETA показаны в поле улицы.", R.drawable.hud_help_eta_street_7), frame("Експериментальне", "Experimental", "Экспериментальное", "Поля ETA показані в окремому блоці праворуч.", "ETA fields are shown in a separate block on the right.", "Поля ETA показаны в отдельном блоке справа.", R.drawable.hud_help_eta_all)),
        topic(HudHelpTopicId.EtaOutput, "Показ ETA", "Show ETA", "Показывать ETA", frame("Вимкнено", "Off", "Выкл.", "OFF: час прибуття не показується ні у вуличному полі, ні в окремому блоці.", "OFF: arrival time is hidden in both the street field and separate block.", "OFF: время прибытия скрыто и в поле улицы, и в отдельном блоке.", R.drawable.hud_help_baseline), frame("Увімкнено", "On", "Вкл.", "ON: час прибуття показано у вуличному та експериментальному блоках.", "ON: arrival time is shown in both street and experimental blocks.", "ON: время прибытия показано в поле улицы и экспериментальном блоке.", R.drawable.hud_help_eta_both_1)),
        topic(HudHelpTopicId.RemainingTime, "Залишок часу", "Remaining time", "Оставшееся время", frame("Вимкнено", "Off", "Выкл.", "OFF: залишок часу не показується ні у вуличному полі, ні в окремому блоці.", "OFF: remaining time is hidden in both the street field and separate block.", "OFF: оставшееся время скрыто и в поле улицы, и в отдельном блоке.", R.drawable.hud_help_baseline), frame("Увімкнено", "On", "Вкл.", "ON: залишок часу показано у вуличному та експериментальному блоках.", "ON: remaining time is shown in both street and experimental blocks.", "ON: оставшееся время показано в поле улицы и экспериментальном блоке.", R.drawable.hud_help_eta_both_2)),
        topic(HudHelpTopicId.RemainingDistance, "Залишок дистанції", "Remaining distance", "Оставшееся расстояние", frame("Вимкнено", "Off", "Выкл.", "OFF: залишок дистанції не показується ні у вуличному полі, ні в окремому блоці.", "OFF: remaining distance is hidden in both the street field and separate block.", "OFF: оставшееся расстояние скрыто и в поле улицы, и в отдельном блоке.", R.drawable.hud_help_baseline), frame("Увімкнено", "On", "Вкл.", "ON: залишок дистанції показано у вуличному та експериментальному блоках.", "ON: remaining distance is shown in both street and experimental blocks.", "ON: оставшееся расстояние показано в поле улицы и экспериментальном блоке.", R.drawable.hud_help_eta_both_4)),
        topic(HudHelpTopicId.SpeedLimitMode, "Режим обмеження швидкості", "Speed limit output mode", "Режим вывода ограничения скорости", frame("Вимкнено", "Off", "Выкл.", "OFF: доданий знак обмеження не надсилається; штатний знак біля швидкості залишається на місці.", "OFF: the added speed sign is not sent; the native speed-limit glyph remains separate and fixed.", "OFF: добавленный знак ограничения не передаётся; штатный знак рядом со скоростью остаётся на месте.", R.drawable.hud_help_baseline), frame("Штатний", "Native", "Штатный", "Обмеження 115 у штатному полі біля швидкості автомобіля; маневр і смуги залишаються вільними для навігації.", "Speed limit 115 in the native field beside vehicle speed; maneuver and lane fields remain available for navigation.", "Ограничение 115 в штатном поле рядом со скоростью автомобиля; поля манёвра и полос остаются для навигации.", R.drawable.hud_help_speed_native), frame("Поле маневру", "Maneuver field", "Поле манёвра", "Знак обмеження розміщено в полі маневру; штатний знак біля швидкості не змінюється.", "The added speed sign is placed in the maneuver field; the native speed-limit glyph is separate.", "Добавленный знак размещён в поле манёвра; штатный знак рядом со скоростью остаётся отдельно.", R.drawable.hud_help_speed_maneuver), frame("Поле смуг", "Lane field", "Поле полос", "Знак обмеження розміщено в полі смуг; штатний знак біля швидкості не змінюється.", "The added speed sign is placed in the lane field; the native speed-limit glyph is separate.", "Добавленный знак размещён в поле полос; штатный знак рядом со скоростью остаётся отдельно.", R.drawable.hud_help_speed_lanes), frame("Вільне → маневр", "Free → maneuver", "Свободное → манёвр", "Якщо поле маневру вільне, знак займає його. Штатний знак біля швидкості не змінюється.", "If the maneuver field is empty, the sign uses it. The factory sign beside vehicle speed stays unchanged.", "Если поле манёвра свободно, знак занимает его. Штатный знак рядом со скоростью не меняется.", R.drawable.hud_help_speed_maneuver), frame("Вільне → смуги", "Free → lanes", "Свободное → полосы", "Якщо поле смуг вільне, знак займає його. Штатний знак біля швидкості не змінюється.", "If the lane field is empty, the sign uses it. The factory sign beside vehicle speed stays unchanged.", "Если поле полос свободно, знак занимает его. Штатный знак рядом со скоростью не меняется.", R.drawable.hud_help_speed_lanes), frame("Композитний маневр", "Composite maneuver", "Композитный манёвр", "Композитний доданий знак у полі маневру; позиція залежить від bitmap-вмісту, v4 — лише ілюстрація. Штатний знак біля швидкості не змінюється.", "Composite added sign in the maneuver field; position depends on bitmap content, and v4 is illustrative only. The native speed-limit glyph is separate.", "Композитный добавленный знак в поле манёвра; положение зависит от содержимого изображения, v4 — только иллюстрация. Штатный знак ограничения остаётся отдельно.", R.drawable.hud_help_speed_composite_maneuver), frame("Композитні смуги", "Composite lanes", "Композитные полосы", "Композитний доданий знак у полі смуг; позиція залежить від bitmap-вмісту, v4 — лише ілюстрація. Штатний знак біля швидкості не змінюється.", "Composite added sign in the lane field; position depends on bitmap content, and v4 is illustrative only. The native speed-limit glyph is separate.", "Композитный добавленный знак в поле полос; положение зависит от содержимого изображения, v4 — только иллюстрация. Штатный знак ограничения остаётся отдельно.", R.drawable.hud_help_speed_composite_lanes)),
        topic(HudHelpTopicId.SpeedLimitNativeClearMode,
            "Очищувати штатне поле обмеження швидкості",
            "Clear native speed limit field",
            "Очищать штатное поле ограничения скорости",
            frame("Ніколи", "Never", "Никогда",
                "Не втручатися у штатний знак обмеження швидкості",
                "Leave the native speed limit sign unchanged",
                "Не вмешиваться в штатный знак ограничения скорости", R.drawable.hud_help_baseline),
            frame("Немає нав. даних", "No nav. data", "Нет нав. данных",
                "Очищувати штатний знак, коли навігатор не передає обмеження швидкості",
                "Clear the native sign when the navigator provides no speed limit",
                "Очищать штатный знак, когда навигатор не передаёт ограничение скорости",
                R.drawable.hud_help_baseline),
            frame("Завжди", "Always", "Всегда",
                "Прибирати штатний знак щоразу, коли ADAS показує нове значення",
                "Clear the native sign whenever ADAS shows a new value",
                "Убирать штатный знак каждый раз, когда ADAS показывает новое значение",
                R.drawable.hud_help_baseline)),
        topic(HudHelpTopicId.SpeedLimitNativeFallbackMode,
            "Запасний режим виводу обмеження швидкості",
            "Fallback speed limit output mode",
            "Запасной режим вывода ограничения скорости",
            frame("Вимкнено", "Off", "Выкл.",
                "Запасний вивід вимкнено; використовується лише штатне поле.",
                "Fallback output is disabled; only the native field is used.",
                "Запасной вывод выключен; используется только штатное поле.",
                R.drawable.hud_help_speed_native),
            frame("Поле маневру", "Maneuver field", "Поле манёвра",
                "Якщо штатне поле не оновлюється, знак показується у полі маневру.",
                "If the native field does not update, the sign is shown in the maneuver field.",
                "Если штатное поле не обновляется, знак показывается в поле манёвра.",
                R.drawable.hud_help_speed_maneuver),
            frame("Поле смуг", "Lane field", "Поле полос",
                "Якщо штатне поле не оновлюється, знак показується у полі смуг.",
                "If the native field does not update, the sign is shown in the lane field.",
                "Если штатное поле не обновляется, знак показывается в поле полос.",
                R.drawable.hud_help_speed_lanes),
            frame("Вільне поле", "Free field", "Свободное поле",
                "Якщо штатне поле не оновлюється, знак займає вільне поле.",
                "If the native field does not update, the sign uses a free field.",
                "Если штатное поле не обновляется, знак занимает свободное поле.",
                R.drawable.hud_help_speed_maneuver),
            frame("Композитний", "Composite", "Композитный",
                "Якщо штатне поле не оновлюється, знак додається до bitmap-вмісту вибраного поля.",
                "If the native field does not update, the sign is composed into the selected field bitmap.",
                "Если штатное поле не обновляется, знак добавляется к bitmap-содержимому выбранного поля.",
                R.drawable.hud_help_speed_composite_maneuver)),
        topic(HudHelpTopicId.SpeedLimitFallback, "Накладання обмеження", "Speed limit fallback", "Наложение ограничения", frame("Без накладання", "No overlay", "Без наложения", "Обидва поля зайняті, накладання вимкнене: маневр і смуги залишаються без змін.", "Both fields are occupied and overlay is off: maneuver and lanes remain unchanged.", "Оба поля заняты, наложение выключено: манёвр и полосы остаются без изменений.", R.drawable.hud_help_baseline), frame("Маневр", "Maneuver", "Манёвр", "Коли обидва поля зайняті, знак тимчасово замінює маневр.", "When both fields are occupied, the sign temporarily replaces the maneuver.", "Когда оба поля заняты, знак временно заменяет манёвр.", R.drawable.hud_help_speed_maneuver), frame("Смуги", "Lanes", "Полосы", "Коли обидва поля зайняті, знак тимчасово замінює смуги.", "When both fields are occupied, the sign temporarily replaces the lanes.", "Когда оба поля заняты, знак временно заменяет полосы.", R.drawable.hud_help_speed_lanes)),
        topic(HudHelpTopicId.SpeedLimitCompositeField, "Поле композитного знаку", "Composite output field", "Поле вывода в композитном режиме", frame("Маневр", "Maneuver", "Манёвр", "Композитний знак у полі маневру; позиція залежить від bitmap-вмісту, v4 — лише ілюстрація.", "Composite sign in the maneuver field; position depends on bitmap content, and v4 is illustrative only.", "Композитный знак в поле манёвра; положение зависит от содержимого изображения, v4 — только иллюстрация.", R.drawable.hud_help_speed_composite_maneuver), frame("Смуги", "Lanes", "Полосы", "Композитний знак у полі смуг; позиція залежить від bitmap-вмісту, v4 — лише ілюстрація.", "Composite sign in the lane field; position depends on bitmap content, and v4 is illustrative only.", "Композитный знак в поле полос; положение зависит от содержимого изображения, v4 — только иллюстрация.", R.drawable.hud_help_speed_composite_lanes), frame("Вільне або маневру", "Free or maneuver", "Свободное или манёвр", "Вільне поле має пріоритет; якщо обидва зайняті, знак додається до обраного зображення. Позиція залежить від bitmap-вмісту, v4 — лише ілюстрація.", "A free field has priority; if both are occupied, the sign is composed into the selected image. Position depends on bitmap content, and v4 is illustrative only.", "Свободное поле имеет приоритет; если оба заняты, знак добавляется к выбранному изображению. Положение зависит от содержимого изображения, v4 — только иллюстрация.", R.drawable.hud_help_speed_composite_maneuver), frame("Вільне або смуг", "Free or lanes", "Свободное или полосы", "Вільне поле має пріоритет; якщо обидва зайняті, знак додається до обраного зображення. Позиція залежить від bitmap-вмісту, v4 — лише ілюстрація.", "A free field has priority; if both are occupied, the sign is composed into the selected image. Position depends on bitmap content, and v4 is illustrative only.", "Свободное поле имеет приоритет; если оба заняты, знак добавляется к выбранному изображению. Положение зависит от содержимого изображения, v4 — только иллюстрация.", R.drawable.hud_help_speed_composite_lanes)),
        topic(HudHelpTopicId.SpeedLimitCompositeManeuverSize, "Розмір знаку в маневрі", "Maneuver sign size", "Размер знака в манёвре", frame("Звичайний", "Normal", "Обычный", "Звичайний розмір; позиція залежить від bitmap-вмісту, v4 — лише ілюстрація.", "Normal size; position depends on bitmap content, and v4 is illustrative only.", "Обычный размер; положение зависит от содержимого изображения, v4 — только иллюстрация.", R.drawable.hud_help_speed_composite_maneuver), frame("Малий", "Small", "Маленький", "Малий розмір; позиція залежить від bitmap-вмісту, v4 — лише ілюстрація.", "Small size; position depends on bitmap content, and v4 is illustrative only.", "Маленький размер; положение зависит от содержимого изображения, v4 — только иллюстрация.", R.drawable.hud_help_speed_composite_maneuver_small)),
        topic(HudHelpTopicId.SpeedLimitCompositeLaneSize, "Розмір знаку в смугах", "Lane sign size", "Размер знака в полосах", frame("Звичайний", "Normal", "Обычный", "Звичайний розмір; позиція залежить від bitmap-вмісту, v4 — лише ілюстрація.", "Normal size; position depends on bitmap content, and v4 is illustrative only.", "Обычный размер; положение зависит от содержимого изображения, v4 — только иллюстрация.", R.drawable.hud_help_speed_composite_lanes), frame("Малий", "Small", "Маленький", "Малий розмір; позиція залежить від bitmap-вмісту, v4 — лише ілюстрація.", "Small size; position depends on bitmap content, and v4 is illustrative only.", "Маленький размер; положение зависит от содержимого изображения, v4 — только иллюстрация.", R.drawable.hud_help_speed_composite_lanes_small)),
        topic(HudHelpTopicId.WazeAlerts, "Попередження Waze", "Waze alerts", "Предупреждения Waze", frame("Вимкнено", "Off", "Выкл.", "OFF: попередження не надсилаються.", "OFF: warnings are not sent.", "OFF: предупреждения не передаются.", R.drawable.hud_help_baseline), frame("Увімкнено", "On", "Вкл.", "ON: попередження займають вибране поле.", "ON: warnings use the selected field.", "ON: предупреждения занимают выбранное поле.", R.drawable.hud_help_warning_maneuver)),
        topic(HudHelpTopicId.WazeAlertField, "Поле виводу попередження Waze", "Waze alert output field", "Поле вывода предупреждения Waze", frame("Маневру", "Maneuver", "Манёвр", "Попередження у полі маневру; маршрутна дистанція та вулиця тимчасово замінюються, смуги залишаються.", "Warnings use the maneuver field; route distance and street are temporarily replaced while lanes remain.", "Предупреждения в поле манёвра; дистанция маршрута и улица временно заменяются, полосы остаются.", R.drawable.hud_help_warning_maneuver), frame("Експериментальне", "Experimental", "Экспериментальное", "Попередження показано в окремому блоці ліворуч від смуг.", "The warning is shown in a separate block to the left of the lanes.", "Предупреждение показано в отдельном блоке слева от полос." , R.drawable.hud_help_warning_separate))
    )

    private val byId = topics.associateBy(HudHelpTopic::id)

    fun topic(id: HudHelpTopicId): HudHelpTopic = requireNotNull(byId[id])

    // The app language is independent of Android's resource locale.
    // Native distance/street names are shared; ETA units and the alert sample follow it.
    fun localizedImage(imageRes: Int, language: Language): Int = if (language == Language.Ru) when (imageRes) {
        R.drawable.hud_help_eta_all -> R.drawable.hud_help_eta_all_ru
        R.drawable.hud_help_eta_no_arrival -> R.drawable.hud_help_eta_no_arrival_ru
        R.drawable.hud_help_eta_no_distance -> R.drawable.hud_help_eta_no_distance_ru
        R.drawable.hud_help_eta_duration -> R.drawable.hud_help_eta_duration_ru
        R.drawable.hud_help_eta_both_2 -> R.drawable.hud_help_eta_both_2_ru
        R.drawable.hud_help_eta_street_2 -> R.drawable.hud_help_eta_street_2_ru
        R.drawable.hud_help_eta_street_3 -> R.drawable.hud_help_eta_street_3_ru
        R.drawable.hud_help_eta_street_6 -> R.drawable.hud_help_eta_street_6_ru
        R.drawable.hud_help_eta_street_7 -> R.drawable.hud_help_eta_street_7_ru
        R.drawable.hud_help_eta_replace_2 -> R.drawable.hud_help_eta_replace_2_ru
        R.drawable.hud_help_eta_replace_3 -> R.drawable.hud_help_eta_replace_3_ru
        R.drawable.hud_help_eta_replace_6 -> R.drawable.hud_help_eta_replace_6_ru
        R.drawable.hud_help_eta_replace_7 -> R.drawable.hud_help_eta_replace_7_ru
        R.drawable.hud_help_eta_both_replace_2 -> R.drawable.hud_help_eta_both_replace_2_ru
        else -> imageRes
    } else if (language == Language.Ua) imageRes else when (imageRes) {
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

    private fun topic(id: HudHelpTopicId, titleUa: String, titleEn: String, titleRu: String, vararg frames: HudHelpFrame) =
        HudHelpTopic(id, titleUa, titleEn, titleRu, frames.toList())

    private fun frame(
        labelUa: String,
        labelEn: String,
        labelRu: String,
        captionUa: String,
        captionEn: String,
        captionRu: String,
        imageRes: Int = R.drawable.hud_help_baseline
    ) = HudHelpFrame(labelUa, labelEn, labelRu, captionUa, captionEn, captionRu, imageRes)
}
