package com.bydhud.app

/** Coalesces Android's multiple notifications for a single runtime wake. */
internal class UpdateWakePolicy {
    private var entered = false
    private var asleep = false
    private var lastWake = 0L

    @Synchronized fun onEntry(now: Long, interactive: Boolean = true): Boolean {
        if (asleep && interactive) return onWake("user-entry", now)
        if (!entered) { entered = true; lastWake = now }
        return false
    }

    @Synchronized fun onSleep() { asleep = true }

    @Synchronized fun onWake(action: String, now: Long): Boolean {
        // Some BYD firmware emits QUICKBOOT without SCREEN_OFF. Coalesce its boot burst
        // for 30 seconds; later QUICKBOOT is the explicit fallback wake boundary.
        val quickBoot = action == "android.intent.action.QUICKBOOT_POWERON"
        if (entered && !asleep && (!quickBoot || now - lastWake < 30_000L)) return false
        entered = true
        asleep = false
        lastWake = now
        return true
    }

    @Synchronized fun reset() { entered = false; asleep = false; lastWake = 0L }
}
