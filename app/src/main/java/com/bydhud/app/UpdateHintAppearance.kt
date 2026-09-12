package com.bydhud.app

import android.content.Context

/** Persisted user-selected appearance for the short-lived update hint. */
internal data class UpdateHintAppearance(
    val transparencyPercent: Int = 0,
    val cornerRadiusDp: Int = 18,
    val borderWidthDp: Int = 1,
    val borderArgb: Int = DEFAULT_COLOR,
    val sizePercent: Int = 100
) {
    val alpha: Float get() = 1f - transparencyPercent.coerceIn(TRANSPARENCY_RANGE) / 100f
    val scale: Float get() = sizePercent.coerceIn(SIZE_RANGE) / 100f

    fun normalized() = copy(
        transparencyPercent = transparencyPercent.coerceIn(TRANSPARENCY_RANGE),
        cornerRadiusDp = cornerRadiusDp.coerceIn(CORNER_RANGE),
        borderWidthDp = borderWidthDp.coerceIn(BORDER_RANGE),
        borderArgb = borderArgb or 0xFF000000.toInt(),
        sizePercent = sizePercent.coerceIn(SIZE_RANGE)
    )

    companion object {
        private const val PREFS_NAME = "bydhud_update_hint_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_TRANSPARENCY = "transparency"
        private const val KEY_CORNER = "corner_radius_dp"
        private const val KEY_BORDER_WIDTH = "border_width_dp"
        private const val KEY_BORDER_COLOR = "border_argb"
        private const val KEY_SIZE = "size_percent"
        const val DEFAULT_COLOR: Int = 0xFF2F86F6.toInt()
        val TRANSPARENCY_RANGE = 0..100
        val CORNER_RANGE = 0..40
        val BORDER_RANGE = 0..16
        val SIZE_RANGE = 50..150

        fun isEnabled(context: Context): Boolean = preferences(context).getBoolean(KEY_ENABLED, true)

        fun setEnabled(context: Context, enabled: Boolean) {
            preferences(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        }

        fun read(context: Context): UpdateHintAppearance {
            val prefs = preferences(context)
            return UpdateHintAppearance(
                prefs.getInt(KEY_TRANSPARENCY, 0),
                prefs.getInt(KEY_CORNER, 18),
                prefs.getInt(KEY_BORDER_WIDTH, 1),
                prefs.getInt(KEY_BORDER_COLOR, DEFAULT_COLOR),
                prefs.getInt(KEY_SIZE, 100)
            ).normalized()
        }

        fun write(context: Context, value: UpdateHintAppearance) {
            val safe = value.normalized()
            preferences(context).edit()
                .putInt(KEY_TRANSPARENCY, safe.transparencyPercent)
                .putInt(KEY_CORNER, safe.cornerRadiusDp)
                .putInt(KEY_BORDER_WIDTH, safe.borderWidthDp)
                .putInt(KEY_BORDER_COLOR, safe.borderArgb)
                .putInt(KEY_SIZE, safe.sizePercent)
                .apply()
        }

        private fun preferences(context: Context) = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
