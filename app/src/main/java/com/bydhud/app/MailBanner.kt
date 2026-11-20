package com.bydhud.app

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import kotlin.math.roundToInt

internal object MailBanner {
    private var remove: (() -> Unit)? = null
    private var currentCard: View? = null
    private var completed: ((Boolean) -> Unit)? = null
    private var wasShown = false
    private var attachTimeout: Runnable? = null
    private val timer = Handler(Looper.getMainLooper())
    private val expire = Runnable { dismiss() }

    fun dismiss(animated: Boolean = true) {
        timer.removeCallbacks(expire)
        attachTimeout?.let(timer::removeCallbacks)
        attachTimeout=null
        val card = currentCard
        val cleanup = remove
        val done=completed
        val shown=wasShown
        completed=null; wasShown=false
        currentCard = null
        remove = null
        card?.animate()?.cancel()
        if (animated && card?.isAttachedToWindow == true) {
            card.animate().translationY(-card.bottom.toFloat()).setDuration(220)
                .withEndAction { cleanup?.invoke(); done?.invoke(shown) }.start()
        } else { cleanup?.invoke(); done?.invoke(shown) }
    }

    fun show(app: Context, anchor: View?, language: Language, dark: Boolean, messages: List<MailLetter>,
        allowed: () -> Boolean, shown: () -> Unit, finished: (Boolean) -> Unit) {
        if (messages.isEmpty() || !allowed()) { finished(false); return }
        dismiss(animated = false)
        val overlay = Settings.canDrawOverlays(app)
        AppEventLogger.event(app,"developer-mail stage=banner/attempt messages=${messages.joinToString(",") { it.id }} " +
            "overlay=$overlay anchorAttached=${anchor?.isAttachedToWindow} anchorShown=${anchor?.isShown} anchorFocus=${anchor?.hasWindowFocus()}")
        // Use the update hint's default-display window context for the tablet overlay.
        val context = try {
            if (overlay) {
                val display = app.getSystemService(DisplayManager::class.java)
                    .getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: run { finished(false); return }
                val displayContext = app.createDisplayContext(display)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                else displayContext
            } else anchor?.context ?: app
        } catch (e: RuntimeException) {
            failure(app, "prepare", e)
            finished(false)
            return
        }
        val p = if (dark) darkPalette() else lightPalette()
        val density = context.resources.displayMetrics.density
        fun dp(n: Int) = (n * density).roundToInt()
        fun text(value: String, size: Float, color: Int, lines: Int = 2) = TextView(context).apply {
            this.text = value
            textSize = size
            maxLines = lines
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(color)
        }
        val title = if (messages.size == 1) messages.single().subject else language.choose(
            "Нових листів: ${messages.size}", "New messages: ${messages.size}", "Новых писем: ${messages.size}")
        val body = if (messages.size == 1) messages.single().body else messages.joinToString(" · ") { it.subject }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(p.panel.toArgb())
                setStroke(dp(1), p.accent.toArgb())
            }
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_mail_closed)
                    setColorFilter(p.accent.toArgb())
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(9) })
                addView(text("BYD HUD · " + language.choose("Пошта", "Mail", "Почта"), 13f, p.muted.toArgb(), 1))
            })
            addView(text(title, 19f, p.text.toArgb()).apply { setTypeface(typeface, Typeface.BOLD) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
            addView(View(context).apply { setBackgroundColor(p.borderStrong.toArgb()) },
                LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(8); bottomMargin = dp(8) })
            addView(text(body, 16f, p.muted.toArgb()))
            setOnClickListener {
                // A tap during entry also proves that the user saw this card.
                if(currentCard===this && !wasShown && allowed()) { wasShown=true; shown() }
                dismiss()
            }
        }
        val root = FrameLayout(context).apply {
            setPadding(0, dp(12), 0, 0)
            addView(card, FrameLayout.LayoutParams(-1, -2))
        }
        val width = minOf(dp(440), context.resources.displayMetrics.widthPixels - dp(24))
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        card.translationY = -root.measuredHeight.toFloat()
        if (overlay) {
            try {
                val manager = context.getSystemService(WindowManager::class.java)
                val params = WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
                manager.addView(root, params)
                remove = {
                    try { manager.removeViewImmediate(root) }
                    catch (e: RuntimeException) { failure(app, "remove-overlay", e) }
                }
            } catch (e: RuntimeException) { failure(app, "attach-overlay", e) }
        }
        if (remove == null && anchor != null && anchor.isShown && anchor.hasWindowFocus()) {
            val popup = PopupWindow(root, width, WindowManager.LayoutParams.WRAP_CONTENT, false)
            try {
                popup.showAtLocation(anchor, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 0)
                remove = {
                    try { popup.dismiss() }
                    catch (e: RuntimeException) { failure(app, "remove-popup", e) }
                }
            } catch (e: RuntimeException) {
                failure(app, "attach-popup", e)
            }
        }
        if (remove != null) {
            currentCard = card
            completed=finished
            val check=Runnable {
                if(currentCard===card && !wasShown) {
                    AppEventLogger.event(app,"developer-mail stage=banner/attach-timeout attached=${root.isAttachedToWindow} shown=${root.isShown}")
                    dismiss(animated=false)
                }
            }
            attachTimeout=check
            timer.postDelayed(check,3_000)
            root.post {
                if (currentCard === card && root.isAttachedToWindow && root.isShown && allowed()) {
                    card.animate().translationY(0f).setDuration(220)
                        .setInterpolator(DecelerateInterpolator()).withEndAction {
                            if(currentCard===card && root.isAttachedToWindow && root.isShown && allowed()) {
                                wasShown=true
                                timer.removeCallbacks(check); attachTimeout=null
                                shown()
                                timer.postDelayed(expire,10_000)
                            } else if(currentCard===card) dismiss(animated=false)
                        }.start()
                } else if (currentCard === card) dismiss(animated = false)
            }
        } else finished(false)
    }

    private fun failure(context: Context, stage: String, error: RuntimeException) {
        AppEventLogger.event(context, "developer-mail banner stage=$stage " + Log.getStackTraceString(error))
    }
}
