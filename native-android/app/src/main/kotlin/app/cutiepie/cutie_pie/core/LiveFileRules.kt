package app.cutiepie.cutie_pie.core

import org.json.JSONObject

/** Versioned live stream; crypto keys travel only inside the existing Signal envelope. */
object LiveFileRules {
    fun validate(v:JSONObject) {
        val type=v.getString("type")
        when(type) {
            "stream_ready" -> { NativeIdentity.exact(v,setOf("type","enabled"));require(v.get("enabled") is Boolean) }
            "stream_offer" -> {
                NativeIdentity.exact(v,setOf("type","id","name","mime","size","key","header","quick"))
                id(v);require(ProtocolRules.integer(v.get("size")) in 0..LargeFileRules.MAX_SIZE)
                val name=v.getString("name");require(name.length in 1..120 && name.none { Character.isISOControl(it) || it=='/' || it=='\\' })
                require(v.getString("mime").matches(Regex("[a-zA-Z0-9.+_-]{1,80}/[a-zA-Z0-9.+_-]{1,80}")))
                require(v.get("quick") == true)
                WireCodec.key(v.getString("key"),32).fill(0);WireCodec.key(v.getString("header"),24).fill(0)
            }
            "stream_pause" -> { NativeIdentity.exact(v,setOf("type","id","paused","seq"));id(v);require(v.get("paused") is Boolean);require(ProtocolRules.integer(v.get("seq")) in 1..1_000_000_000) }
            "stream_progress" -> { NativeIdentity.exact(v,setOf("type","id","bytes"));id(v);require(ProtocolRules.integer(v.get("bytes")) in 0..LargeFileRules.MAX_SIZE) }
            else -> { require(type in setOf("stream_accept","stream_done","stream_abort"));NativeIdentity.exact(v,setOf("type","id"));id(v) }
        }
    }
    private fun id(v:JSONObject) { WireCodec.key(v.getString("id"),24).fill(0) }
    fun identity(name:String,uid:String):String {
        require(uid.matches(Regex("[a-f0-9]{64}")))
        val last=FriendlyContactId.shareNumber(uid).takeLast(4)
        val clean=name.filter { !Character.isISOControl(it) && Character.getType(it)!=Character.FORMAT.toInt() }.take(80).trim()
        return if(clean.isBlank()) "•••• $last" else "$clean · •••• $last"
    }
}
