package app.cutiepie.cutie_pie

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View

/** Presentation-only tokens. Crypto/network ownership never depends on a visual style. */
object CuteUi {
    const val STYLE_MATERIAL = "material"
    const val STYLE_GLASS = "glass"
    const val ROSE = 0xFFFF4F9A.toInt()
    const val INDIGO = 0xFF5965FF.toInt()
    const val SECURE = 0xFF1A9B78.toInt()
    const val DANGER = 0xFFD6455D.toInt()

    fun dark(view: View) = view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    fun glassSurface(view: View) = if (dark(view)) 0xE6221C2A.toInt() else 0xE8FFFFFF.toInt()
    fun glassContainer(view: View) = if (dark(view)) 0xD92A2334.toInt() else 0xDDFBF8FD.toInt()
    fun glassInk(view: View) = if (dark(view)) 0xFFF4EAF5.toInt() else 0xFF201A22.toInt()
    fun glassSubtle(view: View) = if (dark(view)) 0xFFD4C6D4.toInt() else 0xFF625864.toInt()

    fun background(view: View, glass: Boolean): GradientDrawable = if (glass) {
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (dark(view)) intArrayOf(0xFF17111E.toInt(), 0xFF101A2C.toInt())
            else intArrayOf(0xFFFFF7FC.toInt(), 0xFFF0F4FF.toInt())
        )
    } else GradientDrawable().apply {
        setColor(MaterialYou.color(view, com.google.android.material.R.attr.colorSurface))
    }

    fun card(view: View, glass: Boolean, radius: Float): GradientDrawable = GradientDrawable().apply {
        setColor(if (glass) glassContainer(view) else MaterialYou.color(view, com.google.android.material.R.attr.colorSurfaceContainer))
        cornerRadius = radius
        if (glass) setStroke((view.resources.displayMetrics.density).toInt().coerceAtLeast(1), 0x33FFFFFF)
    }

    fun brandGradient(radius: Float): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ROSE, INDIGO)
    ).apply { cornerRadius = radius }
}
