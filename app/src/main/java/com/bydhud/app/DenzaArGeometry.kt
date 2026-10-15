package com.bydhud.app

// Adapted from xor777/denza-lab, commit 854bc915592d93af1b305ca3db54c576f75e8877:
// apps/denza-apps/src/main/java/dev/denza/apps/feature/hud/HudArApproximation.kt.
// Geometry is an approximation, not the navigator's route polyline.

import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private fun normalizeHeading(value: Double): Double = ((value % 360.0) + 360.0) % 360.0

private fun shortestHeadingDelta(from: Double, to: Double): Double =
    ((to - from + 540.0) % 360.0) - 180.0

/** A fresh, course-qualified GNSS fix suitable for the native AR HUD contract. */
data class DenzaVehiclePose(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val speedMetersPerSecond: Double,
    val headingDegrees: Double,
    val accuracyMeters: Double,
    val capturedAtElapsedMs: Long,
)

/** Optional fields 19-22 and 30-33 of the stock HudRoadInfoNotifyStruct. */
data class DenzaArGeometry(
    val vehicleLatitude: Double,
    val vehicleLongitude: Double,
    val vehicleAltitudeMeters: Double,
    val vehicleSpeedMetersPerSecond: Double,
    val vehicleHeadingDegrees: Double,
    val guideLine: String,
    val guidePoint: String,
    val navigatingRatio: Double = 0.0,
)

/**
 * One tracker sample: the distance field 9 carries (the step's held minimum,
 * never the raw report) and the optional AR fields drawn for it.
 */
data class DenzaArFrame(
    val distanceMeters: Int,
    val geometry: DenzaArGeometry?,
)

/**
 * Converts raw GNSS fixes into poses without inventing a course while parked.
 * A previously measured moving course may be held briefly at a light.
 */
class DenzaArPoseFilter {
    private var courseDegrees: Double? = null
    private var courseCapturedAtMs = 0L

    fun onFix(
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double,
        hasAltitude: Boolean,
        speedMetersPerSecond: Double,
        accuracyMeters: Double,
        bearingDegrees: Double,
        hasBearing: Boolean,
        bearingAccuracyDegrees: Double?,
        capturedAtElapsedMs: Long,
    ): DenzaVehiclePose? {
        if (!latitude.isFinite() || latitude !in -85.0..85.0) return null
        if (!longitude.isFinite() || longitude !in -180.0..180.0) return null
        if (!accuracyMeters.isFinite() || accuracyMeters !in 0.0..MAX_ACCURACY_METERS) return null
        if (!speedMetersPerSecond.isFinite() || speedMetersPerSecond < 0.0) return null
        if (capturedAtElapsedMs < 0L) return null
        if (courseCapturedAtMs != 0L && capturedAtElapsedMs < courseCapturedAtMs) return null

        val bearingAccurate = bearingAccuracyDegrees == null ||
            (bearingAccuracyDegrees.isFinite() && bearingAccuracyDegrees <= MAX_BEARING_ACCURACY_DEGREES)
        val movingBearing = speedMetersPerSecond >= COURSE_ACQUIRE_SPEED_MPS &&
            hasBearing && bearingDegrees.isFinite() && bearingAccurate
        if (movingBearing) {
            val measured = normalizeHeading(bearingDegrees)
            courseDegrees = courseDegrees?.let { previous ->
                normalizeHeading(previous + shortestHeadingDelta(previous, measured) * COURSE_SMOOTHING)
            } ?: measured
            courseCapturedAtMs = capturedAtElapsedMs
        }

        val course = courseDegrees ?: return null
        if (!movingBearing && capturedAtElapsedMs - courseCapturedAtMs > COURSE_HOLD_MS) return null
        return DenzaVehiclePose(
            latitude = latitude,
            longitude = longitude,
            altitudeMeters = if (hasAltitude && altitudeMeters.isFinite()) altitudeMeters else 0.0,
            speedMetersPerSecond = speedMetersPerSecond,
            headingDegrees = course,
            accuracyMeters = accuracyMeters,
            capturedAtElapsedMs = capturedAtElapsedMs,
        )
    }

