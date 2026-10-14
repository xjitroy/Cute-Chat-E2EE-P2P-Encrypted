package app.cutiepie.cutie_pie

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton

/** A local presentation panel, never a connection-status or security validator. */
class HomeSecurityHeader(
    context: Context,
    logo: View,
    settings: View,
    headline: String,
    detail: String,
    disclosure: String,
    closeLabel: String,
    expandLabel: String,
    collapseLabel: String,
    initiallyExpanded: Boolean,
    private val animations: () -> Boolean,
    private val changed: (Boolean) -> Unit
) : LinearLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(72) }
    private val title = TextView(context).apply {
        text = "Cute Chat"; textSize = 26f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(MaterialYou.color(this, com.google.android.material.R.attr.colorOnSurface))
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false; tag = "home-brand-title"
    }
    private val cue = TextView(context).apply {
        text = "⌄"; textSize = 11f; includeFontPadding = false
        setTextColor(MaterialYou.color(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
    }
    private val panel = ScrollView(context).apply { tag = "home-security-panel"; isFillViewport = false; isVerticalScrollBarEnabled = false }
    private var animator: ValueAnimator? = null
    private var expanded = initiallyExpanded
    val isExpanded: Boolean get() = expanded
    private var startX = 0f
    private var startY = 0f
    private var eligible = false
    private var consuming = false
    private val expandDescription = expandLabel
    private val collapseDescription = collapseLabel
    private val titleGroup = LinearLayout(context).apply {
        orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(72); setPadding(dp(10), dp(4), dp(8), dp(4))
        isClickable = true; isFocusable = true; tag = "home-security-toggle"
        addView(title); addView(cue); setOnClickListener { setExpanded(!expanded) }
    }

    init {
        orientation = VERTICAL; tag = "home-security-header"
        heading.addView(logo, LayoutParams(dp(44), dp(44)))
        heading.addView(titleGroup, LayoutParams(0, -2, 1f)); heading.addView(settings, LayoutParams(dp(48), dp(48)))
        addView(heading)
        val dark = CuteUi.dark(this)
        val ink = if (dark) 0xFFF8F1FF.toInt() else 0xFF24172F.toInt()
        val content = LinearLayout(context).apply {
            orientation = VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(14))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                if (dark) intArrayOf(0xFF382042.toInt(), 0xFF1C3353.toInt()) else intArrayOf(0xFFFFE5F0.toInt(), 0xFFE4E8FF.toInt()))
                .apply { cornerRadius = dp(24).toFloat() }
        }
        val badge = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        badge.addView(TextView(context).apply {
            text = "P2P · E2EE"; textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(ink)
            setCompoundDrawablesRelativeWithIntrinsicBounds(MaterialYou.icon(context, "shield", ink), null, null, null)
            compoundDrawablePadding = dp(8)
        }, LayoutParams(0, -2, 1f))
        badge.addView(MaterialButton(context).apply {
            text = ""; contentDescription = closeLabel; tag = "home-security-close"
            icon = MaterialYou.icon(context, "close", ink); iconTint = MaterialYou.tint(ink); iconPadding = 0; iconSize = dp(22)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START; insetTop = 0; insetBottom = 0
            minWidth = 0; minimumWidth = 0; setPadding(dp(12), 0, dp(12), 0)
            backgroundTintList = MaterialYou.tint(Color.TRANSPARENT)
            setOnClickListener { setExpanded(false) }
        }, LayoutParams(dp(48), dp(48)))
        content.addView(badge)
        fun copy(text: String, size: Float, bold: Boolean = false) = TextView(context).apply {
            this.text = text; textSize = size; setTextColor(ink); includeFontPadding = false
            setPadding(0, 0, 0, dp(9)); if (bold) setTypeface(typeface, Typeface.BOLD)
        }
        content.addView(copy(headline, 21f, true))
        content.addView(copy(detail, 13f)); content.addView(copy(disclosure, 11f))
        panel.addView(content); addView(panel, LayoutParams(-1, if (expanded) -2 else 0).apply { bottomMargin = dp(8) })
        applyState()
        // Cap only the panel, not text: large type/small screens can scroll it.
        post { if (isAttachedToWindow && expanded) setExpanded(true, false) }
    }
    private fun applyState() {
        panel.visibility = if (expanded) VISIBLE else GONE
        cue.text = if (expanded) "P2P · E2EE  ⌃" else "⌄"
        titleGroup.contentDescription = if (expanded) collapseDescription else expandDescription
        title.textSize = if (expanded) 34f else 26f
    }
    fun setExpanded(show: Boolean, animate: Boolean = true) {
        animator?.cancel(); animator = null
        expanded = show; changed(show)
        panel.measure(MeasureSpec.makeMeasureSpec(width.coerceAtLeast(dp(240)), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val maximum = minOf(dp(240), (resources.displayMetrics.heightPixels * .34f).toInt())
        val target = if (show) panel.measuredHeight.coerceAtMost(maximum) else 0
        val from = if (panel.visibility == GONE) 0 else panel.height
        val fromTitle = title.textSize / resources.displayMetrics.scaledDensity
        val toTitle = if (show) 34f else 26f
        cue.text = if (show) "P2P · E2EE  ⌃" else "⌄"
        titleGroup.contentDescription = if (show) collapseDescription else expandDescription
        panel.visibility = VISIBLE
        if (!animate || !animations() || from == target) {
            panel.layoutParams.height = target; panel.requestLayout(); panel.visibility = if (show) VISIBLE else GONE
            panel.alpha = 1f; title.textSize = toTitle; return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220; interpolator = DecelerateInterpolator()
            addUpdateListener { value ->
                val fraction = value.animatedValue as Float
                panel.layoutParams.height = (from + (target - from) * fraction).toInt(); panel.requestLayout()
                panel.alpha = if (show) .65f + .35f * fraction else 1f - .35f * fraction
                title.textSize = fromTitle + (toTitle - fromTitle) * fraction
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (animator === animation) { panel.visibility = if (expanded) VISIBLE else GONE; panel.alpha = 1f; animator = null }
                }
            }); start()
        }
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            startX = event.x; startY = event.y; consuming = false
            // Swipe the app heading or card handle; body text keeps native scroll.
            eligible = event.y <= heading.bottom + if (expanded) dp(56) else 0
        } else if (event.actionMasked == MotionEvent.ACTION_MOVE && eligible && !consuming) {
            val dy = event.y - startY
            if (kotlin.math.abs(dy) >= dp(48) && kotlin.math.abs(dy) > kotlin.math.abs(event.x - startX) * 1.3f && ((dy > 0 && !expanded) || (dy < 0 && expanded))) {
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(cancel); cancel.recycle()
                consuming = true; setExpanded(dy > 0)
            }
        }
        if (consuming) { if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) { consuming = false; eligible = false }; return true }
        return super.dispatchTouchEvent(event)
    }
    override fun onDetachedFromWindow() { animator?.cancel(); animator = null; super.onDetachedFromWindow() }
}
