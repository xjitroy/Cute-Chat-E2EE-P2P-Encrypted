package app.cutiepie.cutie_pie.core

import org.junit.Assert.*
import org.junit.Test
import app.cutiepie.cutie_pie.core.VoiceMessageGesture.Action.*

class VoiceMessageGestureTest {
    @Test fun holdReleaseSendsButShortTapWaitsForAnotherTap() {
        val hold = VoiceMessageGesture(80f)
        hold.press(200f, true); assertEquals(START_HOLD, hold.holdDeadline())
        assertTrue(hold.holding); assertEquals(SEND, hold.release())
        val tap = VoiceMessageGesture(80f)
        tap.press(200f, true); assertEquals(START_TAP, tap.release())
        tap.press(200f, true); assertEquals(NONE, tap.holdDeadline())
        assertEquals(SEND, tap.release()); assertEquals(NONE, tap.release())
    }
    @Test fun slideCancelDoesNotSendOrStartOnRelease() {
        for (held in listOf(false, true)) {
            val gesture = VoiceMessageGesture(80f)
            gesture.press(200f, true); if (held) gesture.holdDeadline()
            assertEquals(NONE, gesture.move(130f)); assertEquals(CANCEL, gesture.move(120f))
            assertEquals(NONE, gesture.holdDeadline()); assertEquals(NONE, gesture.release())
        }
    }
    @Test fun PermissionAndLifecycleCancellationNeverResumeCapture() {
        val gesture = VoiceMessageGesture(80f)
        gesture.press(200f, false)
        assertEquals(NONE, gesture.holdDeadline()); assertEquals(NONE, gesture.release())
        gesture.press(200f, true); gesture.holdDeadline(); gesture.cancel()
        assertEquals(NONE, gesture.release()); assertEquals(NONE, gesture.holdDeadline())
    }
    @Test fun accessibilityClickSupportsStartAndSendWithoutTouch() {
        val gesture = VoiceMessageGesture(80f)
        assertEquals(START_TAP, gesture.click()); assertEquals(SEND, gesture.click())
        gesture.press(100f, true); gesture.release(); gesture.press(100f, true)
        assertEquals(CANCEL, gesture.move(10f)); assertEquals(NONE, gesture.release())
    }
}
