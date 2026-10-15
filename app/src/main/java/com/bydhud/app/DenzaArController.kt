package com.bydhud.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock

/** Owned by the existing HUD output thread; never creates another SOME/IP sender. */
class DenzaArController(context: Context, private val worker: Handler) {
    private val context = context.applicationContext
    private val manager = context.getSystemService(LocationManager::class.java)
    private val filter = DenzaArPoseFilter()
    private val tracker = DenzaArTracker()
    private var stepIndex = -1
    private var pose: DenzaVehiclePose? = null
    private var listener: LocationListener? = null
    private var session = Long.MIN_VALUE
    private var nextRegistrationAt = 0L
    private var status = "off"

    fun status(): String = status

    /** The held distance and optional AR geometry for [frame]; null while disabled, without a frame or permission. */
    fun resolve(enabled: Boolean, generation: Long, frame: DirectTbtFrame?, now: Long): DenzaArFrame? {
        check(android.os.Looper.myLooper() == worker.looper)
        if (!enabled || frame == null) {
            stop()
            return null
        }
        if (session != generation) {
            stop()
            session = generation
        }
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stop()
            status = "location_permission_missing"
            return null
        }
        register(now)
        if (stepIndex != frame.sourceStepIndex) {
            tracker.reset()
            stepIndex = frame.sourceStepIndex
        }
        val result = tracker.resolve(
            DenzaGuidance(DenzaManeuver.fromGoogle(frame.rawManeuverType), frame.roadText, frame.distanceMeters),
            pose, now,
        )
        status = if (result.geometry == null) "compact_only" else "geometry_ready"
        return result
    }

    private fun register(now: Long) {
        if (listener != null || manager == null || now < nextRegistrationAt) return
        nextRegistrationAt = now + 5_000L
        val subscription = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (listener !== this) return // A queued callback from a stopped route is obsolete.
                val fixTime = location.elapsedRealtimeNanos / 1_000_000L
                val age = SystemClock.elapsedRealtime() - fixTime
                pose = if (age !in 0L..2_500L || !location.hasSpeed()) null else filter.onFix(
                    location.latitude, location.longitude, location.altitude, location.hasAltitude(),
                    location.speed.toDouble(), if (location.hasAccuracy()) location.accuracy.toDouble() else -1.0,
                    location.bearing.toDouble(), location.hasBearing(),
                    if (location.hasBearingAccuracy()) location.bearingAccuracyDegrees.toDouble() else null,
                    fixTime,
                )
            }

            override fun onProviderDisabled(provider: String) {
                if (listener !== this) return
                pose = null
                filter.reset()
                tracker.reset()
            }
            override fun onProviderEnabled(provider: String) = Unit
            @Deprecated("Legacy platform callback")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        listener = subscription
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 200L, 0f, subscription, worker.looper)
        } catch (_: SecurityException) {
            listener = null
        } catch (_: IllegalArgumentException) {
            listener = null // No GPS provider: keep the existing compact guidance.
        }
    }

    fun stop() {
        val subscription = listener
        listener = null
        if (subscription != null) {
            try { manager?.removeUpdates(subscription) } catch (_: SecurityException) { /* Revoked permission. */ }
        }
        pose = null
        filter.reset()
        tracker.reset()
        session = Long.MIN_VALUE
        stepIndex = -1
        nextRegistrationAt = 0L
        status = "off"
    }
}
