package app.cutiepie.cutie_pie.core

import org.junit.Assert.*
import org.junit.Test

class VideoQualityPolicyTest {
    private val formats=listOf(VideoQualityPolicy.Format(1920,1080,30),VideoQualityPolicy.Format(1280,720,60),VideoQualityPolicy.Format(640,480,30))
    @Test fun defaultsAndUnsupported60UseRealCameraFormats() {
        assertEquals(VideoQualityPolicy.defaults,VideoQualityPolicy.choose(VideoQualityPolicy.defaults,formats,false))
        assertFalse(VideoQualityPolicy.supports(VideoQualityPolicy.Preset(1920,1080,60),formats))
        assertTrue(VideoQualityPolicy.supports(VideoQualityPolicy.Preset(1280,720,60),formats))
        assertEquals(30,VideoQualityPolicy.choose(VideoQualityPolicy.Preset(1920,1080,60),formats,false).fps)
    }
    @Test fun protectionCapsResolutionAndFrameRateEvenAfterManualSelection() {
        assertEquals(VideoQualityPolicy.Preset(1280,720,30),VideoQualityPolicy.choose(VideoQualityPolicy.Preset(1920,1080,60),formats,true))
        assertEquals(480,VideoQualityPolicy.choose(VideoQualityPolicy.defaults,listOf(formats.last()),true).height)
    }
    @Test fun exactBatteryBoundaryAndThermalHysteresis() {
        assertTrue(VideoQualityPolicy.protect(49,0,300)); assertFalse(VideoQualityPolicy.protect(50,0,300))
        assertTrue(VideoQualityPolicy.protect(100,3,300)); assertTrue(VideoQualityPolicy.protect(100,0,430))
        assertFalse(VideoQualityPolicy.recovery(54,0,300)); assertFalse(VideoQualityPolicy.recovery(80,2,300))
        assertTrue(VideoQualityPolicy.recovery(55,1,400)); assertFalse(VideoQualityPolicy.protect(-1,0,0)); assertFalse(VideoQualityPolicy.recovery(-1,0,0))
    }
    @Test fun hardwareModernCodecsPreferredWithoutForcingSoftwareAv1() {
        assertTrue(VideoCompressionPolicy.codecRank("H265",emptyMap(),setOf("H265"))<VideoCompressionPolicy.codecRank("H264",emptyMap(),emptySet()))
        assertTrue(VideoCompressionPolicy.codecRank("AV1",emptyMap(),emptySet())>VideoCompressionPolicy.codecRank("VP8",emptyMap(),emptySet()))
        assertEquals(9,VideoCompressionPolicy.codecRank("rtx",emptyMap(),emptySet()))
    }
    @Test fun rtpCodecNamesMapToAndroidEncoderMimeTypes() {
        assertEquals("video/avc",VideoQualityPolicy.androidMime("H264"))
        assertEquals("video/hevc",VideoQualityPolicy.androidMime("H265"))
        assertEquals("video/av01",VideoQualityPolicy.androidMime("AV1"))
        assertEquals("video/x-vnd.on2.vp9",VideoQualityPolicy.androidMime("vp9"))
        assertNull(VideoQualityPolicy.androidMime("rtx"))
    }
}