    fun reset() {
        courseDegrees = null
        courseCapturedAtMs = 0L
    }

    private companion object {
        const val MAX_ACCURACY_METERS = 20.0
        const val MAX_BEARING_ACCURACY_DEGREES = 45.0
        const val COURSE_ACQUIRE_SPEED_MPS = 1.5
        const val COURSE_HOLD_MS = 15_000L
        const val COURSE_SMOOTHING = 0.35
    }
}

/**
 * Builds a deliberately conservative pseudo-route from the current pose and
 * the navigator's distance-to-maneuver.
 *
 * The vehicle AR engine (libAREngine.so, ArNavigation::judgeRecycle) plays a
 * turn as timed phases and, from the twist on, interrupts the scene and
 * restarts the current phase whenever field 31 differs from its value at scene
 * start, field 9 is larger than the value stored at the last phase change, or
 * field 28 changes. The stock navigator keeps field 31 fixed for the whole
 * maneuver and lets field 9 only fall, and so does this tracker within one
 * step: the distance it reports is the step's minimum, the turn point is
 * projected once and never moves, and AR that has been lost does not come back
 * before the next step.
 *
 * AR starts at [AR_START_DISTANCE_METERS], or earlier when the car is fast
 * enough that the firmware's four-second entry would not finish before the
 * turn. Inside [TURN_HOLD_DISTANCE_METERS] AR already shown holds until the
 * navigator moves on: turning, the car leaves the course the point was
 * projected along, which the guards read as a wrong projection, so there the
 * line and point stay as last drawn and only the vehicle fields follow the car.
 */
class DenzaArTracker {
    private data class StepKey(
        val maneuver: DenzaManeuver,
        val nextRoadName: String,
    )

    private data class GeoPoint(val latitude: Double, val longitude: Double)

    /** The step's fixed turn point and the course it was projected along. */
    private data class Anchor(val point: GeoPoint, val incomingHeadingDegrees: Double)

    private var stepKey: StepKey? = null
    private var heldDistanceMeters = 0
    private var anchor: Anchor? = null
    private var shownGeometry: DenzaArGeometry? = null
    private var arEnded = false
    private var cachedPose: DenzaVehiclePose? = null
    private var cachedGeometry: DenzaArGeometry? = null

    fun resolve(
        guidance: DenzaGuidance,
        pose: DenzaVehiclePose?,
        nowElapsedMs: Long,
    ): DenzaArFrame {
        val key = StepKey(
            maneuver = guidance.maneuver,
            nextRoadName = guidance.nextRoadName.trim().lowercase(Locale.ROOT),
        )
        val reported = guidance.maneuverDistanceMeters
        // A distance that climbs back by the route-reset margin is a reroute,
        // not a navigator rounding or GNSS wobble around the same maneuver.
        if (stepKey != key || reported - heldDistanceMeters >= ROUTE_RESET_DISTANCE_JUMP_METERS) {
            reset()
            stepKey = key
            heldDistanceMeters = reported
        } else {
            heldDistanceMeters = min(heldDistanceMeters, reported)
        }
        val distance = heldDistanceMeters
        val shown = shownGeometry
        val geometry = when {
            arEnded -> null
            shown != null && distance <= TURN_HOLD_DISTANCE_METERS ->
                pose?.takeIf { poseIsUsable(it, nowElapsedMs) }?.let { shown.following(it) } ?: shown
            else -> resolveGeometry(guidance.maneuver, distance, pose, nowElapsedMs)
        }
        if (geometry != null) shownGeometry = geometry
        // A packet without AR clears field 31, and every return after such a
        // gap would restart the firmware's turn scene, so AR once shown and
        // lost stays off for the rest of the step.
        if (geometry == null && anchor != null) arEnded = true
        return DenzaArFrame(distance, geometry)
    }

    fun reset() {
        stepKey = null
        heldDistanceMeters = 0
        anchor = null
        shownGeometry = null
        arEnded = false
        cachedPose = null
        cachedGeometry = null
    }

