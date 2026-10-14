package app.cutiepie.cutie_pie.core

import org.junit.Assert.*
import org.junit.Test

class VideoDiagnosticsTest {
    private fun stats(t:Double,bytes:Long=1000,id:String="v",frames:Long=10):List<VideoDiagnostics.Stat> = listOf(
        VideoDiagnostics.Stat(id,"outbound-rtp",t,mapOf("kind" to "video","frameWidth" to 1920,"frameHeight" to 1080,"bytesSent" to bytes,"framesEncoded" to frames,"codecId" to "c","transportId" to "tr","remoteId" to "rr","qualityLimitationReason" to "none")),
        VideoDiagnostics.Stat("in","inbound-rtp",t,mapOf("kind" to "video","frameWidth" to 1280,"frameHeight" to 720,"bytesReceived" to bytes*2,"framesPerSecond" to 30.0,"codecId" to "c","transportId" to "tr")),
        VideoDiagnostics.Stat("c","codec",t,mapOf("mimeType" to "video/AV1")),
        VideoDiagnostics.Stat("tr","transport",t,mapOf("selectedCandidatePairId" to "selected")),
        VideoDiagnostics.Stat("wrong","candidate-pair",t,mapOf("state" to "succeeded","currentRoundTripTime" to 99.0)),
        VideoDiagnostics.Stat("selected","candidate-pair",t,mapOf("state" to "succeeded","currentRoundTripTime" to 0.1,"availableOutgoingBitrate" to 4_000_000)),
        VideoDiagnostics.Stat("rr","remote-inbound-rtp",t,mapOf("fractionLost" to 0.02)))
    @Test fun selectedTransportRatherThanFirstSucceededCandidateDrivesQuality() {
        val s=VideoDiagnostics().read(1_000_000.0,stats(1_000_000.0))!!
        assertEquals(0.1,s.rtt!!,0.0001);assertEquals(4_000_000.0,s.bandwidth!!,0.1)
        assertEquals(0.02,s.loss!!,0.0001);assertEquals("AV1",s.sent!!.codec)
        assertEquals(1920,s.sent.width);assertEquals(1280,s.received!!.width)
    }
    @Test fun realByteAndFrameDeltasMeasureBothStreamsWithoutInventingFirstSampleBitrate() {
        val d=VideoDiagnostics();assertNull(d.read(1_000_000.0,stats(1_000_000.0))!!.sent!!.bitrate)
        val s=d.read(4_000_000.0,stats(4_000_000.0,bytes=1_126_000,frames=100))!!
        assertEquals(3_000_000L,s.sent!!.bitrate);assertEquals(6_000_000L,s.received!!.bitrate)
        assertEquals(30.0,s.sent.fps!!,0.001)
    }
    @Test fun codecStreamReplacementAndCounterRollbackRebaseWithoutNegativeBitrate() {
        val d=VideoDiagnostics();d.read(1_000_000.0,stats(1_000_000.0))
        assertNull(d.read(4_000_000.0,stats(4_000_000.0,id="new"))!!.sent!!.bitrate)
        assertNull(d.read(7_000_000.0,stats(7_000_000.0,bytes=1,id="new"))!!.sent!!.bitrate)
        assertEquals(8_000L,d.read(10_000_000.0,stats(10_000_000.0,bytes=3001,id="new"))!!.sent!!.bitrate)
    }
    @Test fun oldDuplicateAndNonfiniteReportsAreIgnoredWithoutPoisoningCounters() {
        val d=VideoDiagnostics();d.read(1_000_000.0,stats(1_000_000.0))
        assertNull(d.read(1_000_000.0,stats(1_000_000.0,bytes=999999)))
        assertNull(d.read(0.0,stats(0.0)));assertNull(d.read(Double.NaN,emptyList()))
        assertEquals(3_000_000L,d.read(4_000_000.0,stats(4_000_000.0,bytes=1_126_000))!!.sent!!.bitrate)
    }
    @Test fun noSelectedPairCannotMasqueradeAsHealthyNetwork() {
        val s=VideoDiagnostics().read(1.0,stats(1.0).filter { it.id!="tr" })!!
        assertNull(s.bandwidth);assertNull(s.rtt)
    }
    @Test fun absentVideoAndRepairStreamsAreNotPresentedAsReceivedFullHd() {
        val d=VideoDiagnostics();val s=d.read(1.0,stats(1.0).filter { it.type!="outbound-rtp" && it.type!="inbound-rtp" })!!
        assertNull(s.sent);assertNull(s.received)
        assertNull(d.read(2.0,listOf(VideoDiagnostics.Stat("rtx","outbound-rtp",2.0,mapOf("kind" to "video","bytesSent" to 999999))))!!.sent)
    }
    @Test fun longGapsAndNewCallResetDoNotReportStaleAverages() {
        val d=VideoDiagnostics();d.read(1_000_000.0,stats(1_000_000.0))
        assertNull(d.read(20_000_000.0,stats(20_000_000.0,bytes=999999))!!.sent!!.bitrate)
        d.reset();assertNull(d.read(1_000_000.0,stats(1_000_000.0))!!.sent!!.bitrate)
    }
}
