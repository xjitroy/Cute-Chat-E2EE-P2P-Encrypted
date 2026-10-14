package app.cutiepie.cutie_pie.core

import org.junit.Assert.*
import org.junit.Test
import java.io.*

class LocalSharingRulesTest {
    @Test fun originalBytesSurvivePacketFramingWithoutCompression() {
        val original=ByteArray(LocalSharingRules.MAX_PACKET) { (it*137).toByte() }
        val stored=ByteArrayOutputStream();LocalSharingRules.write(DataOutputStream(stored),original)
        assertArrayEquals(original,LocalSharingRules.read(DataInputStream(ByteArrayInputStream(stored.toByteArray()))))
        assertEquals(WireCodec.digest(original),WireCodec.digest(LocalSharingRules.read(DataInputStream(ByteArrayInputStream(stored.toByteArray())))))
    }
    @Test fun hostileLengthsAreRejectedBeforeAllocationAndTruncationFails() {
        for(size in listOf(-1,0,LocalSharingRules.MAX_PACKET+1,Int.MAX_VALUE)) {
            val bytes=ByteArrayOutputStream();DataOutputStream(bytes).writeInt(size)
            try { LocalSharingRules.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray())));fail() } catch(_:IllegalArgumentException) { }
        }
        val bytes=ByteArrayOutputStream();DataOutputStream(bytes).apply { writeInt(100);write(byteArrayOf(1)) }
        try { LocalSharingRules.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray())));fail() } catch(_:EOFException) { }
    }
    @Test fun bothSidesGetTheSameFreshPairingCodeAndDirectionsHaveDifferentProofContexts() {
        val a="a".repeat(64);val b="b".repeat(64);val x=WireCodec.id();val y=WireCodec.id()
        assertEquals(LocalSharingRules.request(x,y),LocalSharingRules.request(y,x))
        assertEquals(LocalSharingRules.safety(a,b,x,y),LocalSharingRules.safety(b,a,y,x))
        assertNotEquals(LocalSharingRules.safety(a,b,x,y),LocalSharingRules.safety(a,b,x,WireCodec.id()))
        assertNotEquals(LocalSharingRules.context(a,b,x,y,"wifi"),LocalSharingRules.context(b,a,y,x,"wifi"))
        assertNotEquals(LocalSharingRules.context(a,b,x,y,"wifi"),LocalSharingRules.context(a,b,x,y,"bluetooth"))
    }
    @Test fun bluetoothRejectsFilesVideoAndOversizeImagesButWifiKeepsOriginalFormats() {
        LocalSharingRules.attachment("bluetooth","image/png",1024)
        for(mime in listOf("video/mp4","audio/flac","application/pdf")) {
            try { LocalSharingRules.attachment("bluetooth",mime,1024);fail() } catch(_:IllegalArgumentException) { }
            LocalSharingRules.attachment("wifi",mime,1024)
        }
        try { LocalSharingRules.attachment("bluetooth","image/jpeg",LocalSharingRules.BLUETOOTH_PHOTO_LIMIT+1);fail() } catch(_:IllegalArgumentException) { }
        LocalSharingRules.attachment("wifi","image/jpeg",ProtocolRules.MAX_ATTACHMENT)
    }
    @Test fun standardPcmuVectorsAndClippingDoNotOverflowSpeechSamples() {
        val sample=shortArrayOf(0,8,-8,32767,-32768)
        val encoded=G711MuLaw.encode(sample,sample.size)
        assertArrayEquals(byteArrayOf(0xff.toByte(),0xfe.toByte(),0x7e,0x80.toByte(),0),encoded)
        assertArrayEquals(shortArrayOf(0,8,-8,32124,-32124),G711MuLaw.decode(encoded))
    }
    @Test fun speechHasBoundedErrorAndEveryEncodedSampleProducesOneDecodedSample() {
        val samples=ShortArray(800) { (kotlin.math.sin(it*0.17)*20000).toInt().toShort() }
        val bytes=G711MuLaw.encode(samples,samples.size);assertEquals(800,bytes.size)
        val decoded=G711MuLaw.decode(bytes);assertEquals(samples.size,decoded.size)
        for(i in samples.indices) assertTrue(kotlin.math.abs(samples[i].toInt()-decoded[i].toInt())<=1024)
    }
}
