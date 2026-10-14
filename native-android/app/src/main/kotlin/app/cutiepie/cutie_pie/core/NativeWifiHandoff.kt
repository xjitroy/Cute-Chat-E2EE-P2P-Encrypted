package app.cutiepie.cutie_pie.core

import android.content.Context
import android.net.*
import android.net.wifi.*
import android.os.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Android public APIs only. Receiver hosts; selected sender joins. Never bind the process network. */
@android.annotation.SuppressLint("MissingPermission")
class NativeWifiHandoff(private val context:Context,private val owner:ScheduledExecutorService,
    private val local:NativeLocalSharing,private val bluetooth:(String)->Boolean,
    private val wifi:(String)->Boolean,private val consent:()->Boolean,private val busy:()->Boolean,
    private val send:(String,JSONObject)->Unit,private val changed:()->Unit) {
    data class Offer(val peer:String,val id:String)
    @Volatile var offer:Offer?=null;private set
    @Volatile var status="";private set
    private var gate:WifiHandoffRules.Gate?=null
    @Volatile var setupPending=false;private set
    private var beforeHost:Set<String> = emptySet()
    private var reservation:WifiManager.LocalOnlyHotspotReservation?=null
    private var callback:ConnectivityManager.NetworkCallback?=null
    private var touched=0L
    private var networkConnectedAt=0L
    private val main=Handler(Looper.getMainLooper())
    private val connectivity=context.getSystemService(ConnectivityManager::class.java)
    init { owner.scheduleWithFixedDelay({ runCatching { tick() }.onFailure { stop(true,"Automatic Wi-Fi failed; use the same Wi-Fi or manual hotspot") } },1,1,TimeUnit.SECONDS) }
    fun begin(peer:String) {
        check(Build.VERSION.SDK_INT>=29) { "Automatic joining needs Android10+; connect the same Wi-Fi first" }
        if(wifi(peer)) return
        check(bluetooth(peer) && !busy()) { "Finish calls/transfers and connect this phone over Bluetooth first" }
        check(gate==null) { "Finish or cancel the current nearby connection" }
        val entry=WifiHandoffRules.Gate(peer,WireCodec.id(),false,SystemClock.elapsedRealtime())
        gate=entry;setupPending=true;local.holdForHandoff=true;touched=entry.began;status="Waiting for the recipient to allow a local hotspot"
        send(peer,JSONObject().put("type","handoff_request").put("id",entry.id).put("expiresAt",System.currentTimeMillis()+WifiHandoffRules.SETUP_MS));changed()
    }
    fun control(peer:String,v:JSONObject) {
        if(!bluetooth(peer)) return
        WifiHandoffRules.validate(v)
        val id=v.getString("id")
        when(v.getString("type")) {
            "handoff_request" -> {
                if(!WifiHandoffRules.fresh(v.getLong("expiresAt"),System.currentTimeMillis()) || !consent() || busy() || gate!=null || Build.VERSION.SDK_INT<26) {
                    send(peer,JSONObject().put("type","handoff_abort").put("id",id));return
                }
                val e=WifiHandoffRules.Gate(peer,id,true,SystemClock.elapsedRealtime());gate=e;setupPending=false;touched=e.began
                offer=Offer(peer,id);status="Nearby file request · allow a temporary local hotspot?";changed()
            }
            "handoff_wifi" -> {
                val e=gate ?: return
                if(!e.credentials(peer,id,SystemClock.elapsedRealtime())) return
                if(busy()) { stop(true,"A call or transfer is active; retry when it finishes");return }
                // Never retain credentials in preferences, discovery, UI, crash logs or cloud signaling.
                join(e,v)
            }
            "handoff_abort" -> if(gate?.matches(peer,id)==true) stop(false,"Nearby connection declined or stopped")
        }
    }
    fun accept(id:String,allow:Boolean) {
        if(Build.VERSION.SDK_INT<26) return
        val e=gate ?: return
        if(e.id!=id || !e.host) return
        if(!allow || !consent() || busy()) { stop(true,"Nearby connection declined");return }
        if(!e.accept(e.peer,id,SystemClock.elapsedRealtime())) return
        offer=null;setupPending=true;local.holdForHandoff=true;status="Starting a temporary local hotspot";changed()
        if(!local.wifi) local.start(true,local.bluetooth)
        beforeHost=local.addresses().map { it.substringBefore(':') }.toSet()
        try {
            context.applicationContext.getSystemService(WifiManager::class.java).startLocalOnlyHotspot(object:WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(value:WifiManager.LocalOnlyHotspotReservation) { owner.execute {
                    if(gate!==e || e.phase!="hosting") { release(value);return@execute }
                    reservation=value
                    if(Build.VERSION.SDK_INT<26) { release(value);return@execute }
                    runCatching { advertise(e,value,0) }.onFailure { stop(true,"Hotspot unavailable; connect both phones to the same Wi-Fi") }
                } }
                override fun onFailed(reason:Int) { owner.execute { if(gate===e) stop(true,"Android could not start the hotspot ($reason); use the same Wi-Fi") } }
                override fun onStopped() { owner.execute { if(gate===e) stop(true,"Local hotspot stopped") } }
            },main)
        } catch(_:Exception) { stop(true,"Allow nearby/location permission and enable Wi-Fi; or use the same network") }
    }
    @androidx.annotation.RequiresApi(26)
    @Suppress("DEPRECATION") private fun advertise(e:WifiHandoffRules.Gate,value:WifiManager.LocalOnlyHotspotReservation,attempt:Int) {
        if(gate!==e) return
        val addresses=local.addresses().filter { it.substringBefore(':') !in beforeHost }.take(8)
        if(addresses.isEmpty()) {
            check(attempt<20)
            owner.schedule({ runCatching { advertise(e,value,attempt+1) }.onFailure { if(gate===e) stop(true,"Hotspot address unavailable") } },500,TimeUnit.MILLISECONDS);return
        }
        val ssid:String;val password:String;val security:String
        if(Build.VERSION.SDK_INT>=30) {
            val c=value.softApConfiguration;ssid=checkNotNull(c.ssid);password=checkNotNull(c.passphrase)
            security=when(c.securityType) { SoftApConfiguration.SECURITY_TYPE_WPA2_PSK -> "wpa2";SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> "wpa3";else -> error("Unsupported hotspot security") }
        } else { val c=checkNotNull(value.wifiConfiguration);ssid=checkNotNull(c.SSID).removeSurrounding("\"");password=checkNotNull(c.preSharedKey).removeSurrounding("\"");security="wpa2" }
        val port=addresses.first().substringAfterLast(':').toInt()
        val config=JSONObject().put("type","handoff_wifi").put("id",e.id).put("ssid",ssid).put("password",password).put("security",security)
            .put("port",port).put("hosts",JSONArray(addresses.map { it.substringBefore(':') }))
        WifiHandoffRules.validate(config);send(e.peer,config);status="Hotspot ready · waiting for the other phone";changed()
    }
    private fun join(e:WifiHandoffRules.Gate,v:JSONObject) {
        if(Build.VERSION.SDK_INT<29) { stop(true,"Connect to the hotspot using Android Wi-Fi settings");return }
        val spec=WifiNetworkSpecifier.Builder().setSsid(v.getString("ssid")).apply {
            if(v.getString("security")=="wpa3") setWpa3Passphrase(v.getString("password")) else setWpa2Passphrase(v.getString("password"))
        }.build()
        val hosts=v.getJSONArray("hosts").let { a -> (0 until a.length()).map { a.getString(it) } };val port=v.getInt("port")
        val cb=object:ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network:Network) { owner.execute {
                if(gate!==e) return@execute
                local.handoffNetwork=network;networkConnectedAt=SystemClock.elapsedRealtime()
                if(!local.wifi) local.start(true,local.bluetooth)
                connectHost(e,network,hosts,port,0)
            } }
            override fun onLost(network:Network) { owner.execute { if(gate===e && local.handoffNetwork==network) stop(true,"Nearby Wi-Fi connection lost") } }
            override fun onUnavailable() { owner.execute { if(gate===e) stop(true,"Wi-Fi connection not approved; retry or use the same Wi-Fi") } }
        }
        callback=cb;status="Approve the nearby Wi-Fi connection in Android";changed()
        try { connectivity.requestNetwork(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(spec).build(),cb,90_000) }
        catch(_:Exception) { stop(true,"Automatic joining unavailable; allow nearby/location permission or use the same Wi-Fi") }
    }
    private fun connectHost(e:WifiHandoffRules.Gate,network:Network,hosts:List<String>,port:Int,attempt:Int) {
        if(gate!==e) return
        val properties=connectivity.getLinkProperties(network)
        val addresses=hosts.map { InetAddress.getByName(it) }
        val host=addresses.firstOrNull { address -> properties?.linkAddresses?.any { lp -> lp.address is java.net.Inet4Address && prefix(lp.address,address,lp.prefixLength) }==true }
        if(host==null) {
            if(attempt>=20) { stop(true,"No direct hotspot address; use manual Wi-Fi connection");return }
            owner.schedule({ connectHost(e,network,hosts,port,attempt+1) },500,TimeUnit.MILLISECONDS);return
        }
        local.manual(checkNotNull(host.hostAddress),port);status="Checking the Wi-Fi peer identity and encrypted session";changed()
    }
    private fun prefix(a:InetAddress,b:InetAddress,bits:Int):Boolean {
        val x=a.address;val y=b.address;return x.size==y.size && bits in 1..x.size*8 && (0 until bits).all { ((x[it/8].toInt() xor y[it/8].toInt()) and (1 shl (7-it%8)))==0 }
    }
    private fun tick() {
        val e=gate ?: return;val now=SystemClock.elapsedRealtime()
        if(wifi(e.peer) && e.phase in setOf("requested","joining") && local.handoffNetwork==null) {
            stop(true,"Already connected by direct Wi-Fi · files can now send");return
        }
        if(wifi(e.peer)) { if(e.phase!="connected") { e.connected();setupPending=false;touched=now;status="Encrypted Wi-Fi ready · automatic file delivery";changed() };if(busy()) touched=now }
        if(e.phase!="connected" && now-e.began>=WifiHandoffRules.SETUP_MS) stop(true,"Nearby setup timed out; select the phone to retry")
        else if(e.phase=="connected" && !busy() && now-maxOf(touched,networkConnectedAt)>=WifiHandoffRules.IDLE_MS) stop(false,"Nearby sharing finished; temporary Wi-Fi released")
    }
    private fun release(value:WifiManager.LocalOnlyHotspotReservation) { if(Build.VERSION.SDK_INT>=26) value.close() }
    fun stop(notify:Boolean=false,message:String="") {
        val e=gate;gate=null;offer=null;setupPending=false;beforeHost=emptySet();e?.close()
        if(notify && e!=null && bluetooth(e.peer)) runCatching { send(e.peer,JSONObject().put("type","handoff_abort").put("id",e.id)) }
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } };callback=null
        reservation?.let { runCatching { release(it) } };reservation=null
        local.handoffNetwork=null;local.holdForHandoff=false;status=message;changed()
    }
}
