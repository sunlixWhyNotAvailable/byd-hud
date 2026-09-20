package com.bydhud.app

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.roundToInt

internal sealed class UpdateHintPresentationDecision {
    data object None : UpdateHintPresentationDecision()
    data class Show(val resultId: Long, val available: AppUpdateManager.CheckResult.Available) : UpdateHintPresentationDecision()
    data class Release(val resultId: Long, val reason: String) : UpdateHintPresentationDecision()
}

/** Pure once-per-result presentation policy used by the process observer. */
internal class UpdateHintPresentationPolicy {
    private var lastHandledResultId = 0L
    private var activeResultId = 0L
    private var ownUiVisible = false

    fun onSnapshot(snapshot: AppUpdateManager.Snapshot, enabled: Boolean): UpdateHintPresentationDecision {
        if (snapshot.resultId == 0L) {
            return release("result-invalidated")
        }
        if (snapshot.resultId <= lastHandledResultId) return UpdateHintPresentationDecision.None
        lastHandledResultId = snapshot.resultId
        val available = snapshot.result as? AppUpdateManager.CheckResult.Available
            ?: return release("new-result-not-available")
        if (!enabled || ownUiVisible) return release("new-result-suppressed")
        activeResultId = snapshot.resultId
        return UpdateHintPresentationDecision.Show(snapshot.resultId, available)
    }

    fun afterRelease(resultId: Long) {
        if (activeResultId == resultId) activeResultId = 0L
    }

    fun setOwnUiVisible(visible: Boolean): UpdateHintPresentationDecision {
        ownUiVisible = visible
        return if (visible) release("own-ui-visible") else UpdateHintPresentationDecision.None
    }

    fun disable(): UpdateHintPresentationDecision = release("disabled")

    fun invalidate(resultId: Long, reason: String): UpdateHintPresentationDecision =
        if (activeResultId == resultId) release(reason) else UpdateHintPresentationDecision.None

    fun shutdown(): UpdateHintPresentationDecision = release("shutdown")

    private fun release(reason: String): UpdateHintPresentationDecision {
        val resultId = activeResultId
        if (resultId == 0L) return UpdateHintPresentationDecision.None
        activeResultId = 0L
        return UpdateHintPresentationDecision.Release(resultId, reason)
    }
}

