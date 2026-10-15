package com.bydhud.app

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class DenzaArControllerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(LocationManager::class.java)
    private val gps = shadowOf(manager)
    private lateinit var controller: DenzaArController

    @Before fun setup() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        gps.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        controller = DenzaArController(context, Handler(Looper.getMainLooper()))
    }
    @After fun cleanup() { controller.stop() }

    @Test fun `disabled and missing precise permission never subscribe`() {
        assertNull(resolve(false))
        assertTrue(gps.locationUpdateListeners.isEmpty())
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertNull(resolve())
        assertTrue(gps.locationUpdateListeners.isEmpty())
        assertEquals("location_permission_missing", controller.status())
    }

    @Test fun `fresh moving fix enables geometry and stale fix clears it`() {
        assertNull(geometry())
        fix()
        assertNotNull(geometry())
        shadowOf(Looper.getMainLooper()).idleFor(2501, TimeUnit.MILLISECONDS)
        assertNull(geometry())
        assertEquals("compact_only", controller.status())
    }

    @Test fun `stop unsubscribes and queued callback cannot seed next route`() {
        resolve()
        val obsolete = gps.locationUpdateListeners.single()
        fix()
        assertNotNull(geometry())
        controller.stop()
        assertTrue(gps.locationUpdateListeners.isEmpty())
        resolve(generation = 2)
        obsolete.onLocationChanged(location())
        assertNull(geometry(generation = 2))
        fix()
        assertNotNull(geometry(generation = 2))
    }

    @Test fun `permission revocation removes subscription and geometry`() {
        resolve(); fix(); assertNotNull(geometry())
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertNull(resolve())
        assertTrue(gps.locationUpdateListeners.isEmpty())
    }

    @Test fun `next identical maneuver gets its own geographic anchor`() {
        resolve(); fix()
        val first = geometry()!!
        fix(latitude = 55.75009)
        val sameStep = geometry()!!
        assertEquals(first.guidePoint, sameStep.guidePoint)
        val nextStep = geometry(frame = frame(1))!!
        assertNotEquals(first.guidePoint, nextStep.guidePoint)
    }

    @Test fun `held distance never grows within a google step and restarts with the next`() {
        assertEquals(100, resolve(frame = frame(distance = 100))!!.distanceMeters)
        assertEquals(96, resolve(frame = frame(distance = 96))!!.distanceMeters)
        assertEquals(96, resolve(frame = frame(distance = 110))!!.distanceMeters)
        assertEquals(110, resolve(frame = frame(1, distance = 110))!!.distanceMeters)
    }

    @Test fun `parked startup and roundabout use compact guidance only`() {
        resolve(); fix(speed = 0f)
        assertNull(geometry())
        fix()
        assertNotNull(geometry())
        assertNull(geometry(frame = DirectTbtFrame(55, 11, 99, 60, "", "", "", null,
            null, emptyList(), DirectTbtFrame.AlertOverlay.inactive())))
    }

    // Inside the 50 m start at the fixes' 8 m/s and outside the 25 m turn hold.
    private fun frame(index: Int = 0, distance: Int = 40) = DirectTbtFrame(7, 2, 1, distance, "Road", "Left",
        "Road", null, null, emptyList(), DirectTbtFrame.AlertOverlay.inactive()).withSourceStepIndex(index)
    private fun resolve(enabled: Boolean = true, generation: Long = 1, frame: DirectTbtFrame = frame()) =
        controller.resolve(enabled, generation, frame, SystemClock.elapsedRealtime())
    private fun geometry(generation: Long = 1, frame: DirectTbtFrame = frame()) =
        resolve(generation = generation, frame = frame)?.geometry
    private fun location(latitude: Double = 55.75, speed: Float = 8f) = Location(LocationManager.GPS_PROVIDER).apply {
        this.latitude = latitude; longitude = 37.61; accuracy = 4f; this.speed = speed
        bearing = 0f; bearingAccuracyDegrees = 5f; altitude = 120.0
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }
    private fun fix(latitude: Double = 55.75, speed: Float = 8f) {
        shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS)
        gps.simulateLocation(location(latitude, speed))
        shadowOf(Looper.getMainLooper()).idle()
    }
}
