package app.cutiepie.cutie_pie.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProtocolRulesTest {
    @Test fun screenshotAndMediaStateControlsAreStrictlyTyped() {
        assertThrows(IllegalArgumentException::class.java) { ProtocolRules.validate("capture_notice", JSONObject().put("context", "chat").put("callId", WireCodec.id()), "self", "peer") }
        assertThrows(IllegalArgumentException::class.java) { ProtocolRules.validate("media_state", JSONObject().put("id", WireCodec.id()).put("used", "1").put("expired", false), "self", "peer") }
        assertThrows(IllegalArgumentException::class.java) { ProtocolRules.validate("media_state", JSONObject().put("id", WireCodec.id()).put("used", 3).put("expired", false), "self", "peer") }
    }
    @Test fun rejectsUnknownFieldsNumericCoercionAndOversizedChunks() {
        val id = "0123456789abcdef"
        for (data in listOf(
            JSONObject().put("id", id).put("index", "0").put("bytes", "AQ"),
            JSONObject().put("id", id).put("index", 0).put("bytes", WireCodec.b64(ByteArray(12001))),
            JSONObject().put("id", id).put("index", 0).put("bytes", "AQ").put("extra", "unexpected"),
        )) assertThrows(IllegalArgumentException::class.java) { ProtocolRules.validate("transfer_chunk", data, "self", "peer") }
    }
    @Test fun replyReferencesAndCallControlsAreBoundedAndTyped() {
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolRules.validate("text", JSONObject().put("text", "hello").put("replyTo", "wrong"), "self", "peer")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolRules.validate("call_offer", JSONObject().put("callId", WireCodec.id()).put("video", "true")
                .put("sdp", "valid shape only").put("expiresAt", 100L), "self", "peer")
        }
    }
}
