package app.cutiepie.cutie_pie

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Build
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import kotlin.math.abs

/** A real native gesture control; accessibility/keyboard click remains available. */
class SlideSharingAction(context:Context,title:String,hint:String,private val direction:Int,
    private val accent:Int,private val surface:Int,private val ink:Int,private val action:()->Unit):FrameLayout(context) {
    private fun dp(n:Float)=n*resources.displayMetrics.density
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var drag=0f;private var phase=0f;private var down=0f;private var tracking=false
    private var hintAnimator:ValueAnimator?=null
    private var settling:ValueAnimator?=null
    init {
        require(direction==1 || direction == -1)
        setWillNotDraw(false);minimumHeight=dp(140f).toInt();isClickable=true;isFocusable=true
        contentDescription="$title. $hint"
        background=MaterialYou.shape(surface,dp(28f))
        addView(TextView(context).apply { text=title;textSize=22f;setTextColor(ink);typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);maxLines=2 },LayoutParams(-1,-2).apply { leftMargin=dp(24f).toInt();rightMargin=dp(104f).toInt();topMargin=dp(30f).toInt() })
        addView(TextView(context).apply { text=hint;textSize=13f;setTextColor(ink);alpha=.7f;maxLines=2 },LayoutParams(-1,-2).apply { leftMargin=dp(24f).toInt();rightMargin=dp(104f).toInt();topMargin=dp(72f).toInt() })
        ViewCompat.setAccessibilityHeading(this,false)
        setOnClickListener { action() }
    }
    override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo) { super.onInitializeAccessibilityNodeInfo(info);info.className="android.widget.Button" }
    private fun hintMotion() {
        hintAnimator?.cancel();hintAnimator=null
        if(windowVisibility!=VISIBLE || !isShown || Build.VERSION.SDK_INT>=26 && !ValueAnimator.areAnimatorsEnabled()) return
        hintAnimator=ValueAnimator.ofFloat(0f,1f).apply { duration=1800;repeatCount=ValueAnimator.INFINITE;addUpdateListener { phase=it.animatedValue as Float;invalidate() };start() }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow();post { hintMotion() } }
    override fun onWindowVisibilityChanged(visibility:Int) { super.onWindowVisibilityChanged(visibility);if(visibility==VISIBLE) hintMotion() else { hintAnimator?.cancel();hintAnimator=null } }
    override fun onDetachedFromWindow() { hintAnimator?.cancel();hintAnimator=null;settling?.cancel();settling=null;super.onDetachedFromWindow() }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val cx=width-dp(57f);val cy=height/2f
        paint.style=Paint.Style.FILL;paint.color=accent;paint.alpha=28;canvas.drawRoundRect(cx-dp(30f),dp(12f),cx+dp(30f),height-dp(12f),dp(30f),dp(30f),paint)
        for(i in 0..1) {
            val y=cy+direction*(dp(30f+i*12f)+dp(5f)*phase)
            paint.color=accent;paint.alpha=(100+90*(1-phase)).toInt();paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(2f);paint.strokeCap=Paint.Cap.ROUND
            val path=Path().apply { moveTo(cx-dp(5f),y-direction*dp(4f));lineTo(cx,y);lineTo(cx+dp(5f),y-direction*dp(4f)) };canvas.drawPath(path,paint)
        }
        paint.style=Paint.Style.FILL;paint.color=accent;paint.alpha=255;canvas.drawCircle(cx,cy+drag,dp(23f),paint)
        paint.color=Color.WHITE;paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(2.5f);paint.strokeCap=Paint.Cap.ROUND
        val y=cy+drag;canvas.drawLine(cx,y-direction*dp(8f),cx,y+direction*dp(8f),paint)
        val path=Path().apply { moveTo(cx-dp(7f),y+direction*dp(1f));lineTo(cx,y+direction*dp(8f));lineTo(cx+dp(7f),y+direction*dp(1f)) };canvas.drawPath(path,paint)
    }
    private fun reset() {
        settling?.cancel();settling=ValueAnimator.ofFloat(drag,0f).apply { duration=220;interpolator=android.view.animation.DecelerateInterpolator();addUpdateListener { drag=it.animatedValue as Float;invalidate() };start() }
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if(event.x<width-dp(96f)) return super.onTouchEvent(event)
                tracking=true;down=event.y;settling?.cancel();parent?.requestDisallowInterceptTouchEvent(true);return true
            }
            MotionEvent.ACTION_MOVE -> if(tracking) { drag=(event.y-down).coerceIn(-dp(46f),dp(46f));if(drag*direction<0) drag*=.15f;invalidate();return true }
            MotionEvent.ACTION_UP -> if(tracking) {
                tracking=false;parent?.requestDisallowInterceptTouchEvent(false)
                val complete=drag*direction>=dp(32f);val tap=abs(event.y-down)<ViewConfiguration.get(context).scaledTouchSlop
                reset();if(complete || tap) { performHapticFeedback(if(Build.VERSION.SDK_INT>=30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY);performClick() };return true
            }
            MotionEvent.ACTION_CANCEL -> if(tracking) { tracking=false;parent?.requestDisallowInterceptTouchEvent(false);reset();return true }
        }
        return super.onTouchEvent(event)
    }
}
