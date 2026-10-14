package app.cutiepie.cutie_pie

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Lightweight native graphics; the app remains usable throughout the brief animation. */
class SecurityArrivalView(context:Context,title:String,detail:String,animate:Boolean):LinearLayout(context) {
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    init {
        orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(12),dp(16),dp(12));isClickable=false
        val accent=MaterialYou.color(this,androidx.appcompat.R.attr.colorPrimary)
        val ink=MaterialYou.color(this,com.google.android.material.R.attr.colorOnSurface)
        background=MaterialYou.shape(MaterialYou.color(this,com.google.android.material.R.attr.colorSurfaceContainer),dp(24).toFloat())
        val lock=ImageView(context).apply { setImageDrawable(MaterialYou.icon(context,"shield",accent));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        addView(lock,LayoutParams(dp(32),dp(32)))
        addView(LinearLayout(context).apply {
            orientation=VERTICAL
            addView(TextView(context).apply { text=title;textSize=16f;setTextColor(ink);setTypeface(typeface,android.graphics.Typeface.BOLD);includeFontPadding=false })
            addView(TextView(context).apply { text=detail;textSize=12f;setTextColor(MaterialYou.color(this,com.google.android.material.R.attr.colorOnSurfaceVariant));includeFontPadding=false;setPadding(0,dp(5),0,0) })
        },LayoutParams(0,-2,1f).apply { leftMargin=dp(12) })
        if(animate) { alpha=.3f;scaleX=.97f;scaleY=.97f;animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240).start() }
    }
    override fun onDetachedFromWindow() { animate().cancel();super.onDetachedFromWindow() }
}
