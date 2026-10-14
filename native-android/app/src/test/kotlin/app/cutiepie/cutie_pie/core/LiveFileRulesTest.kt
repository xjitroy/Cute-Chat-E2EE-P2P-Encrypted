package app.cutiepie.cutie_pie.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LiveFileRulesTest {
    private fun offer(size:Long=5L*1024*1024*1024)=JSONObject().put("type","stream_offer").put("id",WireCodec.id()).put("name","original.any")
        .put("mime","application/octet-stream").put("size",size).put("key",WireCodec.b64(ByteArray(32))).put("header",WireCodec.b64(ByteArray(24))).put("quick",true)
    @Test fun longLiveOffersNeedNoWholeFileHashPrepassAndUseExplicitCapability() {
        for(size in listOf(0L,1L,5L*1024*1024*1024,LargeFileRules.MAX_SIZE)) {
            val parsed=NativeExtensions.parse(NativeExtensions.envelope(offer(size)))!!;assertEquals(size,parsed.getLong("size"));assertFalse(parsed.has("hash"))
        }
        for(assist in listOf(false,true)) { val features=NativeExtensions.parse(NativeExtensions.features(assist))!!.getJSONArray("features");assertTrue(features.length()<=8);assertTrue((0 until features.length()).any { features.getString(it)=="live_file" }) }
    }
    @Test fun malformedAndOversizeOffersFailBeforeAnyFileAllocation() {
        for(size in listOf(-1L,LargeFileRules.MAX_SIZE+1)) assertThrows(IllegalArgumentException::class.java) { LiveFileRules.validate(offer(size)) }
        for(name in listOf("../file","a\\file","\u0000file","")) assertThrows(IllegalArgumentException::class.java) { LiveFileRules.validate(offer().put("name",name)) }
        for(change in listOf(offer().put("size",3.4),offer().put("quick",false),offer().put("key",WireCodec.b64(ByteArray(31))),offer().put("header",WireCodec.b64(ByteArray(23))),offer().put("hash","not-approved"))) assertThrows(IllegalArgumentException::class.java) { LiveFileRules.validate(change) }
    }
    @Test fun pauseProgressAndReadinessAreStrictAuthenticatedControls() {
        val id=WireCodec.id()
        val p=JSONObject().put("type","stream_pause").put("id",id).put("paused",true).put("seq",1)
        assertTrue(NativeExtensions.parse(NativeExtensions.envelope(p))!!.getBoolean("paused"))
        assertThrows(IllegalArgumentException::class.java) { LiveFileRules.validate(JSONObject(p.toString()).put("seq",0)) }
        assertThrows(IllegalArgumentException::class.java) { LiveFileRules.validate(JSONObject(p.toString()).put("paused","true")) }
        for(bytes in listOf(0L,5L*1024*1024*1024)) LiveFileRules.validate(JSONObject().put("type","stream_progress").put("id",id).put("bytes",bytes))
        assertThrows(IllegalArgumentException::class.java) { LiveFileRules.validate(JSONObject().put("type","stream_progress").put("id",id).put("bytes",-1)) }
        LiveFileRules.validate(JSONObject().put("type","stream_ready").put("enabled",true))
        assertThrows(IllegalArgumentException::class.java) { LiveFileRules.validate(JSONObject().put("type","stream_ready").put("enabled",true).put("deviceName","hardware")) }
    }
    @Test fun onlyNumericPublicIdSuffixIsShownAndNameCannotSpoofExtraRows() {
        val uid="a".repeat(64);val number=FriendlyContactId.shareNumber(uid)
        assertEquals("Alice · •••• "+number.takeLast(4),LiveFileRules.identity("Alice",uid))
        assertEquals("•••• "+number.takeLast(4),LiveFileRules.identity("",uid))
        assertFalse(LiveFileRules.identity("A\nB\u202eC",uid).contains('\n'))
        assertFalse(LiveFileRules.identity("Alice",uid).contains(number))
        assertThrows(IllegalArgumentException::class.java) { LiveFileRules.identity("Alice","untrusted Bluetooth MAC") }
    }
}
