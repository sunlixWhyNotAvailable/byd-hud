package com.bydhud.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.roundToInt

/** A fixed anchor plus a transient menu; touches outside both pass to the underlying app. */
class DashboardWidgetOverlayService : Service(), SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    private lateinit var windows: WindowManager
    private lateinit var overlayContext: Context
    private lateinit var anchorView: ComposeView
    private lateinit var menuView: ComposeView
    private var anchorAttached = false
    private var menuAttached = false
    private var closing = false
    private var windowDp by mutableStateOf(Offset.Zero)
    private val main = Handler(Looper.getMainLooper())
    private val inactivityCallback = Runnable {
        val state = DashboardWidgetController.state
        if (state.expanded && state.autoCollapseAfterInactivity) {
            DashboardWidgetController.updateGesture(state.copy(expanded = false))
        }
    }
    private val anchorParams = overlayParams()
    private val menuParams = overlayParams()

    override fun onCreate() {
        super.onCreate()
        DashboardWidgetController.serviceCreated(this)
        MainActivity.requestRuntimeStatusRefresh(this, false, "widget-service")
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "BYD HUD dashboard widget", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(NOTIFICATION_ID, notification())
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val displayContext = if (display != null) createDisplayContext(display) else this
        overlayContext = if (Build.VERSION.SDK_INT >= 30) {
            displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else displayContext
        windows = overlayContext.getSystemService(WindowManager::class.java)
        anchorView = ComposeView(overlayContext).apply {
            setViewTreeLifecycleOwner(this@DashboardWidgetOverlayService)
            setViewTreeSavedStateRegistryOwner(this@DashboardWidgetOverlayService)
            setContent {
                val widgetState = DashboardWidgetController.state
                DashboardWidgetAnchorContent(
                    state = widgetState,
                    windowSizeDp = windowDp,
                    ua = DashboardWidgetController.ukrainian,
                    onChange = { DashboardWidgetController.updateGesture(it) },
                    onHide = { DashboardWidgetController.hide(this@DashboardWidgetOverlayService) },
                    onPositionSettled = { DashboardWidgetController.savePosition(this@DashboardWidgetOverlayService) },
                    onInteraction = { markInteraction() }
                )
                SideEffect { updateWindows(widgetState) }
            }
        }
        menuView = ComposeView(overlayContext).apply {
            setViewTreeLifecycleOwner(this@DashboardWidgetOverlayService)
            setViewTreeSavedStateRegistryOwner(this@DashboardWidgetOverlayService)
            setContent {
                val widgetState = DashboardWidgetController.state
                widgetState.layout(windowDp.x, windowDp.y).menu?.let { menu ->
                    DashboardWidgetMenuContent(
                        state = widgetState,
                        layout = menu,
                        busy = DashboardWidgetController.busy,
                        onMode = { DashboardWidgetController.requestMode(this@DashboardWidgetOverlayService, it) },
                        onInteraction = { markInteraction() }
                    )
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!canShow()) {
            removeOverlay()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        render()
        // Restore an already enabled widget after process loss, without sending a vehicle command.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        render()
    }

    private fun canShow() = !closing && DashboardWidgetController.state.visible &&
        !HudPrefs.isUserShutdownActive(this) && DashboardWidgetController.hasOverlayPermission()

    @Suppress("DEPRECATION")
    internal fun render() {
        if (!canShow()) {
            removeOverlay()
            stopSelf()
            return
        }
        val density = overlayContext.resources.displayMetrics.density
        windowDp = if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windows.maximumWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            Offset((metrics.bounds.width() - insets.left - insets.right) / density,
                (metrics.bounds.height() - insets.top - insets.bottom) / density)
        } else {
            val metrics = DisplayMetrics()
            windows.defaultDisplay.getMetrics(metrics)
            Offset(metrics.widthPixels / density, metrics.heightPixels / density)
        }
        updateWindows(DashboardWidgetController.state)
    }

    private fun updateWindows(state: DashboardWidgetState) {
        if (!canShow() || windowDp.x <= 0 || windowDp.y <= 0) return
        val layout = state.layout(windowDp.x, windowDp.y)
        try {
            anchorAttached = updateWindow(anchorView, anchorParams, layout.anchor, anchorAttached)
            val menu = layout.menu
            if (menu == null) detachMenu()
            else menuAttached = updateWindow(menuView, menuParams,
                DashboardWidgetWindowLayout(menu.left, menu.top, menu.width, menu.height), menuAttached)
        } catch (error: RuntimeException) {
            Log.e("DashboardWidget", "Unable to attach/update overlay", error)
            removeOverlay()
            Toast.makeText(this, if (DashboardWidgetController.ukrainian) "Не вдалося показати віджет"
                else "Unable to show widget", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun updateWindow(view: ComposeView, params: WindowManager.LayoutParams,
        layout: DashboardWidgetWindowLayout, attached: Boolean): Boolean {
        val density = overlayContext.resources.displayMetrics.density
        val width = (layout.width * density).roundToInt().coerceAtLeast(1)
        val height = (layout.height * density).roundToInt().coerceAtLeast(1)
        val x = (layout.left * density).roundToInt()
        val y = (layout.top * density).roundToInt()
        if (attached && params.width == width && params.height == height && params.x == x && params.y == y) {
            return true
        }
        params.width = width
        params.height = height
        params.x = x
        params.y = y
        if (attached) windows.updateViewLayout(view, params) else windows.addView(view, params)
        return true
    }

    private fun markInteraction() {
        main.removeCallbacks(inactivityCallback)
        main.postDelayed(inactivityCallback, INACTIVITY_TIMEOUT_MS)
    }

    private fun detachMenu() {
        if (!menuAttached) return
        try {
            windows.removeViewImmediate(menuView)
        } catch (error: RuntimeException) {
            Log.w("DashboardWidget", "Menu overlay already detached", error)
        } finally {
            menuAttached = false
        }
    }

    internal fun removeOverlay() {
        closing = true
        main.removeCallbacks(inactivityCallback)
        detachMenu()
        if (anchorAttached) {
            try {
                windows.removeViewImmediate(anchorView)
            } catch (error: RuntimeException) {
                Log.w("DashboardWidget", "Anchor overlay already detached", error)
            } finally {
                anchorAttached = false
            }
        }
    }

    private fun notification(): Notification {
        val ua = DashboardWidgetController.ukrainian
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_hud_notification)
            .setContentTitle(if (ua) "Віджет приборки · BYD HUD" else "Dashboard widget · BYD HUD")
            .setContentText(if (ua) "Натисніть, щоб відкрити BYD HUD" else "Tap to open BYD HUD")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build()
    }

    internal fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    override fun onDestroy() {
        removeOverlay()
        if (::anchorView.isInitialized) anchorView.disposeComposition()
        if (::menuView.isInitialized) menuView.disposeComposition()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        DashboardWidgetController.serviceDestroyed(this)
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "byd_hud_dashboard_widget"
        const val NOTIFICATION_ID = 4303
        const val INACTIVITY_TIMEOUT_MS = 5_000L

        fun overlayParams() = WindowManager.LayoutParams(
            1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            windowAnimations = 0
        }
    }
}
