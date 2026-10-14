package app.cutiepie.cutie_pie.core

import org.json.JSONObject

/** Validate before committing incoming ratchet state or enqueuing local payloads. */
object ProtocolRules {
    const val CHUNK_SIZE = 12000
    const val MAX_ATTACHMENT = 32 * 1024 * 1024
    val emojis = setOf("❤️", "😂", "👍", "😮", "😢", "🙏", "🔥", "🎉")
    fun id(value: Any?): String {
        require(value is String && value.matches(Regex("[A-Za-z0-9_-]{16,128}"))) { "Invalid message reference" }
        return value
    }
    fun integer(value: Any?): Long {
        require(value is Int || value is Long) { "Integer required" }
        return (value as Number).toLong()
    }
    private fun exact(data: JSONObject, fields: Set<String>) = NativeIdentity.exact(data, fields)
    private fun nullable(data: JSONObject, key: String) = data.opt(key).takeUnless { it === JSONObject.NULL }
    fun hash(value: Any?) { require(value is String && value.matches(Regex("[a-f0-9]{64}"))) }
    fun validate(kind: String, data: JSONObject, self: String, peer: String) {
        when (kind) {
            "local_ping" -> { exact(data,setOf("id"));id(data.get("id")) }
            "bt_offer" -> {
                exact(data,setOf("callId","expiresAt","codec","rate"));id(data.get("callId"))
                require(integer(data.get("expiresAt"))>=0 && data.getString("codec")=="PCMU" && integer(data.get("rate"))==8000L)
            }
            "bt_answer" -> { exact(data,setOf("callId","codec","rate"));id(data.get("callId"));require(data.getString("codec")=="PCMU" && integer(data.get("rate"))==8000L) }
            "local_audio" -> {
                exact(data,setOf("callId","seq","codec","bytes"));id(data.get("callId"))
                require(integer(data.get("seq")) in 0..9007199254740990L && data.getString("codec")=="PCMU")
                require(WireCodec.decode(data.getString("bytes"),800).size in 1..800)
            }
            "capture_notice" -> {
                exact(data, setOf("context", "callId"))
                require(data.getString("context") in setOf("chat", "call"))
                nullable(data, "callId")?.let(::id)
                require((data.getString("context") == "call") == (nullable(data, "callId") != null))
            }
            "media_state" -> {
                exact(data, setOf("id", "used", "expired")); id(data.get("id"))
                require(integer(data.get("used")) in 0L..2L && data.get("expired") is Boolean)
            }
            "text", "link" -> {
                exact(data, setOf("text") + if (data.has("replyTo")) setOf("replyTo") else emptySet())
                require(data.getString("text").length <= 4000)
                if (data.has("replyTo")) id(data.get("replyTo"))
            }
            "reaction" -> {
                exact(data, setOf("messageId", "emoji", "revision")); id(data.get("messageId"))
                require(data.getString("emoji").let { it.isEmpty() || it in emojis })
                require(integer(data.get("revision")) in 1L..9007199254740991L)
            }
            "ack" -> {
                exact(data, setOf("ackId", "deliveredId")); id(data.get("ackId"))
                nullable(data, "deliveredId")?.let(::id)
            }
            "read" -> {
                exact(data, setOf("ids"))
                val ids = data.getJSONArray("ids"); require(ids.length() <= 100)
                for (i in 0 until ids.length()) id(ids.get(i))
            }
            "ping_label" -> {
                exact(data, setOf("label", "revision", "author"))
                val label = data.getString("label")
                require(label.length in 1..32 && label.trim() == label && label.none {
                    Character.isISOControl(it) || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069'
                })
                require(integer(data.get("revision")) in 1L..9007199254740990L)
                require(data.getString("author") == self || data.getString("author") == peer)
            }
            "presence" -> {
                exact(data, setOf("active", "lastSeen", "sharing"))
                require(data.get("active") is Boolean && data.get("sharing") is Boolean)
                nullable(data, "lastSeen")?.let { require(integer(it) >= 0) }
            }
            "transfer_start" -> {
                exact(data, setOf("id", "name", "mime", "size", "hash", "purpose")); id(data.get("id"))
                val size = integer(data.get("size")); require(size in 1L..MAX_ATTACHMENT.toLong())
                val name = data.getString("name"); val mime = data.getString("mime")
                require(name.length in 1..120 && name.none { Character.isISOControl(it) })
                require(mime.length in 1..100 && mime.none { Character.isISOControl(it) })
                hash(data.get("hash"))
                require(data.getString("purpose") in setOf("file", "photo", "view_once", "view_twice"))
                if (data.getString("purpose") == "photo") require(size <= 512000 && mime.startsWith("image/"))
                if (viewLimit(data.getString("purpose")) > 0) require(mime.startsWith("image/") || mime.startsWith("video/"))
            }
            "transfer_chunk" -> {
                exact(data, setOf("id", "index", "bytes")); id(data.get("id"))
                require(integer(data.get("index")) in 0L..((MAX_ATTACHMENT + CHUNK_SIZE - 1) / CHUNK_SIZE).toLong())
                require(WireCodec.decode(data.getString("bytes"), CHUNK_SIZE).isNotEmpty())
            }
            "transfer_finish" -> { exact(data, setOf("id")); id(data.get("id")) }
            "call_offer", "call_answer", "call_ice", "call_end" -> {
                id(data.get("callId"))
                when (kind) {
                    "call_offer" -> {
                        exact(data, setOf("callId", "video", "sdp", "expiresAt"))
                        require(data.get("video") is Boolean && integer(data.get("expiresAt")) >= 0)
                        require(data.getString("sdp").length in 1..15999)
                    }
                    "call_answer" -> { exact(data, setOf("callId", "sdp")); require(data.getString("sdp").length in 1..15999) }
                    "call_ice" -> {
                        exact(data, setOf("callId", "candidate", "sdpMid", "sdpMLineIndex"))
                        require(data.getString("candidate").length in 1..4096)
                        require(nullable(data, "sdpMid") == null || nullable(data, "sdpMid") is String)
                        nullable(data, "sdpMLineIndex")?.let { require(integer(it) in 0L..Int.MAX_VALUE.toLong()) }
                    }
                    else -> exact(data, setOf("callId"))
                }
            }
            "hello" -> {
                exact(data, setOf("requestId", "photoHash")); id(data.get("requestId"))
                nullable(data, "photoHash")?.let(::hash)
            }
            "profile_request" -> { exact(data, setOf("hash")); nullable(data, "hash")?.let(::hash) }
            "consent_request", "consent_approval" -> { exact(data, setOf("code")); require(data.getString("code").length in 1..12000); if(kind == "consent_request") NativeExtensions.parse(data) }
            else -> error("Unsupported message kind")
        }
    }
    fun viewLimit(purpose: String) = when (purpose) { "view_once" -> 1; "view_twice" -> 2; else -> 0 }
}
