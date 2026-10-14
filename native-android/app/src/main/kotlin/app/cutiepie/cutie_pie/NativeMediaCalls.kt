package app.cutiepie.cutie_pie

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import app.cutiepie.cutie_pie.core.VideoQualityPolicy
import app.cutiepie.cutie_pie.core.VideoCompressionPolicy
import app.cutiepie.cutie_pie.core.VideoDiagnostics
import app.cutiepie.cutie_pie.core.CallEntryAccess
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.PowerManager
import app.cutiepie.cutie_pie.core.NativeOwner
import app.cutiepie.cutie_pie.core.WireCodec
import app.cutiepie.cutie_pie.core.CallAudioFocusRetry
import app.cutiepie.cutie_pie.core.CallTimingPolicy
import org.json.JSONObject
import org.webrtc.*
import java.util.concurrent.TimeUnit

/** Genuine call media only; offer/answer/fingerprints/ICE travel inside Signal messages. */
class NativeMediaCalls(private val app: CutieApplication, private val owner: NativeOwner,
    private val presentation: Presentation = PlatformPresentation(app)) {
    /** Platform presentation is replaceable for isolated media/Signal integration QA. */
    interface Presentation {
        fun sync(raw: Map<String, Any>)
        fun prepareAudio(id: String): Boolean
        fun finish(id: String)
    }
    private class PlatformPresentation(private val app: CutieApplication) : Presentation {
        override fun sync(raw: Map<String, Any>) = NativeCalls.sync(app, raw)
        override fun prepareAudio(id: String) = NativeCalls.prepareMedia(id)
        override fun finish(id: String) = NativeCalls.finish(id)
    }
    val callEntry = CallEntryAccess()
    private fun captureAllowed()=callEntry.permits(id,owner.foreground,owner.viewUnlocked)
    val egl = EglBase.create()
    private val main = Handler(Looper.getMainLooper())
    @Volatile var id = ""; private set
    @Volatile var peer = ""; private set
    @Volatile var status = "idle"; private set
    @Volatile var video = false; private set
    @Volatile var muted = false; private set
    @Volatile var cameraOff = false; private set
    @Volatile var screenSharing = false; private set
    @Volatile var screenDisplayWidth=1; private set
    @Volatile var screenDisplayHeight=1; private set
    @Volatile var screenRotation=0; private set
    private var screenEncodedWidth=0
    private var screenEncodedHeight=0
    @Volatile var speaker = false; private set
    @Volatile var connectedAt = 0L; private set
    @Volatile var remoteRinging = false; private set
    @Volatile var quality = "Measuring network"; private set
    @Volatile var captureQuality = ""; private set
    @Volatile var localVideo: VideoTrack? = null; private set
    @Volatile var remoteVideo: VideoTrack? = null; private set
    private var factory: PeerConnectionFactory? = null
    private var rtc: PeerConnection? = null
    private var bluetoothCall=false
    private var bluetoothSpeech:NativeBluetoothSpeech?=null
    private var bluetoothSequence=0L
    private var bluetoothReceived=-1L
    private var bluetoothAudioAt=0L
    private val bluetoothQueued=java.util.concurrent.atomic.AtomicInteger(0)
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var audioDevice: org.webrtc.audio.JavaAudioDeviceModule? = null
    private var videoSource: VideoSource? = null
    private var cameraVideo: VideoTrack? = null
    private var videoSender: RtpSender? = null
    private var capturer: CameraVideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var screenSource: VideoSource? = null
    private var screenTrack: VideoTrack? = null
    private var screenHelper: SurfaceTextureHelper? = null
    @Volatile var videoPresets: List<VideoQualityPolicy.Preset> = emptyList(); private set
    @Volatile var requestedVideoPreset = VideoQualityPolicy.defaults; private set
    @Volatile var videoProtection = false; private set
    @Volatile var videoNotice = ""; private set
    @Volatile var compressionMode = VideoCompressionPolicy.mode(owner.preferences.getString("videoCompression",null)); private set
    private val videoDiagnostics = VideoDiagnostics()
    private val detailGate = VideoCompressionPolicy.DetailGate()
    private var negotiatedVideoCodec = ""
    private var lastMediaStatsAt = 0L
    private data class CodecFit(val width:Int,val height:Int,val fps:Int,val codec:String?,val encoder:Boolean)
    private val codecFits=mutableMapOf<CodecFit,Boolean>()
    private var cameraEnumerator: CameraEnumerator? = null
    private var cameraName = ""
    private var cameraFormats: List<VideoQualityPolicy.Format> = emptyList()
    private var recoveredAt = 0L
    private var captureWidth = 640
    private var captureHeight = 480
    private var captureFps = 24
    private var offer: JSONObject? = null
    private val incomingIce = ArrayDeque<IceCandidate>()
    private val outgoingIce = ArrayDeque<JSONObject>()
    private var remoteSet = false
    private var descriptionSent = false
    private var pendingDescription: Pair<String,JSONObject>? = null
    private fun drainCallSignals() {
        if(!active || peer !in owner.connected) return
        try {
            pendingDescription?.let { (kind,value) -> owner.sendControl(peer,kind,value); pendingDescription=null; descriptionSent=true }
            if(descriptionSent) while(outgoingIce.isNotEmpty()) {
                owner.sendControl(peer,"call_ice",outgoingIce.first()); outgoingIce.removeFirst()
            }
        } catch (_: IllegalStateException) { /* Connection/outbox pressure: retain bounded controls until the stage deadline. */ }
        catch (_: Exception) { failed("encrypted_call_control_failed") }
    }
    private var deadline = 0L
    private var deadlineTask: java.util.concurrent.ScheduledFuture<*>? = null
    private var incoming = false
    private var historyRecorded = false
    private var offerExpiresAt = 0L
    private var ringConfirmedSent = false
    private var ringCpuLock: PowerManager.WakeLock? = null
    private var pendingPreparation = ""
    private var preparationStage = "idle"
    private val audioRetry=CallAudioFocusRetry()
    private var audioFocusReady=false
    private class AudioFocusPending(val delay:Long):Exception()
    @Volatile var lastFailure = ""; private set
    @Volatile var failureRevision = 0L; private set
    private fun failed(code: String) {
        lastFailure = "$preparationStage · $code"; failureRevision++
        owner.preferences.edit().putString("callFailure",lastFailure).apply()
        end(outcome="failed")
    }
    private fun preparePending() {
        val kind=pendingPreparation
        if(kind.isEmpty() || !active || !captureAllowed() || peer !in owner.connected) return
        if(audioOwned && !delegatedFocus && !audioFocusReady && audioRetry.delay(id,SystemClock.elapsedRealtime())>0) return
        pendingPreparation=""
        try {
            if(owner.transport(peer)=="bluetooth") {
                check(!video) { "Bluetooth supports audio calls only" };bluetoothCall=true;media()
                val data=JSONObject().put("callId",id).put("codec","PCMU").put("rate",8000)
                if(kind=="call_offer") {
                    offerExpiresAt=System.currentTimeMillis()+CallTimingPolicy.RING_MS;data.put("expiresAt",offerExpiresAt)
                    status="calling";setDeadline(CallTimingPolicy.RING_MS);pendingDescription="bt_offer" to data
                } else { pendingDescription="bt_answer" to data;startBluetoothSpeech() }
                drainCallSignals();changed();return
            }
            media(); val token=id
            if(kind == "call_offer") rtc!!.createOffer(observer(token,created={ local(it,"call_offer") }),MediaConstraints())
            else setRemote(SessionDescription.Type.OFFER,checkNotNull(offer).getString("sdp")) {
                rtc!!.createAnswer(observer(token,created={ local(it,"call_answer") }),MediaConstraints())
            }
        } catch (failure: Exception) {
            if(failure is AudioFocusPending && active) {
                pendingPreparation=kind;preparationStage="audio_focus_wait";val token=id
                owner.executor.schedule({ if(id==token && active) preparePending() },failure.delay,TimeUnit.MILLISECONDS)
                changed();return
            }
            failed(when(failure) {
            is SecurityException -> "android_permission_or_foreground_denied"
            is java.util.concurrent.TimeoutException -> "android_service_not_ready"
            else -> "media_initialization_${failure.javaClass.simpleName}"
        }) }
    }
    private fun releaseRingCpu() {
        safely { ringCpuLock?.let { if(it.isHeld) it.release() } }; ringCpuLock=null
    }
    private fun setDeadline(delay: Long) {
        deadlineTask?.cancel(false); deadlineTask = null
        deadline = if(delay > 0) SystemClock.elapsedRealtime() + delay else 0
        if(delay > 0) { val token = id; deadlineTask = owner.executor.schedule({
            if(id == token && active && deadline > 0 && SystemClock.elapsedRealtime() >= deadline)
                end(outcome=CallTimingPolicy.timeout(incoming,status))
        },delay,TimeUnit.MILLISECONDS) }
    }
    private fun reachedPeer() {
        if(!historyRecorded) { historyRecorded=true; owner.recordCallStart(id,peer,incoming,video) }
    }
    private fun confirmIncomingRing() {
        if(active && incoming && status == "incoming" && !ringConfirmedSent && owner.supportsCallRing(peer)) {
            try { owner.sendCallRing(peer,id,offerExpiresAt); ringConfirmedSent = true } catch (_: Exception) { /* Bounded outbox pressure is retried; never tear down a valid inbound frame. */ }
        }
    }
    private var sequence = 0L
    private val audio by lazy { app.getSystemService(AudioManager::class.java) }
    private var focus: AudioFocusRequest? = null
    private var focusEpoch = 0L
    private var delegatedFocus = false
    private var priorMode = AudioManager.MODE_NORMAL
    private var priorSpeaker = false
    private var audioOwned = false
    @Volatile var audioInterrupted = false; private set
    private var legacyFocus: AudioManager.OnAudioFocusChangeListener? = null
    private var priorDevice: android.media.AudioDeviceInfo? = null
    private var deviceCallback: AudioDeviceCallback? = null
    private val power by lazy { app.getSystemService(PowerManager::class.java) }
    private var proximityLock: PowerManager.WakeLock? = null
    val active get() = id.isNotEmpty()
    var onChanged: (() -> Unit)? = null
    private fun dispatch(token: String, block: () -> Unit) = owner.executor.execute { if (id == token && active) try { block() } catch (failure: Exception) { failed("callback_${failure.javaClass.simpleName}") } }
    init {
        owner.onCallControl = ::receive
        owner.onBluetoothAudio={ from,data ->
            if(bluetoothCall && active && peer==from && data.getString("callId")==id && status=="connected") {
                val seq=data.getLong("seq")
                if(seq>bluetoothReceived) { bluetoothReceived=seq;bluetoothAudioAt=SystemClock.elapsedRealtime();bluetoothSpeech?.receive(WireCodec.decode(data.getString("bytes"),800)) }
            }
        }
        owner.onCallPeerReady = { from -> if(peer == from) { confirmIncomingRing(); preparePending(); drainCallSignals() } }
        owner.callActiveForSharing={ active }
        owner.onContactBlocked = { blocked -> if (active && peer == blocked) end() }
        owner.executor.scheduleWithFixedDelay({
            if (!active && (rtc != null || audioSource != null || audioTrack != null || audioDevice != null || capturer != null)) releaseOwnedMedia()
            if (active) {
                preparePending()
                if(bluetoothCall && status=="connected" && (peer !in owner.connected || SystemClock.elapsedRealtime()-bluetoothAudioAt>10_000)) { failed("bluetooth_audio_link_lost");return@scheduleWithFixedDelay }
                if(video) refreshRemoteVideo()
                if(video && capturer != null) updateVideoProtection()
                if(screenSharing) updateScreenGeometry()
                drainCallSignals()
                if(status == "incoming") confirmIncomingRing()
                if (deadline > 0 && SystemClock.elapsedRealtime() >= deadline) end(outcome = CallTimingPolicy.timeout(incoming,status))
                else { val token = id; routeSpeaker()
                    if(lastMediaStatsAt>0 && SystemClock.elapsedRealtime()-lastMediaStatsAt>9_000) quality="Network / video statistics: waiting for fresh measurements"
                    rtc?.getStats { report -> dispatch(token) {
                    // Diagnostics/adaptation failures must never end an otherwise healthy call.
                    try {
                        val snapshot=videoDiagnostics.read(report.timestampUs,report.statsMap.values.map {
                            VideoDiagnostics.Stat(it.id,it.type,it.timestampUs,it.members)
                        }) ?: return@dispatch
                        lastMediaStatsAt=SystemClock.elapsedRealtime()
                        val observedCodec=snapshot.sent?.codec.orEmpty()
                        if(observedCodec.isNotEmpty() && observedCodec!=negotiatedVideoCodec) {
                            detailGate.reset(); negotiatedVideoCodec=observedCodec; refreshVideoPresets()
                            if(!screenSharing) applyVideoPreset()
                        }
                        val preset=VideoQualityPolicy.Preset(captureWidth,captureHeight,captureFps)
                        detailGate.observe(SystemClock.elapsedRealtime(),snapshot.bandwidth,snapshot.rtt,snapshot.loss,
                            snapshot.limitation,VideoCompressionPolicy.detailFloor(preset,negotiatedVideoCodec,screenSharing),videoProtection)
                        configureVideoSender()
                        val network=if(snapshot.rtt==null) "Network: measuring" else "Network RTT: ${(snapshot.rtt*1000).toInt()} ms"
                        fun stream(label:String,s:VideoDiagnostics.Stream?):String {
                            if(s==null) return "$label: measuring"
                            val fps=s.fps?.toInt()?.let { "${it}fps" } ?: "fps measuring"
                            val rate=s.bitrate?.let { " · %.2f Mbps".format(java.util.Locale.ROOT,it/1_000_000.0) }.orEmpty()
                            return "$label ${s.width}×${s.height} · $fps ${s.codec}$rate"
                        }
                        quality=if(captureQuality.isEmpty()) network else "$network · Capture $captureQuality\n"+
                            stream("Sent",snapshot.sent)+"\n"+stream("Received",snapshot.received)+
                            if(videoNotice.isEmpty()) "" else "\n$videoNotice"
                        changed()
                    } catch (_: Exception) { detailGate.reset(); configureVideoSender(); quality="Network / video statistics temporarily unavailable"; changed() }
                } } }
            }
        }, 3, 3, TimeUnit.SECONDS)
    }
    private fun changed() {
        val token = id; val stage = status; val isVideo = video
        val name = owner.contacts.find { it.uid == peer }?.name ?: "Private caller"
        val seq = ++sequence
        main.post {
            if (id != token) return@post
            if (token.isEmpty()) Unit
            else presentation.sync(mapOf("bridge" to app.callBridge, "sequence" to seq, "id" to token,
                "name" to name, "video" to isVideo, "status" to stage, "alerts" to owner.preferences.getBoolean("alerts", true),
                "ringback" to CallTimingPolicy.ringback(stage,remoteRinging), "ringRemainingMs" to (deadline-SystemClock.elapsedRealtime()).coerceIn(0,CallTimingPolicy.RING_MS),
                "background" to !owner.foreground, "private" to !owner.preferences.getBoolean("preview", false)))
            onChanged?.invoke()
        }
    }
    fun start(to: String, withVideo: Boolean) {
        check(!owner.wifiHandoff.setupPending) { "Finish or cancel nearby Wi-Fi setup before calling" }
        check(!active && owner.foreground && owner.contacts.any { it.uid == to })
        check(!withVideo || owner.transport(to)!="bluetooth") { "Use Wi-Fi for video calls" }
        bluetoothCall=false;bluetoothSequence=0;bluetoothReceived=-1
        peer = to; id = WireCodec.id(); video = withVideo; status = "preparing"
        incoming=false; historyRecorded=false; remoteRinging=false; ringConfirmedSent=false
        lastFailure=""; preparationStage="waiting_for_authenticated_peer"; pendingPreparation="call_offer"
        setDeadline(CallTimingPolicy.CONNECT_MS); changed()
        owner.wakeForActivity(to); preparePending()
    }
    fun receive(from: String, kind: String, data: JSONObject) {
        val token = data.getString("callId")
        if (kind == "call_offer" || kind=="bt_offer") {
            val expires = data.getLong("expiresAt"); val now = System.currentTimeMillis()
            if (from !in owner.connected || expires <= now || expires > now + 90000) return
            if(owner.wifiHandoff.setupPending) { owner.sendControl(from,"call_end",JSONObject().put("callId",token));return }
            if (active) { if (id != token || peer != from) owner.sendControl(from, "call_end", JSONObject().put("callId", token)); return }
            if((kind=="bt_offer" && owner.transport(from)!="bluetooth") || (kind=="call_offer" && owner.transport(from)=="bluetooth")) return
            bluetoothCall=kind=="bt_offer";bluetoothSequence=0;bluetoothReceived=-1
            peer = from; id = token; video = if(bluetoothCall) false else data.getBoolean("video"); offer = JSONObject(data.toString())
            incoming=true; historyRecorded=false; remoteRinging=false; ringConfirmedSent=false; offerExpiresAt=expires
            reachedPeer(); status = "incoming"
            val budget=CallTimingPolicy.ringBudget(expires,now)
            owner.ringLease(SystemClock.elapsedRealtime()+budget)
            releaseRingCpu()
            safely { ringCpuLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"CuteChat:IncomingRingWindow").apply { setReferenceCounted(false); acquire(budget+2000) } }
            setDeadline(budget); changed(); confirmIncomingRing(); return
        }
        if (id != token || peer != from) return
        when (kind) {
            "call_ring" -> if(status == "calling" && !incoming && data.getLong("expiresAt") == offerExpiresAt && offerExpiresAt > System.currentTimeMillis()) {
                reachedPeer(); remoteRinging=true; changed()
            }
            "call_end" -> end(false)
            "bt_answer" -> if(bluetoothCall && status=="calling" && owner.transport(from)=="bluetooth") {
                reachedPeer();remoteRinging=false;startBluetoothSpeech()
            }
            "call_answer" -> if (!bluetoothCall && status == "calling") {
                reachedPeer(); remoteRinging=false; status = "connecting"; setDeadline(CallTimingPolicy.CONNECT_MS); changed()
                try { setRemote(SessionDescription.Type.ANSWER, data.getString("sdp")) {} } catch (_: Exception) { failed("sdp_remote_failed") }
            }
            "call_ice" -> {
                val candidate = IceCandidate(if (data.isNull("sdpMid")) null else data.getString("sdpMid"),
                    if (data.isNull("sdpMLineIndex")) 0 else data.getInt("sdpMLineIndex"), data.getString("candidate"))
                if (remoteSet) try { rtc?.addIceCandidate(candidate) } catch (_: Exception) { /* Other candidates may still work. */ }
                else if(incomingIce.size < 128) incomingIce.add(candidate)
            }
        }
    }
    fun answer() {
        check(status == "incoming" && captureAllowed() && peer in owner.connected)
        checkNotNull(offer); status = "connecting"
        releaseRingCpu()
        owner.ringLease(0)
        owner.callLease(true,peer)
        // Accepting near the end of the ringing timeout must still allow ICE to connect.
        setDeadline(CallTimingPolicy.CONNECT_MS); changed()
        lastFailure=""; pendingPreparation="call_answer"; preparePending()
    }
    private fun media() {
        check(app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        if (video) check(app.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        check(captureAllowed())
        // Stop ringtone and activate the self-managed Telecom connection BEFORE
        // acquiring media focus. Telecom then owns focus; a second independent
        // request would be revoked as Android changes the call to active.
        val token=id
        if(!audioOwned) {
        preparationStage="android_presentation"
        val telecomOwnsAudio = try { presentation.prepareAudio(id) } catch (_: Exception) { false }
        // Must be started while visible after permission grant, before capture begins.
        owner.callLease(true, peer)
        preparationStage="android_foreground_service"
        ConnectionService.startAndAwait(app, if (video) 1 else 2)
        check(captureAllowed() || callEntry.confirmed(id))
        priorMode = audio.mode; priorSpeaker = audio.isSpeakerphoneOn; audioOwned = true
        if (Build.VERSION.SDK_INT >= 31) priorDevice = audio.communicationDevice
        delegatedFocus = telecomOwnsAudio;audioFocusReady=telecomOwnsAudio;audioRetry.begin(id,SystemClock.elapsedRealtime())
        val focusGeneration = ++focusEpoch
        val focusToken = id
        val listener = AudioManager.OnAudioFocusChangeListener { change -> dispatch(focusToken) {
            if (delegatedFocus || focusEpoch != focusGeneration) return@dispatch
            // Transient interruptions (including Telecom's answer transition) are
            // not a remote hangup. Suspend the microphone until focus returns.
            audioInterrupted = change != AudioManager.AUDIOFOCUS_GAIN
            audioTrack?.setEnabled(!muted && !audioInterrupted)
            bluetoothSpeech?.muted=muted || audioInterrupted
            if (!audioInterrupted) routeSpeaker()
            updateProximity()
            changed()
        } }
        if (!telecomOwnsAudio) {
            preparationStage="audio_focus"
            if (Build.VERSION.SDK_INT >= 26) {
                focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener(listener,main).build()
            } else legacyFocus = listener
        }
        }
        if(!delegatedFocus && !audioFocusReady) {
            preparationStage="audio_focus"
            check(audioRetry.delay(id,SystemClock.elapsedRealtime())>=0) { "Android audio remained busy" }
            val result=if(Build.VERSION.SDK_INT>=26) audio.requestAudioFocus(checkNotNull(focus)) else audio.requestAudioFocus(checkNotNull(legacyFocus),AudioManager.STREAM_VOICE_CALL,AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            if(result!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                val retry=audioRetry.denied(id,SystemClock.elapsedRealtime())
                if(retry<0) error("Android audio is busy; no microphone capture started")
                throw AudioFocusPending(retry)
            }
            audioFocusReady=true;audioInterrupted=false
        }
        audio.mode = AudioManager.MODE_IN_COMMUNICATION; speaker = video; routeSpeaker(); updateProximity()
        if (Build.VERSION.SDK_INT >= 23) {
            val callback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = dispatch(token) { routeSpeaker(); updateProximity() }
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = dispatch(token) { routeSpeaker(); updateProximity() }
            }
            deviceCallback = callback; audio.registerAudioDeviceCallback(callback, main)
        }
        if(bluetoothCall) { preparationStage="bluetooth_speech";return }
        preparationStage="webrtc_audio"
        audioDevice = org.webrtc.audio.JavaAudioDeviceModule.builder(app).createAudioDeviceModule()
        val options=PeerConnectionFactory.Options().apply {
            if(owner.transport(peer)=="wifi") {
                networkIgnoreMask=PeerConnectionFactory.Options.ADAPTER_TYPE_CELLULAR or PeerConnectionFactory.Options.ADAPTER_TYPE_VPN
                // Hotspot/AP interfaces may have no Android Network handle: enumerate host interfaces there.
                disableNetworkMonitor=owner.unmanagedLocalNetwork(peer)
            }
            disableEncryption=false
        }
        factory = PeerConnectionFactory.builder().setOptions(options).setAudioDeviceModule(audioDevice).setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext)).createPeerConnectionFactory()
        // Same-LAN calls need host ICE only; Internet/STUN availability is not a prerequisite.
        val iceServers=if(owner.transport(peer)=="wifi") emptyList() else listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer())
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy=PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        rtc = checkNotNull(factory!!.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = dispatch(token) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                        reachedPeer(); status = "connected"; remoteRinging=false; if (connectedAt == 0L) { connectedAt = SystemClock.elapsedRealtime(); owner.recordCallConnected(id) }; setDeadline(0); changed()
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> { status = "reconnecting"; setDeadline(20000); changed() }
                    PeerConnection.IceConnectionState.FAILED -> failed("media_ice_failed")
                    PeerConnection.IceConnectionState.CLOSED -> failed("media_ice_closed")
                    else -> Unit
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(candidate: IceCandidate) = dispatch(token) {
                val value = JSONObject().put("callId", token).put("candidate", candidate.sdp)
                    .put("sdpMid", candidate.sdpMid ?: JSONObject.NULL).put("sdpMLineIndex", candidate.sdpMLineIndex)
                if(outgoingIce.size < 128) outgoingIce.add(value)
                drainCallSignals()
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) { channel.close(); channel.dispose() }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = dispatch(token) {
                receivedVideo(receiver.track())
            }
            override fun onTrack(transceiver: RtpTransceiver) = dispatch(token) {
                receivedVideo(transceiver.receiver.track())
            }
            override fun onRemoveTrack(receiver: RtpReceiver) = dispatch(token) {
                if(remoteVideo===receiver.track()) { remoteVideo=null;changed() }
            }
        }))
        audioSource = factory!!.createAudioSource(MediaConstraints())
        audioTrack = factory!!.createAudioTrack("cutie-audio", audioSource!!).also { it.setEnabled(!muted && !audioInterrupted);rtc!!.addTrack(it, listOf("cutie-media")) }
        if (video) {
            preparationStage="camera_capture"
            val enumerator: CameraEnumerator = if (Camera2Enumerator.isSupported(app)) Camera2Enumerator(app) else Camera1Enumerator(false)
            val camera = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) } ?: enumerator.deviceNames.first()
            capturer = checkNotNull(enumerator.createCapturer(camera, null))
            helper = SurfaceTextureHelper.create("CutieCamera", egl.eglBaseContext)
            videoSource = factory!!.createVideoSource(false)
            capturer!!.initialize(helper, app, videoSource!!.capturerObserver)
            val cameraTrack = factory!!.createVideoTrack("cutie-video", videoSource!!)
            cameraVideo = cameraTrack; localVideo = cameraTrack
            videoSender = rtc!!.addTrack(cameraTrack, listOf("cutie-media"))
            cameraEnumerator=enumerator; cameraName=camera; refreshCameraFormats()
            updateVideoProtection(apply=false)
            val initial=VideoQualityPolicy.choose(requestedVideoPreset,cameraFormats,videoProtection || compressionMode==VideoCompressionPolicy.Mode.DATA_SAVER)
            captureWidth=initial.width; captureHeight=initial.height; captureFps=initial.fps
            videoSource!!.adaptOutputFormat(captureWidth,captureHeight,captureFps)
            capturer!!.startCapture(captureWidth,captureHeight,captureFps)
            captureQuality="${captureWidth}×${captureHeight} · ${captureFps}fps"
            configureVideoSender(); preferVideoCodecs()
        }
        updateProximity()
    }
    private fun receivedVideo(track: MediaStreamTrack?) {
        val next=track as? VideoTrack ?: return
        if(remoteVideo!==next) { remoteVideo=next;changed() }
    }
    private fun refreshRemoteVideo() {
        if(!active || !video || remoteVideo!=null) return
        rtc?.receivers?.forEach { receivedVideo(it.track()) }
    }
    private fun local(description: SessionDescription, kind: String) {
        preparationStage="encrypted_media_negotiation"
        val token = id
        rtc!!.setLocalDescription(observer(token, set = {
            configureVideoSender()
            val data = JSONObject().put("callId", token).put("sdp", description.description)
            if (kind == "call_offer") { offerExpiresAt=System.currentTimeMillis()+CallTimingPolicy.RING_MS; data.put("video", video).put("expiresAt",offerExpiresAt); status = "calling"; setDeadline(CallTimingPolicy.RING_MS) }
            pendingDescription=kind to data; drainCallSignals()
            changed()
        }), description)
    }
    private fun setRemote(type: SessionDescription.Type, sdp: String, then: () -> Unit) {
        rtc!!.setRemoteDescription(observer(id, set = {
            remoteSet = true; refreshRemoteVideo(); while (incomingIce.isNotEmpty()) try { rtc!!.addIceCandidate(incomingIce.removeFirst()) } catch (_: Exception) { }
            then()
        }), SessionDescription(type, sdp))
    }
    private fun observer(token: String, created: (SessionDescription) -> Unit = {}, set: () -> Unit = {}) = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = dispatch(token) { created(description) }
        override fun onSetSuccess() = dispatch(token, set)
        override fun onCreateFailure(error: String) = dispatch(token) { failed("sdp_create_failed") }
        override fun onSetFailure(error: String) = dispatch(token) { failed("sdp_set_failed") }
    }
    private fun startBluetoothSpeech() {
        check(bluetoothCall && active && captureAllowed() && owner.transport(peer)=="bluetooth")
        if(bluetoothSpeech!=null) return
        val token=id;val target=peer
        bluetoothAudioAt=SystemClock.elapsedRealtime();bluetoothReceived=-1
        status="connected";remoteRinging=false;connectedAt=SystemClock.elapsedRealtime();owner.recordCallConnected(id);setDeadline(0)
        quality="Bluetooth · Signal E2EE · PCMU8kHz speech · photo transfers pause during this call"
        val speech=NativeBluetoothSpeech({ bytes ->
            if(bluetoothQueued.incrementAndGet()>2) { bluetoothQueued.decrementAndGet();bytes.fill(0) }
            else owner.executor.execute { try {
                if(id==token && active && bluetoothCall && status=="connected") owner.sendBluetoothAudio(target,JSONObject().put("callId",token).put("seq",bluetoothSequence++).put("codec","PCMU").put("bytes",WireCodec.b64(bytes)))
            } catch(_:Exception) { if(id==token && active) failed("bluetooth_speech_send_failed") }
                finally { bytes.fill(0);bluetoothQueued.decrementAndGet() } }
        },{ dispatch(token) { failed("bluetooth_microphone_or_playback_failed") } })
        bluetoothSpeech=speech;speech.muted=muted || audioInterrupted
        try { speech.start();updateProximity();changed() } catch(t:Exception) { speech.close();bluetoothSpeech=null;throw t }
    }
    fun toggleMute() { muted = !muted; audioTrack?.setEnabled(!muted && !audioInterrupted); bluetoothSpeech?.muted=muted || audioInterrupted; changed() }
    /** Telecom can create its Connection after media's fallback focus request.
     * Transfer ownership instead of leaving the microphone paused by that loss. */
    fun adoptTelecomAudio(token: String) {
        if (id != token || !active || !audioOwned) return
        delegatedFocus = true; audioFocusReady=true;focusEpoch++
        if (Build.VERSION.SDK_INT >= 26) focus?.let { audio.abandonAudioFocusRequest(it) }
        else legacyFocus?.let { audio.abandonAudioFocus(it) }
        focus = null; legacyFocus = null
        audioInterrupted = false; audioTrack?.setEnabled(!muted); bluetoothSpeech?.muted=muted; routeSpeaker(); updateProximity(); changed()
    }
    fun resumeAudio() {
        if (!active || (rtc==null && !bluetoothCall) || (!owner.foreground && !callEntry.visibleFor(id))) return
        audio.mode = AudioManager.MODE_IN_COMMUNICATION; routeSpeaker(); updateProximity()
        if (!audioInterrupted) { audioTrack?.setEnabled(!muted); bluetoothSpeech?.muted=muted; return }
        if (delegatedFocus) { audioInterrupted = false; audioTrack?.setEnabled(!muted); bluetoothSpeech?.muted=muted; changed(); return }
        val granted = if (Build.VERSION.SDK_INT >= 26) focus?.let { audio.requestAudioFocus(it) }
            else legacyFocus?.let { audio.requestAudioFocus(it, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) }
        if (granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioInterrupted = false; audioTrack?.setEnabled(!muted); bluetoothSpeech?.muted=muted; changed()
        }
    }
    fun toggleCamera() { if (screenSharing) return; cameraOff = !cameraOff; cameraVideo?.setEnabled(!cameraOff); localVideo?.setEnabled(!cameraOff); changed() }
    fun switchCamera() {
        if(screenSharing) return
        val next=cameraEnumerator?.deviceNames?.firstOrNull { it!=cameraName } ?: return
        val token=id
        capturer?.switchCamera(object: CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(front: Boolean) = dispatch(token) { detailGate.reset(); cameraName=next; refreshCameraFormats(); applyVideoPreset(); changed() }
            override fun onCameraSwitchError(error: String) = dispatch(token) { videoNotice="Camera switch unavailable"; changed() }
        },next)
    }
    private fun refreshCameraFormats() {
        cameraFormats=cameraEnumerator?.getSupportedFormats(cameraName)?.map { VideoQualityPolicy.Format(it.width,it.height,it.framerate.max/1000) }.orEmpty()
        refreshVideoPresets()
    }
    private fun refreshVideoPresets() {
        videoPresets=VideoQualityPolicy.presets.filter { VideoQualityPolicy.supports(it,cameraFormats) &&
            (it.fps<=30 || (negotiatedVideoCodec.isNotEmpty() && hardwareRateSupported(it,negotiatedVideoCodec))) }
    }
    private fun hardwareRateSupported(preset: VideoQualityPolicy.Preset, codecName: String? = null) = hardwareCodecFit(preset,codecName,true)
    private fun hardwareCodecFit(preset: VideoQualityPolicy.Preset, codecName: String?, encoder:Boolean):Boolean {
        if(factory==null) return false
        val key=CodecFit(preset.width,preset.height,preset.fps,codecName,encoder)
        codecFits[key]?.let { return it }
        val fit=try {
            val capabilities=if(encoder) factory!!.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
                else factory!!.getRtpReceiverCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
            val available=capabilities.codecs.mapNotNull { VideoQualityPolicy.androidMime(it.name) }.toSet()
                .let { names -> if(codecName==null) names else names.intersect(setOfNotNull(VideoQualityPolicy.androidMime(codecName))) }
            android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).codecInfos.any { codec ->
                codec.isEncoder==encoder && (if(Build.VERSION.SDK_INT>=29) codec.isHardwareAccelerated else !codec.name.startsWith("OMX.google.") && !codec.name.startsWith("c2.android.")) &&
                    codec.supportedTypes.any { mime -> mime.lowercase(java.util.Locale.ROOT) in available && try {
                        codec.getCapabilitiesForType(mime).videoCapabilities?.areSizeAndRateSupported(preset.width,preset.height,preset.fps.toDouble())==true
                    } catch (_: Exception) { false } }
            }
        } catch (_: Exception) { false }
        if(codecFits.size>=64) codecFits.clear()
        codecFits[key]=fit
        return fit
    }
    fun selectVideoPreset(preset: VideoQualityPolicy.Preset) {
        if(!active || !video || screenSharing || !owner.foreground || !owner.viewUnlocked || preset !in videoPresets) return
        detailGate.reset(); requestedVideoPreset=preset; updateVideoProtection(apply=false); applyVideoPreset(); changed()
    }
    fun selectCompressionMode(mode: VideoCompressionPolicy.Mode) {
        if(!active || !video || !owner.foreground || !owner.viewUnlocked) return
        compressionMode=mode
        owner.preferences.edit().putString("videoCompression",mode.key).apply()
        detailGate.reset()
        if(!screenSharing) applyVideoPreset()
        configureVideoSender(); changed()
    }
    private fun updateVideoProtection(apply: Boolean = true) {
        val battery=app.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level=battery?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1) ?: -1
        val scale=battery?.getIntExtra(BatteryManager.EXTRA_SCALE,-1) ?: -1
        val percent=if(level>=0 && scale>0) (level.toLong()*100/scale).toInt() else -1
        val temperature=battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,0) ?: 0
        val thermal=if(Build.VERSION.SDK_INT>=29) power.currentThermalStatus else 0
        val was=videoProtection
        if(VideoQualityPolicy.protect(percent,thermal,temperature)) { videoProtection=true; recoveredAt=0 }
        else if(videoProtection && VideoQualityPolicy.recovery(percent,thermal,temperature)) {
            if(recoveredAt==0L) recoveredAt=SystemClock.elapsedRealtime()
            if(SystemClock.elapsedRealtime()-recoveredAt>=30_000) videoProtection=false
        } else recoveredAt=0
        videoNotice=if(videoProtection) "Battery / heat protection · up to 720p / 30fps" else ""
        if(apply) { if(was!=videoProtection && !screenSharing) applyVideoPreset(); configureVideoSender() }
    }
    private fun applyVideoPreset() {
        if(capturer==null || screenSharing) return
        val target=if(requestedVideoPreset.fps>30 && (negotiatedVideoCodec.isEmpty() || !hardwareRateSupported(requestedVideoPreset,negotiatedVideoCodec))) requestedVideoPreset.copy(fps=30) else requestedVideoPreset
        val next=VideoQualityPolicy.choose(target,cameraFormats,videoProtection || compressionMode==VideoCompressionPolicy.Mode.DATA_SAVER)
        if(next.width==captureWidth && next.height==captureHeight && next.fps==captureFps) return
        try {
            capturer!!.changeCaptureFormat(next.width,next.height,next.fps)
            videoSource?.adaptOutputFormat(next.width,next.height,next.fps)
            captureWidth=next.width; captureHeight=next.height; captureFps=next.fps
            captureQuality="${captureWidth}×${captureHeight} · ${captureFps}fps"
            configureVideoSender()
        } catch (_: Exception) { videoNotice="Camera quality change unavailable; current call retained" }
    }
    private fun configureVideoSender() {
        val sender=videoSender ?: return
        try {
            val params=sender.parameters
            if(params.encodings.isEmpty()) return
            val cap=VideoCompressionPolicy.budget(VideoQualityPolicy.Preset(captureWidth,captureHeight,captureFps),compressionMode,negotiatedVideoCodec,screenSharing)
            val fps=if(screenSharing) 15 else captureFps
            val adaptation=when(VideoCompressionPolicy.adaptation(compressionMode,detailGate.permits(SystemClock.elapsedRealtime()),videoProtection,screenSharing)) {
                VideoCompressionPolicy.Adaptation.DETAIL -> RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
                VideoCompressionPolicy.Adaptation.SMOOTH -> RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
                VideoCompressionPolicy.Adaptation.BALANCED -> RtpParameters.DegradationPreference.BALANCED
            }
            if(params.encodings.all { it.maxBitrateBps==cap && it.maxFramerate==fps && it.minBitrateBps==null } && params.degradationPreference==adaptation) return
            params.degradationPreference=adaptation
            params.encodings.forEach { it.maxBitrateBps=cap; it.maxFramerate=fps; it.minBitrateBps=null }
            // A ceiling, not forced traffic: WebRTC congestion control still owns allocation.
            if(!sender.setParameters(params)) videoNotice="Adaptive sender retained default limits"
        } catch (_: Exception) { videoNotice="Adaptive sender retained default limits" }
    }
    private fun preferVideoCodecs() {
        try {
            val decoders=HardwareVideoDecoderFactory(egl.eglBaseContext).supportedCodecs.map { it.name.uppercase(java.util.Locale.ROOT) }.toSet()
            val recoveryTarget=VideoQualityPolicy.choose(VideoQualityPolicy.defaults,cameraFormats,false)
            val hardware=HardwareVideoEncoderFactory(egl.eglBaseContext,true,true).supportedCodecs.map { it.name.uppercase(java.util.Locale.ROOT) }.distinct()
                .filter { it in decoders && hardwareRateSupported(VideoQualityPolicy.Preset(captureWidth,captureHeight,captureFps),it) &&
                    hardwareRateSupported(recoveryTarget,it) && hardwareCodecFit(recoveryTarget,it,false) }.toSet()
            val codecs=factory!!.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
                .sortedBy { if(owner.transport(peer)=="wifi") VideoCompressionPolicy.localCodecRank(it.name,it.parameters,hardware)
                    else VideoCompressionPolicy.codecRank(it.name,it.parameters,hardware) }
            // Generate compatible SDP via the SDK; never rewrite fingerprints or SRTP.
            rtc!!.transceivers.filter { it.mediaType==MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }.forEach {
                if(it.setCodecPreferences(codecs).isError) videoNotice="Default compatible codec negotiation retained"
            }
        } catch (_: Exception) { videoNotice="Default compatible codec negotiation retained" }
    }
    fun startScreenShare(resultData: Intent) {
        check(active && video && owner.foreground && !screenSharing && rtc != null)
        val token = id
        ConnectionService.startAndAwait(app, 4)
        val geometry=screenGeometry()
        screenDisplayWidth=geometry.first;screenDisplayHeight=geometry.second;screenRotation=geometry.third
        var width=geometry.first;var height=geometry.second
        val scale = minOf(1f, 1280f / maxOf(width, height)); width = (width * scale).toInt().coerceAtLeast(2); height = (height * scale).toInt().coerceAtLeast(2)
        try {
            screenSharing = true
            try { capturer?.stopCapture() } catch (_: Exception) { }
            val capture = ScreenCapturerAndroid(resultData, object : android.media.projection.MediaProjection.Callback() {
                override fun onStop() = dispatch(token) { if (screenSharing) stopScreenShare() }
                override fun onCapturedContentResize(width:Int,height:Int) = dispatch(token) { if(screenSharing) updateScreenGeometry() }
            })
            screenCapturer = capture
            screenHelper = SurfaceTextureHelper.create("CuteScreen", egl.eglBaseContext)
            screenSource = factory!!.createVideoSource(true).also { it.setIsScreencast(true) }
            capture.initialize(screenHelper, app, screenSource!!.capturerObserver)
            screenTrack = factory!!.createVideoTrack("cute-screen", screenSource!!)
            check(videoSender?.setTrack(screenTrack, true) == true)
            localVideo = screenTrack; captureQuality = "screen ${width}×${height} · 15fps"
            screenEncodedWidth=width;screenEncodedHeight=height
            detailGate.reset(); configureVideoSender()
            capture.startCapture(width, height, 15); changed()
        } catch (failure: Throwable) {
            screenSharing = false
            // Restore sender BEFORE disposing a track it may still reference.
            cameraVideo?.let { videoSender?.setTrack(it, true) }
            localVideo = cameraVideo; disposeScreenCapture()
            try { capturer?.startCapture(captureWidth, captureHeight, captureFps) } catch (_: Exception) { }
            captureQuality = "${captureWidth}×${captureHeight} · ${captureFps}fps"
            detailGate.reset(); configureVideoSender()
            ConnectionService.start(app, 1); changed(); throw failure
        }
    }
    private fun screenGeometry():Triple<Int,Int,Int> {
        val metrics=android.util.DisplayMetrics()
        @Suppress("DEPRECATION") val display=app.getSystemService(android.view.WindowManager::class.java).defaultDisplay
        @Suppress("DEPRECATION") display.getRealMetrics(metrics)
        return Triple(metrics.widthPixels.coerceIn(1,8192),metrics.heightPixels.coerceIn(1,8192),display.rotation)
    }
    private fun updateScreenGeometry() {
        if(!screenSharing) return
        val geometry=screenGeometry();val scale=minOf(1f,1280f/maxOf(geometry.first,geometry.second))
        val width=(geometry.first*scale).toInt().coerceAtLeast(2);val height=(geometry.second*scale).toInt().coerceAtLeast(2)
        if(width!=screenEncodedWidth || height!=screenEncodedHeight || geometry.third!=screenRotation) try {
            screenCapturer?.changeCaptureFormat(width,height,15);screenSource?.adaptOutputFormat(width,height,15)
            screenEncodedWidth=width;screenEncodedHeight=height;captureQuality="screen ${width}×${height} · 15fps"
            screenDisplayWidth=geometry.first;screenDisplayHeight=geometry.second;screenRotation=geometry.third
        } catch (_: Exception) { app.assist.stopLocal() }
    }
    fun stopScreenShare() {
        if (!screenSharing) return
        app.assist.stopLocal()
        screenSharing = false
        detailGate.reset()
        val camera = cameraVideo
        if (camera != null) videoSender?.setTrack(camera, true)
        localVideo = camera; disposeScreenCapture()
        try { capturer?.startCapture(captureWidth, captureHeight, captureFps) } catch (_: Exception) { }
        captureQuality = "${captureWidth}×${captureHeight} · ${captureFps}fps"
        if (active) { updateVideoProtection(apply=false); applyVideoPreset(); configureVideoSender(); ConnectionService.start(app, 1) }
        changed()
    }
    private fun disposeScreenCapture() {
        safely { screenCapturer?.stopCapture() }; safely { screenCapturer?.dispose() }; screenCapturer = null
        safely { screenTrack?.dispose() }; screenTrack = null
        safely { screenSource?.dispose() }; screenSource = null
        safely { screenHelper?.dispose() }; screenHelper = null
    }
    private fun routeSpeaker() {
        if (!active || !audioOwned || audioInterrupted) return
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            val devices = audio.availableCommunicationDevices
            val preferred = if (speaker) devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            else devices.firstOrNull { it.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET) }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            preferred?.let { if (audio.communicationDevice?.id != it.id) audio.setCommunicationDevice(it) }
        } else audio.isSpeakerphoneOn = speaker
    }
    private fun updateProximity() {
        val external = if (Build.VERSION.SDK_INT >= 31) audio.communicationDevice?.type?.let {
            it != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE && it != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } == true else audio.isBluetoothScoOn || audio.isWiredHeadsetOn
        val shouldHold = active && (rtc != null || bluetoothSpeech!=null) && !video && !speaker && !external && !audioInterrupted
        if (shouldHold && power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            val lock = proximityLock ?: power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "CuteChat:audioProximity").also { proximityLock = it }
            // Six-hour safety timeout is longer than the app's availability cap;
            // normal call end/speaker/video transitions release it immediately.
            if (!lock.isHeld) lock.acquire(6 * 60 * 60 * 1000L)
        } else {
            proximityLock?.let { if (it.isHeld) it.release() }; proximityLock = null
        }
    }
    fun toggleSpeaker() { speaker = !speaker; routeSpeaker(); updateProximity(); changed() }
    fun end(notify: Boolean = true, outcome: String? = null) {
        if (!active) { releaseOwnedMedia(); return }
        releaseRingCpu()
        owner.ringLease(0)
        callEntry.clear()
        app.assist.stopLocal()
        val oldId = id; val oldPeer = peer
        audioRetry.clear(oldId);audioFocusReady=false
        val duration = if (connectedAt > 0) (SystemClock.elapsedRealtime() - connectedAt).coerceAtLeast(0) else 0L
        val result = if (connectedAt > 0) "ended" else outcome ?: if(status == "incoming") { if(notify) "declined" else "missed" } else if(notify) "cancelled" else "unanswered"
        owner.recordCallEnd(oldId, duration, result)
        if (notify) try { owner.sendControl(oldPeer, "call_end", JSONObject().put("callId", oldId)) } catch (_: Exception) { }
        setDeadline(0); remoteRinging=false; historyRecorded=false; ringConfirmedSent=false; incoming=false; offerExpiresAt=0
        pendingPreparation=""; preparationStage="idle"
        videoPresets=emptyList(); cameraFormats=emptyList(); cameraEnumerator=null; cameraName=""; requestedVideoPreset=VideoQualityPolicy.defaults; videoProtection=false; recoveredAt=0; videoNotice=""
        videoDiagnostics.reset(); detailGate.reset(); negotiatedVideoCodec=""; lastMediaStatsAt=0; quality="Measuring network"
        id = ""; status = "idle"; peer = ""; connectedAt = 0; captureQuality = ""; focusEpoch++; delegatedFocus = false
        releaseOwnedMedia()
        offer = null; pendingDescription=null; incomingIce.clear(); outgoingIce.clear()
        remoteSet = false; descriptionSent = false; muted = false; cameraOff = false; audioInterrupted = false
        safely { proximityLock?.let { if (it.isHeld) it.release() } }; proximityLock = null
        if (Build.VERSION.SDK_INT >= 23) safely { deviceCallback?.let { audio.unregisterAudioDeviceCallback(it) } }
        deviceCallback = null
        if (audioOwned) {
            safely { audio.isSpeakerphoneOn = priorSpeaker }; safely { audio.mode = priorMode }
            if (Build.VERSION.SDK_INT >= 31) { val device = priorDevice; safely { if (device == null) audio.clearCommunicationDevice() else audio.setCommunicationDevice(device) }; priorDevice = null }
            if (Build.VERSION.SDK_INT >= 26) safely { focus?.let { audio.abandonAudioFocusRequest(it) } }
            else safely { legacyFocus?.let { audio.abandonAudioFocus(it) } }
            focus = null; legacyFocus = null; audioOwned = false
        }
        owner.callLease(false)
        val retainChatLease = owner.hasLease()
        main.post {
            safely { presentation.finish(oldId) }
            if (active) { onChanged?.invoke(); return@post }
            // Reset the service instance, not just its notification: Android may
            // retain previous microphone/camera foreground types otherwise.
            app.stopService(android.content.Intent(app, ConnectionService::class.java))
            if (retainChatLease || owner.foreground) try { ConnectionService.start(app, 3) } catch (_: Exception) { owner.stop() }
            onChanged?.invoke()
        }
    }
    private fun safely(action: () -> Unit) { try { action() } catch (_: Exception) { } }
    /** One failed camera disposal must never skip microphone shutdown. */
    private fun releaseOwnedMedia() {
        safely { bluetoothSpeech?.close() };bluetoothSpeech=null;bluetoothCall=false;bluetoothAudioAt=0
        codecFits.clear()
        safely { audioTrack?.setEnabled(false) }
        screenSharing = false; safely { disposeScreenCapture() }
        val oldVideo = cameraVideo; cameraVideo = null; localVideo = null; remoteVideo = null; videoSender = null
        safely { capturer?.stopCapture() }; safely { capturer?.dispose() }; capturer = null
        safely { rtc?.close() }; safely { rtc?.dispose() }; rtc = null
        safely { oldVideo?.dispose() }; safely { audioTrack?.dispose() }; audioTrack = null
        safely { audioSource?.dispose() }; audioSource = null
        safely { videoSource?.dispose() }; videoSource = null; safely { helper?.dispose() }; helper = null
        safely { factory?.dispose() }; factory = null
        safely { audioDevice?.release() }; audioDevice = null
    }
}
