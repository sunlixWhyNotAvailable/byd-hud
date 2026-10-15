package com.bydhud.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DenzaArGeometryTest {
    @Test
    fun `pose filter acquires moving course and only holds it briefly while stopped`() {
        val filter = DenzaArPoseFilter()

        assertNull(filter.onFix(speedMetersPerSecond = 0.2, bearingDegrees = 80.0, capturedAtElapsedMs = 1_000L))
        val moving = filter.onFix(
            speedMetersPerSecond = 8.0,
            bearingDegrees = 80.0,
            capturedAtElapsedMs = 2_000L,
        )
        assertNotNull(moving)
        assertEquals(80.0, moving!!.headingDegrees, 1e-9)

        val stopped = filter.onFix(
            speedMetersPerSecond = 0.0,
            hasBearing = false,
            bearingDegrees = 0.0,
            capturedAtElapsedMs = 16_000L,
        )
        assertEquals(80.0, stopped!!.headingDegrees, 1e-9)
        assertNull(
            filter.onFix(
                speedMetersPerSecond = 0.0,
                hasBearing = false,
                bearingDegrees = 0.0,
                capturedAtElapsedMs = 17_001L,
            ),
        )
        assertNull(
            filter.onFix(
                speedMetersPerSecond = 8.0,
                bearingDegrees = 90.0,
                capturedAtElapsedMs = 1_999L,
            ),
        )
    }

    @Test
    fun `pose filter rejects accuracy mutations and smooths north on the short arc`() {
        val filter = DenzaArPoseFilter()
        assertNull(filter.onFix(accuracyMeters = 20.1, capturedAtElapsedMs = 1_000L))
        assertNull(
            filter.onFix(
                speedMetersPerSecond = 8.0,
                bearingDegrees = 90.0,
                bearingAccuracyDegrees = 45.1,
                capturedAtElapsedMs = 2_000L,
            ),
        )

        val first = filter.onFix(
            speedMetersPerSecond = 8.0,
            bearingDegrees = 350.0,
            capturedAtElapsedMs = 3_000L,
        )!!
        val second = filter.onFix(
            speedMetersPerSecond = 8.0,
            bearingDegrees = 10.0,
            capturedAtElapsedMs = 4_000L,
        )!!
        assertEquals(350.0, first.headingDegrees, 1e-9)
        assertTrue(second.headingDegrees > 350.0 || second.headingDegrees < 10.0)
    }

    @Test
    fun `distance support boundary and unsupported maneuver mutations fail closed`() {
        listOf(3, 150).forEach { distance ->
            assertNotNull(DenzaArTracker().geometry(guidance(distance = distance), pose(), NOW))
        }
        listOf(0, 2, 151, 500).forEach { distance ->
            assertNull(DenzaArTracker().geometry(guidance(distance = distance), pose(), NOW))
        }
        listOf(
            DenzaManeuver.UNKNOWN,
            DenzaManeuver.ROUNDABOUT,
        ).forEach { maneuver ->
            assertNull(DenzaArTracker().geometry(guidance(maneuver), pose(), NOW))
        }
    }

    @Test
    fun `stale future and imprecise pose mutations fail closed`() {
        assertNull(
            DenzaArTracker().geometry(
                guidance(),
                pose(capturedAtElapsedMs = NOW - 2_501L),
                NOW,
            ),
        )
        assertNull(
            DenzaArTracker().geometry(
                guidance(),
                pose(capturedAtElapsedMs = NOW + 1L),
                NOW,
            ),
        )
        assertNull(
            DenzaArTracker().geometry(
                guidance(),
                pose(accuracyMeters = 20.1),
                NOW,
            ),
        )
    }

    @Test
    fun `left and right paths mirror while straight remains centered`() {
        val left = geometry(DenzaManeuver.LEFT)
        val right = geometry(DenzaManeuver.RIGHT)
        val straight = geometry(DenzaManeuver.STRAIGHT)
        val leftPoint = parseGuidePoint(left.guidePoint)
        val rightPoint = parseGuidePoint(right.guidePoint)
        val straightPoint = parseGuidePoint(straight.guidePoint)
        val leftLast = parseGuideLine(left.guideLine).last()
        val rightLast = parseGuideLine(right.guideLine).last()
        val straightLast = parseGuideLine(straight.guideLine).last()

        assertTrue(leftLast.longitude < leftPoint.longitude)
        assertTrue(rightLast.longitude > rightPoint.longitude)
        assertEquals(
            abs(leftLast.longitude - leftPoint.longitude),
            abs(rightLast.longitude - rightPoint.longitude),
            2e-6,
        )
        assertEquals(straightPoint.longitude, straightLast.longitude, 2e-6)
    }

    @Test
    fun `slight normal and sharp mutations produce increasing exit deflection`() {
        val slight = exitDeflection(DenzaManeuver.SLIGHT_RIGHT)
        val normal = exitDeflection(DenzaManeuver.RIGHT)
        val sharp = exitDeflection(DenzaManeuver.SHARP_RIGHT)

        assertTrue("slight=$slight normal=$normal", slight in 10.0..<normal)
        assertTrue("normal=$normal sharp=$sharp", normal < sharp)
        assertTrue("sharp=$sharp", sharp < 170.0)
    }

    @Test
    fun `left and right u turns mirror and finish on the return heading`() {
        val left = geometry(DenzaManeuver.U_TURN_LEFT)
        val right = geometry(DenzaManeuver.U_TURN_RIGHT)
        val leftPoint = parseGuidePoint(left.guidePoint)
        val rightPoint = parseGuidePoint(right.guidePoint)
        val leftLine = parseGuideLine(left.guideLine)
        val rightLine = parseGuideLine(right.guideLine)

        assertEquals(-175.0, terminalDeflection(left), 2.0)
        assertEquals(175.0, terminalDeflection(right), 2.0)
        assertTrue(leftLine.last().longitude < leftPoint.longitude)
        assertTrue(rightLine.last().longitude > rightPoint.longitude)
        assertEquals(
            abs(leftLine.last().longitude - leftPoint.longitude),
            abs(rightLine.last().longitude - rightPoint.longitude),
            2e-6,
        )
        assertTrue(leftLine.last().latitude < leftPoint.latitude)
        assertTrue(rightLine.last().latitude < rightPoint.latitude)
    }

    @Test
    fun `neither the car nor a falling distance moves the anchored turn point`() {
        val tracker = DenzaArTracker()
        val first = tracker.geometry(guidance(distance = 100), pose(latitude = LATITUDE), NOW)!!
        val movedPose = pose(latitude = LATITUDE + metersToLatitude(10.0), capturedAtElapsedMs = NOW + 500L)
        val sameDistance = tracker.geometry(guidance(distance = 100), movedPose, NOW + 500L)!!
        // Google's 87 m disagrees with the GNSS track by 3 m.
        val updatedDistance = tracker.geometry(guidance(distance = 87), movedPose, NOW + 700L)!!

        assertEquals(first.guidePoint, sameDistance.guidePoint)
        assertEquals(first.guidePoint, updatedDistance.guidePoint)
        assertTrue(first.guideLine != sameDistance.guideLine)
    }

    @Test
    fun `reroute past the activation range starts a new step with its own anchor`() {
        val tracker = DenzaArTracker()
        val first = tracker.geometry(guidance(distance = 100), pose(), NOW)!!
        assertNull(tracker.geometry(guidance(distance = 151), pose(), NOW + 100L))
        val movedPose = pose(latitude = LATITUDE + metersToLatitude(20.0), capturedAtElapsedMs = NOW + 500L)
        val reentered = tracker.geometry(guidance(distance = 100), movedPose, NOW + 500L)!!

        assertTrue(
            distanceMeters(parseGuidePoint(first.guidePoint), parseGuidePoint(reentered.guidePoint)) > 19.0,
        )
    }

    @Test
    fun `large increasing distance mutation resets a recalculated route anchor`() {
        val tracker = DenzaArTracker()
        val first = tracker.geometry(guidance(distance = 80), pose(), NOW)!!
        val movedPose = pose(latitude = LATITUDE + metersToLatitude(10.0), capturedAtElapsedMs = NOW + 500L)
        val frame = tracker.resolve(guidance(distance = 130), movedPose, NOW + 500L)
        val recalculated = frame.geometry!!

        assertEquals(130, frame.distanceMeters)

        assertTrue(
            distanceMeters(parseGuidePoint(first.guidePoint), parseGuidePoint(recalculated.guidePoint)) > 50.0,
        )
    }

    @Test
    fun `large lateral course mutation is suppressed instead of rotating the route`() {
        val tracker = DenzaArTracker()
        assertNotNull(tracker.geometry(guidance(distance = 100), pose(headingDegrees = 0.0), NOW))
        assertNull(
            tracker.geometry(
                guidance(distance = 90),
                pose(headingDegrees = 90.0, capturedAtElapsedMs = NOW + 500L),
                NOW + 500L,
            ),
        )
    }

    @Test
    fun `stale incoming course cannot turn a right instruction into a reversal`() {
        val tracker = DenzaArTracker()
        assertNotNull(
            tracker.geometry(
                guidance(maneuver = DenzaManeuver.RIGHT, distance = 100),
                pose(headingDegrees = 0.0),
                NOW,
            ),
        )

        val foldedApproach = pose(
            latitude = LATITUDE + metersToLatitude(150.0),
            longitude = LONGITUDE + metersToLongitude(42.0),
            headingDegrees = 220.0,
            capturedAtElapsedMs = NOW + 500L,
        )
        assertNull(
            tracker.geometry(
                guidance(maneuver = DenzaManeuver.RIGHT, distance = 100),
                foldedApproach,
                NOW + 500L,
            ),
        )
    }

    @Test
    fun `moderately curved approach keeps terminal direction tied to the maneuver`() {
        val tracker = DenzaArTracker()
        assertNotNull(
            tracker.geometry(
                guidance(maneuver = DenzaManeuver.RIGHT, distance = 100),
                pose(headingDegrees = 0.0),
                NOW,
            ),
        )
        val curvedApproach = pose(
            latitude = LATITUDE + metersToLatitude(61.7),
            longitude = LONGITUDE - metersToLongitude(32.1),
            headingDegrees = 40.0,
            capturedAtElapsedMs = NOW + 500L,
        )

        val geometry = tracker.geometry(
            guidance(maneuver = DenzaManeuver.RIGHT, distance = 100),
            curvedApproach,
            NOW + 500L,
        )!!

        assertEquals(90.0, terminalDeflection(geometry), 2.0)
    }

    @Test
    fun `left-curving approach cannot slant a slight left onto the right of the nose`() {
        val tracker = DenzaArTracker()
        assertNotNull(
            tracker.geometry(
                guidance(maneuver = DenzaManeuver.SLIGHT_LEFT, distance = 100),
                pose(headingDegrees = 0.0),
                NOW,
            ),
        )
        // The car bears left toward the fork while Yandex's displayed distance
        // is unchanged, so the world-anchored point ends far right of the nose
        // and the drawn arrow would read as a right turn.
        val curved = pose(
            latitude = LATITUDE + metersToLatitude(55.0),
            longitude = LONGITUDE - metersToLongitude(10.0),
            headingDegrees = 325.0,
            capturedAtElapsedMs = NOW + 500L,
        )
        assertNull(
            tracker.geometry(
                guidance(maneuver = DenzaManeuver.SLIGHT_LEFT, distance = 100),
                curved,
                NOW + 500L,
            ),
        )
    }

    @Test
    fun `gentle drift inside the slight cone keeps the tip left of the nose`() {
        val tracker = DenzaArTracker()
        assertNotNull(
            tracker.geometry(
                guidance(maneuver = DenzaManeuver.SLIGHT_LEFT, distance = 100),
                pose(headingDegrees = 0.0),
                NOW,
            ),
        )
        val drifted = pose(
            latitude = LATITUDE + metersToLatitude(30.0),
            longitude = LONGITUDE - metersToLongitude(3.0),
            headingDegrees = 350.0,
            capturedAtElapsedMs = NOW + 500L,
        )

        val geometry = tracker.geometry(
            guidance(maneuver = DenzaManeuver.SLIGHT_LEFT, distance = 100),
            drifted,
            NOW + 500L,
        )!!

        assertTrue(terminalNoseDeflection(geometry) < -10.0)
    }

    @Test
    fun `straight guidance far off the nose fails closed instead of slanting`() {
        val tracker = DenzaArTracker()
        assertNotNull(
            tracker.geometry(
                guidance(maneuver = DenzaManeuver.STRAIGHT, distance = 100),
                pose(headingDegrees = 0.0),
                NOW,
            ),
        )
        assertNotNull(
            tracker.geometry(
                guidance(maneuver = DenzaManeuver.STRAIGHT, distance = 100),
                pose(headingDegrees = 10.0, capturedAtElapsedMs = NOW + 400L),
                NOW + 400L,
            ),
        )
        assertNull(
            tracker.geometry(
                guidance(maneuver = DenzaManeuver.STRAIGHT, distance = 100),
                pose(headingDegrees = 20.0, capturedAtElapsedMs = NOW + 500L),
                NOW + 500L,
            ),
        )
        // Within the cone again, but AR lost in this step does not come back.
        assertNull(
            tracker.geometry(
                guidance(maneuver = DenzaManeuver.STRAIGHT, distance = 100),
                pose(headingDegrees = 10.0, capturedAtElapsedMs = NOW + 600L),
                NOW + 600L,
            ),
        )
    }

    @Test
    fun `every generated maneuver keeps its semantic direction across compass headings`() {
        val expectedDeflections = mapOf(
            DenzaManeuver.STRAIGHT to 0.0,
            DenzaManeuver.SLIGHT_LEFT to -35.0,
            DenzaManeuver.LEFT to -90.0,
            DenzaManeuver.SHARP_LEFT to -135.0,
            DenzaManeuver.SLIGHT_RIGHT to 35.0,
            DenzaManeuver.RIGHT to 90.0,
            DenzaManeuver.SHARP_RIGHT to 135.0,
            DenzaManeuver.U_TURN_LEFT to -175.0,
            DenzaManeuver.U_TURN_RIGHT to 175.0,
        )

        expectedDeflections.forEach { (maneuver, expected) ->
            for (heading in 0 until 360 step 15) {
                val geometry = DenzaArTracker().geometry(
                    guidance(maneuver = maneuver, distance = 100),
                    pose(headingDegrees = heading.toDouble()),
                    NOW,
                )
                assertNotNull("maneuver=$maneuver heading=$heading", geometry)
                assertEquals(
                    "maneuver=$maneuver heading=$heading",
                    expected,
                    terminalDeflection(checkNotNull(geometry)),
                    2.0,
                )
                assertEquals(
                    "maneuver=$maneuver heading=$heading",
                    expected,
                    terminalNoseDeflection(geometry),
                    2.0,
                )
            }
        }
    }

    @Test
    fun `lateral course mutation ends ar for the step instead of rebasing the anchor`() {
        val tracker = DenzaArTracker()
        assertNotNull(tracker.geometry(guidance(distance = 100), pose(headingDegrees = 0.0), NOW))
        val changedCourse = pose(
            headingDegrees = 90.0,
            capturedAtElapsedMs = NOW + 500L,
        )

        assertNull(tracker.geometry(guidance(distance = 90), changedCourse, NOW + 500L))
        assertNull(tracker.geometry(guidance(distance = 90), changedCourse, NOW + 600L))
    }

    @Test
    fun `second by second distance with gnss disagreement keeps one guide point and no dropout`() {
        // The car drives from 100 m to 3 m before a right turn at 4 m/s. Google
        // reports its own rounded distance once a second, up to 3 m off the
        // GNSS track and sometimes higher than the second before; the 5 Hz
        // pose wanders 2 m along, 1 m across and 1.5 degrees in course; the
        // output loop samples every 350 ms.
        val tracker = DenzaArTracker()
        val guidePoints = LinkedHashSet<String>()
        val distances = ArrayList<Int>()
        val reports = ArrayList<Int>()
        var elapsed = 0L
        while (100.0 - SPEED_MPS * elapsed / 1000.0 >= 3.0) {
            val second = elapsed / 1000L
            val reported = Math.round(100.0 - SPEED_MPS * second + 3.0 * sin(2.9 * second)).toInt()
            val fixAt = elapsed - elapsed % 200L
            val fixSeconds = fixAt / 1000.0
            val along = SPEED_MPS * fixSeconds + 2.0 * sin(2.1 * fixSeconds + 1.0)
            val across = 1.0 * sin(1.7 * fixSeconds)
            val course = 1.5 * sin(0.9 * fixSeconds + 0.3)
            val frame = tracker.resolve(
                guidance(maneuver = DenzaManeuver.RIGHT, distance = reported),
                pose(
                    latitude = LATITUDE + metersToLatitude(along),
                    longitude = LONGITUDE + metersToLongitude(across),
                    headingDegrees = (course + 360.0) % 360.0,
                    capturedAtElapsedMs = NOW + fixAt,
                ),
                NOW + elapsed,
            )
            val geometry = frame.geometry
            assertNotNull("elapsed=$elapsed reported=$reported", geometry)
            guidePoints += geometry!!.guidePoint
            distances += frame.distanceMeters
            reports += reported
            elapsed += 350L
        }

        assertTrue("samples=${distances.size}", distances.size > 60)
        assertTrue("reports=$reports", reports.zipWithNext().any { (before, after) -> after > before })
        assertEquals(listOf(guidePoints.first()), guidePoints.toList())
        assertTrue("distances=$distances", distances.zipWithNext().all { (before, after) -> after <= before })
        assertEquals(reports.runningReduce { held, next -> minOf(held, next) }, distances)
    }

    @Test
    fun `held distance only falls within a step and a reroute starts a new one`() {
        val tracker = DenzaArTracker()
        val samples = listOf(100 to 0.0, 98 to 2.0, 99 to 2.5, 97 to 3.0, 120 to 3.5)
        val frames = samples.mapIndexed { index, (distance, travelled) ->
            val at = NOW + index * 350L
            tracker.resolve(
                guidance(distance = distance),
                pose(latitude = LATITUDE + metersToLatitude(travelled), capturedAtElapsedMs = at),
                at,
            )
        }

        assertEquals(listOf(100, 98, 98, 97, 97), frames.map { it.distanceMeters })
        val firstPoint = frames.first().geometry!!.guidePoint
        frames.forEach { assertEquals(firstPoint, it.geometry!!.guidePoint) }

        val rerouted = tracker.resolve(
            guidance(distance = 140),
            pose(latitude = LATITUDE + metersToLatitude(4.0), capturedAtElapsedMs = NOW + 2_000L),
            NOW + 2_000L,
        )
        assertEquals(140, rerouted.distanceMeters)
        val reroutedPoint = parseGuidePoint(rerouted.geometry!!.guidePoint)
        assertTrue(distanceMeters(parseGuidePoint(firstPoint), reroutedPoint) > 40.0)
    }

    @Test
    fun `held distance covers maneuvers without ar`() {
        listOf(DenzaManeuver.ROUNDABOUT, DenzaManeuver.UNKNOWN).forEach { maneuver ->
            val tracker = DenzaArTracker()
            val distances = listOf(100, 104, 96, 125, 140).map { distance ->
                tracker.resolve(guidance(maneuver, distance), pose(), NOW).also {
                    assertNull("maneuver=$maneuver", it.geometry)
                }.distanceMeters
            }
            assertEquals("maneuver=$maneuver", listOf(100, 100, 96, 96, 140), distances)
        }
    }

    @Test
    fun `ar lost once stays off for the step and a new step starts it again`() {
        val tracker = DenzaArTracker()
        val stale = pose(capturedAtElapsedMs = NOW - 3_000L)
        // Before AR has been shown, a sample without it does not close the step.
        val waiting = tracker.resolve(guidance(distance = 100), stale, NOW)
        assertNull(waiting.geometry)
        assertEquals(100, waiting.distanceMeters)
        assertNotNull(tracker.geometry(guidance(distance = 100), pose(), NOW))

        assertNull(tracker.geometry(guidance(distance = 95), stale, NOW + 350L))
        (1..4).forEach { sample ->
            val at = NOW + 350L + sample * 350L
            val recovered = pose(
                latitude = LATITUDE + metersToLatitude(sample * 2.0),
                capturedAtElapsedMs = at,
            )
            val frame = tracker.resolve(guidance(distance = 95 - sample * 2), recovered, at)
            assertNull("sample=$sample", frame.geometry)
            assertEquals(95 - sample * 2, frame.distanceMeters)
        }

        val nextAt = NOW + 3_000L
        val nextStep = tracker.resolve(
            DenzaGuidance(DenzaManeuver.LEFT, "Next road", 120),
            pose(capturedAtElapsedMs = nextAt),
            nextAt,
        )
        assertNotNull(nextStep.geometry)
        assertEquals(120, nextStep.distanceMeters)
        tracker.reset()
        assertNotNull(tracker.geometry(guidance(distance = 100), pose(capturedAtElapsedMs = nextAt), nextAt))
    }

    @Test
    fun `simulated turn shows ar from fifty metres through the corner with one guide point`() {
        val tracker = DenzaArTracker()
        val turnAheadMeters = 100.0
        val arcRadius = 6.0
        val arcLength = PI / 2.0 * arcRadius
        val straight = turnAheadMeters - arcRadius
        var travelled = 0.0
        var pose: DenzaVehiclePose? = null
        var reported = 0
        var reports = 0
        var speed = 10.0
        val guidePoints = LinkedHashSet<String>()
        val heldLines = LinkedHashSet<String>()
        var firstArDistance: Int? = null
        var firstInRange: Int? = null
        var last: DenzaArFrame? = null
        var tick = 0
        // The approach, a right turn on a 6 m arc that starts 6 m before the node, and 10 m of
        // the new street while Google still reports 0 and has not moved on.
        while (travelled < straight + arcLength + 10.0) {
            val now = NOW + tick * POLL_MS
            val (north, east, heading) = when {
                travelled <= straight -> Triple(travelled, 0.0, 0.0)
                travelled <= straight + arcLength -> {
                    val angle = (travelled - straight) / arcRadius
                    Triple(straight + arcRadius * sin(angle), arcRadius * (1.0 - cos(angle)), Math.toDegrees(angle))
                }
                else -> Triple(turnAheadMeters, arcRadius + travelled - straight - arcLength, 90.0)
            }
            // A GNSS fix and Google's distance each about once a second, out of phase,
            // with a few metres of disagreement and a degree or so of course jitter.
            if (tick % 3 == 0) {
                val fix = tick / 3
                pose = pose(
                    latitude = LATITUDE + metersToLatitude(north + 0.5 * cos(fix * 0.9)),
                    longitude = LONGITUDE + metersToLongitude(east + 0.4 * sin(fix * 1.3 + 0.3)),
                    headingDegrees = (360.0 + heading + 1.2 * sin(fix * 2.1 + 0.5)) % 360.0,
                    speedMetersPerSecond = speed,
                    capturedAtElapsedMs = now,
                )
            }
            if (tick == 0 || tick % 3 == 2) {
                val remaining = turnAheadMeters - travelled
                reported = max(0, (remaining + 3.0 * sin(reports * 1.7 + 1.0)).roundToInt())
                reports++
            }

            val frame = tracker.resolve(guidance(DenzaManeuver.RIGHT, reported), pose, now)
            val held = frame.distanceMeters

            if (held > 50) assertNull("tick=$tick held=$held", frame.geometry)
            if (firstInRange == null && held <= 50) firstInRange = held
            if (firstArDistance == null && frame.geometry != null) firstArDistance = held
            if (firstArDistance != null) assertNotNull("tick=$tick held=$held", frame.geometry)
            frame.geometry?.let {
                guidePoints += it.guidePoint
                if (held <= 25) heldLines += it.guideLine
            }
            last = frame
            // 36 km/h, braking from 40 m out toward 14 km/h at the corner.
            val remaining = turnAheadMeters - travelled
            speed = if (remaining > 40.0) 10.0 else max(4.0, 4.0 + 6.0 * remaining / 40.0)
            travelled += speed * POLL_MS / 1000.0
            tick++
        }

        // Google reports once a second, so AR starts at the first report inside 50 m.
        assertEquals(firstInRange, checkNotNull(firstArDistance))
        assertEquals(guidePoints.toString(), 1, guidePoints.size)
        assertEquals(1, heldLines.size)
        val exit = checkNotNull(checkNotNull(last).geometry)
        assertEquals(0, checkNotNull(last).distanceMeters)
        assertEquals(90.0, exit.vehicleHeadingDegrees, 3.0)
    }

    @Test
    fun `ar starts at fifty metres at turning speed and earlier when faster`() {
        listOf(8.0 to 50, 20.0 to 80, 60.0 to 150).forEach { (speed, start) ->
            val tracker = DenzaArTracker()
            assertNull("speed=$speed", tracker.geometry(guidance(distance = start + 1), pose(speedMetersPerSecond = speed), NOW))
            assertNotNull("speed=$speed", tracker.geometry(guidance(distance = start), pose(speedMetersPerSecond = speed), NOW))
        }
    }

    @Test
    fun `inside the turn hold ar follows the car and ends only with the step`() {
        val tracker = DenzaArTracker()
        val approach = tracker.geometry(guidance(DenzaManeuver.RIGHT, 40), pose(), NOW)!!
        // Turning into the corner: a nose 60 degrees off the approach fails every guard.
        val turning = pose(
            latitude = LATITUDE + metersToLatitude(36.0),
            longitude = LONGITUDE + metersToLongitude(3.0),
            headingDegrees = 60.0,
            capturedAtElapsedMs = NOW + 3_000L,
        )
        val outside = DenzaArTracker()
        outside.geometry(guidance(DenzaManeuver.RIGHT, 40), pose(), NOW)!!
        assertNull(outside.geometry(guidance(DenzaManeuver.RIGHT, 30), turning, NOW + 3_000L))
        val held = tracker.geometry(guidance(DenzaManeuver.RIGHT, 4), turning, NOW + 3_000L)!!
        assertEquals(approach.guidePoint, held.guidePoint)
        assertEquals(approach.guideLine, held.guideLine)
        assertEquals(60.0, held.vehicleHeadingDegrees, 0.0)
        assertEquals(turning.latitude, held.vehicleLatitude, 0.0)

        // A fix that has gone stale keeps the last frame instead of dropping AR mid-turn.
        assertEquals(held, tracker.geometry(guidance(DenzaManeuver.RIGHT, 0), turning, NOW + 6_000L))
        val next = tracker.resolve(
            DenzaGuidance(DenzaManeuver.LEFT, "Next road", 300),
            pose(capturedAtElapsedMs = NOW + 6_000L),
            NOW + 6_000L,
        )
        assertNull(next.geometry)
        assertEquals(300, next.distanceMeters)
    }

    @Test
    fun `the turn hold does not bring back ar lost before it`() {
        val tracker = DenzaArTracker()
        assertNotNull(tracker.geometry(guidance(distance = 45), pose(), NOW))
        assertNull(tracker.geometry(guidance(distance = 40), pose(), NOW + 2_501L))
        val fresh = pose(latitude = LATITUDE + metersToLatitude(25.0), capturedAtElapsedMs = NOW + 3_000L)
        assertNull(tracker.geometry(guidance(distance = 20), fresh, NOW + 3_000L))
    }

    @Test
    fun `longitude wrap mutation keeps every coordinate finite and normalized`() {
        val geometry = DenzaArTracker().geometry(
            guidance(maneuver = DenzaManeuver.RIGHT, distance = 150),
            pose(latitude = 80.0, longitude = 179.9999, headingDegrees = 90.0),
            NOW,
        )!!

        (parseGuideLine(geometry.guideLine) + parseGuidePoint(geometry.guidePoint)).forEach { point ->
            assertTrue(point.latitude.isFinite())
            assertTrue(point.longitude.isFinite())
            assertTrue(point.longitude in -180.0..180.0)
        }
        assertFalse(geometry.guideLine.contains("NaN"))
        assertFalse(geometry.guideLine.contains("Infinity"))
    }

    @Test
    fun `some ip payload uses verified AR field numbers and wire types`() {
        val ar = geometry(DenzaManeuver.LEFT)
        val fields = decodeFields(DenzaArFields.encode(ar))

        assertEquals(1, fields.getValue(19).wireType)
        assertEquals(1, fields.getValue(20).wireType)
        assertEquals(0, fields.getValue(21).wireType)
        assertEquals(0, fields.getValue(22).wireType)
        assertEquals(2, fields.getValue(30).wireType)
        assertEquals(2, fields.getValue(31).wireType)
        assertEquals(1, fields.getValue(32).wireType)
        assertEquals(1, fields.getValue(33).wireType)
        assertEquals(ar.vehicleLongitude, fields.getValue(19).doubleValue(), 0.0)
        assertEquals(ar.vehicleLatitude, fields.getValue(20).doubleValue(), 0.0)
        assertEquals(ar.guideLine, fields.getValue(30).stringValue())
        assertEquals(ar.guidePoint, fields.getValue(31).stringValue())
    }

    private fun DenzaArPoseFilter.onFix(
        latitude: Double = LATITUDE,
        longitude: Double = LONGITUDE,
        altitudeMeters: Double = 120.0,
        hasAltitude: Boolean = true,
        speedMetersPerSecond: Double = 8.0,
        accuracyMeters: Double = 4.0,
        bearingDegrees: Double = 0.0,
        hasBearing: Boolean = true,
        bearingAccuracyDegrees: Double? = 5.0,
        capturedAtElapsedMs: Long,
    ) = onFix(
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = altitudeMeters,
        hasAltitude = hasAltitude,
        speedMetersPerSecond = speedMetersPerSecond,
        accuracyMeters = accuracyMeters,
        bearingDegrees = bearingDegrees,
        hasBearing = hasBearing,
        bearingAccuracyDegrees = bearingAccuracyDegrees,
        capturedAtElapsedMs = capturedAtElapsedMs,
    )

    private fun guidance(
        maneuver: DenzaManeuver = DenzaManeuver.LEFT,
        distance: Int = 100,
    ) = DenzaGuidance(maneuver, "Test road", distance)

    private fun pose(
        latitude: Double = LATITUDE,
        longitude: Double = LONGITUDE,
        headingDegrees: Double = 0.0,
        accuracyMeters: Double = 4.0,
        // Fast enough that AR may start anywhere in the 150 m window; the start distance has its own tests.
        speedMetersPerSecond: Double = 40.0,
        capturedAtElapsedMs: Long = NOW,
    ) = DenzaVehiclePose(
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = 120.0,
        speedMetersPerSecond = speedMetersPerSecond,
        headingDegrees = headingDegrees,
        accuracyMeters = accuracyMeters,
        capturedAtElapsedMs = capturedAtElapsedMs,
    )

    private fun DenzaArTracker.geometry(
        guidance: DenzaGuidance,
        pose: DenzaVehiclePose?,
        nowElapsedMs: Long,
    ): DenzaArGeometry? = resolve(guidance, pose, nowElapsedMs).geometry

    private fun geometry(maneuver: DenzaManeuver): DenzaArGeometry =
        DenzaArTracker().geometry(guidance(maneuver), pose(), NOW)!!

    private fun exitDeflection(maneuver: DenzaManeuver): Double {
        val geometry = geometry(maneuver)
        val guidePoint = parseGuidePoint(geometry.guidePoint)
        val last = parseGuideLine(geometry.guideLine).last()
        return abs(bearingDegrees(guidePoint, last))
    }

    private fun terminalDeflection(geometry: DenzaArGeometry): Double {
        val line = parseGuideLine(geometry.guideLine)
        val approach = bearingDegrees(line.first(), parseGuidePoint(geometry.guidePoint))
        val terminal = bearingDegrees(line[line.lastIndex - 1], line.last())
        return ((terminal - approach + 540.0) % 360.0) - 180.0
    }

    private fun terminalNoseDeflection(geometry: DenzaArGeometry): Double {
        val line = parseGuideLine(geometry.guideLine)
        val terminal = bearingDegrees(line[line.lastIndex - 1], line.last())
        return ((terminal - geometry.vehicleHeadingDegrees + 540.0) % 360.0) - 180.0
    }

    private data class Point(val latitude: Double, val longitude: Double)

    private fun parseGuidePoint(value: String): Point {
        val fields = value.split(',')
        return Point(latitude = fields[1].toDouble(), longitude = fields[0].toDouble())
    }

    private fun parseGuideLine(value: String): List<Point> = TRIPLE.findAll(value).map { match ->
        Point(
            latitude = match.groupValues[2].toDouble(),
            longitude = match.groupValues[1].toDouble(),
        )
    }.toList()

    private fun bearingDegrees(first: Point, second: Point): Double {
        val lat1 = Math.toRadians(first.latitude)
        val lat2 = Math.toRadians(second.latitude)
        val deltaLon = Math.toRadians(second.longitude - first.longitude)
        val value = Math.toDegrees(
            atan2(
                sin(deltaLon) * cos(lat2),
                cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon),
            ),
        )
        return (value + 360.0) % 360.0
    }

    private fun distanceMeters(first: Point, second: Point): Double {
        val north = (second.latitude - first.latitude) * 111_195.0
        val east = (second.longitude - first.longitude) * 111_195.0 *
            cos(Math.toRadians((first.latitude + second.latitude) / 2.0))
        return kotlin.math.hypot(north, east)
    }

    private fun metersToLatitude(value: Double): Double = value / 111_195.0

    private fun metersToLongitude(value: Double): Double =
        value / (111_195.0 * cos(Math.toRadians(LATITUDE)))

    private data class WireField(val wireType: Int, val bytes: ByteArray) {
        fun doubleValue(): Double = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).double
        fun stringValue(): String = bytes.toString(Charsets.UTF_8)
    }

    private fun decodeFields(payload: ByteArray): Map<Int, WireField> {
        val outerOffset = 0
        val end = payload.size
        val result = LinkedHashMap<Int, WireField>()
        var offset = outerOffset
        while (offset < end) {
            val tag = readVarint(payload, offset)
            offset = tag.nextOffset
            val field = tag.value ushr 3
            val wireType = tag.value and 7
            val bytes = when (wireType) {
                0 -> {
                    val value = readVarint(payload, offset)
                    offset = value.nextOffset
                    byteArrayOf()
                }
                1 -> payload.copyOfRange(offset, offset + 8).also { offset += 8 }
                2 -> {
                    val size = readVarint(payload, offset)
                    offset = size.nextOffset
                    payload.copyOfRange(offset, offset + size.value).also { offset += size.value }
                }
                else -> error("Unsupported wire type $wireType")
            }
            result[field] = WireField(wireType, bytes)
        }
        return result
    }

    private data class Varint(val value: Int, val nextOffset: Int)

    private fun readVarint(bytes: ByteArray, start: Int): Varint {
        var value = 0
        var shift = 0
        var offset = start
        while (true) {
            val byte = bytes[offset++].toInt() and 0xff
            value = value or ((byte and 0x7f) shl shift)
            if (byte and 0x80 == 0) return Varint(value, offset)
            shift += 7
        }
    }

    private companion object {
        const val LATITUDE = 55.75
        const val LONGITUDE = 37.61
        const val NOW = 100_000L
        const val SPEED_MPS = 4.0
        const val POLL_MS = 350L
        val TRIPLE = Regex("\\[(-?[0-9.]+),(-?[0-9.]+),0(?:\\.0)?]")
    }
}
