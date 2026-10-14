package app.cutiepie.cutie_pie.core

import kotlin.math.abs

/** Only unconsumed pull distance at the first row can reveal the Home panel. */
class HomePullGesture(private val slop: Float, private val revealDistance: Float) {
    enum class Action { NONE, COLLAPSE, REVEAL }
    private var x = 0f
    private var y = 0f
    private var topY: Float? = null
    private var enabled = false
    private var revealed = false
    fun begin(x: Float, y: Float, atTop: Boolean) {
        this.x = x; this.y = y; topY = if (atTop) y else null
        enabled = true; revealed = false
    }
    fun cancel() { enabled = false; topY = null }
    fun move(x: Float, y: Float, atTop: Boolean, expanded: Boolean): Action {
        if (!enabled || revealed) return Action.NONE
        val dx = abs(x - this.x); val dy = y - this.y
        if (!atTop) topY = null else if (topY == null) topY = y
        if (abs(dy) <= slop || abs(dy) <= dx * 1.3f) return Action.NONE
        if (dy < 0 && expanded) return Action.COLLAPSE
        val pull = topY?.let { y - it } ?: 0f
        if (!expanded && atTop && pull > revealDistance && pull > dx * 1.3f) {
            revealed = true; return Action.REVEAL
        }
        return Action.NONE
    }
}