/** Owns the app's one update-hint window; it never initiates an update check. */
object UpdateHintManager : Application.ActivityLifecycleCallbacks {
    private data class ActiveHint(
        val resultId: Long,
        val eventId: String,
        val info: AppUpdateManager.UpdateInfo,
        val windowContext: Context,
        val windows: WindowManager,
        val container: FrameLayout,
        val card: UpdateHintCardView,
        var params: WindowManager.LayoutParams? = null,
        var placement: UpdateHintPlacement? = null,
        var expiresAtElapsedMs: Long = 0L,
        var animator: ValueAnimator? = null
    )

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val policy = UpdateHintPresentationPolicy()
    private val visibleActivities = Collections.newSetFromMap(IdentityHashMap<Activity, Boolean>())
    private val pendingRoute = MutableStateFlow<Long?>(null)
    val pendingRouteResultId: StateFlow<Long?> = pendingRoute.asStateFlow()
    private var application: Application? = null
    private var appearance = UpdateHintAppearance()
    private var languageCode = "en"
    private var darkTheme = true
    private var active: ActiveHint? = null
    private var lastLoggedResultId = 0L
    private var screenOnReceiverRegistered = false
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_ON) return
            val hint = active ?: return
            if (hint.expiresAtElapsedMs > 0L && SystemClock.elapsedRealtime() >= hint.expiresAtElapsedMs) {
                dismiss(hint.resultId, "timeout-after-screen-on")
            } else if (hint.expiresAtElapsedMs > 0L) {
                scheduleExpiry(hint)
            }
        }
    }

    /** Parent integration: call once from the admitted main-process Application path. */
    @JvmStatic
    fun initialize(application: Application) {
        onMain {
            if (this.application != null) return@onMain
            this.application = application
            appearance = UpdateHintAppearance.read(application)
            languageCode = HudPrefs.uiLanguage(application)
            darkTheme = HudPrefs.isDarkTheme(application)
            application.registerActivityLifecycleCallbacks(this)
            scope.launch {
                AppUpdateManager.snapshot.collect { snapshot ->
                    pendingRoute.value?.let { pending ->
                        if (pending != snapshot.resultId) pendingRoute.value = null
                    }
                    val enabled = UpdateHintAppearance.isEnabled(application)
                    if (snapshot.resultId > lastLoggedResultId) {
                        lastLoggedResultId = snapshot.resultId
                        val reason = when {
                            snapshot.result !is AppUpdateManager.CheckResult.Available -> "not-available"
                            !enabled -> "disabled"
                            visibleActivities.isNotEmpty() -> "own-ui-visible"
                            else -> "eligible"
                        }
                        AppEventLogger.event(application, "update_hint admission resultId=${snapshot.resultId} reason=$reason")
                    }
                    applyDecision(policy.onSnapshot(snapshot, enabled))
                }
            }
        }
    }

    @JvmStatic fun isEnabled(context: Context): Boolean = UpdateHintAppearance.isEnabled(context)
    internal fun appearance(context: Context): UpdateHintAppearance =
        if (application == null) UpdateHintAppearance.read(context) else appearance

    @JvmStatic
    fun setEnabled(context: Context, enabled: Boolean) {
        UpdateHintAppearance.setEnabled(context, enabled)
        onMain { if (!enabled) applyDecision(policy.disable()) }
    }

    internal fun setAppearance(context: Context, value: UpdateHintAppearance) {
        val normalized = value.normalized()
        UpdateHintAppearance.write(context, normalized)
        onMain {
            appearance = normalized
            updateActiveAppearance()
        }
    }

    internal fun setPresentation(languageCode: String, darkTheme: Boolean) = onMain {
        this.languageCode = languageCode
        this.darkTheme = darkTheme
        updateActiveAppearance()
    }

    internal fun consumeRouteRequest(resultId: Long) {
        if (pendingRoute.value == resultId) pendingRoute.value = null
    }

    /** Parent integration: call beside AppUpdateManager.resetForShutdown(). */
    @JvmStatic fun shutdown() = onMain {
        pendingRoute.value = null
        applyDecision(policy.shutdown())
    }

    @JvmStatic
    fun onResultInvalidated(resultId: Long, reason: String) = onMain {
        if (pendingRoute.value == resultId) pendingRoute.value = null
        applyDecision(policy.invalidate(resultId, reason))
    }

    @JvmStatic
    fun onInstallStarted(resultId: Long) = onResultInvalidated(resultId, "install")

    override fun onActivityStarted(activity: Activity) {
        if (activity is MainActivity && visibleActivities.add(activity) && visibleActivities.size == 1) {
            val decision = policy.setOwnUiVisible(true)
            if (decision is UpdateHintPresentationDecision.Release) {
                AppUpdateManager.showRetainedOffer(decision.resultId)
            }
            applyDecision(decision)
        }
    }

    override fun onActivityStopped(activity: Activity) {
        if (activity is MainActivity && visibleActivities.remove(activity) && visibleActivities.isEmpty()) {
            applyDecision(policy.setOwnUiVisible(false))
        }
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        if (activity is MainActivity && visibleActivities.remove(activity) && visibleActivities.isEmpty()) {
            applyDecision(policy.setOwnUiVisible(false))
        }
    }

    private fun applyDecision(decision: UpdateHintPresentationDecision) {
        when (decision) {
            UpdateHintPresentationDecision.None -> Unit
            is UpdateHintPresentationDecision.Show -> show(decision.resultId, decision.available.info)
            is UpdateHintPresentationDecision.Release -> dismiss(decision.resultId, decision.reason)
        }
    }

    private fun show(resultId: Long, info: AppUpdateManager.UpdateInfo) {
        val app = application ?: return
        if (!Settings.canDrawOverlays(app)) {
            AppEventLogger.event(app, "update_hint skipped resultId=$resultId reason=overlay-permission-missing")
            policy.afterRelease(resultId)
            return
        }
        active?.let { dismiss(it.resultId, "replaced") }
        try {
            val display = app.getSystemService(DisplayManager::class.java)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY)
            if (display == null) {
                AppEventLogger.event(app, "update_hint skipped resultId=$resultId reason=display-missing")
                policy.afterRelease(resultId)
                return
            }
            val windowContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                app.createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            } else app.createDisplayContext(display)
            val windows = windowContext.getSystemService(WindowManager::class.java)
            val eventId = "hud-update-$resultId"
            lateinit var hint: ActiveHint
            val card = UpdateHintCardView(
                windowContext,
                onOpen = { openRetainedOffer(resultId) },
                onClose = { dismiss(resultId, "close") }
            ).apply { bind(info.version, languageCode, darkTheme, appearance) }
            val container = FrameLayout(windowContext).apply {
                clipChildren = false
                clipToPadding = false
                alpha = 1f
                addView(card)
            }
            val preferredWidth = UpdateHintCardView.preferredWidthPx(windowContext, appearance)
            val preferredHeight = measureCard(card, preferredWidth)
            card.layoutParams = FrameLayout.LayoutParams(preferredWidth, preferredHeight)
            hint = ActiveHint(resultId, eventId, info, windowContext, windows, container, card)
            active = hint
            UpdateHintCoordinator.request(
                app,
                UpdateHintRequest(eventId, SystemClock.elapsedRealtimeNanos(), appearance.sizePercent,
                    preferredWidth, preferredHeight)
            ) { placement -> onPlacement(hint, placement) }
        } catch (error: RuntimeException) {
            Log.w(TAG, "prepare failed: ${error.javaClass.simpleName}")
            AppEventLogger.event(app, "update_hint failed resultId=$resultId stage=prepare error=${error.javaClass.simpleName}")
            dismiss(resultId, "prepare-failed")
        }
    }

    private fun onPlacement(hint: ActiveHint, placement: UpdateHintPlacement) {
        if (active !== hint) return
        val params = hint.params
        if (params == null) attach(hint, placement) else animatePlacement(hint, placement)
    }

    private fun attach(hint: ActiveHint, placement: UpdateHintPlacement) {
        val params = WindowManager.LayoutParams(
            placement.widthPx, placement.heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, windowFlags(), PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = placement.xPx
            y = placement.yPx
            alpha = appearance.alpha
        }
        setCardScale(hint, placement)
        try {
            hint.windows.addView(hint.container, params)
            hint.container.translationX = -placement.widthPx.toFloat()
            hint.container.animate()
                .translationX(0f)
                .setDuration(MOVE_DURATION_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
            hint.params = params
            hint.placement = placement
            hint.expiresAtElapsedMs = SystemClock.elapsedRealtime() + DISPLAY_DURATION_MS
            UpdateHintCoordinator.markVisible(hint.eventId, hint.expiresAtElapsedMs)
            registerScreenOnReceiver()
            scheduleExpiry(hint)
            Log.i(TAG, "shown resultId=${hint.resultId}")
            application?.let { AppEventLogger.event(it, "update_hint shown resultId=${hint.resultId}") }
        } catch (error: RuntimeException) {
            Log.w(TAG, "attach failed: ${error.javaClass.simpleName}")
            application?.let { AppEventLogger.event(it, "update_hint failed resultId=${hint.resultId} stage=attach error=${error.javaClass.simpleName}") }
            dismiss(hint.resultId, "attach-failed")
        }
    }

    private fun animatePlacement(hint: ActiveHint, target: UpdateHintPlacement) {
        val start = hint.placement ?: target
        if (start == target) return
        hint.animator?.cancel()
        hint.animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MOVE_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                if (active !== hint) return@addUpdateListener
                val fraction = animation.animatedFraction
                val current = UpdateHintPlacement(
                    lerp(start.xPx, target.xPx, fraction), lerp(start.yPx, target.yPx, fraction),
                    lerp(start.widthPx, target.widthPx, fraction), lerp(start.heightPx, target.heightPx, fraction),
                    start.effectiveSizePercent + (target.effectiveSizePercent - start.effectiveSizePercent) * fraction
                )
                updateLayout(hint, current)
            }
            start()
        }
    }

    private fun updateLayout(hint: ActiveHint, placement: UpdateHintPlacement) {
        val params = hint.params ?: return
        params.x = placement.xPx
        params.y = placement.yPx
        params.width = placement.widthPx.coerceAtLeast(1)
        params.height = placement.heightPx.coerceAtLeast(1)
        setCardScale(hint, placement)
        hint.placement = placement
        try {
            hint.windows.updateViewLayout(hint.container, params)
        } catch (error: RuntimeException) {
            dismiss(hint.resultId, "layout-failed")
        }
    }

    private fun setCardScale(hint: ActiveHint, placement: UpdateHintPlacement) {
        val scale = (placement.effectiveSizePercent / appearance.sizePercent.coerceAtLeast(1)).coerceAtLeast(0.01f)
        hint.card.pivotX = 0f
        hint.card.pivotY = 0f
        hint.card.scaleX = scale
        hint.card.scaleY = scale
    }

    private fun updateActiveAppearance() {
        val hint = active ?: return
        hint.card.bind(hint.info.version, languageCode, darkTheme, appearance)
        hint.container.alpha = 1f
        val params = hint.params
        if (params != null) {
            params.flags = windowFlags()
            params.alpha = appearance.alpha
            try { hint.windows.updateViewLayout(hint.container, params) }
            catch (_: RuntimeException) { dismiss(hint.resultId, "appearance-failed"); return }
        }
        val width = UpdateHintCardView.preferredWidthPx(hint.windowContext, appearance)
        val height = measureCard(hint.card, width)
        hint.card.layoutParams = FrameLayout.LayoutParams(width, height)
        UpdateHintCoordinator.updateGeometry(hint.eventId, appearance.sizePercent, width, height)
    }

    private fun openRetainedOffer(resultId: Long) {
        val app = application ?: return
        if (!AppUpdateManager.showRetainedOffer(resultId)) {
            dismiss(resultId, "stale-tap")
            return
        }
        pendingRoute.value = resultId
        dismiss(resultId, "open")
        try {
            app.startActivity(Intent(app, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            ))
        } catch (error: RuntimeException) {
            pendingRoute.value = null
            Log.w(TAG, "open failed: ${error.javaClass.simpleName}")
        }
    }

    private fun scheduleExpiry(hint: ActiveHint) {
        handler.removeCallbacksAndMessages(hint)
        val remainingMs = (hint.expiresAtElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        handler.postAtTime({
            if (active === hint && SystemClock.elapsedRealtime() >= hint.expiresAtElapsedMs) {
                dismiss(hint.resultId, "timeout")
            } else if (active === hint) {
                scheduleExpiry(hint)
            }
        }, hint, SystemClock.uptimeMillis() + remainingMs)
    }

    private fun dismiss(resultId: Long, reason: String) {
        val hint = active ?: return
        if (hint.resultId != resultId) return
        active = null
        policy.afterRelease(resultId)
        handler.removeCallbacksAndMessages(hint)
        unregisterScreenOnReceiver()
        hint.animator?.cancel()
        hint.container.animate().cancel()
        if (hint.params != null) {
            try { hint.windows.removeViewImmediate(hint.container) }
            catch (error: RuntimeException) { Log.w(TAG, "remove failed: ${error.javaClass.simpleName}") }
        }
        UpdateHintCoordinator.release(hint.eventId, reason)
        Log.i(TAG, "released resultId=$resultId reason=$reason")
        application?.let { AppEventLogger.event(it, "update_hint released resultId=$resultId reason=$reason") }
    }

    private fun registerScreenOnReceiver() {
        val app = application ?: return
        if (screenOnReceiverRegistered) return
        try {
            app.registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
            screenOnReceiverRegistered = true
        } catch (error: RuntimeException) {
            Log.w(TAG, "screen-on receiver failed: ${error.javaClass.simpleName}")
        }
    }

    private fun unregisterScreenOnReceiver() {
        val app = application ?: return
        if (!screenOnReceiverRegistered) return
        try { app.unregisterReceiver(screenOnReceiver) }
        catch (_: IllegalArgumentException) { }
        screenOnReceiverRegistered = false
    }

    private fun windowFlags(): Int = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        (if (appearance.alpha == 0f) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)

    private fun measureCard(card: View, width: Int): Int {
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        return card.measuredHeight.coerceAtLeast(1)
    }

    private fun lerp(start: Int, end: Int, fraction: Float): Int =
        (start + (end - start) * fraction).roundToInt()

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    private const val TAG = "UpdateHintManager"
    private const val DISPLAY_DURATION_MS = 10_000L
    private const val MOVE_DURATION_MS = 220L
}
