package app.cutiepie.cutie_pie.core

import org.junit.Assert.*
import org.junit.Test

class VideoCompressionPolicyTest {
    @Test fun localMixedOemCallsPreferBaselineBeforeModernOrHighProfileVideo() {
        val hw=setOf("H264","H265","VP9")
        val baseline=VideoCompressionPolicy.localCodecRank("H264",mapOf("profile-level-id" to "42e01f"),hw)
        assertEquals(0,baseline)
        assertTrue(baseline<VideoCompressionPolicy.localCodecRank("H265",emptyMap(),hw))
        assertTrue(VideoCompressionPolicy.localCodecRank("VP8",emptyMap(),hw)<VideoCompressionPolicy.localCodecRank("H264",mapOf("profile-level-id" to "640c1f"),hw))
    }
    @Test fun localFallbackStillRetainsModernCodecsAndTransportRepair() {
        val offered=listOf("H265","VP9","rtx","red","ulpfec")
        val ordered=offered.sortedBy { VideoCompressionPolicy.localCodecRank(it,emptyMap(),setOf("H265","VP9")) }
        assertEquals(offered.toSet(),ordered.toSet());assertEquals("H265",ordered.first())
        assertTrue(ordered.indexOf("rtx")>ordered.indexOf("VP9"))
    }
    private val p=VideoQualityPolicy.defaults
    private fun rank(c:String,hw:Set<String>,profile:String="")=VideoCompressionPolicy.codecRank(c,mapOf("profile-level-id" to profile),hw)
    @Test fun efficientCodecNeedsFittedHardwareAndCompatibilityFallbackStaysAheadOfSoftwareAv1() {
        assertTrue(rank("H265",setOf("H265")) < rank("VP9",setOf("VP9")))
        assertTrue(rank("VP9",setOf("VP9")) < rank("AV1",setOf("AV1")))
        assertTrue(rank("VP9",setOf("VP9")) < rank("H264",setOf("H264")))
        assertTrue(rank("H264",emptySet()) < rank("AV1",emptySet()))
        assertTrue(rank("VP8",emptySet()) < rank("H265",emptySet()))
    }
    @Test fun highProfileOnlyPreferredWithValidSdkProfileAndHardware() {
        assertTrue(rank("H264",setOf("H264"),"640c1f") < rank("H264",setOf("H264"),"42e01f"))
        assertEquals(rank("H264",emptySet()),rank("H264",emptySet(),"640c1f"))
        assertEquals(rank("H264",setOf("H264")),rank("H264",setOf("H264"),"64garbage"))
    }
    @Test fun transportRepairCapabilitiesAreRetainedAfterPrimaryVideo() {
        for(c in listOf("rtx","red","ulpfec","flexfec-03")) assertTrue(rank(c,emptySet())>rank("AV1",emptySet()))
    }
    @Test fun budgetsBound60FpsAndUseExplicitDataSavingRatherThanLosslessPromise() {
        for(mode in VideoCompressionPolicy.Mode.entries) for(p in VideoQualityPolicy.presets) for(codec in listOf("H264","VP8","VP9","H265","AV1","")) {
            val cap=VideoCompressionPolicy.budget(p,mode,codec,false)
            assertTrue(cap>0);assertTrue(cap<=p.maxBitrate)
        }
        assertEquals(p.maxBitrate,VideoCompressionPolicy.budget(p,VideoCompressionPolicy.Mode.DETAIL,"AV1",false))
        assertEquals(2_000_000,VideoCompressionPolicy.budget(p,VideoCompressionPolicy.Mode.DATA_SAVER,"H264",false))
        assertEquals(6_000_000,VideoCompressionPolicy.budget(p,VideoCompressionPolicy.Mode.DETAIL,"H264",true))
    }
    @Test fun hevcCapableAndVp9OnlyDevicePairsPreferDifferentSingleCodecs() {
        val offered=listOf("AV1","H264","VP9","H265","VP8","rtx")
        fun chosen(hw:Set<String>,remote:Set<String>)=offered.filter { it in remote }
            .minBy { rank(it,hw) }
        assertEquals("H265",chosen(setOf("H265","VP9"),setOf("H265","VP9","H264")))
        assertEquals("VP9",chosen(setOf("VP9"),setOf("VP9","H264","VP8")))
        assertEquals("VP9",chosen(setOf("H265","VP9"),setOf("VP9","H264")))
        assertEquals("H264",chosen(emptySet(),setOf("VP9","H264","VP8")))
    }
    @Test fun detailKeepsFullFidelityBudgetForBothRequestedCodecs() {
        for(p in VideoQualityPolicy.presets) for(codec in listOf("VP9","H265","video/VP9","video/H265")) {
            assertEquals(p.maxBitrate,VideoCompressionPolicy.budget(p,VideoCompressionPolicy.Mode.DETAIL,codec,false))
            assertTrue(VideoCompressionPolicy.budget(p,VideoCompressionPolicy.Mode.DATA_SAVER,codec,false)<p.maxBitrate)
        }
    }
    @Test fun invalidSavedPreferenceFallsBackAndHeatOverridesUserModes() {
        assertEquals(VideoCompressionPolicy.Mode.DETAIL,VideoCompressionPolicy.mode("lossless"))
        for(mode in VideoCompressionPolicy.Mode.entries) assertEquals(VideoCompressionPolicy.Adaptation.BALANCED,VideoCompressionPolicy.adaptation(mode,true,true,false))
        assertEquals(VideoCompressionPolicy.Adaptation.BALANCED,VideoCompressionPolicy.adaptation(VideoCompressionPolicy.Mode.DATA_SAVER,true,false,false))
    }
    private fun good(g:VideoCompressionPolicy.DetailGate,now:Long) = g.observe(now,8_000_000.0,0.1,0.0,"none",3_000_000.0,false)
    @Test fun healthyDetailNeedsThreeMeasurementsAndSixSeconds() {
        val g=VideoCompressionPolicy.DetailGate();good(g,100);good(g,3100);assertFalse(g.permits(3100))
        good(g,6100);assertTrue(g.permits(6100));assertFalse(g.permits(13101))
    }
    @Test fun congestionCpuOrMissingMeasurementsImmediatelyReleaseResolutionPriority() {
        for(reason in listOf("bandwidth","cpu","unknown")) {
            val g=VideoCompressionPolicy.DetailGate();good(g,100);good(g,3100);good(g,6100)
            g.observe(9100,8_000_000.0,0.1,0.0,reason,3_000_000.0,false);assertFalse(g.permits(9100))
        }
        val g=VideoCompressionPolicy.DetailGate();good(g,100);good(g,3100);good(g,6100)
        g.observe(9100,null,0.1,0.0,"none",3_000_000.0,false);assertFalse(g.permits(9100))
    }
    @Test fun lossNaNHeatAndLowBandwidthNeverEnableDetail() {
        for(i in 0..4) {
            val g=VideoCompressionPolicy.DetailGate()
            for(t in listOf(100L,3100L,6100L)) g.observe(t,if(i==0) Double.NaN else if(i==1) 100.0 else 8_000_000.0,
                if(i==2) 1.0 else 0.1,if(i==3) 0.1 else 0.0,"none",3_000_000.0,i==4)
            assertFalse(g.permits(6100))
        }
    }
    @Test fun recoveryCooldownAndStaleOrDuplicateSamplesCannotReenableEarly() {
        val g=VideoCompressionPolicy.DetailGate();g.observe(100,10.0,0.1,0.0,"none",3_000_000.0,false)
        good(g,3100);good(g,6100);good(g,8100);assertFalse(g.permits(8100));good(g,9100);assertTrue(g.permits(9100))
        good(g,20000);assertFalse(g.permits(20000));good(g,20000);good(g,19000);assertFalse(g.permits(20000))
        g.reset();assertFalse(g.permits(20000))
    }
}
