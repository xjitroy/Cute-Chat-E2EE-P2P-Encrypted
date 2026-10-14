package app.cutiepie.cutie_pie.core

import org.json.JSONArray
import org.json.JSONObject

/** Versioned controls inside the existing authenticated Signal consent envelope.
 * Earlier native receivers validate/acknowledge this reserved envelope but ignore
 * its contents. Destructive controls require advertised peer support. */
object NativeExtensions {
    private const val PREFIX = "CCX1:"
    fun envelope(value: JSONObject): JSONObject = JSONObject().put("code", PREFIX + WireCodec.canonical(value)).also { parse(it) }
    fun features(assist:Boolean = false) = envelope(JSONObject().put("type","features").put("features",JSONArray(listOf("delete","profile","call_ring","large_file","wifi_handoff","live_file") + if(assist) listOf("assist") else emptyList())))
    fun parse(data: JSONObject): JSONObject? {
        val code = data.getString("code")
        if (!code.startsWith(PREFIX)) return null
        val value = WireCodec.parseObject(code.removePrefix(PREFIX),4096)
        when (value.getString("type")) {
            "features" -> {
                NativeIdentity.exact(value,setOf("type","features"))
                val features = value.getJSONArray("features"); require(features.length() <= 8)
                for(i in 0 until features.length()) require(features.getString(i).matches(Regex("[a-z_]{1,32}")))
            }
            "delete" -> { NativeIdentity.exact(value,setOf("type","id")); ProtocolRules.id(value.get("id")) }
            "call_ring" -> {
                NativeIdentity.exact(value,setOf("type","callId","expiresAt"))
                ProtocolRules.id(value.get("callId")); require(ProtocolRules.integer(value.get("expiresAt")) >= 0)
            }
            "assist_grant", "assist_accept", "assist_revoke" -> {
                NativeIdentity.exact(value,setOf("type","callId","epoch"))
                ProtocolRules.id(value.get("callId")); ProtocolRules.id(value.get("epoch"))
            }
            "assist_state" -> {
                NativeIdentity.exact(value,setOf("type","callId","epoch","window","width","height","rotation","active","remaining"))
                ProtocolRules.id(value.get("callId")); ProtocolRules.id(value.get("epoch"))
                val window=value.getString("window"); require(window.isEmpty() || window.matches(Regex("[A-Za-z0-9_-]{16,128}")))
                require(ProtocolRules.integer(value.get("width")) in 1..8192 && ProtocolRules.integer(value.get("height")) in 1..8192)
                require(ProtocolRules.integer(value.get("rotation")) in 0..3 && value.get("active") is Boolean)
                require(ProtocolRules.integer(value.get("remaining")) in 0..1_800_000)
            }
            "assist_input" -> {
                NativeIdentity.exact(value,setOf("type","callId","epoch","window","seq","action","x","y","x2","y2","duration"))
                listOf("callId","epoch","window").forEach { ProtocolRules.id(value.get(it)) }
                require(ProtocolRules.integer(value.get("seq")) in 1..1_000_000_000)
                require(value.getString("action") in setOf("tap","swipe","back"))
                listOf("x","y","x2","y2").forEach { require(ProtocolRules.integer(value.get(it)) in 0..10000) }
                require(ProtocolRules.integer(value.get("duration")) in 1..600)
            }
            "handoff_request", "handoff_wifi", "handoff_abort" -> WifiHandoffRules.validate(value)
            "large_offer", "large_accept", "large_done", "large_abort" -> LargeFileRules.validate(value)
            "stream_offer", "stream_accept", "stream_done", "stream_abort", "stream_pause", "stream_progress", "stream_ready" -> LiveFileRules.validate(value)
            "profile" -> {
                NativeIdentity.exact(value,setOf("type","bio","photoHash"))
                val bio = value.getString("bio"); require(bio.length <= 240 && bio.none { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() })
                if(!value.isNull("photoHash")) ProtocolRules.hash(value.get("photoHash"))
            }
            else -> error("Unsupported native extension")
        }
        return value
    }
}
