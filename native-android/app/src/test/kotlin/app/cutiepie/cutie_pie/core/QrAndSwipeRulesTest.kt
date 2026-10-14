package app.cutiepie.cutie_pie.core

import org.junit.Assert.*
import org.junit.Test

class QrAndSwipeRulesTest {
    @Test fun portraitCameraDoesNotApplySensorRotationTwice() {
        for(sensor in listOf(90,270)) {
            val t=QrPreviewGeometry.transform(sensor,0,640,480,160,172)
            assertEquals(0f,t.rotation,0f)
            assertEquals(1f,t.scaleX,0.001f)
            assertTrue(t.scaleY>=1f)
        }
    }
    @Test fun allDisplayRotationsUseCenteredAspectPreservingCrop() {
        for(sensor in listOf(0,90,180,270)) for(display in 0..3) {
            val t=QrPreviewGeometry.transform(sensor,display,640,480,320,172)
            assertEquals(-90f*display,t.rotation,0f)
            val naturalWidth=if(sensor%180==0) 640 else 480
            val naturalHeight=if(sensor%180==0) 480 else 640
            assertEquals(t.scaleX*320/naturalWidth,t.scaleY*172/naturalHeight,0.0001f)
            val coveredWidth=if(display%2==0) t.scaleX*320 else t.scaleY*172
            val coveredHeight=if(display%2==0) t.scaleY*172 else t.scaleX*320
            assertTrue(coveredWidth>=319.9f);assertTrue(coveredHeight>=171.9f)
        }
    }
    @Test fun resizingAndHalfTurnsAreHandled() {
        val t=QrPreviewGeometry.transform(90,2,640,480,172,320)
        assertEquals(-180f,t.rotation,0f)
        assertNotEquals(QrPreviewGeometry.transform(90,0,640,480,320,172).scaleX,t.scaleX)
    }
    @Test fun invalidGeometryFailsInsteadOfDividingByZero() {
        for(args in listOf(listOf(90,0,640,480,0,172),listOf(45,0,640,480,160,172),listOf(90,5,640,480,160,172))) {
            try { QrPreviewGeometry.transform(args[0],args[1],args[2],args[3],args[4],args[5]);fail("Invalid preview") } catch(_:IllegalArgumentException) {}
        }
    }
    @Test fun swipesRespectDirectionRtlAndVirtualQrAccess() {
        assertEquals("calls",TabSwipeRules.next("home",-90f,0f,360f,72f,false))
        assertEquals("contacts",TabSwipeRules.next("home",90f,0f,360f,72f,false))
        assertEquals("contacts",TabSwipeRules.next("home",-90f,0f,360f,72f,true))
        assertEquals("contacts",TabSwipeRules.next("sharing",-90f,0f,360f,72f,false))
    }
    @Test fun shortDragsCancelButDeliberateFastFlickCanComplete() {
        assertNull(TabSwipeRules.next("home",-40f,100f,360f,72f,false))
        assertNull(TabSwipeRules.next("home",-10f,-1200f,360f,72f,false))
        assertNull(TabSwipeRules.next("home",-40f,1200f,360f,72f,false))
        assertEquals("calls",TabSwipeRules.next("home",-40f,-1200f,360f,72f,false))
    }
    @Test fun callsLocksUnknownPagesAndNonfiniteInputNeverNavigate() {
        for(page in listOf("call","lock","settings","bogus")) assertNull(TabSwipeRules.next(page,-90f,0f,360f,72f,false))
        assertNull(TabSwipeRules.next("home",Float.NaN,0f,360f,72f,false))
        assertNull(TabSwipeRules.next("home",-90f,Float.POSITIVE_INFINITY,360f,72f,false))
        assertNull(TabSwipeRules.next("home",-90f,0f,0f,72f,false))
    }
    @Test fun chatHeaderKeepsItsQrShortcut() { assertEquals("contacts",TabSwipeRules.next("chat",-90f,0f,360f,72f,false)) }
}
