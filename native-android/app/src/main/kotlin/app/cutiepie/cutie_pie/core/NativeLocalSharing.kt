package app.cutiepie.cutie_pie.core

import android.Manifest
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.net.nsd.*
import android.net.wifi.WifiManager
import android.os.Build
import org.json.JSONObject
import java.net.*
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

/** Explicit, foreground LAN/Classic Bluetooth discovery. No Internet/directory dependency. */
@android.annotation.SuppressLint("MissingPermission") // Guards are explicit; revoked grants fail closed without capture.
class NativeLocalSharing(private val context:Context,private val owner:ScheduledExecutorService,
    private val uid:()->String,private val known:(String)->Boolean,private val invitation:()->String,
    private val packet:(LocalStreamLink,JSONObject)->Unit,private val ended:(LocalStreamLink)->Unit,
    private val changed:()->Unit) {
    data class Device(val id:String,val name:String,val medium:String,val claimedPeer:String="")
    @Volatile var devices:List<Device> = emptyList();private set
    @Volatile var wifi=false;private set
    @Volatile var bluetooth=false;private set
    @Volatile var notice="Sharing is off";private set
    private val io=Executors.newFixedThreadPool(3)
    private val pendingAccepted=java.util.concurrent.Semaphore(16)
    private val sockets=mutableSetOf<LocalStreamLink>()
    private val connecting=mutableSetOf<String>()
    private val endpoints=linkedMapOf<String,NsdServiceInfo>()
    private val bluetoothEndpoints=linkedMapOf<String,BluetoothDevice>()
    private val nsd=context.getSystemService(NsdManager::class.java)
    private var registration:NsdManager.RegistrationListener?=null
    private var discovery:NsdManager.DiscoveryListener?=null
    private var server:ServerSocket?=null
    private var btServer:BluetoothServerSocket?=null
    private var multicast:WifiManager.MulticastLock?=null
    private var receiver:BroadcastReceiver?=null
    private var generation=0L
    private var resolving=false
    private val resolution=ArrayDeque<Pair<Long,NsdServiceInfo>>()
    private val retryAt=mutableMapOf<String,Long>()
    private fun dispatch(block:()->Unit)=owner.execute { try { block() } catch(_:Exception) { notice="Local discovery unavailable; check permissions and network";changed() } }
    private fun publish() {
        devices=(endpoints.map { (id,s)->Device(id,"Nearby Wi-Fi device","wifi",s.attributes["uid"]?.toString(Charsets.UTF_8).orEmpty()) }+
            emptyList<Device>()).take(64)
        changed()
    }
    private fun allowed(permission:String)=context.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED
    fun start(withWifi:Boolean,withBluetooth:Boolean) {
        stopDiscovery(true);sockets.toList().filter { (it.medium=="wifi" && !withWifi) || (it.medium=="bluetooth" && !withBluetooth) }.forEach { it.close() };wifi=withWifi;bluetooth=withBluetooth;val token=generation
        val failures=mutableListOf<String>()
        if(wifi) try { startWifi(token) } catch(_:Exception) { wifi=false;runCatching { server?.close() };server=null;failures.add("Wi-Fi discovery unavailable; check nearby permission/network") }
        if(bluetooth) try { startBluetooth(token) } catch(_:Exception) { bluetooth=false;runCatching { btServer?.close() };btServer=null;failures.add("Bluetooth unavailable; enable the radio and nearby permission") }
        notice=(if(wifi || bluetooth) "Nearby discovery · both people must accept" else "Sharing is off")+failures.joinToString(prefix=if(failures.isEmpty()) "" else " · ",separator=" · ");publish()
    }
    var holdForHandoff=false
    @Volatile var handoffNetwork:android.net.Network?=null
    fun stopDiscovery(force:Boolean=false) {
        if(holdForHandoff && !force) return
        generation++;resolving=false;resolution.clear()
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } };discovery=null
        registration?.let { runCatching { nsd.unregisterService(it) } };registration=null
        runCatching { server?.close() };server=null
        runCatching { btServer?.close() };btServer=null
        val adapter=context.getSystemService(BluetoothManager::class.java)?.adapter
        if(Build.VERSION.SDK_INT<31 || allowed(Manifest.permission.BLUETOOTH_SCAN)) runCatching { adapter?.cancelDiscovery() }
        receiver?.let { runCatching { context.unregisterReceiver(it) } };receiver=null
        multicast?.let { runCatching { if(it.isHeld) it.release() } };multicast=null
        endpoints.clear();bluetoothEndpoints.clear();publish()
    }
    fun stop() { holdForHandoff=false;handoffNetwork=null;stopDiscovery(true);wifi=false;bluetooth=false;sockets.toList().forEach { it.close() };connecting.clear();notice="Sharing is off";publish() }
    private fun startWifi(token:Long) {
        val socket=ServerSocket().apply { reuseAddress=true;bind(InetSocketAddress(0)) };server=socket
        multicast=context.applicationContext.getSystemService(WifiManager::class.java)?.createMulticastLock("CuteChatLocal")?.apply { setReferenceCounted(false);acquire() }
        val info=NsdServiceInfo().apply {
            serviceName="CuteChat-"+WireCodec.id().take(10);serviceType=SERVICE;port=socket.localPort
            setAttribute("uid",uid());setAttribute("v","1")
        }
        val register=object:NsdManager.RegistrationListener {
            override fun onServiceRegistered(service:NsdServiceInfo) {}
            override fun onRegistrationFailed(service:NsdServiceInfo,error:Int)=dispatch { if(generation==token) { notice="Wi-Fi discovery registration failed; manual address is available";changed() } }
            override fun onServiceUnregistered(service:NsdServiceInfo) {}
            override fun onUnregistrationFailed(service:NsdServiceInfo,error:Int) {}
        };registration=register;nsd.registerService(info,NsdManager.PROTOCOL_DNS_SD,register)
        val listener=object:NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type:String) {}
            override fun onServiceFound(service:NsdServiceInfo)=dispatch {
                if(generation==token && resolution.size<32 && service.serviceType.trimEnd('.')==SERVICE.trimEnd('.')) { resolution.add(token to service);resolveNext() }
            }
            override fun onServiceLost(service:NsdServiceInfo)=dispatch { if(token!=generation) return@dispatch;endpoints.remove("wifi:"+service.serviceName);publish() }
            override fun onDiscoveryStopped(type:String) {}
            override fun onStartDiscoveryFailed(type:String,error:Int)=dispatch { if(generation==token) { notice="Discovery blocked by this Wi-Fi; use manual address or hotspot";changed() } }
            override fun onStopDiscoveryFailed(type:String,error:Int) {}
        };discovery=listener;nsd.discoverServices(SERVICE,NsdManager.PROTOCOL_DNS_SD,listener)
        Thread({ try { while(!socket.isClosed) {
            val accepted=socket.accept()
            if(!pendingAccepted.tryAcquire()) { accepted.close();continue }
            dispatch { try { if(token!=generation || !localAddress(accepted.inetAddress)) accepted.close() else attach("wifi","incoming",accepted.getInputStream(),accepted.getOutputStream(),accepted) }
                finally { pendingAccepted.release() } }
        } } catch(_:Exception) { } },"CuteWifiListen").apply { isDaemon=true;start() }
    }
    @Suppress("DEPRECATION") private fun resolveNext() {
        if(resolving || resolution.isEmpty()) return
        val (token,info)=resolution.removeFirst();resolving=true
        nsd.resolveService(info,object:NsdManager.ResolveListener {
            override fun onResolveFailed(service:NsdServiceInfo,error:Int)=dispatch { if(token==generation) { resolving=false;resolveNext() } }
            override fun onServiceResolved(service:NsdServiceInfo)=dispatch {
                if(token!=generation) return@dispatch
                resolving=false
                val peer=service.attributes["uid"]?.toString(Charsets.UTF_8).orEmpty()
                if(peer.matches(Regex("[a-f0-9]{64}")) && peer!=uid() && service.host!=null && localAddress(service.host) && endpoints.size<32) {
                    val id="wifi:"+service.serviceName;endpoints[id]=service;publish()
                    // The TXT claim only selects a probe. Signed admission AND Signal hello still authenticate.
                    if(known(peer)) connect(id)
                }
                resolveNext()
            }
        })
    }
    private fun startBluetooth(token:Long) {
        check(Build.VERSION.SDK_INT<31 || allowed(Manifest.permission.BLUETOOTH_CONNECT))
        val adapter=checkNotNull(context.getSystemService(BluetoothManager::class.java)?.adapter);check(adapter.isEnabled) { "Enable Bluetooth first" }
        adapter.bondedDevices.take(32).forEach { device -> bluetoothEndpoints["bluetooth:"+device.address]=device;runCatching { device.fetchUuidsWithSdp() } }
        val listening=adapter.listenUsingRfcommWithServiceRecord("Cute Chat",UUID.fromString(BT_UUID));btServer=listening
        Thread({ try { while(true) {
            val accepted=listening.accept()
            if(!pendingAccepted.tryAcquire()) { accepted.close();continue }
            dispatch { try { if(token!=generation) accepted.close() else attach("bluetooth","bluetooth:"+accepted.remoteDevice.address,accepted.inputStream,accepted.outputStream,accepted) }
                finally { pendingAccepted.release() } }
        } } catch(_:Exception) { } },"CuteBluetoothListen").apply { isDaemon=true;start() }
        val canScan=if(Build.VERSION.SDK_INT>=31) allowed(Manifest.permission.BLUETOOTH_SCAN) else allowed(Manifest.permission.ACCESS_FINE_LOCATION)
        if(canScan) {
            val found=object:BroadcastReceiver() { override fun onReceive(c:Context,intent:Intent) {
                if(intent.action !in setOf(BluetoothDevice.ACTION_FOUND,BluetoothDevice.ACTION_UUID)) return
                @Suppress("DEPRECATION") val device=intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                dispatch {
                    if(token!=generation) return@dispatch
                    val id="bluetooth:"+device.address
                    if(intent.action==BluetoothDevice.ACTION_FOUND && bluetoothEndpoints.size<32 && id !in bluetoothEndpoints) {
                        bluetoothEndpoints[id]=device;runCatching { device.fetchUuidsWithSdp() }
                    }
                    @Suppress("DEPRECATION") val uuids=intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)?.filterIsInstance<android.os.ParcelUuid>().orEmpty()
                    if(uuids.any { it.uuid==UUID.fromString(BT_UUID) }) { bluetoothEndpoints[id]=device;connect(id) }
                }
            } }
            receiver=found
            if(Build.VERSION.SDK_INT>=33) context.registerReceiver(found,IntentFilter(BluetoothDevice.ACTION_FOUND).apply { addAction(BluetoothDevice.ACTION_UUID) },Context.RECEIVER_EXPORTED) else context.registerReceiver(found,IntentFilter(BluetoothDevice.ACTION_FOUND).apply { addAction(BluetoothDevice.ACTION_UUID) })
            adapter.startDiscovery()
        }
    }
    fun refresh() { if(wifi || bluetooth) start(wifi,bluetooth) }
    fun retryTrusted() {
        endpoints.forEach { (id,s) -> val peer=s.attributes["uid"]?.toString(Charsets.UTF_8).orEmpty()
            if(known(peer) && android.os.SystemClock.elapsedRealtime() >= (retryAt[id] ?: 0L)) connect(id)
        }
    }
    fun connect(id:String) {
        if(sockets.any { it.endpoint==id } || id in connecting) return
        if(sockets.size+connecting.size>=16) { notice="Up to16 simultaneous local links; disconnect one to add another";changed();return }
        connecting.add(id)
        val token=generation;retryAt[id]=android.os.SystemClock.elapsedRealtime()+15000
        val service=endpoints[id];val device=bluetoothEndpoints[id]
        io.execute {
            var resource:java.io.Closeable?=null
            try {
                val incoming:java.io.InputStream;val outgoing:java.io.OutputStream;val medium:String
                if(service!=null) {
                    require(localAddress(service.host));val socket=wifiSocket(service.host);resource=socket;socket.tcpNoDelay=true;socket.connect(InetSocketAddress(service.host,service.port),5000)
                    incoming=socket.getInputStream();outgoing=socket.getOutputStream();medium="wifi"
                } else {
                    check(device!=null);val adapter=context.getSystemService(BluetoothManager::class.java)?.adapter
                    if(Build.VERSION.SDK_INT<31 || allowed(Manifest.permission.BLUETOOTH_SCAN)) runCatching { adapter?.cancelDiscovery() }
                    val socket=device.createRfcommSocketToServiceRecord(UUID.fromString(BT_UUID));resource=socket
                    val guard=owner.schedule({ runCatching { socket.close() } },20,java.util.concurrent.TimeUnit.SECONDS)
                    try { socket.connect() } finally { guard.cancel(false) }
                    incoming=socket.inputStream;outgoing=socket.outputStream;medium="bluetooth"
                }
                val connected=resource!!
                dispatch { connecting.remove(id);if(token!=generation) connected.close() else attach(medium,id,incoming,outgoing,connected) }
                resource=null
            } catch(_:Exception) { dispatch { connecting.remove(id);notice="Device unreachable; open Sharing on both phones and check pairing or Wi-Fi isolation";changed() } }
            finally { runCatching { resource?.close() } }
        }
    }
    private fun wifiSocket(address:InetAddress):Socket {
        val connectivity=context.getSystemService(android.net.ConnectivityManager::class.java)
        val wifiNetwork=(listOfNotNull(handoffNetwork)+connectivity.allNetworks.toList()).distinct().firstOrNull { network ->
            connectivity.getNetworkCapabilities(network)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)==true &&
                connectivity.getLinkProperties(network)?.linkAddresses?.any { samePrefix(it.address,address,it.prefixLength) }==true
        }
        if(wifiNetwork!=null) return wifiNetwork.socketFactory.createSocket()
        // Hotspot hosts often have no Wi-Fi Network object: bind their directly attached address.
        val local=NetworkInterface.getNetworkInterfaces().toList().filter { permittedInterface(it) }.flatMap { it.interfaceAddresses }
            .firstOrNull { samePrefix(it.address,address,it.networkPrefixLength.toInt()) }?.address
        return Socket().apply { if(local!=null) bind(InetSocketAddress(local,0)) }
    }
    fun manual(address:String,port:Int) {
        require(wifi && address.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) && port in 1..65535)
        val host=InetAddress.getByName(address);require(localAddress(host))
        val info=NsdServiceInfo().apply { this.host=host;this.port=port }
        val id="wifi:manual:$address:$port";endpoints[id]=info;publish();connect(id)
    }
    fun addresses():List<String> = runCatching { NetworkInterface.getNetworkInterfaces().toList().flatMap { iface -> if(!permittedInterface(iface)) emptyList() else iface.inetAddresses.toList().filter { it is Inet4Address && !it.isLoopbackAddress && !it.isAnyLocalAddress && it.isSiteLocalAddress }.map { "${it.hostAddress}:${server?.localPort ?: 0}" } } }.getOrDefault(emptyList()).filter { !it.endsWith(":0") }
    private fun attach(medium:String,id:String,input:java.io.InputStream,output:java.io.OutputStream,resource:java.io.Closeable) {
        if(sockets.size>=16) { resource.close();return }
        val link=LocalStreamLink(medium,id,input,output,resource,owner,packet) { closed -> sockets.remove(closed);ended(closed);changed() }
        if(medium=="wifi" && resource is Socket) {
            val connectivity=context.getSystemService(android.net.ConnectivityManager::class.java)
            link.unmanagedNetwork=connectivity.allNetworks.none { network -> connectivity.getLinkProperties(network)?.linkAddresses?.any { it.address.address.contentEquals(resource.localAddress.address) }==true }
        }
        sockets.add(link)
        try { link.begin(invitation());changed() } catch(t:Exception) { link.close();throw t }
    }
    companion object {
        const val SERVICE="_cutechat._tcp."
        const val BT_UUID="a7fd39f1-9106-46b6-9cd9-efdc52927851"
        private fun permittedInterface(iface:NetworkInterface)=iface.isUp && !iface.isLoopback && !listOf("tun","wg","ppp","rmnet","ccmni","v4-","clat","pdp","ipsec","vti","bnep").any { iface.name.lowercase().startsWith(it) }
        private fun samePrefix(local:InetAddress,remote:InetAddress,bits:Int):Boolean {
            val a=local.address;val b=remote.address
            return a.size==b.size && bits>0 && bits<=a.size*8 && (0 until bits).all { bit -> ((a[bit/8].toInt() xor b[bit/8].toInt()) and (1 shl (7-bit%8)))==0 }
        }
        /** Only directly attached subnet/link-local addresses; never an Internet file relay. */
        fun localAddress(address:InetAddress):Boolean {
            if(address.isLoopbackAddress || address.isAnyLocalAddress || address.isMulticastAddress) return false
            return runCatching { NetworkInterface.getNetworkInterfaces().toList().any { iface -> permittedInterface(iface) && iface.interfaceAddresses.any { local ->
                samePrefix(local.address,address,local.networkPrefixLength.toInt())
            } } }.getOrDefault(false)
        }
    }
}
