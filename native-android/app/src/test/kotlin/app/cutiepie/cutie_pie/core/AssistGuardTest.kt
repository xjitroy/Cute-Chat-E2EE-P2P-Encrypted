package app.cutiepie.cutie_pie.core
import org.junit.Assert.*
import org.junit.Test
class AssistGuardTest {
    private fun ready()=AssistGuard().apply { grant("peer","call","epoch",1000,60000,30000);accept("peer","call","epoch",1000);publish("frame","window",1000) }
    @Test fun noInputBeforeExplicitAcceptance() { val g=AssistGuard();g.grant("p","c","e",0,60000,30000);g.publish("f","w",0);assertNull(g.authorize("p","c","e","f",1,10)) }
    @Test fun peerCallAndEpochMustMatch() { val g=ready();assertNull(g.authorize("other","call","epoch","frame",1,1010));assertNull(g.authorize("peer","other","epoch","frame",1,1010));assertNull(g.authorize("peer","call","old","frame",1,1010));assertNotNull(g.authorize("peer","call","epoch","frame",1,1010)) }
    @Test fun replayAndOutOfOrderDropped() { val g=ready();assertNotNull(g.authorize("peer","call","epoch","frame",2,1010));assertNull(g.authorize("peer","call","epoch","frame",2,1210));assertNull(g.authorize("peer","call","epoch","frame",1,1210)) }
    @Test fun networkDelayedFrameNeverExecutes() { assertNull(ready().authorize("peer","call","epoch","frame",1,2501)) }
    @Test fun windowChangeInvalidatesCommands() { val g=ready();g.publish("new","otherWindow",1100);assertNull(g.authorize("peer","call","epoch","frame",1,1200));assertNotNull(g.authorize("peer","call","epoch","new",1,1200)) }
    @Test fun rateLimitAndPause() { val g=ready();assertNotNull(g.authorize("peer","call","epoch","frame",1,1010));assertNull(g.authorize("peer","call","epoch","frame",2,1100));g.pause();assertNull(g.authorize("peer","call","epoch","frame",2,1300)) }
    @Test fun revocationInvalidatesEverything() { val g=ready();g.clear();assertNull(g.authorize("peer","call","epoch","frame",1,1010));assertFalse(g.live(1010)) }
    @Test fun heartbeatDoesNotExtendIdleButPerformedInputDoes() { val g=ready();g.publish("later","window",30900);assertFalse(g.live(31000));val h=ready();h.performed("epoch",30000);assertTrue(h.live(31000));h.performed("other",60000);assertFalse(h.live(60000)) }
    @Test fun continuousInputCannotExtendHardDeadline() { val g=ready();g.performed("epoch",30000);g.performed("epoch",59000);assertFalse(g.live(61000)) }
    @Test fun onlyOrdinaryPackages() { assertFalse(AssistSafety.packageAllowed("app.cutiepie.cutie_pie","Cute Chat",false));assertFalse(AssistSafety.packageAllowed("com.android.settings","Settings",true));assertFalse(AssistSafety.packageAllowed("com.example.bank","Bank",false));assertFalse(AssistSafety.packageAllowed("com.vendor.security","Security",true));assertTrue(AssistSafety.packageAllowed("com.example.gallery","Gallery",false));assertTrue(AssistSafety.packageAllowed("com.google.android.apps.photos","Photos",true)) }
    @Test fun letterboxAndMismatchedVideoCannotSendCoordinates() {
        assertNull(AssistSafety.normalize(1000,1000,2000,1000,2f,500f,100f))
        assertEquals(5000 to 5000,AssistSafety.normalize(1000,1000,2000,1000,2f,500f,500f))
        assertNull(AssistSafety.normalize(1000,1000,1000,2000,2f,500f,500f))
        assertNull(AssistSafety.normalize(1000,1000,1000,2000,Float.NaN,500f,500f))
    }
    @Test fun customCanvasAndTextInjectionTargetsAreNotTappable() {
        assertFalse(AssistSafety.clickableClass("android.view.SurfaceView"))
        assertFalse(AssistSafety.clickableClass("com.example.CustomCanvas"))
        assertFalse(AssistSafety.clickableClass("android.widget.EditText"))
        assertTrue(AssistSafety.clickableClass("android.widget.ImageButton"))
        assertTrue(AssistSafety.clickableClass("android.widget.FrameLayout"))
    }
}
