package app.cutiepie.cutie_pie

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import org.webrtc.NetworkChangeDetector
import org.webrtc.NetworkMonitor
import org.webrtc.NetworkMonitorAutoDetect
import java.util.concurrent.atomic.AtomicBoolean

/** Keep the SDK monitor, additionally report local-only Wi-Fi with its real Android handle.
 * The stock callback requires INTERNET and can miss a newly joined local hotspot.
 * No process binding, radio switch, relay, capture or network credentials are involved. */
object NativeRtcNetworks {
    private var installed = false
    @Synchronized fun install() {
        if (installed) return
        NetworkMonitor.getInstance().setNetworkChangeDetectorFactory { observer, context ->
            LocalWifiDetector(observer, context.applicationContext)
        }
        installed = true
    }
    private class LocalWifiDetector(private val observer: NetworkChangeDetector.Observer, context: Context) : NetworkChangeDetector {
        private val connectivity = context.getSystemService(ConnectivityManager::class.java)
        private val closed = AtomicBoolean(false)
        private val lock = Any()
        private val local = mutableMapOf<Long, NetworkChangeDetector.NetworkInformation>()
        private val delegate = NetworkMonitorAutoDetect(object : NetworkChangeDetector.Observer() {
            override fun getFieldTrialsString() = observer.fieldTrialsString
            override fun onConnectionTypeChanged(type: NetworkChangeDetector.ConnectionType) {
                if (!closed.get()) observer.onConnectionTypeChanged(if(type==NetworkChangeDetector.ConnectionType.CONNECTION_NONE &&
                    synchronized(lock) { local.isNotEmpty() }) NetworkChangeDetector.ConnectionType.CONNECTION_WIFI else type)
            }
            override fun onNetworkConnect(info: NetworkChangeDetector.NetworkInformation) {
                if (!closed.get()) observer.onNetworkConnect(info)
            }
            override fun onNetworkDisconnect(handle: Long) {
                // Losing INTERNET is not losing an already observed local-only Wi-Fi link.
                if (!closed.get() && synchronized(lock) { handle !in local }) observer.onNetworkDisconnect(handle)
            }
            override fun onNetworkPreference(types: MutableList<NetworkChangeDetector.ConnectionType>, preference: Int) {
                if (!closed.get()) observer.onNetworkPreference(types, preference)
            }
        }, context)
        private var registered = false
        private val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = update(network)
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = update(network)
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = update(network)
            override fun onLost(network: Network) {
                val removed = synchronized(lock) { local.remove(network.networkHandle) }
                if (!closed.get() && removed != null) {
                    observer.onNetworkDisconnect(network.networkHandle)
                    observer.onConnectionTypeChanged(currentConnectionType)
                }
            }
        }
        init {
            try {
                connectivity.registerNetworkCallback(NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback)
                registered = true
                connectivity.allNetworks.forEach(::update)
            } catch (_: RuntimeException) { /* Preserve the SDK monitor if Android denies or limits registration. */ }
        }
        private fun information(network: Network): NetworkChangeDetector.NetworkInformation? {
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return null
            val properties = connectivity.getLinkProperties(network) ?: return null
            val name = properties.interfaceName ?: return null
            val addresses = properties.linkAddresses.map { it.address }.filter {
                !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isMulticastAddress
            }.map { NetworkChangeDetector.IPAddress(it.address) }.toTypedArray()
            if (addresses.isEmpty()) return null
            return NetworkChangeDetector.NetworkInformation(name, NetworkChangeDetector.ConnectionType.CONNECTION_WIFI,
                NetworkChangeDetector.ConnectionType.CONNECTION_NONE, network.networkHandle, addresses,
                NetworkChangeDetector.NetworkSlice.NO_SLICE)
        }
        private fun update(network: Network) {
            if (closed.get()) return
            val info = runCatching { information(network) }.getOrNull() ?: return
            val changed = synchronized(lock) {
                val previous = local.put(info.handle, info)
                previous == null || previous.name != info.name || !previous.ipAddresses.map { it.address.toList() }
                    .equals(info.ipAddresses.map { it.address.toList() })
            }
            if (changed && !closed.get()) {
                observer.onNetworkConnect(info)
                observer.onConnectionTypeChanged(currentConnectionType)
            }
        }
        override fun getCurrentConnectionType(): NetworkChangeDetector.ConnectionType {
            val type=delegate.currentConnectionType
            return if(type==NetworkChangeDetector.ConnectionType.CONNECTION_NONE && synchronized(lock) { local.isNotEmpty() })
                NetworkChangeDetector.ConnectionType.CONNECTION_WIFI else type
        }
        override fun supportNetworkCallback() = delegate.supportNetworkCallback()
        override fun getActiveNetworkList(): MutableList<NetworkChangeDetector.NetworkInformation> {
            val merged = delegate.activeNetworkList.orEmpty().associateBy { it.handle }.toMutableMap()
            if (!closed.get()) runCatching { connectivity.allNetworks.forEach { network -> information(network)?.let { merged[it.handle] = it } } }
            return merged.values.toMutableList()
        }
        override fun destroy() {
            if (!closed.compareAndSet(false, true)) return
            if (registered) runCatching { connectivity.unregisterNetworkCallback(callback) }
            registered = false
            synchronized(lock) { local.clear() }
            delegate.destroy()
        }
    }
}
