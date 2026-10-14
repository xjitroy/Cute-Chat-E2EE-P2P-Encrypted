package app.cutiepie.cutie_pie

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.core.graphics.PathParser
import com.google.android.material.color.MaterialColors

/** Semantic Material 3 roles; Android 12+ receives the phone's dynamic palette. */
object MaterialYou {
    fun color(view: View, attribute: Int) = MaterialColors.getColor(view, attribute)
    fun shape(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
    fun tint(color: Int) = ColorStateList.valueOf(color)
    private val paths = mapOf(
        "resize" to "M12,2L8,6H11V11H6V8L2,12L6,16V13H11V18H8L12,22L16,18H13V13H18V16L22,12L18,8V11H13V6H16Z",
        "chevron" to "M9,6L7.59,7.41L12.17,12L7.59,16.59L9,18L15,12Z",
        "close" to "M19,6.41L17.59,5L12,10.59L6.41,5L5,6.41L10.59,12L5,17.59L6.41,19L12,13.41L17.59,19L19,17.59L13.41,12Z",
        "chat" to "M20,2H4C2.9,2 2,2.9 2,4V22L6,18H20C21.1,18 22,17.1 22,16V4C22,2.9 21.1,2 20,2M6,6H18V8H6ZM6,10H18V12H6ZM6,14H14V16H6Z",
        "plus" to "M11,5H13V11H19V13H13V19H11V13H5V11H11Z",
        "clock" to "M12,2A10,10 0,1 0,12,22A10,10 0,1 0,12,2M12,4A8,8 0,1 1,12,20A8,8 0,1 1,12,4M11,6V13L16,16L17,14.27L13,12V6Z",
        "account" to "M12,2A5,5 0,1 0,12,12A5,5 0,1 0,12,2M12,14C6,14 3,17 3,20V22H21V20C21,17 18,14 12,14Z",
        "info" to "M12,2A10,10 0,1 0,12,22A10,10 0,1 0,12,2M11,6H13V8H11ZM11,10H13V18H11Z",
        "palette" to "M12,2C6.48,2 2,6.48 2,12C2,17.52 6.48,22 12,22C13.66,22 15,20.66 15,19C15,18.22 14.7,17.51 14.21,16.98C14.04,16.8 13.94,16.56 13.94,16.3C13.94,15.75 14.39,15.3 14.94,15.3H16.7C19.63,15.3 22,12.93 22,10C22,5.58 17.52,2 12,2M6.5,12A1.5,1.5 0,1 1,6.5,9A1.5,1.5 0,1 1,6.5,12M9.5,8A1.5,1.5 0,1 1,9.5,5A1.5,1.5 0,1 1,9.5,8M14.5,8A1.5,1.5 0,1 1,14.5,5A1.5,1.5 0,1 1,14.5,8M17.5,12A1.5,1.5 0,1 1,17.5,9A1.5,1.5 0,1 1,17.5,12",
        "back" to "M20,11H7.83L13.42,5.41L12,4L4,12L12,20L13.42,18.59L7.83,13H20Z",
        "call" to "M6.62,10.79C8.06,13.62 10.38,15.94 13.21,17.38L15.41,15.18C15.68,14.91 16.08,14.82 16.43,14.94C17.55,15.31 18.76,15.51 20,15.51C20.55,15.51 21,15.96 21,16.51V20C21,20.55 20.55,21 20,21C10.61,21 3,13.39 3,4C3,3.45 3.45,3 4,3H7.5C8.05,3 8.5,3.45 8.5,4C8.5,5.25 8.7,6.45 9.07,7.57C9.18,7.92 9.1,8.31 8.82,8.59Z",
        "video" to "M17,10.5V7C17,5.9 16.1,5 15,5H3C1.9,5 1,5.9 1,7V17C1,18.1 1.9,19 3,19H15C16.1,19 17,18.1 17,17V13.5L23,19V5Z",
        "mic" to "M12,14C13.66,14 15,12.66 15,11V5C15,3.34 13.66,2 12,2C10.34,2 9,3.34 9,5V11C9,12.66 10.34,14 12,14M17,11C17,13.76 14.76,16 12,16C9.24,16 7,13.76 7,11H5C5,14.53 7.61,17.43 11,17.92V21H13V17.92C16.39,17.43 19,14.53 19,11Z",
        "camera" to "M9,2L7,5H3C1.9,5 1,5.9 1,7V19C1,20.1 1.9,21 3,21H21C22.1,21 23,20.1 23,19V7C23,5.9 22.1,5 21,5H17L15,2ZM12,8A5,5 0,1 1,12,18A5,5 0,1 1,12,8M12,10A3,3 0,1 0,12,16A3,3 0,1 0,12,10",
        "gallery" to "M21,19V5C21,3.9 20.1,3 19,3H5C3.9,3 3,3.9 3,5V19C3,20.1 3.9,21 5,21H19C20.1,21 21,20.1 21,19M8.5,6A1.5,1.5 0,1 1,8.5,9A1.5,1.5 0,1 1,8.5,6M5,18L9,13L12,16L15,12L19,18Z",
        "music" to "M12,3V13.55C11.41,13.21 10.73,13 10,13C7.79,13 6,14.79 6,17C6,19.21 7.79,21 10,21C12.21,21 14,19.21 14,17V7H18V3Z",
        "send" to "M2,21L23,12L2,3V10L17,12L2,14Z",
        "attach" to "M16.5,6V17.5C16.5,20 14.5,22 12,22C9.5,22 7.5,20 7.5,17.5V5C7.5,3.34 8.84,2 10.5,2C12.16,2 13.5,3.34 13.5,5V15.5C13.5,16.33 12.83,17 12,17C11.17,17 10.5,16.33 10.5,15.5V6H12V15.5V5C12,4.17 11.33,3.5 10.5,3.5C9.67,3.5 9,4.17 9,5V17.5C9,19.16 10.34,20.5 12,20.5C13.66,20.5 15,19.16 15,17.5V6Z",
        "shield" to "M12,2L3,6V12C3,17 6.84,21.74 12,23C17.16,21.74 21,17 21,12V6L12,2M10,16L6,12L7.41,10.59L10,13.17L16.59,6.58L18,8Z",
        "ping" to "M12,22C13.1,22 14,21.1 14,20H10C10,21.1 10.9,22 12,22M18,16V11C18,7.93 16.36,5.36 13.5,4.68V4C13.5,3.17 12.83,2.5 12,2.5C11.17,2.5 10.5,3.17 10.5,4V4.68C7.63,5.36 6,7.92 6,11V16L4,18V19H20V18Z",
        "qr" to "M3,3H10V10H3ZM5,5V8H8V5ZM14,3H21V10H14ZM16,5V8H19V5ZM3,14H10V21H3ZM5,16V19H8V16ZM14,14H17V17H14ZM18,18H21V21H18ZM18,14H21V16H18ZM14,18H16V21H14Z",
        "settings" to "M19.14,12.94C19.18,12.64 19.2,12.33 19.2,12C19.2,11.67 19.18,11.36 19.14,11.06L21.17,9.48L19.25,6.15L16.85,7.11C16.35,6.73 15.81,6.41 15.2,6.18L14.84,3.63H11L10.64,6.18C10.03,6.41 9.49,6.73 8.99,7.11L6.59,6.15L4.67,9.48L6.7,11.06C6.66,11.36 6.64,11.67 6.64,12C6.64,12.33 6.66,12.64 6.7,12.94L4.67,14.52L6.59,17.85L8.99,16.89C9.49,17.27 10.03,17.59 10.64,17.82L11,20.37H14.84L15.2,17.82C15.81,17.59 16.35,17.27 16.85,16.89L19.25,17.85L21.17,14.52ZM12.92,15.5C10.99,15.5 9.42,13.93 9.42,12C9.42,10.07 10.99,8.5 12.92,8.5C14.85,8.5 16.42,10.07 16.42,12C16.42,13.93 14.85,15.5 12.92,15.5Z",
        "end" to "M12,9C8.2,9 4.8,10.4 2.2,12.8L4.2,16.2C4.5,16.7 5.1,16.9 5.6,16.7L8,15.6V12.7C10.6,11.8 13.4,11.8 16,12.7V15.6L18.4,16.7C18.9,16.9 19.5,16.7 19.8,16.2L21.8,12.8C19.2,10.4 15.8,9 12,9Z",
        "speaker" to "M3,9V15H7L12,20V4L7,9ZM16,12C16,10.23 14.98,8.71 13.5,7.97V16.02C14.98,15.29 16,13.77 16,12M13.5,3.23V5.29C16.39,6.15 18.5,8.83 18.5,12C18.5,15.17 16.39,17.85 13.5,18.71V20.77C17.51,19.86 20.5,16.28 20.5,12Z",
        "light" to "M12,2C8.14,2 5,5.14 5,9C5,11.38 6.19,13.47 8,14.74V17C8,17.55 8.45,18 9,18H15C15.55,18 16,17.55 16,17V14.74C17.81,13.47 19,11.38 19,9C19,5.14 15.86,2 12,2M9,21C9,21.55 9.45,22 10,22H14C14.55,22 15,21.55 15,21V20H9Z",
        "swap" to "M6,3L2,7L6,11V8H18V6H6ZM18,13V16H6V18H18V21L22,17Z",
        "quality" to "M3,4H21V20H3ZM5,6V18H19V6ZM7,8H9V11H11V8H13V16H11V13H9V16H7ZM14,8H17C18.1,8 19,8.9 19,10V14C19,15.1 18.1,16 17,16H14ZM16,10V14H17V10Z",
        "wifi" to "M1,9L3,11C8,6 16,6 21,11L23,9C17,3 7,3 1,9M5,13L7,15C10,12 14,12 17,15L19,13C15,9 9,9 5,13M9,17L12,20L15,17C13,15 11,15 9,17Z",
        "bluetooth" to "M12,2V9L7,4L5.6,5.4L12,12L5.6,18.6L7,20L12,15V22L19,16L14.5,12L19,8ZM14,6.3L16,8L14,9.7ZM14,14.3L16,16L14,17.7Z",
        "share" to "M18,16C17.2,16 16.5,16.3 16,16.8L8.9,12.7C9,12.5 9,12.2 9,12C9,11.8 9,11.5 8.9,11.3L16,7.2C16.5,7.7 17.2,8 18,8A3,3 0,1 0,15,5C15,5.2 15,5.5 15.1,5.7L8,9.8C7.5,9.3 6.8,9 6,9A3,3 0,1 0,6,15C6.8,15 7.5,14.7 8,14.2L15.1,18.3C15,18.5 15,18.8 15,19A3,3 0,1 0,18,16Z",
        "download" to "M11,3H13V13L17,9L18.4,10.4L12,16.8L5.6,10.4L7,9L11,13ZM4,18H20V21H4Z",
        "folder" to "M2,6C2,4.9 2.9,4 4,4H9L11,6H20C21.1,6 22,6.9 22,8V18C22,19.1 21.1,20 20,20H4C2.9,20 2,19.1 2,18Z",
        "more" to "M12,4A2,2 0,1 1,12,8A2,2 0,1 1,12,4M12,10A2,2 0,1 1,12,14A2,2 0,1 1,12,10M12,16A2,2 0,1 1,12,20A2,2 0,1 1,12,16"
    )
    fun icon(context: Context, name: String, color: Int): Drawable = object : Drawable() {
        private val path = PathParser.createPathFromPathData(paths[name] ?: paths.getValue("shield"))!!
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        private var tint: ColorStateList? = null
        override fun draw(canvas: Canvas) { val save = canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat()); canvas.scale(bounds.width()/24f, bounds.height()/24f); canvas.drawPath(path, paint); canvas.restoreToCount(save) }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
        override fun setTintList(colors: ColorStateList?) { tint=colors; onStateChange(state); invalidateSelf() }
        override fun isStateful() = tint?.isStateful == true
        override fun onStateChange(state: IntArray): Boolean {
            val next=tint?.getColorForState(state,paint.color) ?: color
            if(next==paint.color) return false
            paint.color=next; invalidateSelf(); return true
        }
        @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth() = (24*context.resources.displayMetrics.density).toInt()
        override fun getIntrinsicHeight() = intrinsicWidth
    }
}
