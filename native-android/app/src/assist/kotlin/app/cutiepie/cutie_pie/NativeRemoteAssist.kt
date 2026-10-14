package app.cutiepie.cutie_pie

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.cutiepie.cutie_pie.core.*
import org.json.JSONObject

/** Same native Signal owner as chat/calls. No cloud control, text injection or offline execution. */
class NativeRemoteAssist(private val app:CutieApplication) {
    private val owner get()=app.owner
    private val calls get()=app.calls
    private val main=Handler(Looper.getMainLooper())
    private val guard=AssistGuard()
    private data class Ticket(val call:String,val peer:String,val packages:Set<String>,val issued:Long,var authenticated:Long=0)
    private val tickets=mutableMapOf<String,Ticket>()
    @Volatile var hostEpoch=""; private set
    @Volatile var hostGranted=false; private set
    @Volatile var hostActive=false; private set
    @Volatile var allowedPackages:Set<String> = emptySet(); private set
    @Volatile var hostStatus="Control off"; private set
    @Volatile var remoteEpoch=""; private set
    @Volatile var remoteAccepted=false; private set
    data class RemoteFrame(val token:String,val width:Int,val height:Int,val rotation:Int,val active:Boolean,val received:Long,val remaining:Long)
    @Volatile var remoteFrame:RemoteFrame?=null; private set
    private var remoteGrantedAt=0L
    private var remoteSequence=0L
    private var sentInput=-1000L
    private var hostAccepted=false
    private var grantedAt=0L
    private var pendingSnapshot:RemoteControlService.Snapshot?=null
    private var lastPublished=-1000L
    init {
        owner.onAssistControl=::receive
        owner.onAssistDisconnect={ peer -> if(peer==calls.peer) { stopOwner(false);clearRemote(false) } }
        owner.executor.scheduleWithFixedDelay({
            val now=SystemClock.elapsedRealtime()
            if(hostEpoch.isNotEmpty()) {
                if(app.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked || !calls.active || calls.status!="connected" || !calls.screenSharing || calls.peer !in owner.connected || !guard.live(now) || (!hostAccepted && now-grantedAt>60_000) || RemoteControlService.instance==null) stopOwner()
                else if(hostAccepted && now-lastPublished>=700) publish(pendingSnapshot,now)
            }
            if(remoteEpoch.isNotEmpty() && (!calls.active || calls.status!="connected" || calls.peer !in owner.connected || (remoteFrame?.let { now-it.received>5000 } ?: (now-remoteGrantedAt>60_000)))) clearRemote(true)
        },500,500,java.util.concurrent.TimeUnit.MILLISECONDS)
    }
    @Synchronized fun ticket(packages:Set<String>):String? {
        if(!owner.foreground || !calls.active || calls.status!="connected" || !calls.screenSharing || !owner.supportsAssist(calls.peer) || packages.isEmpty() || packages.size>8 || RemoteControlService.instance==null || remoteEpoch.isNotEmpty()) return null
        tickets.entries.removeAll { SystemClock.elapsedRealtime()-it.value.issued>90_000 }
        if(tickets.size>=8) return null
        val key=WireCodec.id(); tickets[key]=Ticket(calls.id,calls.peer,packages.toSet(),SystemClock.elapsedRealtime());return key
    }
    @Synchronized fun ticketValid(key:String):Boolean {
        val t=tickets[key] ?: return false
        return SystemClock.elapsedRealtime()-t.issued in 0..90_000 && calls.id==t.call && calls.peer==t.peer && calls.status=="connected" && calls.screenSharing && remoteEpoch.isEmpty() && owner.supportsAssist(t.peer)
    }
    @Synchronized fun authenticated(key:String):Boolean {
        if(!ticketValid(key)) return false
        tickets[key]!!.authenticated=SystemClock.elapsedRealtime(); return true
    }
    fun grant(key:String) {
        val ticket=synchronized(this) {
            val t=tickets.remove(key)
            if(t==null || t.authenticated<=0 || SystemClock.elapsedRealtime()-t.authenticated !in 0..15_000) null else t
        } ?: return
        owner.work {
            if(calls.id!=ticket.call || calls.peer!=ticket.peer || calls.status!="connected" || !calls.screenSharing || remoteEpoch.isNotEmpty() || RemoteControlService.instance==null || !owner.supportsAssist(ticket.peer)) return@work
            stopOwner(); allowedPackages=ticket.packages;hostEpoch=WireCodec.id();hostGranted=true;hostActive=false;hostAccepted=false;grantedAt=SystemClock.elapsedRealtime()
            guard.grant(ticket.peer,ticket.call,hostEpoch,SystemClock.elapsedRealtime(),owner.preferences.getInt("assistMinutes",10).coerceIn(1,30)*60_000L,owner.preferences.getInt("assistIdle",120).coerceIn(30,300)*1000L)
            hostStatus="Waiting for the viewer to accept control"
            if(!send("assist_grant",hostEpoch)) stopOwner(false)
            main.post { RemoteControlService.instance?.refresh() }
        }
    }
    private fun send(type:String,epoch:String,extra:(JSONObject)->Unit={}):Boolean = try {
        if(!owner.supportsAssist(calls.peer)) false else {
            val body=JSONObject().put("type",type).put("callId",calls.id).put("epoch",epoch);extra(body)
            owner.sendControl(calls.peer,"consent_request",NativeExtensions.envelope(body));true
        }
    } catch (_: Exception) { false }
    private fun receive(peer:String,data:JSONObject) {
        if(!calls.active || calls.status!="connected" || calls.peer!=peer || calls.id!=data.getString("callId") || !owner.supportsAssist(peer)) return
        val epoch=data.getString("epoch")
        when(data.getString("type")) {
            "assist_grant" -> {
                if(hostEpoch.isNotEmpty()) { send("assist_revoke",epoch);return }
                clearRemote(false);remoteEpoch=epoch;remoteGrantedAt=SystemClock.elapsedRealtime();remoteAccepted=false;remoteSequence=0;remoteFrame=null
            }
            "assist_accept" -> if(hostGranted && guard.accept(peer,calls.id,epoch,SystemClock.elapsedRealtime())) { hostAccepted=true;hostActive=true;main.post { RemoteControlService.instance?.refresh() } }
            "assist_revoke" -> { if(epoch==hostEpoch) stopOwner(false);if(epoch==remoteEpoch) clearRemote(false) }
            "assist_state" -> if(epoch==remoteEpoch) remoteFrame=RemoteFrame(data.getString("window"),data.getInt("width"),data.getInt("height"),data.getInt("rotation"),data.getBoolean("active"),SystemClock.elapsedRealtime(),data.getLong("remaining"))
            "assist_input" -> {
                if(!hostActive || !hostGranted || !hostAccepted) return
                val frame=guard.authorize(peer,calls.id,epoch,data.getString("window"),data.getLong("seq"),SystemClock.elapsedRealtime()) ?: return
                val copy=JSONObject(data.toString())
                main.post { RemoteControlService.instance?.perform(epoch,frame.key,copy) { performed ->
                    if(performed) owner.work { guard.performed(epoch,SystemClock.elapsedRealtime()) }
                } }
            }
        }
    }
    fun snapshot(snapshot:RemoteControlService.Snapshot?) = owner.work {
        if(hostEpoch.isEmpty() || !hostGranted) return@work
        if(pendingSnapshot?.key!=snapshot?.key) guard.pause()
        pendingSnapshot=snapshot
        if(snapshot==null) guard.pause()
        if(hostAccepted && SystemClock.elapsedRealtime()-lastPublished>=200) publish(snapshot,SystemClock.elapsedRealtime())
    }
    private fun publish(snapshot:RemoteControlService.Snapshot?,now:Long) {
        if(!hostActive) return
        val token=if(snapshot==null) "" else guard.publish(WireCodec.id(),snapshot.key,now).token
        hostStatus=if(snapshot==null) "Control paused · choose an allowed app; credentials stay local" else "Remote control active · ${guard.remaining(now)/1000}s remaining"
        lastPublished=now
        val width=snapshot?.width ?: calls.screenDisplayWidth.coerceIn(1,8192)
        val height=snapshot?.height ?: calls.screenDisplayHeight.coerceIn(1,8192)
        send("assist_state",hostEpoch) { it.put("window",token).put("width",width).put("height",height).put("rotation",snapshot?.rotation ?: 0).put("active",snapshot!=null).put("remaining",guard.remaining(now)) }
    }
    fun acceptRemote(epoch:String) = owner.work {
        if(remoteEpoch!=epoch || calls.status!="connected") return@work
        if(send("assist_accept",epoch)) remoteAccepted=true
    }
    fun input(action:String,x:Int,y:Int,x2:Int=x,y2:Int=y,duration:Int=80,windowToken:String?=null) = owner.work {
        val frame=remoteFrame ?: return@work; val now=SystemClock.elapsedRealtime()
        if(!remoteAccepted || !frame.active || frame.token.isEmpty() || now-frame.received !in 0..1300 || now-sentInput<150 || calls.status!="connected") return@work
        sentInput=now;remoteSequence++
        send("assist_input",remoteEpoch) { it.put("window",windowToken ?: frame.token).put("seq",remoteSequence).put("action",action).put("x",x).put("y",y).put("x2",x2).put("y2",y2).put("duration",duration.coerceIn(1,600)) }
    }
    fun stopHost() { hostActive=false;hostGranted=false;owner.work { stopOwner() } }
    fun stopLocal() { hostActive=false;hostGranted=false;remoteAccepted=false;owner.work { stopOwner();clearRemote(true) } }
    private fun stopOwner(notify:Boolean=true) {
        val old=hostEpoch;hostActive=false;hostGranted=false;hostAccepted=false;hostEpoch="";allowedPackages=emptySet();pendingSnapshot=null;guard.clear();hostStatus="Control off"
        if(notify && old.isNotEmpty()) send("assist_revoke",old)
        main.post { RemoteControlService.instance?.refresh() }
    }
    fun stopRemote(expected:String?=null) { if(expected!=null && remoteEpoch!=expected) return;remoteAccepted=false;owner.work { if(expected==null || remoteEpoch==expected) clearRemote(true) } }
    private fun clearRemote(notify:Boolean) { val old=remoteEpoch;remoteEpoch="";remoteAccepted=false;remoteFrame=null;if(notify && old.isNotEmpty()) send("assist_revoke",old) }
}
