package app.cutiepie.cutie_pie

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ScrollView
import app.cutiepie.cutie_pie.core.HomePullGesture

/** Observes descendant gestures too: a pull may start on a clickable chat row. */
class HomeChatScrollView(context: Context, private val header: HomeSecurityHeader?) : ScrollView(context) {
    private val gesture = HomePullGesture(ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
        64 * resources.displayMetrics.density)
    private var claimingPull = false
    init { isFillViewport = true; tag = "home-chat-scroll" }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { claimingPull = false; gesture.begin(event.x,event.y,scrollY == 0) }
            MotionEvent.ACTION_POINTER_DOWN -> gesture.cancel()
            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1) {
                when (gesture.move(event.x,event.y,scrollY == 0,header?.isExpanded == true)) {
                    HomePullGesture.Action.COLLAPSE -> header?.setExpanded(false)
                    HomePullGesture.Action.REVEAL -> {
                        if (header != null) {
                            claimingPull = true; header.setExpanded(true)
                            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        }
                    }
                    else -> Unit
                }
            }
        }
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            gesture.cancel(); claimingPull = false
        }
        return handled
    }
    // ScrollView sends CANCEL to a pressed child when interception starts. A
    // reveal must not accidentally open the row when the finger is released.
    override fun onInterceptTouchEvent(event: MotionEvent) = claimingPull || super.onInterceptTouchEvent(event)
    override fun onTouchEvent(event: MotionEvent) = if (claimingPull) true else super.onTouchEvent(event)
    override fun onScrollChanged(l: Int,t: Int,oldl: Int,oldt: Int) {
        super.onScrollChanged(l,t,oldl,oldt)
        if (t > oldt && header?.isExpanded == true) header.setExpanded(false)
    }
}
