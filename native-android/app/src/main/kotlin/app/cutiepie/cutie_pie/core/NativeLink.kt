package app.cutiepie.cutie_pie.core

import org.json.JSONObject
import java.io.Closeable

/** One peer route; every content frame still goes through the sole Signal owner. */
interface NativeLink : Closeable {
    val requestId: String
    val ready: Boolean
    val medium: String get() = "internet"
    fun start()
    fun send(frame: String,urgent:Boolean=false): Boolean
    fun receiveSetup(purpose: String, setup: JSONObject) { error("No SDP setup on this transport") }
}
