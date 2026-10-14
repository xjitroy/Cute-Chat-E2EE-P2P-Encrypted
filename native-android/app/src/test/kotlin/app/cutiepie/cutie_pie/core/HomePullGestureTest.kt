package app.cutiepie.cutie_pie.core

import org.junit.Assert.assertEquals
import org.junit.Test
import app.cutiepie.cutie_pie.core.HomePullGesture.Action.*

class HomePullGestureTest {
    private fun gesture() = HomePullGesture(8f,64f)
    @Test fun scrollingLaterChatsCollapsesEvenWhenFingerStartsOnARow() {
        val g=gesture(); g.begin(100f,180f,true)
        assertEquals(COLLAPSE,g.move(101f,160f,true,true))
        assertEquals(NONE,g.move(101f,130f,false,false))
    }
    @Test fun scrollingBackThroughEarlierChatsDoesNotRevealBeforeTop() {
        val g=gesture(); g.begin(100f,80f,false)
        assertEquals(NONE,g.move(100f,190f,false,false))
        assertEquals(NONE,g.move(100f,250f,true,false))
        assertEquals(NONE,g.move(100f,310f,true,false))
        assertEquals(REVEAL,g.move(100f,320f,true,false))
        assertEquals(NONE,g.move(100f,400f,true,false))
    }
    @Test fun aNewPullAtTheFirstRowRevealsAndSmallTapMovementDoesNot() {
        val g=gesture(); g.begin(100f,100f,true)
        assertEquals(NONE,g.move(101f,104f,true,false))
        assertEquals(REVEAL,g.move(103f,170f,true,false))
    }
    @Test fun horizontalGesturesAndCancelledMultiTouchDoNotReveal() {
        val g=gesture(); g.begin(100f,100f,true)
        assertEquals(NONE,g.move(220f,180f,true,false))
        g.cancel(); assertEquals(NONE,g.move(100f,240f,true,false))
        g.begin(100f,100f,true); assertEquals(REVEAL,g.move(100f,170f,true,false))
    }
    @Test fun leavingTopResetsUnusedPullDistance() {
        val g=gesture(); g.begin(100f,100f,true)
        assertEquals(NONE,g.move(100f,145f,true,false))
        assertEquals(NONE,g.move(100f,180f,false,false))
        assertEquals(NONE,g.move(100f,220f,true,false))
        assertEquals(NONE,g.move(100f,250f,true,false))
        assertEquals(REVEAL,g.move(100f,290f,true,false))
    }
}
