package app.cutiepie.cutie_pie.core

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import org.json.JSONObject
import java.io.Closeable
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Metadata/SDP/ICE coordination ONLY. There is deliberately no chat-send API. */
class SignedSignaling(
    private val identity: NativeIdentity,
    origin: String,
    private val ownerExecutor: Executor,
    private val onEvent: (JSONObject) -> Unit,
    private val onState: (Boolean, String?) -> Unit,
) : Closeable {
    private val origin = origin.toHttpUrl().also {
        require(it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.query == null &&
            it.fragment == null && it.encodedPath == "/") { "HTTPS origin required" }
    }
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).pingInterval(30, TimeUnit.SECONDS).build()
    private val lock = Any()
    private var socket: WebSocket? = null
    @Volatile var connected = false
        private set

    private fun requestBuilder(method: String, path: String, body: String): Request.Builder {
        require(path.startsWith("/v1/") && !path.contains('?') && !path.contains('#'))
        val time = System.currentTimeMillis().toString()
        val nonce = WireCodec.id()
        val signed = "cutie-pie/http/v1\n$method\n$path\n${identity.uid}\n$time\n$nonce\n${WireCodec.digest(body.toByteArray(Charsets.UTF_8))}"
        return Request.Builder().url(origin.newBuilder().encodedPath(path).build())
            .header("x-cutie-uid", identity.uid)
            .header("x-cutie-key", identity.public.getString("authKey"))
            .header("x-cutie-time", time).header("x-cutie-nonce", nonce)
            .header("x-cutie-signature", identity.sign(signed)).header("content-type", "application/json")
    }
    private fun metadata(method: String, path: String, body: JSONObject? = null): JSONObject {
        val encoded = body?.let(WireCodec::canonical) ?: ""
        require(encoded.toByteArray(Charsets.UTF_8).size <= 32768)
        val payload = if (method == "GET") null else encoded.toRequestBody("application/json".toMediaType())
        http.newCall(requestBuilder(method, path, encoded).method(method, payload).build()).execute().use { response ->
            val responseBody = checkNotNull(response.body)
            require(responseBody.contentLength() <= 65536) { "Response exceeds safe limit" }
            val collected = ByteArrayOutputStream()
            responseBody.byteStream().use { stream ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = stream.read(buffer)
                    if (count == -1) break
                    require(collected.size() + count <= 65536) { "Response exceeds safe limit" }
                    collected.write(buffer, 0, count)
                }
            }
            val bytes = collected.toByteArray()
            val json = WireCodec.parseObject(bytes.toString(Charsets.UTF_8), 65536)
            check(response.isSuccessful) { json.optString("error", "coordination_failed").take(80) }
            return json
        }
    }
    fun register(pushToken: String?, alertsEnabled: Boolean): JSONObject {
        require(pushToken == null || pushToken.length <= 4096)
        return metadata("POST", "/v1/device", JSONObject().put("bundle", identity.public)
            .put("pushToken", pushToken ?: JSONObject.NULL).put("alertsEnabled", alertsEnabled).put("platform", "android"))
    }
    fun acceptContact(peer: String) { validateUid(peer); metadata("PUT", "/v1/contacts/$peer") }
    fun lookupContact(number: String): JSONObject { require(number.matches(Regex("[0-9]{10}"))); return metadata("GET", "/v1/directory/$number") }
    fun requestContact(peer: String) { validateUid(peer); metadata("POST", "/v1/contact-requests", JSONObject().put("peer", peer)) }
    fun contactRequests() = metadata("GET", "/v1/contact-requests")
    fun respondContact(peer: String, action: String) { validateUid(peer); require(action in setOf("accept","decline","block")); metadata("PUT", "/v1/contact-requests/$peer", JSONObject().put("action", action)) }
    fun revokeContact(peer: String) { validateUid(peer); metadata("DELETE", "/v1/contacts/$peer") }
    fun presence(visible: Boolean, active: Boolean, foreground: Boolean) = metadata("PUT", "/v1/presence",
        JSONObject().put("visible", visible).put("active", active).put("foreground", foreground))
    fun ping(peer: String, pending: Boolean): JSONObject {
        validateUid(peer)
        return metadata("POST", "/v1/ping", JSONObject().put("peer", peer).put("requestId", WireCodec.id()).put("pending", pending))
    }
    fun connectContact(peer: String): JSONObject {
        validateUid(peer)
        return metadata("POST", "/v1/connect", JSONObject().put("peer", peer).put("requestId", WireCodec.id()))
    }
    fun chatOpen(peer: String): JSONObject { validateUid(peer); return metadata("POST","/v1/chat-open",JSONObject().put("peer",peer).put("requestId",WireCodec.id())) }
    fun connectionReady(peer:String,id:String) { validateUid(peer);validateRequest(id);metadata("POST","/v1/connection-ready",JSONObject().put("peer",peer).put("requestId",id)) }
    fun backgroundWindow(enabled: Boolean) { metadata("PUT","/v1/background-window",JSONObject().put("enabled",enabled)) }
    fun endPing(peer: String, requestId: String, cancelled: Boolean) {
        validateUid(peer); validateRequest(requestId)
        metadata("POST", "/v1/ping/end", JSONObject().put("peer", peer).put("requestId", requestId).put("cancelled", cancelled))
    }
    fun schedulePing(peer: String, at: Long): JSONObject {
        validateUid(peer); require(at > System.currentTimeMillis() && at <= System.currentTimeMillis() + 7L * 86400000)
        return metadata("POST", "/v1/ping/schedule", JSONObject().put("peer", peer).put("requestId", WireCodec.id()).put("at", at))
    }
    fun watchOnline(peer: String, enabled: Boolean) { validateUid(peer); metadata("PUT", "/v1/online-watch/$peer", JSONObject().put("enabled", enabled)) }
    fun readPresence(peer: String): JSONObject { validateUid(peer); return metadata("GET", "/v1/presence/$peer") }
    fun connect() = synchronized(lock) {
        if (socket != null) return@synchronized
        socket = http.newWebSocket(requestBuilder("GET", "/v1/socket", "").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(lock) {
                    if (socket !== webSocket) { webSocket.cancel(); return }
                    connected = true
                }
                ownerExecutor.execute { if (connected) onState(true, null) }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.toByteArray(Charsets.UTF_8).size > 32768) { webSocket.close(1009, "Frame exceeds limit"); return }
                val json = try { WireCodec.parseObject(text, 32768) } catch (_: Exception) { webSocket.close(1007, "Invalid metadata"); return }
                ownerExecutor.execute { if (synchronized(lock) { socket === webSocket }) onEvent(json) }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { webSocket.close(1003, "Text metadata only") }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { finished(webSocket, "socket_closed") }
            override fun onFailure(webSocket: WebSocket, failure: Throwable, response: Response?) { finished(webSocket, "socket_failed") }
        })
    }
    private fun finished(webSocket: WebSocket, error: String) {
        synchronized(lock) { if (socket !== webSocket) return; socket = null; connected = false }
        ownerExecutor.execute { onState(false, error) }
    }
    fun sendSetup(peer: JSONObject, requestId: String, purpose: String, setup: JSONObject) {
        validateRequest(requestId); validateSetup(purpose, setup)
        val content = JSONObject().put("requestId", requestId).put("purpose", purpose).put("setup", setup)
        val frame = JSONObject().put("kind", "signal").put("peer", peer.getString("uid"))
            .put("requestId", requestId).put("purpose", purpose)
            .put("box", identity.seal(peer, WireCodec.canonical(content).toByteArray(Charsets.UTF_8)))
            .put("sender", identity.uid)
        val signature = identity.sign("cutie-pie/signaling/v1\n${WireCodec.canonical(frame)}")
        frame.remove("sender"); frame.put("signature", signature)
        val wire = WireCodec.canonical(frame)
        require(wire.length <= 26000)
        synchronized(lock) { check(connected && socket?.send(wire) == true) { "Signaling is offline" } }
    }
    fun openSetup(peer: JSONObject, frame: JSONObject): JSONObject {
        NativeIdentity.exact(frame, setOf("kind", "peer", "requestId", "purpose", "box", "signature", "sender"))
        require(frame.getString("kind") == "signal" && frame.getString("sender") == peer.getString("uid") && frame.getString("peer") == identity.uid)
        validateRequest(frame.getString("requestId"))
        val signed = JSONObject(frame.toString()).apply { remove("signature") }
        require(identity.verify(peer, "cutie-pie/signaling/v1\n${WireCodec.canonical(signed)}", frame.getString("signature")))
        val bytes = identity.openBox(frame.getString("box"))
        val content = try { WireCodec.parseObject(bytes.toString(Charsets.UTF_8), 24000) } finally { bytes.fill(0) }
        NativeIdentity.exact(content, setOf("requestId", "purpose", "setup"))
        require(content.getString("requestId") == frame.getString("requestId") && content.getString("purpose") == frame.getString("purpose"))
        return content.getJSONObject("setup").also { validateSetup(content.getString("purpose"), it) }
    }
    fun disconnect() { synchronized(lock) { val old = socket; socket = null; connected = false; old?.close(1000, "Window ended") } }
    override fun close() { disconnect(); http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    companion object {
        private fun validateUid(uid: String) { require(uid.matches(Regex("[a-f0-9]{64}"))) }
        private fun validateRequest(id: String) { require(id.matches(Regex("[A-Za-z0-9_-]{16,128}"))) }
        private fun validateSetup(purpose: String, setup: JSONObject) {
            if (purpose == "ice") {
                NativeIdentity.exact(setup, setOf("candidate", "sdpMid", "sdpMLineIndex"))
                require(setup.getString("candidate").length in 1..4096)
                require(setup.isNull("sdpMid") || setup.get("sdpMid") is String)
                require(setup.isNull("sdpMLineIndex") || setup.get("sdpMLineIndex") is Int)
            } else {
                require(purpose == "offer" || purpose == "answer")
                NativeIdentity.exact(setup, setOf("type", "sdp"))
                require(setup.getString("type") == purpose && setup.getString("sdp").length in 1..15999)
            }
        }
    }
}
