package app.cutiepie.cutie_pie

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/** Small two-axis screen-light control; native buttons provide an accessible alternative. */
class LightResizePad(context: Context,widthPercent: Int,topPercent: Int,
    private val resized: (Int,Int) -> Unit) : View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var side=widthPercent.coerceIn(5,15)
    private var top=topPercent.coerceIn(4,15)
    private val density=resources.displayMetrics.density
    init { minimumHeight=(160*density).toInt(); contentDescription="Screen light size: drag horizontally for sides, vertically for top" }
    fun size(side: Int,top: Int) { this.side=side.coerceIn(5,15);this.top=top.coerceIn(4,15);invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padding=24*density; val cx=width/2f;val cy=height/2f
        paint.color=MaterialYou.color(this,com.google.android.material.R.attr.colorPrimaryContainer)
        canvas.drawRoundRect(padding,padding,width-padding,height-padding,28*density,28*density,paint)
        paint.color=MaterialYou.color(this,com.google.android.material.R.attr.colorOnPrimaryContainer);paint.alpha=60;paint.strokeWidth=2*density
        canvas.drawLine(padding+16*density,cy,width-padding-16*density,cy,paint)
        canvas.drawLine(cx,padding+16*density,cx,height-padding-16*density,paint)
        paint.alpha=255
        val x=padding+(width-2*padding)*(side-5)/10f
        val y=padding+(height-2*padding)*(15-top)/11f
        canvas.drawCircle(x,y,16*density,paint)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val p=24*density
                side=(5+10*((event.x-p)/(width-2*p).coerceAtLeast(1f)).coerceIn(0f,1f)).toInt()
                top=(15-11*((event.y-p)/(height-2*p).coerceAtLeast(1f)).coerceIn(0f,1f)).toInt()
                resized(side,top);invalidate()
                if(event.actionMasked == MotionEvent.ACTION_UP) { parent?.requestDisallowInterceptTouchEvent(false);performClick() }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { parent?.requestDisallowInterceptTouchEvent(false);return true }
        }
        return super.onTouchEvent(event)
    }
    override fun performClick(): Boolean { super.performClick();return true }
}
