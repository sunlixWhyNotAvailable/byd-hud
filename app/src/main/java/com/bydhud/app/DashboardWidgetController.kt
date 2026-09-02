package com.bydhud.app

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Main-thread owner shared by the settings UI and the independent overlay service. */
internal object DashboardWidgetController {
    var state by mutableStateOf(DashboardWidgetState())
        private set
    var ukrainian by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    private var loaded = false
    private var permissionGranted = false
    private var startRequested = false
    private var service: DashboardWidgetOverlayService? = null
    private var commandGeneration = 0L
    private val main by lazy { Handler(Looper.getMainLooper()) }

    @JvmStatic fun snapshot(context: Context): DashboardWidgetState {
        load(context)
        return state
    }

    @JvmStatic fun hasOverlayPermission(): Boolean = permissionGranted

    @JvmStatic fun onRuntimePermissionsRefreshed(context: Context) {
        val app = context.applicationContext
        main.post {
            if (loaded && permissionGranted != MainActivity.cachedDashboardOverlayPermission()) refresh(app)
        }
    }

    private fun load(context: Context) {
        if (!loaded) {
            state = DashboardWidgetPreferences.load(context)
            loaded = true
        }
    }

    @JvmStatic fun onAppOpened(context: Context) {
        load(context)
        state = state.onAppOpened()
        DashboardWidgetPreferences.save(context, state)
        refresh(context)
    }

    @JvmStatic fun onRuntimeStart(context: Context) {
        if (HudPrefs.isBootEnabled(context) && !HudPrefs.isUserShutdownActive(context)) refresh(context)
    }

    @JvmStatic fun updateSettings(context: Context, requested: DashboardWidgetState) {
        load(context)
        state = mergeSettings(state, requested)
        DashboardWidgetPreferences.save(context, state)
        refresh(context)
    }

    // A UI snapshot must never rewind a newer drag or re-show a long-press-hidden widget.
    fun mergeSettings(current: DashboardWidgetState, requested: DashboardWidgetState): DashboardWidgetState {
        val shapeChanged = current.shape != requested.shape
        val layoutChanged = shapeChanged || current.orientation != requested.orientation ||
            current.expandForward != requested.expandForward
        return requested.copy(
            xFraction = current.xFraction, yFraction = current.yFraction,
            hidden = if (shapeChanged) false else current.hidden,
            expanded = if (layoutChanged) false else current.expanded
        ).normalized()
    }

    fun updateGesture(requested: DashboardWidgetState) {
        state = state.copy(xFraction = requested.xFraction, yFraction = requested.yFraction,
            expanded = requested.expanded).normalized()
    }

    fun savePosition(context: Context) {
        DashboardWidgetPreferences.save(context, state)
        MainActivity.publishSharedUiStateChange()
    }

    fun hide(context: Context) {
        if (!state.visible) return
        val app = context.applicationContext
        state = state.hide()
        DashboardWidgetPreferences.save(app, state)
        Log.i("DashboardWidget", "widget_hidden restore_on_app_open=true")
        Toast.makeText(app, if (ukrainian) "Віджет приховано — відкрийте BYD HUD, щоб повернути"
            else "Widget hidden — open BYD HUD to restore", Toast.LENGTH_LONG).show()
        refresh(app)
    }

    fun requestMode(context: Context, mode: DashboardWidgetMode) {
        val app = context.applicationContext
        if (busy || !state.visible || !state.expanded || HudPrefs.isUserShutdownActive(app)) return
        busy = true
        val generation = ++commandGeneration
        state = state.onModeClick()
        // The display controller also serializes against explicit Move/Return actions.
        NavAppDisplayController.get(app).requestWidgetMode(mode.ordinal, state.applyWindowProfile) { error ->
            main.post {
                if (generation != commandGeneration) return@post
                busy = false
                if (!HudPrefs.isUserShutdownActive(app) && !error.isNullOrEmpty()) {
                    Toast.makeText(app, (if (ukrainian) "Не вдалося змінити режим: "
                        else "Unable to change mode: ") + error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @JvmStatic fun refresh(context: Context) {
        load(context)
        val app = context.applicationContext
        permissionGranted = MainActivity.cachedDashboardOverlayPermission()
        ukrainian = HudPrefs.isUaLanguage(app)
        if (!state.visible || !permissionGranted || HudPrefs.isUserShutdownActive(app)) {
            stop(app)
        } else {
            val active = service
            if (active != null) {
                active.updateNotification()
                active.render()
            } else if (!startRequested) {
                startRequested = true
                try {
                    app.startForegroundService(Intent(app, DashboardWidgetOverlayService::class.java))
                } catch (error: RuntimeException) {
                    startRequested = false
                    Log.e("DashboardWidget", "Unable to start overlay", error)
                    Toast.makeText(app, if (ukrainian) "Не вдалося показати віджет" else "Unable to show widget",
                        Toast.LENGTH_LONG).show()
                }
            }
        }
        MainActivity.publishSharedUiStateChange()
    }

    fun serviceCreated(active: DashboardWidgetOverlayService) {
        load(active)
        ukrainian = HudPrefs.isUaLanguage(active)
        service = active
        startRequested = false
    }

    fun serviceDestroyed(active: DashboardWidgetOverlayService) {
        if (service === active) service = null
    }

    @JvmStatic fun shutdown(context: Context) {
        ++commandGeneration
        busy = false
        state = state.copy(expanded = false)
        stop(context.applicationContext)
    }

    private fun stop(context: Context) {
        service?.removeOverlay()
        service = null
        startRequested = false
        context.stopService(Intent(context, DashboardWidgetOverlayService::class.java))
    }
}
