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
import android.os.PowerManager
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

internal data class UpdateHintPresentationAttempt(
    val resultId: Long,
    val number: Int,
    val identity: Long
)

internal data class UpdateHintPresentationRetry(
    val attempt: UpdateHintPresentationAttempt,
    val delayMs: Long
)

/** Fences presentation retries independently from the cached update result. */
internal class UpdateHintRetryPolicy {
    private var identity = 0L
    private var current: UpdateHintPresentationAttempt? = null
    private var attachedIdentity = 0L

    fun begin(resultId: Long): UpdateHintPresentationAttempt =
        UpdateHintPresentationAttempt(resultId, 1, ++identity).also {
            current = it
            attachedIdentity = 0L
        }

    fun isCurrent(attempt: UpdateHintPresentationAttempt): Boolean = current == attempt

    fun isAttached(attempt: UpdateHintPresentationAttempt): Boolean =
        isCurrent(attempt) && attachedIdentity == attempt.identity

    fun attached(attempt: UpdateHintPresentationAttempt): Boolean {
        if (!isCurrent(attempt)) return false
        attachedIdentity = attempt.identity
        return true
    }

    fun technicalFailure(attempt: UpdateHintPresentationAttempt): UpdateHintPresentationRetry? {
        if (!isCurrent(attempt) || isAttached(attempt)) return null
        if (attempt.number >= MAX_ATTEMPTS) {
            current = null
            return null
        }
        val next = attempt.copy(number = attempt.number + 1, identity = ++identity)
        current = next
        return UpdateHintPresentationRetry(next, if (attempt.number == 1) FIRST_RETRY_MS else SECOND_RETRY_MS)
    }

    fun cancel(resultId: Long): Boolean {
        if (current?.resultId != resultId) return false
        current = null
        attachedIdentity = 0L
        return true
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val FIRST_RETRY_MS = 1_000L
        const val SECOND_RETRY_MS = 3_000L
    }
}

internal object UpdateHintScreenPolicy {
    fun canPresent(interactive: Boolean, defaultDisplayOn: Boolean): Boolean =
        interactive && defaultDisplayOn
}