    private fun resolveGeometry(
        maneuver: DenzaManeuver,
        distance: Int,
        pose: DenzaVehiclePose?,
        nowElapsedMs: Long,
    ): DenzaArGeometry? {
        if (!supports(maneuver) || distance !in MIN_MANEUVER_DISTANCE_METERS..MAX_MANEUVER_DISTANCE_METERS) {
            return null
        }
        if (pose == null || !poseIsUsable(pose, nowElapsedMs)) return null
        if (anchor == null && distance > startDistanceMeters(pose)) return null
        if (cachedPose == pose) return cachedGeometry
        // The turn point is projected once per step from the first eligible
        // pose along its course, and is never blended, rebased or recomputed.
        val step = anchor ?: Anchor(
            point = destination(
                GeoPoint(pose.latitude, pose.longitude),
                pose.headingDegrees,
                distance.toDouble(),
            ),
            incomingHeadingDegrees = pose.headingDegrees,
        )
        // A first projection that fails the guards is discarded, so a later
        // sample of the same step may still start AR.
        val geometry = buildGeometry(maneuver, pose, step) ?: return null
        anchor = step
        cachedPose = pose
        cachedGeometry = geometry
        return geometry
    }

    private fun buildGeometry(
        maneuver: DenzaManeuver,
        pose: DenzaVehiclePose,
        step: Anchor,
    ): DenzaArGeometry? {
        val turn = step.point
        val vehicle = GeoPoint(pose.latitude, pose.longitude)
        val remaining = distanceMeters(vehicle, turn)
        if (remaining !in MIN_GUIDE_POINT_DISTANCE_METERS..MAX_GUIDE_POINT_DISTANCE_METERS) return null
        val approachHeading = bearingDegrees(vehicle, turn)
        // The firmware projects the polyline against the live vehicle pose, so
        // the driver reads the bend relative to the nose, not relative to the
        // approach leg. A slight bend survives less frame divergence than a
        // sharp one before its side visually flips.
        if (abs(shortestHeadingDelta(pose.headingDegrees, approachHeading)) >
            maxNoseApproachErrorDegrees(maneuver)
        ) {
            return null
        }
        // The point is only an approximation derived from the course captured
        // at the beginning of this instruction. Once the live approach no
        // longer agrees with that course, omitting AR is safer than drawing a
        // geometrically plausible but semantically wrong turn.
        if (abs(shortestHeadingDelta(step.incomingHeadingDegrees, approachHeading)) >
            MAX_ANCHOR_HEADING_ERROR_DEGREES
        ) {
            return null
        }

        val line = buildGuideLine(
            vehicle = vehicle,
            turn = turn,
            approachHeading = approachHeading,
            exitHeading = approachHeading,
            maneuver = maneuver,
        )
        if (!guideLineIsSafe(line, approachHeading, pose.headingDegrees, maneuver)) return null
        return DenzaArGeometry(
            vehicleLatitude = pose.latitude,
            vehicleLongitude = pose.longitude,
            vehicleAltitudeMeters = pose.altitudeMeters,
            vehicleSpeedMetersPerSecond = pose.speedMetersPerSecond,
            vehicleHeadingDegrees = pose.headingDegrees,
            guideLine = line.toGuideLineJson(),
            guidePoint = turn.toGuidePointString(),
        )
    }

    private fun DenzaArGeometry.following(pose: DenzaVehiclePose): DenzaArGeometry = copy(
        vehicleLatitude = pose.latitude,
        vehicleLongitude = pose.longitude,
        vehicleAltitudeMeters = pose.altitudeMeters,
        vehicleSpeedMetersPerSecond = pose.speedMetersPerSecond,
        vehicleHeadingDegrees = pose.headingDegrees,
    )

    // The firmware needs fly-in, twist and rotate, four seconds, before the arrow points at the turn.
    private fun startDistanceMeters(pose: DenzaVehiclePose): Double = min(
        MAX_MANEUVER_DISTANCE_METERS.toDouble(),
        max(AR_START_DISTANCE_METERS, pose.speedMetersPerSecond * AR_START_LEAD_SECONDS),
    )

