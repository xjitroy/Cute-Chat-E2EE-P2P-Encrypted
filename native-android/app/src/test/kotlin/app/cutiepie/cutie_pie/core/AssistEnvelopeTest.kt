package app.cutiepie.cutie_pie.core
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
class AssistEnvelopeTest {
    private fun input()=JSONObject().put("type","assist_input").put("callId",WireCodec.id()).put("epoch",WireCodec.id()).put("window",WireCodec.id()).put("seq",1).put("action","tap").put("x",100).put("y",100).put("x2",100).put("y2",100).put("duration",80)
    @Test fun onlyDiscreteBoundActions() { assertEquals("tap",NativeExtensions.parse(NativeExtensions.envelope(input()))!!.getString("action"));for(action in listOf("text","unlock","home","shell","permission")) assertThrows(IllegalArgumentException::class.java) { NativeExtensions.envelope(input().put("action",action)) } }
    @Test fun coordinateDurationAndSequenceBounds() { for(pair in listOf("x" to -1,"x2" to 10001,"duration" to 601,"seq" to 0)) assertThrows(IllegalArgumentException::class.java) { NativeExtensions.envelope(input().put(pair.first,pair.second)) } }
    @Test fun arbitraryTextAndEmptyCapabilityRejected() { assertThrows(IllegalArgumentException::class.java) { NativeExtensions.envelope(input().put("text","secret")) };assertThrows(IllegalArgumentException::class.java) { NativeExtensions.envelope(input().put("window","")) } }
    @Test fun normalDoesNotAdvertiseControl() { val f=NativeExtensions.parse(NativeExtensions.features(false))!!.getJSONArray("features");val names=(0 until f.length()).map { f.getString(it) };assertFalse(names.contains("assist"));assertTrue(names.containsAll(listOf("delete","profile","call_ring"))) }
    @Test fun advertisedWithoutReplacingExistingFeatures() { val f=NativeExtensions.parse(NativeExtensions.features(true))!!.getJSONArray("features");val names=(0 until f.length()).map { f.getString(it) };assertTrue(names.containsAll(listOf("assist","delete","profile","call_ring"))) }
}
