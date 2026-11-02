package com.bydhud.app

/** Unknown readings block admission without manufacturing another vehicle cycle. */
internal class UpdateWakePolicy {
    data class Decision(val allowed: Boolean, val startCheck: Boolean, val endCycle: Boolean)

    private var cycleStarted = false

    fun update(screenOn: Boolean, power: Int, automatic: Boolean, runtimeAllowed: Boolean): Decision {
        val endCycle = !screenOn || power == 0 || !automatic || !runtimeAllowed
        if (endCycle) cycleStarted = false
        val allowed = runtimeAllowed && screenOn && power in 1..4
        val start = allowed && automatic && !cycleStarted
        if (start) cycleStarted = true
        return Decision(allowed, start, endCycle)
    }

    fun reset() { cycleStarted = false }
}
