package app.cutiepie.cutie_pie.core
import org.junit.Assert.*
import org.junit.Test
class CallProfileRegressionTest {
    @Test fun temporaryFocusDenialRetriesWithoutGrantingCaptureAndEventuallyStops() {
        val r=CallAudioFocusRetry();r.begin("a",100);assertEquals(0L,r.delay("a",100));assertEquals(100L,r.denied("a",100));assertEquals(50L,r.delay("a",150))
        assertEquals(200L,r.denied("a",200));assertEquals(400L,r.denied("a",400));assertEquals(800L,r.denied("a",800));assertEquals(800L,r.denied("a",1600));assertEquals(-1L,r.denied("a",2400));assertEquals(-1L,r.delay("a",2600))
    }
    @Test fun oldFocusAttemptCannotAffectTheNextRapidCall() {
        val r=CallAudioFocusRetry();r.begin("old",1);r.denied("old",1);r.clear("old");r.begin("new",10);r.clear("old")
        assertEquals(-1L,r.denied("old",20));assertEquals(0L,r.delay("new",20));assertEquals(100L,r.denied("new",20))
    }
    @Test fun clockRollbackCannotExtendAudioAcquisition() { val r=CallAudioFocusRetry();r.begin("a",100);assertEquals(-1L,r.delay("a",99));assertEquals(-1L,r.denied("a",99)) }
    @Test fun profileDecodeStartsWithNonzeroSampleAndBoundsLargePhotos() {
        for((w,h) in listOf(1 to 1,512 to 512,4000 to 3000,20000 to 19999)) { val s=ProfilePhotoRules.sample(w,h);assertTrue(s>=1);assertTrue(w/s<=512 && h/s<=512);assertEquals(0,s and (s-1)) }
        assertEquals(1,ProfilePhotoRules.sample(512,512));assertEquals(8,ProfilePhotoRules.sample(4000,3000))
    }
    @Test fun corruptPhotoDimensionsCannotAllocatePreview() { for(n in listOf(-1,0,20001,Int.MAX_VALUE)) assertThrows(IllegalArgumentException::class.java) { ProfilePhotoRules.sample(n,300) } }
}