    private fun poseIsUsable(pose: DenzaVehiclePose?, nowElapsedMs: Long): Boolean {
        if (pose == null || nowElapsedMs < pose.capturedAtElapsedMs) return false
        if (nowElapsedMs - pose.capturedAtElapsedMs > MAX_POSE_AGE_MS) return false
        return pose.latitude.isFinite() && pose.latitude in -85.0..85.0 &&
            pose.longitude.isFinite() && pose.longitude in -180.0..180.0 &&
            pose.altitudeMeters.isFinite() &&
            pose.speedMetersPerSecond.isFinite() && pose.speedMetersPerSecond >= 0.0 &&
            pose.headingDegrees.isFinite() && pose.accuracyMeters in 0.0..MAX_POSE_ACCURACY_METERS
    }

    private fun buildGuideLine(
        vehicle: GeoPoint,
        turn: GeoPoint,
        approachHeading: Double,
        exitHeading: Double,
        maneuver: DenzaManeuver,
    ): List<GeoPoint> {
        val result = ArrayList<GeoPoint>()
        val approachDistance = distanceMeters(vehicle, turn)
        val approachSteps = ceil(approachDistance / APPROACH_POINT_SPACING_METERS)
            .toInt()
            .coerceIn(1, MAX_APPROACH_STEPS)
        for (index in 0..approachSteps) {
            result += destination(
                vehicle,
                approachHeading,
                approachDistance * index / approachSteps,
            )
        }
        // Use the actual anchored coordinate at the seam to avoid accumulated
        // spherical rounding and to match the stock current-segment endpoint.
        result[result.lastIndex] = turn

        val turnAngle = turnAngleDegrees(maneuver)
        var cursor = turn
        val curveSteps = when (maneuver) {
            DenzaManeuver.U_TURN_LEFT, DenzaManeuver.U_TURN_RIGHT -> 6
            DenzaManeuver.SLIGHT_LEFT, DenzaManeuver.SLIGHT_RIGHT -> 5
            DenzaManeuver.SHARP_LEFT, DenzaManeuver.SHARP_RIGHT -> 3
            DenzaManeuver.LEFT, DenzaManeuver.RIGHT -> 4
            else -> 1
        }
        for (index in 1..EXIT_STEPS) {
            val progress = min(1.0, (index - 0.5) / curveSteps)
            val eased = progress * progress * (3.0 - 2.0 * progress)
            val segmentHeading = normalizeHeading(exitHeading + turnAngle * eased)
            cursor = destination(cursor, segmentHeading, EXIT_POINT_SPACING_METERS)
            result += cursor
        }
        return result
    }

    /**
     * Final semantic guard at the wire boundary: a right command must only bend
     * right, both against its own approach leg and against the vehicle nose the
     * firmware renders the polyline for.
     */
    private fun guideLineIsSafe(
        line: List<GeoPoint>,
        approachHeading: Double,
        noseHeadingDegrees: Double,
        maneuver: DenzaManeuver,
    ): Boolean {
        if (line.size < 3 || line.any { !it.latitude.isFinite() || !it.longitude.isFinite() }) {
            return false
        }
        val expected = turnAngleDegrees(maneuver)
        val maximumDeflection = if (maneuver.isUTurn()) {
            MAX_U_TURN_DEFLECTION_DEGREES
        } else {
            MAX_REGULAR_EXIT_DEFLECTION_DEGREES
        }
        val exitSegments = line.zipWithNext().takeLast(EXIT_STEPS)
        if (exitSegments.size != EXIT_STEPS) return false
        val deflections = exitSegments.map { (from, to) ->
            if (distanceMeters(from, to) < MIN_VALID_SEGMENT_METERS) return false
            shortestHeadingDelta(approachHeading, bearingDegrees(from, to))
        }
        val terminalNose = shortestHeadingDelta(
            noseHeadingDegrees,
            bearingDegrees(line[line.lastIndex - 1], line.last()),
        )
        if (expected == 0.0) {
            return deflections.all { abs(it) <= STRAIGHT_DEFLECTION_TOLERANCE_DEGREES } &&
                abs(terminalNose) <= STRAIGHT_NOSE_ERROR_DEGREES + STRAIGHT_DEFLECTION_TOLERANCE_DEGREES
        }
        val direction = if (expected > 0.0) 1.0 else -1.0
        val directed = deflections.map { it * direction }
        if (directed.any { it < -DIRECTION_TOLERANCE_DEGREES || it > maximumDeflection }) {
            return false
        }
        if (directed.zipWithNext().any { (before, after) -> after + PROGRESSION_TOLERANCE_DEGREES < before }) {
            return false
        }
        // Near 180 degrees the shortest-angle sign aliases, so a U-turn's tip
        // cannot be side-checked against the nose; its cone and progression
        // checks above stay authoritative.
        if (!maneuver.isUTurn() &&
            terminalNose * direction < abs(expected) / 2.0 - TERMINAL_DEFLECTION_TOLERANCE_DEGREES
        ) {
            return false
        }
        return abs(directed.last() - abs(expected)) <= TERMINAL_DEFLECTION_TOLERANCE_DEGREES
    }

