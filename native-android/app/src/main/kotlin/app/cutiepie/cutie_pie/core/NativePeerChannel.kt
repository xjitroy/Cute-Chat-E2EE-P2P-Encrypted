package app.cutiepie.cutie_pie.core

import android.os.SystemClock
import org.json.JSONObject
import org.webrtc.*
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** One bounded, direct STUN-only transport. OPEN is not proof of peer authentication. */
class NativePeerChannel(
    private val factory: PeerConnectionFactory,
    private val signaling: SignedSignaling,
    private val remote: JSONObject,
    override val requestId: String,
    private val initiator: Boolean,
    private val owner: ScheduledExecutorService,
    private val onOpen: () -> Unit,
    private val onFrame: (String) -> Unit,
    private val onWritable: () -> Unit,
    private val onClosed: () -> Unit,
) : FileStreamLink {
    private var peer: PeerConnection? = null
    @Volatile private var channel: DataChannel? = null
    @Volatile private var closed = false
    @Volatile override var onBinary:((String,ByteArray)->Unit)?=null
    private val bulkLock=java.lang.Object()
    private val receiveSlots=java.util.concurrent.Semaphore(8)
    private val textSlots=java.util.concurrent.Semaphore(32)
    private val bulkReader=java.util.concurrent.Executors.newSingleThreadExecutor()
    private var opened = false
    private var remoteSet = false
    private var applyingRemote = false
    private val pendingIce = ArrayDeque<IceCandidate>()
    private val pendingSetups = ArrayDeque<Pair<String, JSONObject>>()
    private var retry: ScheduledFuture<*>? = null
    private val deadline = SystemClock.elapsedRealtime() + 60000
    override val ready: Boolean get() = !closed && channel?.state() == DataChannel.State.OPEN
    init { ProtocolRules.id(requestId) }
    private fun dispatch(block: () -> Unit) { owner.execute { if (!closed) block() } }
    override fun start() {
        check(!closed && peer == null)
        val config = PeerConnection.RTCConfiguration(listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        )).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        peer = checkNotNull(factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (state == PeerConnection.IceConnectionState.FAILED || state == PeerConnection.IceConnectionState.CLOSED) dispatch { close() }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(candidate: IceCandidate) = dispatch {
                if (candidate.sdp.isNotEmpty()) sendSetup("ice", JSONObject().put("candidate", candidate.sdp)
                    .put("sdpMid", candidate.sdpMid ?: JSONObject.NULL).put("sdpMLineIndex", candidate.sdpMLineIndex))
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onRenegotiationNeeded() {}
            override fun onDataChannel(data: DataChannel) = dispatch {
                if (initiator || channel != null || data.label() != LABEL) { data.close(); data.dispose() }
                else attach(data)
            }
        }))
        if (initiator) {
            attach(checkNotNull(peer!!.createDataChannel(LABEL, DataChannel.Init().apply { ordered = true })))
            peer!!.createOffer(observer(onCreated = { setLocal(it) }), MediaConstraints())
        }
        retry = owner.scheduleWithFixedDelay({
            if (!closed && !ready) {
                if (SystemClock.elapsedRealtime() >= deadline) close()
                else if (!remoteSet || !initiator) sendLocal()
            }
        }, 2, 2, TimeUnit.SECONDS)
    }
    private fun attach(data: DataChannel) {
        channel = data
        data.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previous: Long) { synchronized(bulkLock) { bulkLock.notifyAll() };dispatch { if (ready) onWritable() } }
            override fun onStateChange() = dispatch {
                observeState(data)
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                // WebRTC owns/reuses its native ByteBuffer after this callback returns.
                val slots=if(buffer.binary) receiveSlots else textSlots
                if (buffer.data.remaining() > 40000 || !slots.tryAcquire()) { dispatch { close() }; return }
                val copy = ByteArray(buffer.data.remaining()); buffer.data.get(copy)
                if(buffer.binary) {
                    try { bulkReader.execute {
                        try {
                            check(!closed && copy.size in (25+LargeFileRules.OVERHEAD)..(25+LargeFileRules.CHUNK+LargeFileRules.OVERHEAD) && copy[0]==0.toByte())
                            val cipher=copy.copyOfRange(25,copy.size)
                            try { (onBinary ?: error("Unaccepted stream"))(WireCodec.b64(copy.copyOfRange(1,25)),cipher) } finally { cipher.fill(0) }
                        } catch(_:Exception) { dispatch { close() } } finally { copy.fill(0);slots.release() }
                    } } catch(_:java.util.concurrent.RejectedExecutionException) { copy.fill(0);slots.release() }
                } else dispatch { try { onFrame(copy.toString(Charsets.UTF_8)) } finally { copy.fill(0);slots.release() } }
            }
        })
        // The native channel can already be OPEN by the time the owner executor
        // attaches its observer. A state callback is not replayed on registration.
        // Start the authenticated hello once even when that callback was missed.
        observeState(data)
    }
    private fun observeState(data: DataChannel) {
        if (data !== channel || closed) return
        when (data.state()) {
            DataChannel.State.OPEN -> {
                retry?.cancel(false)
                if (!opened) { opened = true; onOpen() }
                onWritable()
            }
            DataChannel.State.CLOSED -> close()
            else -> Unit
        }
    }
    override fun receiveSetup(purpose: String, setup: JSONObject) {
        check(!closed && peer != null)
        if (purpose == "ice") {
            val candidate = IceCandidate(if (setup.isNull("sdpMid")) null else setup.getString("sdpMid"),
                if (setup.isNull("sdpMLineIndex")) 0 else setup.getInt("sdpMLineIndex"), setup.getString("candidate"))
            if (remoteSet) peer!!.addIceCandidate(candidate)
            else { require(pendingIce.size < 128); pendingIce.add(candidate) }
            return
        }
        require(purpose == "offer" || purpose == "answer")
        require((initiator && purpose == "answer") || (!initiator && purpose == "offer"))
        if (remoteSet) { if (!initiator) sendLocal(); return }
        if (applyingRemote) {
            require(pendingSetups.size < 8)
            // Repeated descriptions cannot become an unbounded native callback queue.
            if (pendingSetups.none { it.first == purpose && it.second.getString("sdp") == setup.getString("sdp") }) pendingSetups.add(purpose to setup)
            return
        }
        applyingRemote = true
        val description = SessionDescription(SessionDescription.Type.fromCanonicalForm(purpose), setup.getString("sdp"))
        peer!!.setRemoteDescription(observer(onSet = {
            applyingRemote = false; remoteSet = true
            while (pendingIce.isNotEmpty()) peer!!.addIceCandidate(pendingIce.removeFirst())
            pendingSetups.clear()
            if (!initiator) peer!!.createAnswer(observer(onCreated = { setLocal(it) }), MediaConstraints())
        }), description)
    }
    private fun setLocal(description: SessionDescription) {
        peer!!.setLocalDescription(observer(onSet = { sendLocal() }), description)
    }
    private fun sendLocal() {
        val description = peer?.localDescription ?: return
        sendSetup(description.type.canonicalForm(), JSONObject().put("type", description.type.canonicalForm()).put("sdp", description.description))
    }
    private fun sendSetup(purpose: String, setup: JSONObject) {
        // A temporary coordination outage is retried; it must not destroy a live channel.
        if (!signaling.connected) return
        try { signaling.sendSetup(remote, requestId, purpose, setup) }
        catch (_: IllegalStateException) { /* socket changed while a setup was being queued */ }
    }
    private fun observer(onCreated: (SessionDescription) -> Unit = {}, onSet: () -> Unit = {}) = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = dispatch { onCreated(description) }
        override fun onSetSuccess() = dispatch(onSet)
        override fun onCreateFailure(error: String) = dispatch { close() }
        override fun onSetFailure(error: String) = dispatch { close() }
    }
    override fun send(frame:String,urgent:Boolean):Boolean = synchronized(bulkLock) {
        check(ready);val bytes=frame.toByteArray(Charsets.UTF_8);require(bytes.size<=40000)
        val current=checkNotNull(channel)
        if(current.bufferedAmount()+bytes.size>256*1024) false else current.send(DataChannel.Buffer(ByteBuffer.wrap(bytes),false))
    }
    override fun sendBinary(id:String,cipher:ByteArray,alive:()->Boolean):Boolean {
        require(cipher.size in LargeFileRules.OVERHEAD..LargeFileRules.CHUNK+LargeFileRules.OVERHEAD)
        val bytes=ByteArray(25+cipher.size);WireCodec.key(id,24).copyInto(bytes,1);cipher.copyInto(bytes,25)
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
        try { return synchronized(bulkLock) {
            while(ready && alive() && (channel?.bufferedAmount() ?: Long.MAX_VALUE)+bytes.size>192*1024 && System.nanoTime()<deadline) bulkLock.wait(50)
            if(!ready || !alive() || (channel?.bufferedAmount() ?: Long.MAX_VALUE)+bytes.size>192*1024) false
            else checkNotNull(channel).send(DataChannel.Buffer(ByteBuffer.wrap(bytes),true))
        } } finally { bytes.fill(0) }
    }
    override fun close() {
        if (closed) return
        closed = true; retry?.cancel(false); retry = null
        val data = synchronized(bulkLock) { val old=channel;channel=null;bulkLock.notifyAll();old }; val connection = peer; peer = null
        bulkReader.shutdown()
        pendingIce.clear(); pendingSetups.clear()
        try { data?.unregisterObserver(); data?.close(); data?.dispose() }
        finally { try { connection?.close(); connection?.dispose() } finally { onClosed() } }
    }
    companion object { const val LABEL = "cutie-pie-e2ee-v1" }
}
