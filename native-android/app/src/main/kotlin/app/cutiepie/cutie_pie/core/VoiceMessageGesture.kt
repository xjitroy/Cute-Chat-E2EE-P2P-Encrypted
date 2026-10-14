package app.cutiepie.cutie_pie.core

/** Permission/lifecycle cancellation must discard the gesture, never resume it. */
class VoiceMessageGesture(private val cancelDistance: Float) {
    enum class Action { NONE, START_HOLD, START_TAP, SEND, CANCEL }
    private enum class State { IDLE, PRESSED, HOLD, TAP_RECORDING, TAP_FINISH, CANCELLED }
    private var state = State.IDLE
    private var downX = 0f
    val holding get() = state == State.HOLD

    fun press(x: Float, allowed: Boolean) {
        downX = x
        state = if (!allowed) State.CANCELLED else if (state == State.TAP_RECORDING) State.TAP_FINISH else State.PRESSED
    }
    fun holdDeadline(): Action = if (state == State.PRESSED) {
        state = State.HOLD; Action.START_HOLD
    } else Action.NONE
    fun move(x: Float): Action = if (state in setOf(State.PRESSED, State.HOLD, State.TAP_FINISH) && downX - x >= cancelDistance) {
        state = State.CANCELLED; Action.CANCEL
    } else Action.NONE
    fun release(): Action = when (state) {
        State.PRESSED -> { state = State.TAP_RECORDING; Action.START_TAP }
        State.HOLD, State.TAP_FINISH -> { state = State.IDLE; Action.SEND }
        else -> { if (state == State.CANCELLED) state = State.IDLE; Action.NONE }
    }
    /** Android accessibility click has no physical down/up sequence. */
    fun click(): Action = if (state == State.TAP_RECORDING) {
        state = State.IDLE; Action.SEND
    } else { state = State.TAP_RECORDING; Action.START_TAP }
    fun cancel() { state = State.IDLE }
}