/** Owns the app's one update-hint window; it never initiates an update check. */
object UpdateHintManager : Application.ActivityLifecycleCallbacks {
    private data class ActiveHint(
        val resultId: Long,
        val attempt: UpdateHintPresentationAttempt,
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
    private val retryPolicy = UpdateHintRetryPolicy()
    private val visibleActivities = Collections.newSetFromMap(IdentityHashMap<Activity, Boolean>())
    private val pendingRoute = MutableStateFlow<Long?>(null)
    val pendingRouteResultId: StateFlow<Long?> = pendingRoute.asStateFlow()
    private var application: Application? = null
    private var appearance = UpdateHintAppearance()
    private var languageCode = "en"
    private var darkTheme = true
    private var active: ActiveHint? = null
    private var lastLoggedResultId = 0L
    private var screenReceiverRegistered = false
    private var retryResultId = 0L
    private var retryAttempt: UpdateHintPresentationAttempt? = null
    private var retryRunnable: Runnable? = null
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                onScreenOff()
                return
            }
            if (intent.action != Intent.ACTION_SCREEN_ON) return
            val hint = active ?: return
            if (!screenReady(application ?: context.applicationContext)) {
                dismiss(hint.resultId, "screen-off", hint.attempt)
                return
            }
            if (hint.expiresAtElapsedMs > 0L && SystemClock.elapsedRealtime() >= hint.expiresAtElapsedMs) {
                dismiss(hint.resultId, "timeout-after-screen-on", hint.attempt)
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
            registerScreenReceiver()
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
        unregisterScreenReceiver()
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
    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) {
            active?.let { if (!Settings.canDrawOverlays(activity)) dismiss(it.resultId, "overlay-permission-lost", it.attempt) }
        }
    }
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
        registerScreenReceiver()
        cancelPendingRetry(reason = "new-result")
        active?.let { dismiss(it.resultId, "replaced", it.attempt) }
        val app = application ?: run { dismiss(resultId, "application-missing"); return }
        if (!isCurrentResult(resultId)) {
            dismiss(resultId, "stale-result")
            return
        }
        if (!screenReady(app)) {
            AppEventLogger.event(app, "update_hint skipped resultId=$resultId reason=screen-off")
            dismiss(resultId, "screen-off")
            return
        }
        if (!Settings.canDrawOverlays(app)) {
            AppEventLogger.event(app, "update_hint skipped resultId=$resultId reason=overlay-permission-missing")
            dismiss(resultId, "overlay-permission-missing")
            return
        }
        present(resultId, info, retryPolicy.begin(resultId))
    }

    private fun present(
        resultId: Long,
        info: AppUpdateManager.UpdateInfo,
        attempt: UpdateHintPresentationAttempt
    ) {
        if (!retryPolicy.isCurrent(attempt)) return
        val app = application ?: run { dismiss(resultId, "application-missing"); return }
        AppEventLogger.event(app, "update_hint attempt resultId=$resultId attempt=${attempt.number} stage=prepare")
        if (!isCurrentResult(resultId)) {
            dismiss(resultId, "stale-result")
            return
        }
        if (!screenReady(app)) {
            AppEventLogger.event(app, "update_hint skipped resultId=$resultId attempt=${attempt.number} reason=screen-off")
            dismiss(resultId, "screen-off")
            return
        }
        if (!Settings.canDrawOverlays(app)) {
            AppEventLogger.event(app, "update_hint skipped resultId=$resultId attempt=${attempt.number} reason=overlay-permission-missing")
            dismiss(resultId, "overlay-permission-missing")
            return
        }
        try {
            val display = app.getSystemService(DisplayManager::class.java)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY)
            if (display == null) {
                technicalFailure(resultId, info, attempt, null, "prepare", "display-missing")
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
                onOpen = { openRetainedOffer(resultId, attempt) },
                onClose = { dismiss(resultId, "close", attempt) }
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
            hint = ActiveHint(resultId, attempt, eventId, info, windowContext, windows, container, card)
            active = hint
            UpdateHintCoordinator.request(
                app,
                UpdateHintRequest(eventId, SystemClock.elapsedRealtimeNanos(), appearance.sizePercent,
                    preferredWidth, preferredHeight)
            ) { placement -> onPlacement(hint, placement) }
        } catch (error: RuntimeException) {
            Log.w(TAG, "prepare failed: ${error.javaClass.simpleName}")
            technicalFailure(resultId, info, attempt, active?.takeIf { it.attempt == attempt }, "prepare", error.javaClass.simpleName)
        }
    }

    private fun onPlacement(hint: ActiveHint, placement: UpdateHintPlacement) {
        if (active !== hint || !retryPolicy.isCurrent(hint.attempt)) return
        if (!isCurrentResult(hint.resultId)) { dismiss(hint.resultId, "stale-result", hint.attempt); return }
        if (!screenReady(application ?: return)) { dismiss(hint.resultId, "screen-off", hint.attempt); return }
        if (!Settings.canDrawOverlays(application ?: return)) { dismiss(hint.resultId, "overlay-permission-lost", hint.attempt); return }
        val params = hint.params
        if (params == null) attach(hint, placement) else animatePlacement(hint, placement)
    }

    private fun attach(hint: ActiveHint, placement: UpdateHintPlacement) {
        try {
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
            val app = application ?: run { dismiss(hint.resultId, "application-missing", hint.attempt); return }
            if (!screenReady(app)) { dismiss(hint.resultId, "screen-off", hint.attempt); return }
            if (!Settings.canDrawOverlays(app)) { dismiss(hint.resultId, "overlay-permission-lost", hint.attempt); return }
            hint.windows.addView(hint.container, params)
            hint.params = params
            if (!retryPolicy.attached(hint.attempt)) {
                cleanupAttempt(hint, hint.resultId, "stale-after-attach")
                return
            }
            hint.expiresAtElapsedMs = SystemClock.elapsedRealtime() + DISPLAY_DURATION_MS
            if (!screenReady(app)) { dismiss(hint.resultId, "screen-off", hint.attempt); return }
            if (!Settings.canDrawOverlays(app)) { dismiss(hint.resultId, "overlay-permission-lost", hint.attempt); return }
        } catch (error: RuntimeException) {
            Log.w(TAG, "attach failed: ${error.javaClass.simpleName}")
            technicalFailure(hint.resultId, hint.info, hint.attempt, hint, "attach", error.javaClass.simpleName)
            return
        }
        try {
            hint.container.translationX = -placement.widthPx.toFloat()
            hint.container.animate()
                .translationX(0f)
                .setDuration(MOVE_DURATION_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
            hint.placement = placement
            UpdateHintCoordinator.markVisible(hint.eventId, hint.expiresAtElapsedMs)
            scheduleExpiry(hint)
            Log.i(TAG, "shown resultId=${hint.resultId}")
            application?.let { AppEventLogger.event(it, "update_hint shown resultId=${hint.resultId}") }
        } catch (error: RuntimeException) {
            Log.w(TAG, "post-attach failed: ${error.javaClass.simpleName}")
            application?.let { AppEventLogger.event(it, "update_hint failed resultId=${hint.resultId} stage=post-attach error=${error.javaClass.simpleName}") }
            dismiss(hint.resultId, "post-attach-failed", hint.attempt)
        }
    }

    private fun animatePlacement(hint: ActiveHint, target: UpdateHintPlacement) {
        if (!screenReady(application ?: return)) { dismiss(hint.resultId, "screen-off", hint.attempt); return }
        val start = hint.placement ?: target
        if (start == target) return
        hint.animator?.cancel()
        hint.animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MOVE_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                if (active !== hint || !retryPolicy.isCurrent(hint.attempt)) return@addUpdateListener
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
        if (active !== hint || !retryPolicy.isCurrent(hint.attempt)) return
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
            dismiss(hint.resultId, "layout-failed", hint.attempt)
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
        if (!screenReady(application ?: return)) { dismiss(hint.resultId, "screen-off", hint.attempt); return }
        if (!Settings.canDrawOverlays(application ?: return)) {
            dismiss(hint.resultId, "overlay-permission-lost", hint.attempt)
            return
        }
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

    private fun openRetainedOffer(resultId: Long, attempt: UpdateHintPresentationAttempt) {
        if (!retryPolicy.isCurrent(attempt) || active?.attempt != attempt) return
        val app = application ?: return
        if (!AppUpdateManager.showRetainedOffer(resultId)) {
            dismiss(resultId, "stale-tap", attempt)
            return
        }
        pendingRoute.value = resultId
        dismiss(resultId, "open", attempt)
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
            if (active === hint && retryPolicy.isCurrent(hint.attempt) &&
                SystemClock.elapsedRealtime() >= hint.expiresAtElapsedMs) {
                dismiss(hint.resultId, "timeout", hint.attempt)
            } else if (active === hint && retryPolicy.isCurrent(hint.attempt)) {
                scheduleExpiry(hint)
            }
        }, hint, SystemClock.uptimeMillis() + remainingMs)
    }

    private fun dismiss(
        resultId: Long,
        reason: String,
        expectedAttempt: UpdateHintPresentationAttempt? = null
    ) {
        val hint = active?.takeIf { it.resultId == resultId }
        if (expectedAttempt != null && (hint?.attempt != expectedAttempt || !retryPolicy.isCurrent(expectedAttempt))) return
        val cancelledRetry = cancelPendingRetry(resultId, reason)
        val cancelledAttempt = retryPolicy.cancel(resultId)
        policy.afterRelease(resultId)
        if (hint == null) {
            UpdateHintCoordinator.release(eventId(resultId), reason)
            if (cancelledRetry || cancelledAttempt) {
                Log.i(TAG, "released resultId=$resultId reason=$reason")
                application?.let { AppEventLogger.event(it, "update_hint released resultId=$resultId reason=$reason") }
            }
            return
        }
        if (active === hint) active = null
        handler.removeCallbacksAndMessages(hint)
        cleanupWindow(hint)
        UpdateHintCoordinator.release(hint.eventId, reason)
        Log.i(TAG, "released resultId=$resultId reason=$reason")
        application?.let { AppEventLogger.event(it, "update_hint released resultId=$resultId reason=$reason") }
    }

    private fun cleanupAttempt(hint: ActiveHint?, resultId: Long, reason: String) {
        if (hint == null) {
            UpdateHintCoordinator.release(eventId(resultId), reason)
            return
        }
        if (active === hint) active = null
        handler.removeCallbacksAndMessages(hint)
        cleanupWindow(hint)
        UpdateHintCoordinator.release(hint.eventId, reason)
    }

    private fun cleanupWindow(hint: ActiveHint) {
        hint.animator?.cancel()
        hint.container.animate().cancel()
        if (hint.params != null || hint.container.parent != null) {
            try { hint.windows.removeViewImmediate(hint.container) }
            catch (error: RuntimeException) { Log.w(TAG, "remove failed: ${error.javaClass.simpleName}") }
        }
    }

    private fun technicalFailure(
        resultId: Long,
        info: AppUpdateManager.UpdateInfo,
        attempt: UpdateHintPresentationAttempt,
        hint: ActiveHint?,
        stage: String,
        error: String
    ) {
        if (!retryPolicy.isCurrent(attempt)) return
        val app = application ?: run { dismiss(resultId, "application-missing"); return }
        if (!screenReady(app)) {
            AppEventLogger.event(app, "update_hint failure-cancelled resultId=$resultId attempt=${attempt.number} reason=screen-off")
            dismiss(resultId, "screen-off")
            return
        }
        if (!Settings.canDrawOverlays(app)) {
            AppEventLogger.event(app, "update_hint failure-cancelled resultId=$resultId attempt=${attempt.number} reason=overlay-permission-lost")
            dismiss(resultId, "overlay-permission-lost")
            return
        }
        cleanupAttempt(hint, resultId, "technical-$stage")
        AppEventLogger.event(app, "update_hint failed resultId=$resultId attempt=${attempt.number} stage=$stage error=$error")
        val retry = retryPolicy.technicalFailure(attempt)
        if (retry == null) {
            policy.afterRelease(resultId)
            AppEventLogger.event(app, "update_hint exhausted resultId=$resultId attempt=${attempt.number} stage=$stage")
            return
        }
        val task = Runnable {
            if (retryResultId != resultId || retryAttempt != retry.attempt) return@Runnable
            retryRunnable = null
            retryAttempt = null
            retryResultId = 0L
            if (!retryPolicy.isCurrent(retry.attempt)) return@Runnable
            if (!isCurrentResult(resultId)) { dismiss(resultId, "stale-result"); return@Runnable }
            val app = application ?: run { dismiss(resultId, "application-missing"); return@Runnable }
            if (!screenReady(app)) {
                AppEventLogger.event(app, "update_hint retry-cancelled resultId=$resultId reason=screen-off")
                dismiss(resultId, "screen-off")
                return@Runnable
            }
            if (!Settings.canDrawOverlays(app)) {
                AppEventLogger.event(app, "update_hint retry-cancelled resultId=$resultId reason=overlay-permission-lost")
                dismiss(resultId, "overlay-permission-lost")
                return@Runnable
            }
            present(resultId, info, retry.attempt)
        }
        retryResultId = resultId
        retryAttempt = retry.attempt
        retryRunnable = task
        handler.postDelayed(task, retry.delayMs)
        AppEventLogger.event(app, "update_hint retry resultId=$resultId attempt=${retry.attempt.number} delayMs=${retry.delayMs} after=$stage")
    }

    private fun cancelPendingRetry(resultId: Long? = null, reason: String = "cancelled"): Boolean {
        if (retryRunnable == null || resultId != null && retryResultId != resultId) return false
        val pendingResultId = retryResultId
        val pendingAttempt = retryAttempt?.number ?: 0
        retryRunnable?.let(handler::removeCallbacks)
        retryRunnable = null
        retryAttempt = null
        retryResultId = 0L
        application?.let { AppEventLogger.event(it, "update_hint retry-cancelled resultId=$pendingResultId attempt=$pendingAttempt reason=$reason") }
        return true
    }

    private fun onScreenOff() {
        val resultId = active?.resultId ?: retryResultId.takeIf { it > 0L } ?: return
        AppEventLogger.event(application, "update_hint screen-off resultId=$resultId action=cancel")
        dismiss(resultId, "screen-off")
    }

    private fun screenReady(context: Context): Boolean {
        val interactive = context.getSystemService(PowerManager::class.java)?.isInteractive == true
        val displayOn = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.state == android.view.Display.STATE_ON
        return UpdateHintScreenPolicy.canPresent(interactive, displayOn)
    }

    private fun isCurrentResult(resultId: Long): Boolean {
        val snapshot = AppUpdateManager.snapshot.value
        return snapshot.resultId == resultId && snapshot.result is AppUpdateManager.CheckResult.Available
    }

    private fun eventId(resultId: Long) = "hud-update-$resultId"

    private fun registerScreenReceiver() {
        val app = application ?: return
        if (screenReceiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else app.registerReceiver(screenReceiver, filter)
            screenReceiverRegistered = true
        } catch (error: RuntimeException) {
            Log.w(TAG, "screen receiver failed: ${error.javaClass.simpleName}")
        }
    }

    private fun unregisterScreenReceiver() {
        val app = application ?: return
        if (!screenReceiverRegistered) return
        try { app.unregisterReceiver(screenReceiver) }
        catch (_: IllegalArgumentException) { }
        screenReceiverRegistered = false
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
