package com.bydhud.app

import android.content.Context

/** Only widget settings and its settled anchor are durable; expansion is session-local. */
internal object DashboardWidgetPreferences {
    fun load(context: Context): DashboardWidgetState = decode(preferences(context).all)

    fun save(context: Context, state: DashboardWidgetState) {
        val editor = preferences(context).edit()
        encode(state).forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
            }
        }
        editor.apply()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences("bydhud_dashboard_widget", Context.MODE_PRIVATE)

    fun encode(state: DashboardWidgetState): Map<String, Any> = state.normalized().let {
        mapOf(
            "shape" to it.shape.name, "size" to it.sizeDp,
            "transparency" to it.transparency, "fill" to it.fillArgb,
            "border" to it.borderDp, "border_color" to it.borderArgb,
            "corner_radius" to it.cornerRadiusDp,
            "orientation" to it.orientation.name, "forward" to it.expandForward,
            "auto_collapse" to it.autoCollapse,
            "auto_collapse_inactivity" to it.autoCollapseAfterInactivity,
            "apply_profile" to it.applyWindowProfile,
            "x" to it.xFraction, "y" to it.yFraction, "hidden" to it.hidden
        )
    }

    fun decode(values: Map<String, *>): DashboardWidgetState {
        val defaults = DashboardWidgetState()
        return defaults.copy(
            shape = DashboardWidgetShape.entries.firstOrNull { it.name == values["shape"] } ?: defaults.shape,
            sizeDp = values["size"] as? Int ?: defaults.sizeDp,
            transparency = values["transparency"] as? Int ?: defaults.transparency,
            fillArgb = values["fill"] as? Int ?: defaults.fillArgb,
            borderDp = values["border"] as? Int ?: defaults.borderDp,
            borderArgb = values["border_color"] as? Int ?: defaults.borderArgb,
            cornerRadiusDp = values["corner_radius"] as? Int ?: defaults.cornerRadiusDp,
            orientation = DashboardWidgetOrientation.entries.firstOrNull { it.name == values["orientation"] } ?: defaults.orientation,
            expandForward = values["forward"] as? Boolean ?: defaults.expandForward,
            autoCollapse = values["auto_collapse"] as? Boolean ?: defaults.autoCollapse,
            autoCollapseAfterInactivity = values["auto_collapse_inactivity"] as? Boolean
                ?: defaults.autoCollapseAfterInactivity,
            applyWindowProfile = values["apply_profile"] as? Boolean ?: defaults.applyWindowProfile,
            xFraction = values["x"] as? Float ?: defaults.xFraction,
            yFraction = values["y"] as? Float ?: defaults.yFraction,
            hidden = values["hidden"] as? Boolean ?: defaults.hidden
        ).normalized()
    }
}