    private fun List<GeoPoint>.toGuideLineJson(): String = buildString(size * 32) {
        append('[')
        this@toGuideLineJson.forEachIndexed { index, point ->
            if (index > 0) append(',')
            append('[')
            appendCoordinate(point.longitude)
            append(',')
            appendCoordinate(point.latitude)
            append(",0.0]")
        }
        append(']')
    }

    private fun GeoPoint.toGuidePointString(): String = buildString(32) {
        appendCoordinate(longitude)
        append(',')
        appendCoordinate(latitude)
        append(",0")
    }

    private fun StringBuilder.appendCoordinate(value: Double) {
        append(String.format(Locale.ROOT, "%.7f", value))
    }

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_008.8
        const val MIN_MANEUVER_DISTANCE_METERS = 3
        const val MAX_MANEUVER_DISTANCE_METERS = 150
        const val AR_START_DISTANCE_METERS = 50.0
        const val AR_START_LEAD_SECONDS = 4.0
        const val TURN_HOLD_DISTANCE_METERS = 25
        const val MIN_GUIDE_POINT_DISTANCE_METERS = 1.5
        const val MAX_GUIDE_POINT_DISTANCE_METERS = 220.0
        const val MAX_POSE_AGE_MS = 2_500L
        const val MAX_POSE_ACCURACY_METERS = 20.0
        const val MAX_APPROACH_ERROR_DEGREES = 70.0
        const val STRAIGHT_NOSE_ERROR_DEGREES = 15.0
        const val MAX_ANCHOR_HEADING_ERROR_DEGREES = 45.0
        const val ROUTE_RESET_DISTANCE_JUMP_METERS = 30
        const val APPROACH_POINT_SPACING_METERS = 8.0
        const val EXIT_POINT_SPACING_METERS = 6.0
        const val MAX_APPROACH_STEPS = 24
        const val EXIT_STEPS = 8
        const val MIN_VALID_SEGMENT_METERS = 4.0
        const val STRAIGHT_DEFLECTION_TOLERANCE_DEGREES = 8.0
        const val DIRECTION_TOLERANCE_DEGREES = 2.0
        const val PROGRESSION_TOLERANCE_DEGREES = 3.0
        const val TERMINAL_DEFLECTION_TOLERANCE_DEGREES = 6.0
        const val MAX_REGULAR_EXIT_DEFLECTION_DEGREES = 165.0
        const val MAX_U_TURN_DEFLECTION_DEGREES = 179.0
        // Exact 180 aliases left and right in shortest-angle arithmetic. Stop
        // five degrees short so the requested U-turn direction stays explicit.
        const val U_TURN_DEFLECTION_DEGREES = 175.0

        fun supports(maneuver: DenzaManeuver): Boolean = when (maneuver) {
            DenzaManeuver.STRAIGHT,
            DenzaManeuver.LEFT,
            DenzaManeuver.RIGHT,
            DenzaManeuver.SLIGHT_LEFT,
            DenzaManeuver.SLIGHT_RIGHT,
            DenzaManeuver.SHARP_LEFT,
            DenzaManeuver.SHARP_RIGHT,
            DenzaManeuver.U_TURN_LEFT,
            DenzaManeuver.U_TURN_RIGHT,
            -> true
            else -> false
        }

