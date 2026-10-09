package com.bydhud.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Main-thread owner shared by the settings UI and the independent overlay service. */
internal object DashboardWidgetController {
    var state by mutableStateOf(DashboardWidgetState())
        private set
    var uiLanguage by mutableStateOf(Language.En)
        private set
    var busy by mutableStateOf(false)
        private set
    private var loaded = false
    private var overlayPermission = DashboardWidgetLifecyclePolicy.Permission.UNKNOWN
    private var permissionRefreshFailed = false
    private var service: DashboardWidgetOverlayService? = null
    private var lifecycle = DashboardWidgetLifecyclePolicy.initialState()
    private var serviceGeneration = 0L
    private var commandGeneration = 0L
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var wakeReceiver: BroadcastReceiver? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var pendingWake: Pair<String, Long>? = null

    // Process-scoped observers also cover a manual session with auto-start disabled.
    private fun observeWake(app: Context) {
        if (wakeReceiver == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_SCREEN_ON) onRuntimeWake(app, "screen-on")
                    else if (intent.action == Intent.ACTION_SCREEN_OFF) {
                        pendingWake = null
                        service?.cancelWakeDraw("screen-off")
                        log(app, "sleep source=screen-off")
                    }
                }
            }
            try {
                val filter = IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) }
                if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                else app.registerReceiver(receiver, filter)
                wakeReceiver = receiver
            } catch (error: RuntimeException) {
                log(app, "observer_failed source=broadcast error=${error.javaClass.simpleName}")
            }
        }
        if (displayListener != null) return
        val displays = app.getSystemService(DisplayManager::class.java) ?: return
        var previous = displays.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: Display.STATE_UNKNOWN
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) = onDisplayChanged(id)
            override fun onDisplayRemoved(id: Int) = onDisplayChanged(id)
            override fun onDisplayChanged(id: Int) {
                if (id != Display.DEFAULT_DISPLAY) return
                val next = displays.getDisplay(id)?.state ?: Display.STATE_UNKNOWN
                if (next == previous) return
                log(app, "display_changed from=$previous to=$next")
                previous = next
                if (next == Display.STATE_ON) onRuntimeWake(app, "display-on")
                else {
                    pendingWake = null
                    service?.cancelWakeDraw("display-state-$next")
                }
            }
        }
        try {
            displays.registerDisplayListener(listener, main)
            displayListener = listener
            log(app, "observers_ready displayState=$previous")
        } catch (error: RuntimeException) {
            log(app, "observer_failed source=display error=${error.javaClass.simpleName}")
        }
    }

    @JvmStatic fun onRuntimeWake(context: Context, reason: String) {
        val app = context.applicationContext
        val signaledAt = SystemClock.elapsedRealtime()
        main.post {
            load(app)
            val displayState = app.getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: Display.STATE_UNKNOWN
            val interactive = app.getSystemService(PowerManager::class.java)?.isInteractive == true
            val skip = when {
                !UserRuntimeSession.allowsRuntime(app) -> "runtime-disabled"
                !state.enabled -> "disabled"
                state.hidden -> "user-hidden"
                displayState != Display.STATE_ON || !interactive -> "screen-not-ready"
                else -> "none"
            }
            log(app, "wake source=$reason signalElapsedMs=$signaledAt " +
                "dispatchDelayMs=${SystemClock.elapsedRealtime() - signaledAt} displayState=$displayState " +
                "interactive=$interactive skip=$skip")
            if (skip != "none") return@post
            pendingWake = reason to signaledAt
            refresh(app, publishUi = false)
            MainActivity.requestRuntimeStatusRefresh(app, true, "widget-wake:$reason")
        }
    }

    fun consumeWake(active: DashboardWidgetOverlayService, instance: Long): Pair<String, Long>? {
        if (service !== active || lifecycle.activeInstance != instance) return null
        return pendingWake.also { pendingWake = null }
    }

    private fun log(context: Context, message: String) {
        AppEventLogger.event(context, "dashboard_widget $message elapsedMs=${SystemClock.elapsedRealtime()} " +
            "enabled=${state.enabled} hidden=${state.hidden} permission=$overlayPermission " +
            "shutdown=${HudPrefs.isUserShutdownActive(context)} instance=${lifecycle.activeInstance} " +
            "startPending=${lifecycle.startRequested} closing=${service?.isClosing()}")
    }

    @JvmStatic fun snapshot(context: Context): DashboardWidgetState {
        load(context)
        return state
    }

    @JvmStatic fun hasOverlayPermission(): Boolean =
        overlayPermission == DashboardWidgetLifecyclePolicy.Permission.GRANTED

    @JvmStatic fun onRuntimePermissionsRefreshed(context: Context) {
        val app = context.applicationContext
        main.post {
            if (loaded) {
                log(app, "permission_refreshed value=${MainActivity.cachedDashboardOverlayPermission()}")
                refresh(app, publishUi = false)
            }
        }
    }

    @JvmStatic fun onRuntimePermissionsRefreshFailed(context: Context) {
        val app = context.applicationContext
        main.post {
            if (loaded && overlayPermission == DashboardWidgetLifecyclePolicy.Permission.UNKNOWN) {
                permissionRefreshFailed = true
                log(app, "permission_refresh_failed")
                reconcile(app, allowRetry = false)
            }
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
        if (UserRuntimeSession.allowsRuntime(context)) refresh(context)
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
        Toast.makeText(app, uiLanguage.choose("Віджет приховано — відкрийте BYD HUD, щоб повернути",
            "Widget hidden — open BYD HUD to restore", "Виджет скрыт — откройте BYD HUD, чтобы вернуть"), Toast.LENGTH_LONG).show()
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
                    Toast.makeText(app, uiLanguage.choose("Не вдалося змінити режим: ",
                        "Unable to change mode: ", "Не удалось изменить режим: ") + error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @JvmStatic fun refresh(context: Context) {
        refresh(context, publishUi = true)
    }

    private fun refresh(context: Context, publishUi: Boolean) {
        load(context)
        val app = context.applicationContext
        observeWake(app)
        overlayPermission = MainActivity.cachedDashboardOverlayPermission()
        permissionRefreshFailed = false
        lifecycle = DashboardWidgetLifecyclePolicy.allowRetry(lifecycle)
        uiLanguage = Language.fromCode(HudPrefs.uiLanguage(app))
        reconcile(app, allowRetry = true)
        if (publishUi) MainActivity.publishSharedUiStateChange()
    }

    private fun reconcile(app: Context, allowRetry: Boolean) {
        val shutdown = HudPrefs.isUserShutdownActive(app)
        val active = service
        val transition = DashboardWidgetLifecyclePolicy.reconcile(
            lifecycle, state.visible, overlayPermission, shutdown, permissionRefreshFailed,
            active?.isClosing() == true, allowRetry)
        lifecycle = transition.state
        if (transition.action != DashboardWidgetLifecyclePolicy.Action.RENDER || pendingWake != null) {
            log(app, "reconcile action=${transition.action} retry=$allowRetry permissionRefreshFailed=$permissionRefreshFailed")
        }
        when (transition.action) {
            DashboardWidgetLifecyclePolicy.Action.NONE -> Unit
            DashboardWidgetLifecyclePolicy.Action.START -> start(app)
            DashboardWidgetLifecyclePolicy.Action.RENDER -> active?.let {
                it.updateNotification()
                it.render()
            }
            DashboardWidgetLifecyclePolicy.Action.CLOSE -> stop(app)
        }
    }

    private fun start(app: Context) {
        try {
            app.startForegroundService(Intent(app, DashboardWidgetOverlayService::class.java))
            log(app, "service_start_requested")
        } catch (error: RuntimeException) {
            lifecycle = DashboardWidgetLifecyclePolicy.startFailed(lifecycle)
            Log.e("DashboardWidget", "Unable to start overlay", error)
            log(app, "service_start_failed error=${error.javaClass.simpleName}:${error.message}")
            Toast.makeText(app, uiLanguage.choose("Не вдалося показати віджет", "Unable to show widget", "Не удалось показать виджет"),
                Toast.LENGTH_LONG).show()
        }
    }

    fun serviceCreated(active: DashboardWidgetOverlayService): Long {
        load(active)
        observeWake(active.applicationContext)
        overlayPermission = MainActivity.cachedDashboardOverlayPermission()
        permissionRefreshFailed = false
        uiLanguage = Language.fromCode(HudPrefs.uiLanguage(active))
        val instance = ++serviceGeneration
        lifecycle = DashboardWidgetLifecyclePolicy.serviceCreated(lifecycle, instance)
        service = active
        log(active, "service_created")
        return instance
    }

    fun serviceStart(active: DashboardWidgetOverlayService, instance: Long): DashboardWidgetLifecyclePolicy.ServiceStart {
        if (service !== active || lifecycle.activeInstance != instance) {
            return DashboardWidgetLifecyclePolicy.ServiceStart.STOP
        }
        return DashboardWidgetLifecyclePolicy.serviceStart(
            state.visible, overlayPermission, HudPrefs.isUserShutdownActive(active),
            permissionRefreshFailed, active.isClosing())
    }

    fun serviceAttachmentFailed(active: DashboardWidgetOverlayService, instance: Long) {
        log(active, "attachment_failed failedInstance=$instance")
        val transition = DashboardWidgetLifecyclePolicy.attachmentFailed(lifecycle, instance)
        lifecycle = transition.state
        if (service === active && transition.action == DashboardWidgetLifecyclePolicy.Action.CLOSE) {
            active.closeFromController()
        }
    }

    fun serviceDestroyed(active: DashboardWidgetOverlayService, instance: Long) {
        log(active, "service_destroyed destroyedInstance=$instance")
        val permission = MainActivity.cachedDashboardOverlayPermission()
        val transition = DashboardWidgetLifecyclePolicy.serviceDestroyed(
            lifecycle, instance, state.visible, permission, HudPrefs.isUserShutdownActive(active))
        lifecycle = transition.state
        if (service !== active || transition.state.activeInstance != 0L) return
        service = null
        overlayPermission = permission
        if (transition.action == DashboardWidgetLifecyclePolicy.Action.START) {
            start(active.applicationContext)
        }
    }

    @JvmStatic fun shutdown(context: Context) {
        ++commandGeneration
        busy = false
        state = state.copy(expanded = false)
        stop(context.applicationContext)
    }

    private fun stop(context: Context) {
        pendingWake = null
        log(context, "stop_requested")
        lifecycle = DashboardWidgetLifecyclePolicy.cancel(lifecycle).state
        service?.closeFromController()
        context.stopService(Intent(context, DashboardWidgetOverlayService::class.java))
    }
}
