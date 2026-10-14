package app.cutiepie.cutie_pie.core

import app.cutiepie.cutie_pie.BuildConfig
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.PeerConnectionFactory
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The sole process vault/Signal/network owner; all mutations are serialized here. */
class NativeOwner(private val context: Context) {
    val executor = Executors.newSingleThreadScheduledExecutor()
    private val io = Executors.newFixedThreadPool(2)
    // Accept/revoke ordering is security-sensitive. A slow old accept must never
    // complete after a newer block's revoke on the server.
    private val contactIo = Executors.newSingleThreadExecutor()
    private val revoking = mutableSetOf<String>()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private var vault: NativeVault? = null
    private var identity: NativeIdentity? = null
    private var engine: NativeChatEngine? = null
    private var signal: SignedSignaling? = null
    private var factory: PeerConnectionFactory? = null
    private val channels = mutableMapOf<String, NativeLink>()
    private val authenticated = mutableSetOf<String>()
    private val transferActivityAt=mutableMapOf<String,Long>()
    private val sentAt = mutableMapOf<String, Long>()
    private val helloIds = mutableMapOf<String, String>()
    private val attempts = mutableMapOf<String, Long>()
    private val connectUntil = mutableMapOf<String, Long>()
    private val pendingWindows = linkedMapOf<String, JSONObject>()
    private var pendingChatOpen: String? = null
    private var chatOpenUntil = 0L
    private var chatOpenRetryAt = 0L
    private var openingChat = false
    private val activityWakeAt = mutableMapOf<String,Long>()
    private val connectionNotifiedAt=mutableMapOf<String,Long>()
    var onActivityConnected:((String)->Unit)?=null
    private val remoteAccepted = mutableSetOf<String>()
    private val accepting = mutableSetOf<String>()
    private val contactEpoch = mutableMapOf<String, Long>()
    private val pinging = mutableSetOf<String>()
    private val explicitPings = mutableSetOf<String>()
    private val connections = ContactConnectionPolicy(SystemClock::elapsedRealtime)
    private var reservedCallPeer: String? = null
    private var registering = false
    private val lease = BackgroundLease(SystemClock::elapsedRealtime)
    private var retryAt = 0L
    private var wanted = false
    @Volatile var ready = false; private set
    @Volatile var foreground = false; private set
    @Volatile var activePeer: String? = null
    @Volatile var status = "Open your private space"; private set
    @Volatile var code = ""; private set
    @Volatile var localUid = ""; private set
    @Volatile var contacts: List<Contact> = emptyList(); private set
    @Volatile var blockedContacts: List<Contact> = emptyList(); private set
    @Volatile var incomingRequests: List<Contact> = emptyList(); private set
    private var loadingRequests = false
    private var requestsAt = 0L
    @Volatile var messages: List<Message> = emptyList(); private set
    @Volatile var callHistory: List<PrivateLibrary.Call> = emptyList(); private set
    @Volatile var mediaLibrary: List<PrivateLibrary.Media> = emptyList(); private set
    @Volatile var deletePeers: Set<String> = emptySet(); private set
    @Volatile var deletionRevision = 0L; private set
    val quickShare=app.cutiepie.cutie_pie.NativeQuickShare(context)
    private var largeFiles:NativeLargeFiles?=null
    @Volatile var largeTransfers:List<NativeLargeFiles.View> = emptyList();private set
    @Volatile var largeIds:Set<String> = emptySet();private set
    @Volatile private var fileRoutes:Map<String,FileStreamLink> = emptyMap()
    private fun largeLink(peer:String)=fileRoutes[peer]?.takeIf { it.ready && peer in connected && it.medium!="bluetooth" }
    @Volatile var receivingPeers:Set<String> = emptySet();private set
    @Volatile private var liveReady:Set<String> = emptySet()
    private var receiveEpoch=-1L
    @Volatile var quickBatchBusy=false;private set
    private var quickBatchGeneration=0L
    private var quickBatchPeer:String?=null
    private var transferHolding=false
    private fun updateTransferLease() {
        val active=wanted && fileRoutes.isNotEmpty() && (quickBatchBusy || largeTransfers.any { it.state !in setOf("done","failed") && (it.outgoing || it.state!="offered") })
        lease.fileTransfer(active)
        if(active!=transferHolding) { transferHolding=active;onLease?.invoke(lease.decision().active) }
    }
    private fun readyControl(peer:String) {
        if(peer in liveReady && peer in authenticated) sendControl(peer,"consent_request",NativeExtensions.envelope(JSONObject().put("type","stream_ready").put("enabled",quickShare.enabled)))
    }
    /** Only this foreground-confirmed exact peer/link/URI batch can advance without private-view access. */
    fun sendQuickFiles(peer:String,uris:List<android.net.Uri>)=work {
        check(foreground && viewUnlocked && !quickBatchBusy && canQuickSend(peer,true))
        require(uris.isNotEmpty() && uris.size<=LocalSharingRules.MAX_SELECTION)
        val channel=checkNotNull(largeLink(peer));val pending=java.util.ArrayDeque(uris.distinct());val resolver=context.contentResolver
        val generation=++quickBatchGeneration;quickBatchBusy=true;quickBatchPeer=peer;wanted=true;updateTransferLease();changed()
        fun finish() { if(generation==quickBatchGeneration) { quickBatchBusy=false;quickBatchPeer=null;updateTransferLease();changed() } }
        fun next() {
            if(generation!=quickBatchGeneration || largeLink(peer)!==channel || !channel.ready || contacts.none { it.uid==peer }) { finish();return }
            if(pending.isEmpty()) { finish();return }
            val uri=pending.removeFirst();val timeout=executor.schedule({ if(generation==quickBatchGeneration) { quickBatchGeneration++;quickBatchBusy=false;quickBatchPeer=null;updateTransferLease();status="File provider took too long; select the remaining files again";changed() } },120,TimeUnit.SECONDS)
            io.execute {
                try {
                    val details=resolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME,android.provider.OpenableColumns.SIZE),null,null,null)?.use {
                        check(it.moveToFirst());val n=it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);val z=it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        (if(n>=0) it.getString(n) else "file") to (if(z>=0 && !it.isNull(z)) it.getLong(z) else -1L)
                    } ?: error("Source details unavailable")
                    val mime=resolver.getType(uri) ?: "application/octet-stream"
                    executor.execute {
                        timeout.cancel(false)
                        if(generation!=quickBatchGeneration) return@execute
                        try {
                            check(largeLink(peer)===channel && peer in liveReady)
                            largeFiles!!.sendLive(peer,details.first,mime,details.second,{ resolver.openInputStream(uri) ?: error("Source unavailable") }) { ok -> if(ok) next() else finish() }
                        } catch(_:Exception) { status="Transfer stopped; check source size, connection and receiver storage";finish() }
                    }
                } catch(_:Exception) { executor.execute { timeout.cancel(false);if(generation==quickBatchGeneration) { status="Cannot read this file provider; select a known-size file";finish() } } }
            }
        }
        next()
    }
    private fun cancelPeerBatch(peer:String) {
        if(quickBatchPeer==peer) { quickBatchGeneration++;quickBatchBusy=false;quickBatchPeer=null;updateTransferLease() }
    }
    fun cancelQuickBatch()=work {
        check(foreground && viewUnlocked);quickBatchGeneration++;quickBatchBusy=false;quickBatchPeer=null
        largeTransfers.filter { it.outgoing && it.quick && it.state !in setOf("done","failed") }.forEach { largeFiles?.cancel(it.id) };updateTransferLease();changed()
    }
    fun pauseLargeFile(id:String,paused:Boolean)=work { check(foreground && viewUnlocked);largeFiles?.pause(id,paused) }
    fun transferNotification():Pair<Int,String>? {
        val list=largeTransfers.filter { it.state !in setOf("done","failed") && (it.outgoing || it.state!="offered") }
        val current=list.firstOrNull() ?: return if(quickBatchBusy) 0 to "Preparing selected files" else null
        val percent=if(current.size==0L) 0 else (current.bytes*100/current.size).toInt().coerceIn(0,99)
        return percent to if(current.paused) "File sharing paused · $percent%" else "Encrypted file sharing · $percent%"
    }
    fun sendLargeFile(peer:String,name:String,mime:String,size:Long,quick:Boolean=false,open:()->java.io.InputStream,completed:(Boolean)->Unit) = work {
        try {
            check(foreground && viewUnlocked && transport(peer)=="wifi" && engine!!.supports(peer,"large_file")) { "Connect both updated phones through Wi-Fi first" }
            largeFiles!!.send(peer,name,mime,size,quick,open,completed)
        } catch(failure:Exception) { completed(false);throw failure }
    }
    fun acceptLargeFile(id:String,accept:Boolean)=work { check(foreground && viewUnlocked);largeFiles!!.accept(id,accept,true) }
    fun cancelLargeFile(id:String)=work { check(foreground && viewUnlocked);largeFiles?.cancel(id) }
    fun exportFile(peer:String,id:String,open:()->java.io.OutputStream,automatic:Boolean=false,completed:(Boolean)->Unit)=work {
        try {
            if(automatic) check(preferences.getBoolean("galleryAutoSave",false) && mediaLibrary.any { it.id==id && it.peer==peer && GalleryTimeline.visual(it.mime) })
            else check(viewUnlocked && foreground)
            if(largeFiles?.contains(peer,id)==true) largeFiles!!.export(peer,id,open,completed,if(automatic) { { preferences.getBoolean("galleryAutoSave",false) } } else { { foreground && viewUnlocked } })
            else { val bytes=engine!!.attachment(peer,id);try { open().use { it.write(bytes) };completed(true) } finally { bytes.fill(0) } }
        } catch(failure:Exception) { completed(false);throw failure }
    }
    private var privateLibrary: PrivateLibrary? = null
    private var libraryDirty = true
    private val presenceIo = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile var online: Set<String> = emptySet(); private set
    @Volatile var connected: Set<String> = emptySet(); private set
    private val peerPresence = PeerPresence(SystemClock::elapsedRealtime)
    private var presenceAt = 0L
    @Volatile var connectionStates: Map<String, ContactConnectionState> = emptyMap(); private set
    @Volatile var labels: Map<String, String> = emptyMap(); private set
    @Volatile var lastSeen: Map<String, Long> = emptyMap(); private set
    @Volatile var viewUnlocked = false; private set
    @Volatile var visibleConversationPeer: String? = null; private set
    private val callRingPeers = mutableSetOf<String>()
    var onCallPeerReady: ((String) -> Unit)? = null
    var onMissedCall: ((String, String, Boolean) -> Unit)? = null
    fun viewAccess(visibleAndUnlocked: Boolean, peer: String? = null) { visibleConversationPeer=if(visibleAndUnlocked) peer else null; viewUnlocked = visibleAndUnlocked }
    fun supportsCallRing(peer: String) = peer in authenticated && peer in callRingPeers
    fun sendCallRing(peer: String, callId: String, expiresAt: Long) {
        if (!supportsCallRing(peer)) return
        sendControl(peer,"consent_request",NativeExtensions.envelope(JSONObject().put("type","call_ring").put("callId",callId).put("expiresAt",expiresAt)))
    }
    data class Contact(val uid: String, val name: String, val bundle: JSONObject)
    data class Message(val id: String, val peer: String, val outgoing: Boolean, val kind: String,
        val data: JSONObject, val status: Int, val time: Long, val reactions: String)
    var onAlert: ((String, String?) -> Unit)? = null
    var onFileOffer:((String,String)->Unit)?=null
    var onOnlineAlert: ((String) -> Unit)? = null
    var onCaptureNotice: ((String, String) -> Unit)? = null
    var onMediaReceived: ((String, String, String, String) -> Unit)? = null
    @Volatile var captureNotice: Triple<Long, String, String>? = null; private set
    var onLease: ((Boolean) -> Unit)? = null
    var onAssistControl: ((String,JSONObject) -> Unit)? = null
    @Volatile private var liveAssistPeers:Set<String> = emptySet()
    var onAssistDisconnect:((String)->Unit)?=null
    fun supportsAssist(peer:String)=BuildConfig.REMOTE_CONTROL && peer in connected && peer in liveAssistPeers && transport(peer)!="bluetooth"
    var onCallControl: ((String, String, JSONObject) -> Unit)? = null
    var onContactBlocked: ((String) -> Unit)? = null
    val preferences = context.getSharedPreferences("native_settings", 0)
    private fun changed() { main.post { listeners.forEach { it() } } }
    fun observe(listener: () -> Unit) { listeners.add(listener) }
    fun unobserve(listener: () -> Unit) { listeners.remove(listener) }
    fun work(action: () -> Unit) = executor.execute {
        try { action() } catch (failure: Exception) { status = "Operation could not finish (${failure.javaClass.simpleName}). Your identity and messages are preserved."; changed() }
    }
    init {
        executor.scheduleWithFixedDelay({
            try {
                if (ready && engine?.expireLimitedMedia() == true) refresh()
                if(ready && receiveEpoch!=quickShare.epoch) { receiveEpoch=quickShare.epoch;authenticated.toList().forEach(::readyControl) }
                if (ready && wanted && lease.decision().active) {
                    if (signal?.connected != true && SystemClock.elapsedRealtime() >= retryAt) network()
                    if (signal?.connected == true && SystemClock.elapsedRealtime() % 15000 < 1000) reconcileBlocks()
                    channels.keys.toList().forEach(::flush)
                    if(foreground && SystemClock.elapsedRealtime()%15000<1000) local.retryTrusted()
                    explicitPings.removeAll { (connectUntil[it] ?: 0) <= SystemClock.elapsedRealtime() }
                    if (SystemClock.elapsedRealtime() % 15000 < 1000 && preferences.all.any { it.key.startsWith("online.dirty.") && it.value == true }) syncOnlineWatches()
                    if (signal?.connected == true) contacts.filter { it.uid in explicitPings && (connectUntil[it.uid] ?: 0) > SystemClock.elapsedRealtime() }.forEach {
                        val now = SystemClock.elapsedRealtime()
                        if (now - (attempts[it.uid] ?: -15000L) >= 15000) { attempts[it.uid] = now; try { pingNow(it.uid) } catch (_: Exception) { } }
                    }
                    connectContacts()
                    dispatchChatOpen()
                    if (SystemClock.elapsedRealtime()-presenceAt >= 30000) presence()
                    val visiblePeers = peerPresence.online(authenticated)
                    if (online != visiblePeers) { online = visiblePeers; changed() }
                    if (foreground && SystemClock.elapsedRealtime() - requestsAt > 30000) refreshRequests()
                } else if (ready && wanted) disconnect()
            } catch (_: Exception) { status = "Connection retry pending"; changed() }
        }, 1, 1, TimeUnit.SECONDS)
    }
    data class LocalRequest(val id:String,val peer:String,val name:String,val medium:String,val safety:String,val endpoint:String)
    @Volatile var localRequests:List<LocalRequest> = emptyList();private set
    @Volatile var transports:Map<String,String> = emptyMap();private set
    private data class Admission(val link:LocalStreamLink,val bundle:JSONObject,val name:String,val nonce:String,var approved:Boolean=false,var remoteApproved:Boolean=false)
    private val localAdmissions=mutableMapOf<LocalStreamLink,Admission>()
    val local by lazy { NativeLocalSharing(context,executor,{localUid},{ p -> reservedCallPeer!=p && (channels[p]?.medium!="wifi" || channels[p]?.ready!=true) },
        { identity!!.contactCode(if(preferences.getBoolean("local.shareName",false)) preferences.getString("name","Nearby friend")!! else "Nearby friend") },
        ::localPacket,{ link -> localAdmissions.remove(link);refreshLocalRequests() },::changed) }
    var callActiveForSharing:()->Boolean = { reservedCallPeer!=null }
    @Volatile var localEndpoints:Map<String,String> = emptyMap();private set
    private val handoffPeers=mutableSetOf<String>()
    @Volatile private var handoffReady:Set<String> = emptySet()
    fun canQuickHandoff(peer:String)=peer in connected && transport(peer)=="bluetooth" && peer in handoffReady
    private val quickPeers=mutableSetOf<String>()
    @Volatile private var quickReady:Set<String> = emptySet()
    fun canQuickSend(peer:String,internet:Boolean=false)=peer in connected && transport(peer) in (if(internet) setOf("wifi","internet") else setOf("wifi")) && peer in liveReady
    val wifiHandoff by lazy { NativeWifiHandoff(context,executor,local,
        { p -> channels[p]?.medium=="bluetooth" && p in authenticated && engine?.supports(p,"wifi_handoff")==true },
        { p -> channels[p]?.medium=="wifi" && p in authenticated },
        { foreground && viewUnlocked && quickShare.enabled && reservedCallPeer==null },
        { callActiveForSharing() || largeTransfers.any { it.state !in setOf("done","failed") } },
        { p,value -> sendControl(p,"consent_request",NativeExtensions.envelope(value)) },::changed,
        { p -> engine?.supports(p,"wifi_band")==true }) }
    fun quickConnect(peer:String)=work { check(foreground && viewUnlocked && reservedCallPeer==null);wifiHandoff.begin(peer) }
    fun quickHandoffAccept(id:String,allow:Boolean)=work { check(foreground && viewUnlocked);wifiHandoff.accept(id,allow) }
    fun quickHandoffStop()=work {
        check(foreground && viewUnlocked)
        if(callActiveForSharing() || quickBatchBusy || largeTransfers.any { it.state !in setOf("done","failed") && (it.outgoing || it.state!="offered") }) { status="New receiving stopped; active call/transfer connection retained";changed() }
        else wifiHandoff.stop(true,"Nearby sharing cancelled")
    }
    var onBluetoothAudio:((String,JSONObject)->Unit)?=null
    fun unmanagedLocalNetwork(peer:String)=(channels[peer] as? LocalStreamLink)?.unmanagedNetwork==true
    fun transport(peer:String)=channels[peer]?.medium ?: "internet"
    fun localSettings(wifi:Boolean,bluetooth:Boolean)=work {
        check(ready && foreground && viewUnlocked && reservedCallPeer==null && !callActiveForSharing())
        wifiHandoff.stop(true)
        preferences.edit().putBoolean("local.wifi",wifi).putBoolean("local.bluetooth",bluetooth).putBoolean("local.shareName",true).apply()
        if(wifi || bluetooth) local.start(wifi,bluetooth) else local.stop()
        changed()
    }
    fun localConnect(endpoint:String)=work { check(foreground && viewUnlocked);local.connect(endpoint) }
    fun localRefresh()=work { check(foreground && viewUnlocked);check(!local.holdForHandoff);local.start(preferences.getBoolean("local.wifi",false),preferences.getBoolean("local.bluetooth",false)) }
    fun localManual(address:String,port:Int)=work { check(foreground && viewUnlocked);local.manual(address,port) }
    fun localAccept(id:String,accept:Boolean)=work {
        check(foreground && viewUnlocked)
        val entry=localAdmissions.values.find { it.link.nonce==id } ?: return@work
        if(!accept) entry.link.close() else {
            check(blockedContacts.none { it.uid==entry.bundle.getString("uid") })
            storeContact(entry.bundle,entry.name);refresh();approveLocal(entry)
        }
        refreshLocalRequests()
    }
    private fun refreshLocalRequests() {
        localRequests=localAdmissions.values.filter { !it.approved }.map { LocalRequest(it.link.nonce,it.bundle.getString("uid"),it.name,it.link.medium,LocalSharingRules.safety(localUid,it.bundle.getString("uid"),it.link.nonce,it.nonce),it.link.endpoint) };changed()
    }
    private fun approveLocal(entry:Admission) {
        entry.approved=true
        val peer=entry.bundle.getString("uid")
        val signature=identity!!.sign(LocalSharingRules.context(localUid,peer,entry.link.nonce,entry.nonce,entry.link.medium))
        check(entry.link.offer(JSONObject().put("type","accept").put("signature",signature),true))
        activateLocal(entry);refreshLocalRequests()
    }
    private fun activateLocal(entry:Admission) {
        if(!entry.approved || !entry.remoteApproved || entry.link.ready) return
        val peer=entry.bundle.getString("uid");check(contacts.any { it.uid==peer })
        val old=channels[peer]
        // Never tear down an active call for a radio change, or downgrade a live Wi-Fi route.
        val next=LocalSharingRules.request(entry.link.nonce,entry.nonce)
        if(reservedCallPeer==peer || (old?.medium=="wifi" && entry.link.medium!="wifi") ||
            (old?.medium==entry.link.medium && old.requestId<=next)) { entry.link.close();return }
        installPeer(peer,next) { open,frame,writable,closed ->
            entry.link.apply { bind(LocalSharingRules.request(nonce,entry.nonce),open,frame,writable,closed) }
        }
    }
    private fun localPacket(link:LocalStreamLink,value:JSONObject) {
        check(ready && wanted && lease.decision().active)
        when(value.getString("type")) {
            "invite" -> {
                NativeIdentity.exact(value,setOf("type","code","nonce"));check(link !in localAdmissions)
                val parsed=identity!!.parseContact(value.getString("code"));val peer=parsed.first.getString("uid")
                check(peer!=localUid && blockedContacts.none { it.uid==peer })
                val existing=contacts.find { it.uid==peer }
                check(existing==null || WireCodec.canonical(existing.bundle)==WireCodec.canonical(parsed.first))
                val entry=Admission(link,parsed.first,existing?.name ?: parsed.second.ifEmpty { "Nearby friend" },ProtocolRules.id(value.get("nonce")))
                check(entry.nonce!=link.nonce);localAdmissions[link]=entry
                if(existing!=null) approveLocal(entry) else refreshLocalRequests()
            }
            "accept" -> {
                NativeIdentity.exact(value,setOf("type","signature"));val entry=checkNotNull(localAdmissions[link]);check(!entry.remoteApproved)
                check(identity!!.verify(entry.bundle,LocalSharingRules.context(entry.bundle.getString("uid"),localUid,entry.nonce,link.nonce,link.medium),value.getString("signature")))
                entry.remoteApproved=true;activateLocal(entry)
            }
            "frame" -> {
                NativeIdentity.exact(value,setOf("type","frame"));check(link.ready);link.receiveFrame(value.getString("frame"))
            }
            "audio" -> {
                NativeIdentity.exact(value,setOf("type","frame"));check(link.ready && link.medium=="bluetooth")
                val peer=checkNotNull(localAdmissions[link]).bundle.getString("uid")
                if(channels[peer]===link && peer in authenticated && reservedCallPeer==peer) {
                    val event=engine!!.receive(peer,value.getString("frame"),live=true,medium="bluetooth")
                    onBluetoothAudio?.invoke(peer,event.data)
                }
            }
            else -> error("Unknown local packet")
        }
    }
    fun sendBluetoothAudio(peer:String,data:JSONObject):Boolean {
        val link=channels[peer] as? LocalStreamLink ?: return false
        if(link.medium!="bluetooth" || peer !in authenticated || reservedCallPeer!=peer) return false
        if(!link.audioCapacity()) return false
        return link.sendAudio(engine!!.encryptLive(peer,data))
    }
    fun setup(name: String, create: Boolean) = work {
        if (ready) return@work
        require(name.trim().length in 1..80)
        require(name.none { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() })
        val opened = if (File(context.filesDir, "cutie.db").exists()) NativeVault.openExisting(context)
            else { check(create) { "Confirm first setup" }; NativeVault.createFresh(context) }
        try {
            val loaded = NativeIdentity.loadExisting(opened)
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
            app.cutiepie.cutie_pie.NativeRtcNetworks.install()
            factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
            vault = opened; identity = loaded; localUid = loaded.uid; engine = NativeChatEngine(opened, loaded.public)
            privateLibrary = PrivateLibrary(opened).also { it.recoverInterrupted(System.currentTimeMillis()) }
            largeFiles=NativeLargeFiles(context,opened,executor,::largeLink,{ peer,value -> sendControl(peer,"consent_request",NativeExtensions.envelope(value)) },{
                largeTransfers=largeFiles?.views ?: emptyList();updateTransferLease();libraryDirty=true;refresh()
            },{ peer -> if(peer in authenticated) lease.actualContentActivity(preferences.getInt("grace",5).coerceIn(1,30)) },{ peer,id,mime,name ->
                if(id in largeIds) onMediaReceived?.invoke(peer,id,mime,name)
                if(AlertPolicy.shouldAlert(foreground,viewUnlocked,visibleConversationPeer,peer)) onAlert?.invoke(peer,null)
            },{ foreground && viewUnlocked },{ reservedCallPeer!=null },{ if(quickShare.enabled) quickShare.epoch else -1L },{ name,mime,size -> if(quickShare.enabled) quickShare.open(name,mime,size) else null },{ peer,id -> if(!foreground || !viewUnlocked) onFileOffer?.invoke(peer,id) })
            preferences.edit().putString("name", name.trim()).putBoolean("setup", true).apply()
            code = loaded.contactCode(name)
            signal = SignedSignaling(loaded, "https://cutie-pie-signaling.xbrstu.workers.dev", executor, { value ->
                try { event(value) } catch (_: Exception) { status = "Invalid coordination frame rejected"; changed() }
            }) { connected, _ ->
                status = if (connected) "Connection service ready · waiting for direct peer" else "Connection service offline · retrying"
                if (connected) { acceptRegisteredContacts(); syncOnlineWatches(true); presence(); syncBackgroundWindow(); drainPendingWindows(); connectContacts(); dispatchChatOpen(); refreshRequests() }
                refreshConnectionStates()
                changed()
            }
            ready = true; if (foreground) lease.enterForeground(); wanted = true
            refresh(); if (lease.decision().active) { network(); onLease?.invoke(true) }
        } catch (failure: Throwable) { if (vault !== opened) opened.close(); throw failure }
    }
    private fun network() {
        retryAt = SystemClock.elapsedRealtime() + 15000
        val signaling = signal ?: return
        if (!signaling.connected && !registering) {
            registering = true
            io.execute {
                val succeeded = try { signaling.register(preferences.getString("pushToken", null), preferences.getBoolean("alerts", true)); true } catch (_: Exception) { false }
                executor.execute { registering = false
                    if (succeeded && wanted && lease.decision().active) signaling.connect()
                    else if (!succeeded) { status = "Connection service unavailable; retry pending"; changed() }
                }
            }
        }
    }
    private fun acceptRegisteredContacts() {
        reconcileBlocks()
        for (contact in contacts) if (contact.uid !in remoteAccepted && accepting.add(contact.uid)) {
            val epoch = contactEpoch[contact.uid] ?: 0L
            contactIo.execute {
                val accepted = try { signal!!.acceptContact(contact.uid); true } catch (_: Exception) { false }
                executor.execute { accepting.remove(contact.uid)
                    if (accepted && epoch == (contactEpoch[contact.uid] ?: 0L) && contacts.any { it.uid == contact.uid }) {
                        remoteAccepted.add(contact.uid); syncOnlineWatches(); dispatchChatOpen()
                        if (contact.uid in explicitPings) pingNow(contact.uid) else connectContacts()
                    } else if (epoch != (contactEpoch[contact.uid] ?: 0L)) acceptRegisteredContacts()
                }
            }
        }
    }
    private fun reconcileBlocks() {
        for (contact in blockedContacts) if (vault?.get("contact.block.dirty.${contact.uid}") == "1" && revoking.add(contact.uid)) {
            val epoch = contactEpoch[contact.uid] ?: 0L
            contactIo.execute {
                val revoked = try { signal!!.revokeContact(contact.uid); true } catch (_: Exception) { false }
                executor.execute {
                    revoking.remove(contact.uid)
                    if (revoked && epoch == (contactEpoch[contact.uid] ?: 0L) && blockedContacts.any { it.uid == contact.uid })
                        vault!!.transaction { it.execute("DELETE FROM kv WHERE key=?", "contact.block.dirty.${contact.uid}") }
                    else if (epoch != (contactEpoch[contact.uid] ?: 0L)) reconcileBlocks()
                }
            }
        }
    }
    fun token(token: String) = work {
        require(token.length <= 4096)
        preferences.edit().putString("pushToken", token).apply()
        if (ready) io.execute { try { signal?.register(token, preferences.getBoolean("alerts", true)) } catch (_: Exception) { } }
    }
    fun enter() = work {
        if (!foreground) connections.reset()
        foreground = true; lease.enterForeground(); wanted = true
        preferences.edit().putBoolean("chatWakeStopped",false).apply()
        if (ready) { refresh(); network(); presence(); connectContacts(); refreshRequests(); onLease?.invoke(true)
            if(!local.holdForHandoff && !quickBatchBusy && largeTransfers.none { it.state !in setOf("done","failed") && (it.outgoing || it.state!="offered") } && (preferences.getBoolean("local.wifi",false) || preferences.getBoolean("local.bluetooth",false)))
                runCatching { local.start(preferences.getBoolean("local.wifi",false),preferences.getBoolean("local.bluetooth",false)) }
        }; changed()
    }
    fun leave() = work {
        local.stopDiscovery()
        foreground = false; lease.leaveForeground(preferences.getInt("grace", 5).coerceIn(1, 30))
        if (ready) presence()
        onLease?.invoke(lease.decision().active)
    }
    fun stop() = work { preferences.edit().putBoolean("chatWakeStopped",true).apply(); lease.stop(); wanted = false; disconnect(); onLease?.invoke(false) }
    fun hasLease() = lease.decision().active
    fun backgroundUnavailable() = work {
        if(!foreground && reservedCallPeer == null) { lease.stop();wanted=false;disconnect() }
        status = "Android did not allow this background connection. Open the app to reconnect."; changed()
    }
    fun syncBackgroundWindow() = work {
        val enabled=preferences.getBoolean("chatWake",true) && !preferences.getBoolean("chatWakeStopped",false)
        presenceIo.execute { try { signal?.backgroundWindow(enabled) } catch (_: Exception) { } }
    }
    private fun drainPendingWindows() {
        val frames=pendingWindows.values.toList(); pendingWindows.clear()
        frames.forEach { try { event(it) } catch (_: Exception) { status="Background connection retry pending" } }
    }
    private fun connectWindow(peer: String, value: JSONObject) {
        if(signal?.connected != true) {
            pendingWindows[peer] = JSONObject(value.toString())
            while(pendingWindows.size > 16) pendingWindows.remove(pendingWindows.keys.first())
            connectUntil[peer]=SystemClock.elapsedRealtime()+60000
            network(); return
        }
        startPeer(peer,ProtocolRules.id(value.get("requestId")),value.optString("initiator",peer) == identity!!.uid)
    }
    fun requestChatWindow(peer: String) = work {
        if(!foreground || contacts.none { it.uid == peer }) return@work
        pendingChatOpen=peer; chatOpenUntil=SystemClock.elapsedRealtime()+60000; chatOpenRetryAt=0
        dispatchChatOpen()
    }
    /** Explicit send/call may wake an accepted background peer even with open WSS/P2P. */
    fun wakeForActivity(peer: String) = work {
        if(!foreground || !ready || contacts.none { it.uid == peer }) return@work
        if(transport(peer) in setOf("wifi","bluetooth") && peer in authenticated) { flush(peer);return@work }
        val now=SystemClock.elapsedRealtime()
        if(peer !in online && now-(activityWakeAt[peer] ?: -60000L) >= 60000) {
            activityWakeAt[peer]=now
            pendingChatOpen=peer; chatOpenUntil=now+60000; chatOpenRetryAt=0; dispatchChatOpen()
        }
        if(peer !in authenticated) {
            connectContacts()
            // Initial quiet chat-open/FCM plus existing bounded /v1/connect retries; no automatic alerting Ping.
        }
    }
    private fun dispatchChatOpen() {
        val peer=pendingChatOpen ?: return
        val now=SystemClock.elapsedRealtime()
        if(now >= chatOpenUntil || !foreground || contacts.none { it.uid == peer }) { pendingChatOpen=null; return }
        if(openingChat || now < chatOpenRetryAt || signal?.connected != true || peer !in remoteAccepted) return
        openingChat=true; val epoch=contactEpoch[peer] ?: 0L
        // Runs behind foreground presence snapshots. Cold app entry retains
        // this intent until registration, WSS and local consent are ready.
        presenceIo.execute {
            val window=try { signal!!.chatOpen(peer) } catch (_: Exception) { null }
            executor.execute {
                openingChat=false
                if(epoch != (contactEpoch[peer] ?: 0L)) { if(pendingChatOpen == peer) pendingChatOpen=null; return@execute }
                if(pendingChatOpen == peer) {
                    if(window != null) pendingChatOpen=null else chatOpenRetryAt=SystemClock.elapsedRealtime()+15000
                }
                if(window?.optBoolean("enabled") == true && contacts.any { it.uid == peer } && wanted && lease.decision().active)
                    try { startPeer(peer,ProtocolRules.id(window.get("requestId")),window.getString("sender") == localUid) } catch (_: Exception) { }
                if(pendingChatOpen != peer) dispatchChatOpen()
            }
        }
    }
    fun callLease(active: Boolean, peer: String? = null) { reservedCallPeer = if (active) peer else null; lease.acceptedCall(active); onLease?.invoke(lease.decision().active) }
    fun ringLease(until: Long) { lease.incomingRing(until);onLease?.invoke(lease.decision().active) }
    fun sharePresence() = work { presence() }
    fun refreshPushRegistration() = work { io.execute { try { signal?.register(preferences.getString("pushToken", null), preferences.getBoolean("alerts", true)) } catch (_: Exception) { } } }
    fun availableUntil(until: Long) = work {
        val duration = until - System.currentTimeMillis(); require(duration in 60000..21600000)
        lease.schedule(SystemClock.elapsedRealtime() + duration); preferences.edit().putLong("availableUntil", until).apply()
        wanted = true; network(); onLease?.invoke(true)
    }
    private fun presence() {
        presenceAt = SystemClock.elapsedRealtime()
        val sharing = preferences.getBoolean("lastSeen", false)
        val data = JSONObject().put("active", foreground).put("sharing", sharing)
            .put("lastSeen", if (sharing) System.currentTimeMillis() else JSONObject.NULL)
        for (peer in authenticated.toList()) { engine?.enqueue(peer, "presence", data); flush(peer) }
        val active = wanted && lease.decision().active; val visible = foreground
        // Foreground leave/return snapshots must retain their actor order.
        if (signal?.connected == true) presenceIo.execute { try { signal?.presence(sharing, active, visible) } catch (_: Exception) { } }
    }
    fun renameContact(peer: String, name: String, completed: () -> Unit = {}) = work {
        val clean = name.trim()
        require(clean.length in 1..80 && clean.none { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() })
        require(contacts.any { it.uid == peer })
        vault!!.transaction { it.execute("UPDATE contacts SET alias=? WHERE uid=?", clean, peer) }; refresh(); main.post(completed)
    }
    fun blockContact(peer: String, completed: () -> Unit = {}) = work {
        require(contacts.any { it.uid == peer })
        vault!!.transaction {
            it.execute("UPDATE contacts SET blocked=1 WHERE uid=?", peer)
            it.execute("INSERT OR REPLACE INTO kv(key,value) VALUES(?,?)", "contact.block.dirty.$peer", "1")
        }
        contactEpoch[peer] = (contactEpoch[peer] ?: 0L) + 1L
        onContactBlocked?.invoke(peer)
        cancelPeerBatch(peer);largeFiles?.disconnected(peer);liveReady=liveReady-peer;receivingPeers=receivingPeers-peer
        quickPeers.remove(peer);quickReady=quickPeers.toSet();handoffPeers.remove(peer);handoffReady=handoffPeers.toSet()
        channels.remove(peer)?.close(); authenticated.remove(peer); liveAssistPeers=liveAssistPeers-peer; onAssistDisconnect?.invoke(peer); peerPresence.remove(peer); remoteAccepted.remove(peer); online = online - peer; connected = connected - peer
        connectUntil.remove(peer); attempts.remove(peer); helloIds.remove(peer)
        explicitPings.remove(peer)
        preferences.edit().putBoolean("online.watch.$peer", false).apply()
        refresh(); reconcileBlocks(); main.post(completed)
    }
    fun unblockContact(peer: String, completed: () -> Unit = {}) = work {
        require(blockedContacts.any { it.uid == peer })
        vault!!.transaction {
            it.execute("UPDATE contacts SET blocked=0 WHERE uid=?", peer)
            it.execute("DELETE FROM kv WHERE key=?", "contact.block.dirty.$peer")
        }
        contactEpoch[peer] = (contactEpoch[peer] ?: 0L) + 1L
        connectUntil[peer] = SystemClock.elapsedRealtime() + 60000
        refresh(); acceptRegisteredContacts(); main.post(completed)
    }
    fun onlineWatch(peer: String) = preferences.getBoolean("online.watch.$peer", false)
    fun setOnlineWatch(peer: String, enabled: Boolean) = work {
        require(contacts.any { it.uid == peer })
        // Persist intent locally; network failures/restarts retry without losing it.
        preferences.edit().putBoolean("online.watch.$peer", enabled).putBoolean("online.dirty.$peer", true).apply()
        syncOnlineWatches(); changed()
    }
    private val watching = mutableSetOf<String>()
    private fun syncOnlineWatches(force: Boolean = false) {
        if (signal?.connected != true) return
        for (contact in contacts) if (preferences.contains("online.watch.${contact.uid}") && (force || preferences.getBoolean("online.dirty.${contact.uid}", false)) && watching.add(contact.uid)) {
            val peer = contact.uid; val enabled = onlineWatch(peer)
            io.execute {
                val ok = try { signal!!.watchOnline(peer, enabled); true } catch (_: Exception) { false }
                executor.execute {
                    watching.remove(peer)
                    if (ok && onlineWatch(peer) == enabled) preferences.edit().putBoolean("online.dirty.$peer", false).apply()
                    else if (ok) syncOnlineWatches()
                    else { status = "Online Ping preference saved; waiting for mutual contact/server connection"; changed() }
                }
            }
        }
    }
    fun setLabel(peer: String, label: String) = work {
        val data = JSONObject().put("label", label.trim()).put("revision", System.currentTimeMillis()).put("author", identity!!.uid)
        ProtocolRules.validate("ping_label", data, identity!!.uid, peer)
        vault!!.transaction { it.execute("INSERT OR REPLACE INTO kv(key,value) VALUES(?,?)", "ping.label.$peer", WireCodec.canonical(data)) }
        engine!!.enqueue(peer, "ping_label", data); flush(peer); refresh()
    }
    fun schedule(peer: String, at: Long) = work {
        check(signal?.connected == true)
        io.execute {
            val ok = try { signal!!.schedulePing(peer, at); true } catch (_: Exception) { false }
            executor.execute { status = if (ok) "Reminder scheduled for both phones" else "Reminder could not be scheduled"; changed() }
        }
    }
    fun foregroundPeer(peer: String?) = work {
        if (activePeer != peer) {
            activePeer = peer
            if (peer != null) {
                connectUntil[peer] = SystemClock.elapsedRealtime() + 60000
                io.execute {
                    val metadata = try { signal?.readPresence(peer) } catch (_: Exception) { null }
                    executor.execute {
                        if(contacts.none { it.uid == peer }) return@execute
                        val time = metadata?.opt("lastSeen") as? Number
                        val value = time?.toLong()
                        lastSeen = if (metadata?.optBoolean("shared") == true && value != null && value in 0..(System.currentTimeMillis() + 90000)) lastSeen + (peer to value) else lastSeen - peer
                        changed()
                    }
                }
            }
            refresh()
        }
        if (peer != null && channels[peer] == null && channels.size >= 16) {
            // Prefer the conversation the user opened; never evict an active
            // call or a channel carrying unacknowledged message/file data.
            val idle = channels.keys.firstOrNull { it != peer && it != reservedCallPeer && engine!!.pending(it).isEmpty() }
            if (idle != null) { channels.remove(idle)?.close(); authenticated.remove(idle); liveAssistPeers=liveAssistPeers-idle; onAssistDisconnect?.invoke(idle); connections.closed(idle); refresh() }
        }
        if (peer != null) read(peer)
        connectContacts()
    }
    fun addContact(raw: String, overrideName: String = "", done: (String) -> Unit) = work {
        val parsed = checkNotNull(identity).parseContact(raw.trim()); val bundle = parsed.first
        val peer = bundle.getString("uid"); require(peer != identity!!.uid)
        val name = overrideName.trim().ifEmpty { parsed.second.ifEmpty { "Friend " + peer.take(6) } }
        require(name.length in 1..80)
        vault!!.transaction { tx ->
            val old = tx.rows("SELECT bundle FROM contacts WHERE uid=?", peer).singleOrNull()
            check(old == null || WireCodec.canonical(JSONObject(old["bundle"] as String)) == WireCodec.canonical(bundle)) { "Changed identity requires explicit verification" }
            if (old == null) { check(tx.count("SELECT COUNT(*) FROM contacts") < 256); tx.execute("INSERT INTO contacts(uid,bundle,alias) VALUES(?,?,?)", peer, WireCodec.canonical(bundle), name) }
        }
        connectUntil[peer] = SystemClock.elapsedRealtime() + 60000
        refresh(); if (signal?.connected == true) { acceptRegisteredContacts(); connectContacts() }
        main.post { done(peer) }
    }
    private fun storeContact(bundle: JSONObject, name: String, blocked: Boolean = false) {
        identity!!.validateBundle(bundle)
        val uid = bundle.getString("uid"); require(uid != localUid)
        val clean = name.trim(); require(clean.length in 1..80 && clean.none { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() })
        vault!!.transaction { tx ->
            val old = tx.rows("SELECT bundle,blocked FROM contacts WHERE uid=?", uid).singleOrNull()
            check(old == null || WireCodec.canonical(JSONObject(old["bundle"] as String)) == WireCodec.canonical(bundle))
            check(old == null || old["blocked"] != 1L || blocked) { "Unblock this contact explicitly first" }
            if (old == null) { check(tx.count("SELECT COUNT(*) FROM contacts") < 256); tx.execute("INSERT INTO contacts(uid,bundle,alias,blocked) VALUES(?,?,?,?)", uid, WireCodec.canonical(bundle), clean, if(blocked) 1 else 0) }
            else if(blocked) tx.execute("UPDATE contacts SET blocked=1 WHERE uid=?",uid)
        }
    }
    fun findContact(number: String, completed: (JSONObject?) -> Unit) = work {
        require(ready)
        contactIo.execute {
            val bundle = try { signal!!.lookupContact(number).getJSONObject("bundle") } catch (_: Exception) { null }
            executor.execute {
                val safe = try { bundle?.also { identity!!.validateBundle(it); require(FriendlyContactId.number(FriendlyContactId.create("CUTE", it.getString("uid"))) == number); require(it.getString("uid") != localUid); require(blockedContacts.none { c -> c.uid == it.getString("uid") }) } } catch (_: Exception) { null }
                main.post { completed(safe) }
            }
        }
    }
    fun requestContact(bundle: JSONObject, name: String, completed: (Boolean) -> Unit) = work {
        identity!!.validateBundle(bundle); val peer = bundle.getString("uid")
        require(peer != localUid && blockedContacts.none { it.uid == peer })
        require(name.length in 1..80 && name.none { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() })
        val epoch = contactEpoch[peer] ?: 0L
        // Persist before HTTP so an immediate acceptance cannot race the local record.
        vault!!.transaction { it.execute("INSERT OR REPLACE INTO kv(key,value) VALUES(?,?)", "request.out.$peer", JSONObject().put("bundle",bundle).put("name",name).toString()) }
        contactIo.execute {
            val ok = try { signal!!.requestContact(peer); true } catch (_: Exception) { false }
            executor.execute {
                if (epoch != (contactEpoch[peer] ?: 0L)) { main.post { completed(false) }; return@execute }
                if (!ok) status = "Request not sent. Check connection, a block or the 48-hour retry limit." else status = "Request sent. Chat and calls require their acceptance."
                changed(); main.post { completed(ok) }
            }
        }
    }
    fun refreshRequests() {
        if (!ready || !foreground || signal?.connected != true || loadingRequests || SystemClock.elapsedRealtime() - requestsAt < 15000) return
        loadingRequests = true; requestsAt = SystemClock.elapsedRealtime()
        contactIo.execute {
            val response = try { signal!!.contactRequests() } catch (_: Exception) { null }
            executor.execute {
                loadingRequests = false
                val result = response?.optJSONArray("requests")
                if (result != null) {
                    incomingRequests = (0 until minOf(result.length(),30)).mapNotNull { index -> runCatching {
                        val bundle = result.getJSONObject(index).getJSONObject("bundle"); identity!!.validateBundle(bundle)
                        val peer = bundle.getString("uid"); check(peer != localUid && blockedContacts.none { it.uid == peer })
                        Contact(peer, contacts.find { it.uid == peer }?.name ?: FriendlyContactId.create("CUTE",peer), bundle)
                    }.getOrNull() }
                    response.optJSONArray("accepted")?.let { peers -> for(index in 0 until minOf(peers.length(),200)) completeRequestedContact(peers.getString(index)) }
                    response.optJSONArray("decisions")?.let { decisions -> for(index in 0 until minOf(decisions.length(),60)) {
                        val item = decisions.getJSONObject(index); completeContactResponse(item.getString("peer"),item.getString("action"))
                    } }
                    refresh(); changed()
                }
            }
        }
    }
    private fun completeRequestedContact(peer: String) {
        if (blockedContacts.any { it.uid == peer }) return
        val pending = vault?.get("request.out.$peer")?.let(::JSONObject) ?: return
        storeContact(pending.getJSONObject("bundle"),pending.getString("name"))
        vault!!.transaction { it.execute("DELETE FROM kv WHERE key=?", "request.out.$peer") }
        refresh(); acceptRegisteredContacts(); connectContacts()
    }
    private fun completeContactResponse(peer: String, action: String) {
        val pending = vault?.get("request.response.$peer")?.let(::JSONObject) ?: return
        if (pending.getString("action") != action) return
        if (action != "decline") storeContact(pending.getJSONObject("bundle"),pending.getString("name"),action == "block")
        if(action == "block") {
            contactEpoch[peer]=(contactEpoch[peer] ?: 0L)+1L
            cancelPeerBatch(peer);largeFiles?.disconnected(peer);liveReady=liveReady-peer;receivingPeers=receivingPeers-peer
        quickPeers.remove(peer);quickReady=quickPeers.toSet();handoffPeers.remove(peer);handoffReady=handoffPeers.toSet()
        channels.remove(peer)?.close(); authenticated.remove(peer); liveAssistPeers=liveAssistPeers-peer; onAssistDisconnect?.invoke(peer); remoteAccepted.remove(peer)
            preferences.edit().putBoolean("online.watch.$peer",false).apply()
            onContactBlocked?.invoke(peer)
        }
        vault!!.transaction { it.execute("DELETE FROM kv WHERE key=?", "request.response.$peer") }
        incomingRequests = incomingRequests.filterNot { it.uid == peer }; refresh()
        if(action == "accept") { acceptRegisteredContacts(); connectContacts() }
    }
    fun respondRequest(peer: String, action: String, name: String, completed: () -> Unit) = work {
        val request = incomingRequests.find { it.uid == peer } ?: return@work
        require(action in setOf("accept","decline","block"))
        require(name.length in 1..80 && name.none { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() })
        vault!!.transaction { it.execute("INSERT OR REPLACE INTO kv(key,value) VALUES(?,?)", "request.response.$peer", JSONObject().put("action",action).put("bundle",request.bundle).put("name",name).toString()) }
        contactIo.execute {
            val ok = try { signal!!.respondContact(peer,action); true } catch (_: Exception) { false }
            executor.execute {
                if (ok) {
                    completeContactResponse(peer,action)
                    main.post(completed)
                } else { status = "Contact response could not be saved. Try again when connected."; changed() }
            }
        }
    }
    fun ping(peer: String) = work { pingNow(peer) }
    private fun pingNow(peer: String) {
        if(peer in authenticated && channels[peer]?.ready==true && transport(peer)!="internet") {
            engine!!.enqueue(peer,"local_ping",JSONObject().put("id",WireCodec.id()));flush(peer);return
        }
        check(ready && lease.decision().active)
        require(contacts.any { it.uid == peer })
        if (explicitPings.add(peer)) connectUntil[peer] = SystemClock.elapsedRealtime() + 60000
        network(); if (!signal!!.connected) { connectUntil[peer] = SystemClock.elapsedRealtime() + 60000; return }
        if (peer !in remoteAccepted) { acceptRegisteredContacts(); return }
        if (peer in authenticated && channels[peer]?.ready == true) { explicitPings.remove(peer); flush(peer); return }
        if (!pinging.add(peer)) return
        connections.began(peer); refreshConnectionStates()
        val pending = engine!!.pending(peer).isNotEmpty()
        io.execute {
            val window = try { signal!!.ping(peer, pending) } catch (_: Exception) { null }
            executor.execute {
                pinging.remove(peer)
                if (window != null) explicitPings.remove(peer) else connections.failed(peer)
                if (window != null && contacts.any { it.uid == peer } && wanted && lease.decision().active) try {
                    val id = ProtocolRules.id(window.get("requestId")); lease.ping(id)
                    startPeer(peer, id, window.getString("sender") == identity!!.uid)
                } catch (_: Exception) { status = "Invalid connection request rejected"; changed() }
                else if (window == null) { status = "Waiting for both people to accept the contact and connect"; changed() }
                refreshConnectionStates()
            }
        }
    }
    private fun refreshConnectionStates() {
        val next = connections.snapshot(contacts.map { it.uid }, authenticated, channels.keys, pinging, signal?.connected == true)
        if (next != connectionStates) { connectionStates = next; changed() }
    }
    private fun connectContacts() {
        if (!ready || !foreground || !wanted || !lease.decision().active || signal?.connected != true) return
        val peers = contacts.filter { it.uid in remoteAccepted }.map { it.uid }
        for (peer in connections.due(peers, authenticated, channels.keys, pinging, activePeer)) {
            if (!pinging.add(peer)) continue
            connections.began(peer)
            val epoch = contactEpoch[peer] ?: 0L
            io.execute {
                val window = try { signal!!.connectContact(peer) } catch (_: Exception) { null }
                executor.execute response@{
                    pinging.remove(peer)
                    if (epoch != (contactEpoch[peer] ?: 0L) || contacts.none { it.uid == peer }) { refreshConnectionStates(); return@response }
                    if (window == null || window.optString("reason") == "service_unavailable") connections.failed(peer)
                    else if (!window.optBoolean("online")) connections.available(peer, false)
                    else if (wanted && lease.decision().active) try {
                        connections.available(peer, true)
                        startPeer(peer, ProtocolRules.id(window.get("requestId")), window.getString("sender") == identity!!.uid)
                    } catch (_: Exception) { connections.failed(peer) }
                    if (peer in explicitPings && wanted && lease.decision().active) try { pingNow(peer) } catch (_: Exception) { }
                    refreshConnectionStates()
                }
            }
        }
        refreshConnectionStates()
    }
    private fun event(value: JSONObject) {
        val peer = value.optString("sender")
        when (value.optString("kind")) {
            "contact_request" -> { requestsAt = 0; refreshRequests() }
            "chat_open" -> {
                if(contacts.none { it.uid == peer } || !preferences.getBoolean("chatWake",true) || preferences.getBoolean("chatWakeStopped",false)) return
                val expiry=value.opt("expiresAt").toString().toLongOrNull() ?: return
                if(expiry <= System.currentTimeMillis() || expiry > System.currentTimeMillis()+90000) return
                val fresh=lease.ping(ProtocolRules.id(value.get("requestId")),(expiry-System.currentTimeMillis()).coerceIn(1,60000))
                wanted=true; if(fresh) onLease?.invoke(lease.decision().active)
                connectWindow(peer,value)
            }
            "connect" -> {
                if (contacts.none { it.uid == peer } || !wanted || !lease.decision().active) return
                val expiry = value.opt("expiresAt").toString().toLongOrNull() ?: return
                if (expiry <= System.currentTimeMillis() || expiry > System.currentTimeMillis() + 90000) return
                // Passive setup never shows an alert or extends a Ping lease.
                startPeer(peer, ProtocolRules.id(value.get("requestId")), value.optString("initiator", peer) == identity!!.uid)
            }
            "ping", "nudge" -> {
                if (contacts.none { it.uid == peer }) return
                val expiry = value.opt("expiresAt").toString().toLongOrNull() ?: return
                if (expiry <= System.currentTimeMillis() || expiry > System.currentTimeMillis() + 90000) return
                val id = ProtocolRules.id(value.get("requestId"))
                val fresh = lease.ping(id)
                if (value.optBoolean("onlineAlert", false) && value.optString("kind") == "ping") {
                    val alertKey = "online.seen." + WireCodec.digest(id.toByteArray())
                    if (onlineWatch(peer) && !preferences.contains(alertKey)) {
                        val now = System.currentTimeMillis()
                        val edit = preferences.edit().putLong(alertKey, now)
                        preferences.all.filter { it.key.startsWith("online.seen.") && (it.value as? Long ?: 0) < now - 86400000 }.forEach { edit.remove(it.key) }
                        edit.apply(); onOnlineAlert?.invoke(peer)
                    }
                } else if (fresh && !foreground && value.optString("kind") == "ping") onAlert?.invoke(peer, null)
                wanted = true; onLease?.invoke(lease.decision().active)
                connectWindow(peer,value)
            }
            "signal" -> {
                val contact = contacts.find { it.uid == peer } ?: return
                // Authenticate setup BEFORE allocating a native peer or replacing a request.
                val setup = signal!!.openSetup(contact.bundle, value)
                val id = value.getString("requestId"); val purpose = value.getString("purpose")
                if (channels[peer]?.requestId != id) { if (purpose != "offer") return; startPeer(peer, id, false) }
                if(channels[peer]?.medium=="internet") channels[peer]?.receiveSetup(purpose, setup)
            }
            "revoked" -> channels[value.optString("peer")]?.close()
            "contact" -> {
                if (value.optBoolean("accepted")) completeRequestedContact(peer)
                else { channels[peer]?.close(); remoteAccepted.remove(peer) }
                acceptRegisteredContacts()
            }
            "error" -> { status = "Coordination request rejected; check mutual contact acceptance"; changed() }
        }
    }
    fun push(value: JSONObject) = work {
        if (!ready) {
            if (!preferences.getBoolean("setup", false)) return@work
            // A push does not create a new identity or open a second vault.
            setup(preferences.getString("name", "Cute Chat")!!, false)
            executor.execute { try { event(value); network() } catch (_: Exception) { } }
        } else { event(value); network() }
    }
    private fun startPeer(peer: String, id: String, initiator: Boolean) {
        val remote = contacts.find { it.uid == peer } ?: return
        if(channels[peer]?.ready==true && peer in authenticated) return
        installPeer(peer,id) { open,frame,writable,closed ->
            NativePeerChannel(factory!!,signal!!,remote.bundle,id,initiator,executor,open,frame,writable,closed)
        }
    }
    private fun installPeer(peer:String,id:String,create:(()->Unit,(String)->Unit,()->Unit,()->Unit)->NativeLink) {
        if (channels[peer]?.requestId == id) return
        check(channels.size < 16 || channels.containsKey(peer)) { "Too many simultaneous peers" }
        cancelPeerBatch(peer);largeFiles?.disconnected(peer);liveReady=liveReady-peer;receivingPeers=receivingPeers-peer
        quickPeers.remove(peer);quickReady=quickPeers.toSet();handoffPeers.remove(peer);handoffReady=handoffPeers.toSet()
        channels.remove(peer)?.close(); authenticated.remove(peer); liveAssistPeers=liveAssistPeers-peer; onAssistDisconnect?.invoke(peer); peerPresence.remove(peer); callRingPeers.remove(peer)
        lateinit var channel: NativeLink
        channel = create({
                if (channels[peer] === channel) {
                    helloIds[peer] = engine!!.enqueue(peer, "hello", JSONObject().put("requestId", id).put("photoHash", selfPhotoHash() ?: JSONObject.NULL))
                    flush(peer)
                }
            }, { raw ->
                if (channels[peer] === channel) try {
                    val received = engine!!.receive(peer, raw, medium=channel.medium)
                    if (received.kind == "hello" && received.data.getString("requestId") == id && authenticated.add(peer)) {
                        if(channel.medium=="internet") presenceIo.execute { runCatching { signal?.connectionReady(peer,id) } }
                        val activity=activityWakeAt[peer]
                        if(activity!=null && SystemClock.elapsedRealtime()-activity in 0..60000 && connectionNotifiedAt[peer]!=activity) { connectionNotifiedAt[peer]=activity;if(!foreground || !viewUnlocked) onActivityConnected?.invoke(peer) }

                        val label = vault!!.get("ping.label.$peer")
                        if (label != null) engine!!.enqueue(peer, "ping_label", JSONObject(label))
                        engine!!.enqueue(peer,"consent_request",NativeExtensions.features(BuildConfig.REMOTE_CONTROL))
                        presence()
                    }
                    if (peer in authenticated) {
                        if (received.kind == "consent_request") {
                            val extension = NativeExtensions.parse(received.data)
                            if(extension?.optString("type") == "features") {
                                val features = extension.getJSONArray("features")
                                liveReady=if((0 until features.length()).any { features.getString(it)=="live_file" }) liveReady+peer else liveReady-peer
                                if(peer !in liveReady) receivingPeers=receivingPeers-peer
                                readyControl(peer)
                                if ((0 until features.length()).any { features.getString(it)=="large_file" }) quickPeers.add(peer) else quickPeers.remove(peer)
                                quickReady=quickPeers.toSet()
                                if((0 until features.length()).any { features.getString(it)=="wifi_handoff" }) handoffPeers.add(peer) else handoffPeers.remove(peer)
                                handoffReady=handoffPeers.toSet()
                                liveAssistPeers=if((0 until features.length()).any { features.getString(it)=="assist" }) liveAssistPeers+peer else liveAssistPeers-peer
                                if ((0 until features.length()).any { features.getString(it) == "call_ring" }) callRingPeers.add(peer) else callRingPeers.remove(peer)
                                onCallPeerReady?.invoke(peer); sendProfile(peer)
                            }
                            if(extension?.optString("type")?.startsWith("handoff_")==true && channel.medium=="bluetooth" && engine!!.supports(peer,"wifi_handoff")) wifiHandoff.control(peer,extension)
                            if(extension?.optString("type")=="stream_ready" && peer in liveReady) {
                                receivingPeers=if(extension.getBoolean("enabled")) receivingPeers+peer else receivingPeers-peer
                            }
                            if(extension?.optString("type")?.startsWith("stream_")==true && extension.optString("type")!="stream_ready" && channel is FileStreamLink && channel.medium!="bluetooth" && peer in liveReady) largeFiles?.control(peer,extension,channel as FileStreamLink)
                            if(extension?.optString("type")?.startsWith("large_")==true) {
                                val local=channel as? LocalStreamLink
                                if(local!=null && local.medium=="wifi" && engine!!.supports(peer,"large_file")) largeFiles?.control(peer,extension,local)
                            }
                            if(extension?.optString("type")?.startsWith("assist_")==true && supportsAssist(peer)) onAssistControl?.invoke(peer,extension)
                            if(extension?.optString("type") == "call_ring" && supportsCallRing(peer)) onCallControl?.invoke(peer,"call_ring",extension)
                            if(extension?.optString("type") == "profile" && !extension.isNull("photoHash")) {
                                val hash = extension.getString("photoHash")
                                val current = vault!!.transaction { it.rows("SELECT photo_hash FROM contacts WHERE uid=?",peer).singleOrNull()?.get("photo_hash") }
                                if(current != hash) engine!!.enqueue(peer,"profile_request",JSONObject().put("hash",hash))
                            }
                            if(extension?.optString("type") == "delete") { largeFiles?.prune();libraryDirty = true; deletionRevision++ }
                        }
                        if(received.kind == "profile_request" && engine!!.supports(peer,"profile")) {
                            val hash = selfPhotoHash()
                            if(hash != null && received.data.optString("hash") == hash) {
                                val bytes = vault!!.get("profile.self.photo")?.let { WireCodec.decode(it,512000) }
                                if(bytes != null) try { engine!!.stageAttachment(peer,"profile.jpg","image/jpeg",bytes,"photo") } finally { bytes.fill(0) }
                            }
                        }
                        if(received.kind == "presence") {
                            peerPresence.received(peer,received.data.getBoolean("active"))
                            val value=if(received.data.getBoolean("sharing") && !received.data.isNull("lastSeen")) received.data.getLong("lastSeen") else null
                            lastSeen=if(value != null && value in 0..(System.currentTimeMillis()+90000)) lastSeen+(peer to value) else lastSeen-peer
                        }
                        if (received.kind == "capture_notice") {
                            captureNotice = Triple(SystemClock.elapsedRealtimeNanos(), peer, received.data.getString("context"))
                            onCaptureNotice?.invoke(peer, received.data.getString("context"))
                        }
                        if (received.kind == "transfer_finish") {
                            libraryDirty = true
                            val media = vault!!.transaction { it.rows("SELECT data FROM messages WHERE id=? AND peer=? AND outgoing=0", received.data.getString("id"), peer).singleOrNull() }
                            if (media != null) {
                                val meta = JSONObject(media["data"] as String)
                                if (meta.optInt("viewLimit") == 0) onMediaReceived?.invoke(peer, meta.getString("id"), meta.getString("mime"), meta.getString("name"))
                            }
                        }
                        if (received.kind.startsWith("call_") || received.kind in setOf("bt_offer","bt_answer")) onCallControl?.invoke(peer, received.kind, received.data)
                        if(received.kind=="local_ping" && AlertPolicy.shouldAlert(foreground,viewUnlocked,visibleConversationPeer,peer)) onAlert?.invoke(peer,null)
                        if(received.progress && SystemClock.elapsedRealtime()-(transferActivityAt[peer] ?: -10000L)>=10000) {
                            transferActivityAt[peer]=SystemClock.elapsedRealtime();lease.actualContentActivity(preferences.getInt("grace",5).coerceIn(1,30))
                        }
                        if (received.content) {
                            lease.actualContentActivity(preferences.getInt("grace", 5).coerceIn(1, 30))
                            if (AlertPolicy.shouldAlert(foreground,viewUnlocked,visibleConversationPeer,peer)) onAlert?.invoke(peer,
                                if (preferences.getBoolean("preview", false) && received.kind == "text") received.data.getString("text") else null)
                            if (AlertPolicy.visibleConversation(foreground,viewUnlocked,visibleConversationPeer,peer)) read(peer)
                        }
                    }
                    // Chunk progress and chunk receipts do not change visible
                    // history. Avoid hundreds of full contact/message scans.
                    if(!(received.kind=="consent_request" && NativeExtensions.parse(received.data)?.optString("type")=="stream_progress") && received.kind !in setOf("transfer_start","transfer_chunk","duplicate","call_ice","profile_request") &&
                        !(received.kind == "ack" && received.data.isNull("deliveredId"))) refresh()
                    flush(peer)
                } catch (_: Exception) { status = "Encrypted frame rejected; connection closed without resetting identity"; channel.close(); changed() }
            }, { if (channels[peer] === channel) flush(peer) }, {
                if (channels[peer] === channel) { cancelPeerBatch(peer);largeFiles?.disconnected(peer);liveReady=liveReady-peer;receivingPeers=receivingPeers-peer;channels.remove(peer); authenticated.remove(peer); liveAssistPeers=liveAssistPeers-peer; onAssistDisconnect?.invoke(peer); peerPresence.remove(peer); callRingPeers.remove(peer); connections.closed(peer); refresh() }
            })
        channels[peer] = channel
        if(channel is FileStreamLink && channel.medium!="bluetooth") channel.onBinary={ transfer,cipher ->
            check(fileRoutes[peer]===channel && peer in connected);largeFiles!!.binary(peer,channel,transfer,cipher)
        }
        refreshConnectionStates()
        status = "Establishing direct encrypted connection"
        try { channel.start() } catch (failure: Throwable) { channel.close(); throw failure }
        refresh()
    }
    private fun flush(peer: String) {
        val channel = channels[peer] ?: return
        if (!channel.ready) return
        engine!!.activatePending(peer); engine!!.pumpMediaStates(peer)
        val pausePhotos=channel.medium=="bluetooth" && (reservedCallPeer==peer || engine!!.nonPhotoPending(peer))
        if(!pausePhotos) engine!!.pumpTransfers(peer)
        val now = SystemClock.elapsedRealtime()
        for (row in engine!!.pending(peer)) {
            val id = row["id"] as String
            if(pausePhotos && row["priority"]==2L) continue
            if (peer !in authenticated && id != helloIds[peer] && row["needs_ack"] != 0L) continue
            if (now - (sentAt[id] ?: -5000L) < 3000) continue
            if (!channel.send(row["frame"] as String,urgent=(row["priority"] as? Long ?: 2L)<2)) break
            if(row["priority"]==2L && id !in sentAt && now-(transferActivityAt[peer] ?: -10000L)>=10000 && engine!!.pendingPhoto(peer)) {
                transferActivityAt[peer]=now;lease.actualContentActivity(preferences.getInt("grace",5).coerceIn(1,30))
            }
            engine!!.markHandedOff(row); sentAt[id] = now
        }
        if (sentAt.size > 2048) sentAt.entries.removeAll { now - it.value > 120000 }
    }
    fun send(peer: String, text: String, reply: String?) = work {
        require(text.trim().length in 1..4000)
        val data = JSONObject().put("text", text.trim()); if (reply != null) data.put("replyTo", reply)
        engine!!.enqueue(peer, "text", data, true); flush(peer); refresh()
        lease.actualContentActivity(preferences.getInt("grace", 5).coerceIn(1, 30))
        wakeForActivity(peer)
        if (peer !in authenticated && channels[peer] == null) pingNow(peer)
    }
    fun react(peer: String, id: String, emoji: String) = work { engine!!.react(peer, id, emoji); flush(peer); refresh() }
    fun deleteForEveryone(peer: String, id: String) = work { engine!!.deleteForEveryone(peer,id);largeFiles?.prune(); deletionRevision++; libraryDirty = true; refresh(); flush(peer) }
    fun checkMediaDeleted(peer: String, id: String, completed: (Boolean) -> Unit) = work {
        val deleted = vault?.get("deleted.$peer.$id") != null
        main.post { completed(deleted) }
    }
    private fun selfPhotoHash() = vault?.get("profile.self.hash")
    private fun sendProfile(peer: String) {
        if(peer !in authenticated || contacts.none { it.uid==peer } || engine?.supports(peer,"profile") != true) return
        val data = JSONObject().put("type","profile").put("bio",vault!!.get("profile.self.bio") ?: "").put("photoHash",selfPhotoHash() ?: JSONObject.NULL)
        engine!!.enqueue(peer,"consent_request",NativeExtensions.envelope(data)); flush(peer)
    }
    fun saveProfile(bio: String, photo: ByteArray? = null, clearPhoto: Boolean = false, completed: () -> Unit = {}) = work {
        try {
            check(foreground && viewUnlocked)
            NativeExtensions.envelope(JSONObject().put("type","profile").put("bio",bio).put("photoHash",JSONObject.NULL))
            require(photo == null || photo.size in 1..512000)
            vault!!.transaction { tx ->
                tx.execute("INSERT OR REPLACE INTO kv(key,value) VALUES('profile.self.bio',?)",bio)
                if(clearPhoto) tx.execute("DELETE FROM kv WHERE key IN ('profile.self.photo','profile.self.hash')")
                else if(photo != null) { tx.execute("INSERT OR REPLACE INTO kv(key,value) VALUES('profile.self.photo',?)",WireCodec.b64(photo)); tx.execute("INSERT OR REPLACE INTO kv(key,value) VALUES('profile.self.hash',?)",WireCodec.digest(photo)) }
            }
            authenticated.forEach(::sendProfile); changed(); main.post(completed)
        } finally { photo?.fill(0) }
    }
    fun readProfile(peer: String?, completed: (String, ByteArray?) -> Unit) = work {
        val bio: String; val photo: ByteArray?
        if(peer == null) { bio = vault!!.get("profile.self.bio") ?: ""; photo = vault!!.get("profile.self.photo")?.let { WireCodec.decode(it,512000) } }
        else {
            require(contacts.any { it.uid == peer })
            bio = vault!!.get("profile.$peer")?.let(::JSONObject)?.optString("bio") ?: ""
            photo = vault!!.transaction { it.rows("SELECT photo FROM contacts WHERE uid=? AND blocked=0",peer).singleOrNull()?.get("photo") as? ByteArray }
        }
        main.post { try { completed(bio,photo) } finally { photo?.fill(0) } }
    }
    fun sendAttachment(peer:String,name:String,mime:String,bytes:ByteArray,viewLimit:Int=0,completed:(Boolean)->Unit={})=work {
        var succeeded=false
        try {
            require(viewLimit in 0..2);LocalSharingRules.attachment(transport(peer),mime,bytes.size)
            if(transport(peer)=="bluetooth") check(!engine!!.pendingPhoto(peer)) { "Wait for this photo to arrive before sending another" }
            engine!!.stageAttachment(peer,name,mime,bytes,when(viewLimit) { 1->"view_once";2->"view_twice";else->"file" })
            succeeded=true;libraryDirty=true;flush(peer);refresh()
            if(peer !in authenticated && channels[peer]==null) pingNow(peer)
            wakeForActivity(peer)
        } finally { bytes.fill(0);main.post { completed(succeeded) } }
    }
    fun exportAttachment(peer: String, id: String, onFailure: (() -> Unit)? = null, deliver: (ByteArray) -> Unit) = work {
        try {
            val bytes = engine!!.attachment(peer,id)
            try { deliver(bytes) } finally { bytes.fill(0) }
        } catch (failure: Exception) { if(onFailure != null) onFailure() else throw failure }
    }
    fun readMediaPreview(peer: String, id: String, deliver: (ByteArray?) -> Unit) = work {
        val bytes = if(mediaLibrary.any { it.peer == peer && it.id == id && GalleryTimeline.visual(it.mime) })
            runCatching { engine!!.attachment(peer,id) }.getOrNull() else null
        deliver(bytes)
    }
    fun openMedia(peer: String, id: String, onFailure: (() -> Unit)? = null, deliver: (ByteArray) -> Unit) = work {
        try {
            val bytes = engine!!.openMedia(peer, id); refresh()
            try { deliver(bytes) } finally { bytes.fill(0) }
            flush(peer)
        } catch (failure: Exception) { onFailure?.invoke(); throw failure }
    }
    fun expireMedia(peer: String, id: String) = work { engine!!.expireMedia(peer, id); refresh(); flush(peer) }
    fun reportCapture(peer: String, callId: String?) = work {
        engine!!.enqueue(peer, "capture_notice", JSONObject().put("context", if (callId == null) "chat" else "call").put("callId", callId ?: JSONObject.NULL))
        flush(peer); refresh()
    }
    fun sendControl(peer: String, kind: String, data: JSONObject) {
        check(peer in authenticated && channels[peer]?.ready == true) { "Both phones must be connected" }
        engine!!.enqueue(peer, kind, data); flush(peer)
    }
    private fun read(peer: String) {
        if (!AlertPolicy.visibleConversation(foreground,viewUnlocked,visibleConversationPeer,peer) || peer !in authenticated || !preferences.getBoolean("read", true)) return
        val ids = vault!!.transaction { tx ->
            val found = tx.rows("SELECT id FROM messages WHERE peer=? AND outgoing=0 AND status<3 ORDER BY created_at LIMIT 100", peer)
            found.forEach { tx.execute("UPDATE messages SET status=3 WHERE id=?", it["id"]) }; found.map { it["id"] as String }
        }
        if (ids.isNotEmpty()) engine!!.enqueue(peer, "read", JSONObject().put("ids", JSONArray(ids)))
    }
    fun recordCallStart(id: String, peer: String, incoming: Boolean, video: Boolean, now: Long = System.currentTimeMillis()) = work {
        privateLibrary?.begin(id, peer, incoming, video, now); refreshLibrary()
    }
    fun recordCallConnected(id: String, now: Long = System.currentTimeMillis()) = work { privateLibrary?.connected(id, now); refreshLibrary() }
    fun recordCallEnd(id: String, duration: Long, outcome: String, now: Long = System.currentTimeMillis()) = work {
        val ended = privateLibrary?.finish(id, now, duration, outcome)
        refreshLibrary(); refresh()
        if (ended?.incoming == true && ended.connected == null && ended.outcome == "missed" &&
            AlertPolicy.shouldAlert(foreground,viewUnlocked,visibleConversationPeer,ended.peer))
            main.post { onMissedCall?.invoke(ended.peer,ended.id,ended.video) }
    }
    private fun refreshLibrary() {
        callHistory = privateLibrary?.calls() ?: emptyList()
        if (libraryDirty) { val large=largeFiles?.media() ?: emptyList();largeIds=large.map { it.id }.toSet()
            mediaLibrary = ((privateLibrary?.media() ?: emptyList())+large).sortedByDescending { it.at };libraryDirty = false }
        changed()
    }
    private fun refresh() {
        if (libraryDirty) refreshLibrary()
        val currentVault = vault ?: return
        currentVault.transaction { tx ->
            contacts = tx.rows("SELECT uid,alias,bundle FROM contacts WHERE blocked=0 ORDER BY alias").map {
                Contact(it["uid"] as String, it["alias"] as String, JSONObject(it["bundle"] as String)) }
            blockedContacts = tx.rows("SELECT uid,alias,bundle FROM contacts WHERE blocked=1 ORDER BY alias").map {
                Contact(it["uid"] as String, it["alias"] as String, JSONObject(it["bundle"] as String)) }
            labels = contacts.mapNotNull { contact -> tx.rows("SELECT value FROM kv WHERE key=?", "ping.label.${contact.uid}").singleOrNull()?.let {
                contact.uid to JSONObject(it["value"] as String).getString("label")
            } }.toMap()
            deletePeers = contacts.filter { contact ->
                val features = tx.rows("SELECT value FROM kv WHERE key=?","features.${contact.uid}").singleOrNull()?.get("value") as? String
                features?.let { JSONObject(it).optJSONArray("features") }?.let { list -> (0 until list.length()).any { list.optString(it) == "delete" } } == true
            }.map { it.uid }.toSet()
            messages = activePeer?.let { peer -> tx.rows("SELECT * FROM messages WHERE peer=? ORDER BY created_at DESC LIMIT 100", peer).reversed().map {
                val reaction = tx.rows("SELECT value FROM kv WHERE key=?", "reaction.$peer.${it["id"]}").singleOrNull()?.get("value") as String?
                Message(it["id"] as String, peer, it["outgoing"] == 1L, it["kind"] as String,
                    JSONObject(it["data"] as String), (it["status"] as Long).toInt(), it["created_at"] as Long,
                    reaction?.let { raw -> val values = JSONObject(raw); values.keys().asSequence().map { who -> values.getJSONObject(who).getString("emoji") }.joinToString(" ") } ?: "")
            } } ?: emptyList()
        }
        fileRoutes=channels.mapNotNull { (p,ch)->(ch as? FileStreamLink)?.let { p to it } }.toMap()
        localEndpoints=channels.mapNotNull { (p,ch)->(ch as? LocalStreamLink)?.let { it.endpoint to p } }.toMap()
        transports=channels.mapValues { it.value.medium }
        connected = authenticated.toSet(); online = peerPresence.online(connected); refreshConnectionStates(); changed()
    }
    private fun disconnect() {
        quickBatchGeneration++;quickBatchBusy=false;quickBatchPeer=null;liveReady=emptySet();receivingPeers=emptySet();fileRoutes=emptyMap();lease.fileTransfer(false);transferHolding=false
        wifiHandoff.stop(true);quickPeers.clear();quickReady=emptySet();handoffPeers.clear();handoffReady=emptySet()
        quickShare.disable()
        channels.keys.toList().forEach { largeFiles?.disconnected(it) }
        local.stop();localAdmissions.clear();localRequests=emptyList()
        channels.keys.toList().forEach { onAssistDisconnect?.invoke(it) };liveAssistPeers=emptySet()
        channels.values.toList().forEach { it.close() }; channels.clear(); authenticated.clear(); callRingPeers.clear(); sentAt.clear();transferActivityAt.clear(); helloIds.clear()
        peerPresence.clear()
        connections.reset(); explicitPings.clear(); pinging.clear()
        signal?.disconnect(); status = "Offline · chat stays on this device"; refresh()
        pendingWindows.clear()
        pendingChatOpen=null
    }
}
