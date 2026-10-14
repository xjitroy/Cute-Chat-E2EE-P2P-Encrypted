package app.cutiepie.cutie_pie

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.view.View

/** Small, real-state progress map; no fake percentage or delayed transfer activation. */
class SharingStepsView(context: Context, private val labels: List<String>, private val accent: Int,
    private val ink: Int) : View(context) {
    private val paint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var stage = 0
    private var position = 0f
    private var animator: ValueAnimator? = null
    init { require(labels.size == 4); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
    fun setStage(next: Int) {
        val value = next.coerceIn(0,3)
        contentDescription = labels.joinToString(" → ") + ". " + labels[value]
        if (value == stage) return
        stage = value; animator?.cancel()
        if (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) { position=value.toFloat();invalidate();return }
        animator=ValueAnimator.ofFloat(position,value.toFloat()).apply {
            duration=220;interpolator=android.view.animation.PathInterpolator(.2f,0f,0f,1f)
            addUpdateListener { position=it.animatedValue as Float;invalidate() };start()
        }
    }
    override fun onDraw(canvas: Canvas) {
        val density=resources.displayMetrics.density
        val y=27*density;val start=width/8f;val step=width/4f
        paint.style=Paint.Style.STROKE;paint.strokeWidth=3*density;paint.strokeCap=Paint.Cap.ROUND
        paint.color=ink;paint.alpha=28;canvas.drawLine(start,y,start+3*step,y,paint)
        paint.color=accent;paint.alpha=255;canvas.drawLine(start,y,start+position*step,y,paint)
        for(i in labels.indices) {
            val x=start+i*step
            paint.style=Paint.Style.FILL;paint.color=if(i<=stage) accent else ink;paint.alpha=if(i<=stage) 255 else 35
            canvas.drawCircle(x,y,13*density,paint)
            paint.color=android.graphics.Color.WHITE;paint.alpha=255;paint.textAlign=Paint.Align.CENTER
            paint.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);paint.textSize=12*resources.displayMetrics.scaledDensity
            canvas.drawText(if(i<stage) "✓" else (i+1).toString(),x,y+4*density,paint)
            paint.color=ink;paint.alpha=if(i==stage) 255 else 145;paint.textSize=11*resources.displayMetrics.scaledDensity
            val text=android.text.TextUtils.ellipsize(labels[i],paint,(step-8*density).coerceAtLeast(1f),android.text.TextUtils.TruncateAt.END).toString()
            canvas.drawText(text,x,y+32*density,paint)
        }
    }
    override fun onDetachedFromWindow() { animator?.cancel();animator=null;super.onDetachedFromWindow() }
}