        fun turnAngleDegrees(maneuver: DenzaManeuver): Double = when (maneuver) {
            DenzaManeuver.SLIGHT_LEFT -> -35.0
            DenzaManeuver.LEFT -> -90.0
            DenzaManeuver.SHARP_LEFT -> -135.0
            DenzaManeuver.SLIGHT_RIGHT -> 35.0
            DenzaManeuver.RIGHT -> 90.0
            DenzaManeuver.SHARP_RIGHT -> 135.0
            DenzaManeuver.U_TURN_LEFT -> -U_TURN_DEFLECTION_DEGREES
            DenzaManeuver.U_TURN_RIGHT -> U_TURN_DEFLECTION_DEGREES
            else -> 0.0
        }

        fun DenzaManeuver.isUTurn(): Boolean =
            this == DenzaManeuver.U_TURN_LEFT || this == DenzaManeuver.U_TURN_RIGHT

        /**
         * How far the vehicle nose may diverge from the bearing to the guide
         * point before the rendered arrow stops reading as the commanded side.
         * Half the commanded angle keeps both the sign and a visible share of
         * the bend in the frame the driver sees.
         */
        fun maxNoseApproachErrorDegrees(maneuver: DenzaManeuver): Double = min(
            MAX_APPROACH_ERROR_DEGREES,
            max(STRAIGHT_NOSE_ERROR_DEGREES, abs(turnAngleDegrees(maneuver)) / 2.0),
        )

        fun normalizeHeading(value: Double): Double = ((value % 360.0) + 360.0) % 360.0

        fun shortestHeadingDelta(from: Double, to: Double): Double =
            ((to - from + 540.0) % 360.0) - 180.0

        fun destination(origin: GeoPoint, headingDegrees: Double, distanceMeters: Double): GeoPoint {
            val angularDistance = distanceMeters / EARTH_RADIUS_METERS
            val heading = Math.toRadians(headingDegrees)
            val latitude = Math.toRadians(origin.latitude)
            val longitude = Math.toRadians(origin.longitude)
            val destinationLatitude = asin(
                sin(latitude) * cos(angularDistance) +
                    cos(latitude) * sin(angularDistance) * cos(heading),
            )
            val destinationLongitude = longitude + atan2(
                sin(heading) * sin(angularDistance) * cos(latitude),
                cos(angularDistance) - sin(latitude) * sin(destinationLatitude),
            )
            return GeoPoint(
                latitude = Math.toDegrees(destinationLatitude),
                longitude = normalizeLongitude(Math.toDegrees(destinationLongitude)),
            )
        }

        fun distanceMeters(first: GeoPoint, second: GeoPoint): Double {
            val latitude1 = Math.toRadians(first.latitude)
            val latitude2 = Math.toRadians(second.latitude)
            val latitudeDelta = latitude2 - latitude1
            val longitudeDelta = Math.toRadians(second.longitude - first.longitude)
            val haversine = sin(latitudeDelta / 2).pow(2) +
                cos(latitude1) * cos(latitude2) * sin(longitudeDelta / 2).pow(2)
            return 2.0 * EARTH_RADIUS_METERS * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
        }

        fun bearingDegrees(first: GeoPoint, second: GeoPoint): Double {
            val latitude1 = Math.toRadians(first.latitude)
            val latitude2 = Math.toRadians(second.latitude)
            val longitudeDelta = Math.toRadians(second.longitude - first.longitude)
            val y = sin(longitudeDelta) * cos(latitude2)
            val x = cos(latitude1) * sin(latitude2) -
                sin(latitude1) * cos(latitude2) * cos(longitudeDelta)
            return normalizeHeading(Math.toDegrees(atan2(y, x)))
        }

        fun normalizeLongitude(value: Double): Double = ((value + 540.0) % 360.0) - 180.0
    }
}
