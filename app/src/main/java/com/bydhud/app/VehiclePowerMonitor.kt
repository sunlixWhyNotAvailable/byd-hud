package com.bydhud.app

import android.content.Context
import android.os.Handler
import android.os.Looper

/** One OEM read for all interested main-process consumers. */
internal object VehiclePowerMonitor {
    data class Reading(val raw: Int = -1, val error: String = "not-read")

    private val main = Handler(Looper.getMainLooper())
    private val observers = linkedMapOf<String, (Reading) -> Unit>()
    private var app: Context? = null
    private var initialized = false
    private var generation = 0L
    private var pending = false
    private var reading = Reading()
    private val poll = Runnable { read() }

    fun observe(context: Context, owner: String, observer: (Reading) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        app = context.applicationContext
        if (!initialized) {
            initialized = true
            InstrumentProxyManager.get(app).apply {
                addReadyListener { main.post { schedule() } }
                addUnavailableListener { reason -> main.post {
                    generation++
                    publish(Reading(error = "helper-unavailable:$reason"))
                    schedule()
                } }
            }
        }
        observers[owner] = observer
        main.post { if (observers[owner] === observer) observer(reading) }
        schedule()
    }

    fun remove(owner: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        observers.remove(owner)
        if (observers.isEmpty()) {
            generation++
            main.removeCallbacks(poll)
            reading = Reading()
        }
    }

    private fun schedule(delayMs: Long = 0L) {
        if (observers.isNotEmpty() && !pending && !main.hasCallbacks(poll)) {
            main.postDelayed(poll, delayMs)
        }
    }

    private fun read() {
        val context = app ?: return
        if (observers.isEmpty() || pending) return
        pending = true
        val ticket = generation
        var expired = false
        val timeout = Runnable {
            expired = true
            if (ticket == generation && observers.isNotEmpty()) {
                publish(Reading(error = "vehicle power read timeout after 3000 ms"))
            }
        }
        main.postDelayed(timeout, 3_000L)
        try {
            InstrumentProxyManager.get(context).readVehiclePower { result -> main.post {
                pending = false
                main.removeCallbacks(timeout)
                if (!expired && ticket == generation && observers.isNotEmpty()) {
                    val raw = result.vehiclePower
                    val error = result.error.orEmpty()
                    publish(if (error.isEmpty() && raw in 0..4) Reading(raw, "")
                        else Reading(error = error.ifEmpty { "invalid vehicle power level: $raw" }))
                }
                schedule(1_000L)
            } }
        } catch (error: RuntimeException) {
            pending = false
            main.removeCallbacks(timeout)
            publish(Reading(error = FailureDiagnostics.format(error, "vehicle_power", "read-admission")))
            schedule(1_000L)
        }
    }

    private fun publish(next: Reading) {
        if (reading == next) return
        reading = next
        observers.toList().forEach { (owner, observer) ->
            if (observers[owner] === observer) observer(next)
        }
    }
}
