package com.bydhud.app

import com.bydhud.gmapswire.GmapsWireParser

/** Semantic direction, deliberately separate from the vehicle-specific F28 icon table. */
enum class DenzaManeuver(val nativeId: Int) {
    UNKNOWN(99), STRAIGHT(11), LEFT(1), RIGHT(2), SLIGHT_LEFT(3), SLIGHT_RIGHT(5),
    SHARP_LEFT(7), SHARP_RIGHT(8), U_TURN_LEFT(9), U_TURN_RIGHT(10), ROUNDABOUT(99);

    companion object {
        @JvmStatic
        fun fromGoogle(wireValue: Int): DenzaManeuver {
            val name = GmapsWireParser.maneuverName(wireValue) ?: return UNKNOWN
            return when {
                name.startsWith("ROUNDABOUT_") -> ROUNDABOUT
                name == "STRAIGHT" || name == "NAME_CHANGE" -> STRAIGHT
                name == "KEEP_LEFT" || name == "FORK_LEFT" || name == "MERGE_LEFT" || name == "ON_RAMP_KEEP_LEFT" || name == "OFF_RAMP_KEEP_LEFT" -> SLIGHT_LEFT
                name == "KEEP_RIGHT" || name == "FORK_RIGHT" || name == "MERGE_RIGHT" || name == "ON_RAMP_KEEP_RIGHT" || name == "OFF_RAMP_KEEP_RIGHT" -> SLIGHT_RIGHT
                name.endsWith("U_TURN_LEFT") -> U_TURN_LEFT
                name.endsWith("U_TURN_RIGHT") -> U_TURN_RIGHT
                name.endsWith("SLIGHT_LEFT") -> SLIGHT_LEFT
                name.endsWith("SLIGHT_RIGHT") -> SLIGHT_RIGHT
                name.endsWith("NORMAL_LEFT") -> LEFT
                name.endsWith("NORMAL_RIGHT") -> RIGHT
                name.endsWith("SHARP_LEFT") -> SHARP_LEFT
                name.endsWith("SHARP_RIGHT") -> SHARP_RIGHT
                else -> UNKNOWN // Departure, arrival, ferry and unsided merge do not define a bend.
            }
        }
    }
}

data class DenzaGuidance(
    val maneuver: DenzaManeuver,
    val nextRoadName: String,
    val maneuverDistanceMeters: Int,
)
