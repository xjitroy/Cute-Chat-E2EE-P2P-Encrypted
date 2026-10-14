package app.cutiepie.cutie_pie

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton

/** Branded landing page only. Android owns all credential entry and verification. */
class PrivateUnlockView(context: Context, logo: View, title: String, detail: String,
    action: String, note: String, securityTitle:String, securityDetail:String, animate: Boolean, unlock: () -> Unit) : ScrollView(context) {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    init {
        tag = "private-unlock-page"; isFillViewport = true; isVerticalScrollBarEnabled = false
        val ink = MaterialYou.color(this,com.google.android.material.R.attr.colorOnSurface)
        val softInk = MaterialYou.color(this,com.google.android.material.R.attr.colorOnSurfaceVariant)
        val accent = MaterialYou.color(this,androidx.appcompat.R.attr.colorPrimary)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(8),dp(24),dp(8),dp(24))
        }
        body.addView(SecurityArrivalView(context,securityTitle,securityDetail,animate),LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(16) })
        body.addView(logo,LinearLayout.LayoutParams(dp(116),dp(116)).apply { bottomMargin = dp(28) })
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24),dp(28),dp(24),dp(28))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                if(CuteUi.dark(this)) intArrayOf(0xFF32243F.toInt(),0xFF1C3044.toInt())
                else intArrayOf(0xFFFFEEF5.toInt(),0xFFEAF0FF.toInt())).apply { cornerRadius = dp(32).toFloat() }
        }
        card.addView(ImageView(context).apply {
            setImageDrawable(MaterialYou.icon(context,"shield",accent))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },LinearLayout.LayoutParams(dp(40),dp(40)).apply { bottomMargin = dp(18) })
        fun copy(text: String,size: Float,color: Int) = TextView(context).apply {
            this.text = text; textSize = size; setTextColor(color); gravity = Gravity.CENTER
            includeFontPadding = false; setPadding(0,0,0,dp(16))
        }
        card.addView(copy(title,27f,ink).apply { setTypeface(typeface,Typeface.BOLD); androidx.core.view.ViewCompat.setAccessibilityHeading(this,true) })
        card.addView(copy(detail,14f,softInk))
        card.addView(MaterialButton(context).apply {
            text = action; tag = "private-unlock-button"; textSize = 15f
            cornerRadius = dp(28); minHeight = dp(56); insetTop = 0; insetBottom = 0
            icon = MaterialYou.icon(context,"lock",currentTextColor); iconTint = MaterialYou.tint(currentTextColor)
            setOnClickListener { unlock() }
        },LinearLayout.LayoutParams(-1,-2))
        body.addView(card,LinearLayout.LayoutParams(-1,-2))
        body.addView(copy(note,12f,softInk).apply { setPadding(dp(16),dp(22),dp(16),0) })
        addView(body)
        if(animate) {
            logo.alpha = 0f; logo.translationY = dp(12).toFloat()
            logo.animate().alpha(1f).translationY(0f).setDuration(320).setInterpolator(DecelerateInterpolator()).start()
        }
    }
}
