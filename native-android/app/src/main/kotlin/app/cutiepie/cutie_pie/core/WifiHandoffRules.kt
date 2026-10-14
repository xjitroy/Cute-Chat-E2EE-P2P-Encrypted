package app.cutiepie.cutie_pie.core

import org.json.JSONObject

/** Bounded fresh-session controls carried only inside authenticated Bluetooth Signal envelopes. */
object WifiHandoffRules {
    const val SETUP_MS=120_000L
    const val IDLE_MS=120_000L
    fun validate(v:JSONObject) {
        val type=v.getString("type")
        NativeIdentity.exact(v,when(type) {
            "handoff_request" -> setOf("type","id","expiresAt")
            "handoff_wifi" -> setOf("type","id","ssid","password","security","port","hosts")
            "handoff_abort" -> setOf("type","id")
            else -> error("Unknown Wi-Fi handoff")
        })
        ProtocolRules.id(v.get("id"))
        when(type) {
            "handoff_request" -> require(ProtocolRules.integer(v.get("expiresAt"))>=0)
            "handoff_wifi" -> {
                val ssid=v.getString("ssid");require(ssid.toByteArray(Charsets.UTF_8).size in 1..32 && ssid.none { Character.isISOControl(it) })
                val password=v.getString("password");require(password.length in 8..63 && password.all { it.code in 32..126 })
                require(v.getString("security") in setOf("wpa2","wpa3"))
                require(ProtocolRules.integer(v.get("port")) in 1..65535)
                val hosts=v.getJSONArray("hosts");require(hosts.length() in 1..8)
                for(i in 0 until hosts.length()) require(ipv4(hosts.getString(i)))
            }
        }
    }
    fun ipv4(value:String):Boolean {
        val a=value.split('.');if(a.size!=4 || a.any { !it.matches(Regex("[0-9]{1,3}")) || (it.toIntOrNull() ?: -1) !in 0..255 }) return false
        val first=a[0].toInt();return first in 1..223 && first!=127 && !(first==169 && a[1].toInt()==254)
    }
    fun fresh(expires:Long,now:Long)=expires>now && expires-now<=SETUP_MS
    /** A fresh ID, peer and role bind every callback; a prior session cannot change current radios. */
    class Gate(val peer:String,val id:String,val host:Boolean,val began:Long) {
        var phase=if(host) "offered" else "requested";private set
        fun accept(from:String,session:String,now:Long):Boolean {
            if(!host || phase!="offered" || !matches(from,session) || now-began>=SETUP_MS) return false
            phase="hosting";return true
        }
        fun credentials(from:String,session:String,now:Long):Boolean {
            if(host || phase!="requested" || !matches(from,session) || now-began>=SETUP_MS) return false
            phase="joining";return true
        }
        fun connected() { if(phase in setOf("hosting","joining","requested")) phase="connected" }
        fun matches(from:String,session:String)=phase!="closed" && from==peer && session==id
        fun close() { phase="closed" }
    }
}
