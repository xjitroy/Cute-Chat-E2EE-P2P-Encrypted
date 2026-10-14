package app.cutiepie.cutie_pie.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.*

class LargeFileRulesTest {
    private fun offer(size:Long=5L*1024*1024*1024,quick:Boolean=false)=JSONObject().put("type","large_offer").put("id",WireCodec.id())
        .put("name","original.mp4").put("mime","video/mp4").put("size",size).put("hash","a".repeat(64))
        .put("key",WireCodec.b64(ByteArray(32))).put("header",WireCodec.b64(ByteArray(24))).put("quick",quick)
    @Test fun multiGigabyteSizesStayLongAndBoundedWithoutAllocation() {
        for(size in listOf(2L*1024*1024*1024+123,5L*1024*1024*1024+123,LargeFileRules.MAX_SIZE)) {
            LargeFileRules.validate(offer(size));assertTrue(LargeFileRules.diskBytes(size)>size)
            assertTrue(LargeFileRules.diskBytes(size)<size+size/100+LargeFileRules.RESERVE)
        }
        assertThrows(IllegalArgumentException::class.java) { LargeFileRules.diskBytes(LargeFileRules.MAX_SIZE+1) }
        assertThrows(IllegalArgumentException::class.java) { LargeFileRules.validate(offer(-1)) }
    }
    @Test fun fileKeyAndExplicitExportChoiceAreAuthenticatedInTheSignalExtension() {
        val value=offer(123,true);val wrapped=NativeExtensions.envelope(value)
        val parsed=NativeExtensions.parse(wrapped)!!;assertEquals(123L,parsed.getLong("size"));assertTrue(parsed.getBoolean("quick"))
        assertThrows(IllegalArgumentException::class.java) { LargeFileRules.validate(offer().put("key",WireCodec.b64(ByteArray(31)))) }
        assertThrows(IllegalArgumentException::class.java) { LargeFileRules.validate(offer().put("header",WireCodec.b64(ByteArray(23)))) }
        assertThrows(IllegalArgumentException::class.java) { LargeFileRules.validate(offer().put("quick","true")) }
    }
    @Test fun unsafeNamesAmbiguousCountersAndUnboundControlsAreRejected() {
        for(name in listOf("../file","a\\file","\u0000file","", "a".repeat(121))) assertThrows(IllegalArgumentException::class.java) { LargeFileRules.validate(offer().put("name",name)) }
        assertThrows(IllegalArgumentException::class.java) { LargeFileRules.validate(offer().put("size",3.5)) }
        for(type in listOf("large_accept","large_done","large_abort")) LargeFileRules.validate(JSONObject().put("type",type).put("id",WireCodec.id()))
        assertThrows(IllegalArgumentException::class.java) { LargeFileRules.validate(JSONObject().put("type","large_done").put("id",WireCodec.id()).put("key","extra")) }
        assertNotEquals(LargeFileRules.aad(WireCodec.id(),123).toList(),LargeFileRules.aad(WireCodec.id(),123).toList())
    }
    @Test fun corruptLengthsAndTruncatedCiphertextFailBeforeLargeAllocation() {
        for(size in listOf(-1,0,16,32786,Int.MAX_VALUE)) {
            val bytes=ByteArrayOutputStream().also { DataOutputStream(it).writeInt(size) }.toByteArray()
            assertThrows(IllegalArgumentException::class.java) { LargeFileRules.read(DataInputStream(ByteArrayInputStream(bytes))) }
        }
        val out=ByteArrayOutputStream();LargeFileRules.write(DataOutputStream(out),ByteArray(32785) { (it%251).toByte() })
        assertEquals(32785,LargeFileRules.read(DataInputStream(ByteArrayInputStream(out.toByteArray()))).size)
        assertThrows(EOFException::class.java) { LargeFileRules.read(DataInputStream(ByteArrayInputStream(out.toByteArray().dropLast(1).toByteArray()))) }
    }
}
