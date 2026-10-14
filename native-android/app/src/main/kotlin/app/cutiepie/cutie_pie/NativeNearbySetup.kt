package app.cutiepie.cutie_pie

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import app.cutiepie.cutie_pie.core.NearbySetupRules

/** User-initiated preparation only: normal SDK APIs, OS consent, no hidden radio toggles. */
internal class NativeNearbySetup(private val activity:MainActivity, private val allowed:()->Boolean) : AutoCloseable {
    private val handler=Handler(Looper.getMainLooper())
    private var wifi=false
    private var bluetooth=false
    private var receiving=false
    private var ready:(()->Unit)?=null
    private var pending:NearbySetupRules.Step?=null
    private var returned=false
    private var legacyWait=false
    private var deadline=0L
    private var began=0L
    private var closed=false
    private val manager get()=activity.applicationContext.getSystemService(WifiManager::class.java)
    private fun bluetoothOn()=runCatching { activity.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled==true }.getOrDefault(false)
    @Suppress("DEPRECATION")
    private fun locationOn()=runCatching {
        if(Build.VERSION.SDK_INT>=28) activity.getSystemService(LocationManager::class.java).isLocationEnabled
        else Settings.Secure.getInt(activity.contentResolver,Settings.Secure.LOCATION_MODE,Settings.Secure.LOCATION_MODE_OFF)!=Settings.Secure.LOCATION_MODE_OFF
    }.getOrDefault(false)
    private fun wifiOn()=manager?.isWifiEnabled==true
    private fun message(en:String,bn:String) { if(allowed()) Toast.makeText(activity,activity.tr(en,bn),Toast.LENGTH_LONG).show() }

    fun begin(wifi:Boolean, bluetooth:Boolean, receiving:Boolean, action:()->Unit) {
        if(closed || !allowed()) return
        cancel()
        this.wifi=wifi;this.bluetooth=bluetooth;this.receiving=receiving;ready=action;began=SystemClock.elapsedRealtime()
        next()
    }
    private fun next() {
        if(closed || !allowed() || ready==null) return
        val step=NearbySetupRules.next(Build.VERSION.SDK_INT,wifi,bluetooth,receiving,bluetoothOn(),locationOn(),wifiOn())
        if(step==NearbySetupRules.Step.READY) { val action=ready;cancel();action?.invoke();return }
        pending=step;returned=false
        val intent=when(step) {
            NearbySetupRules.Step.BLUETOOTH -> Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            NearbySetupRules.Step.LOCATION -> {
                message("Android needs Location switched on for nearby discovery on this version. Cute Chat does not read GPS coordinates.","এই Android version-এ কাছের ডিভাইস খুঁজতে Location চালু লাগবে। Cute Chat GPS coordinate পড়ে না।")
                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            }
            NearbySetupRules.Step.WIFI -> {
                @Suppress("DEPRECATION")
                val enabled=Build.VERSION.SDK_INT<29 && runCatching { manager?.setWifiEnabled(true)==true }.getOrDefault(false)
                if(enabled) { legacyWait=true;deadline=SystemClock.elapsedRealtime()+10_000;handler.postDelayed({ foreground() },150);return }
                message("Enable Wi-Fi in Android; setup continues when you return. The receiver creates the temporary hotspot after accepting you.","Android-এ Wi-Fi চালু করুন; ফিরলে setup চলবে। প্রাপক আপনাকে Accept করলে অস্থায়ী hotspot তৈরি হবে।")
                if(Build.VERSION.SDK_INT>=29) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)
            }
            NearbySetupRules.Step.READY -> return
        }
        try { activity.startActivityForResult(intent,REQUEST) }
        catch(_:Exception) { fail("Android could not open nearby settings. Enable Bluetooth/Wi-Fi and try again.","Android কাছের ডিভাইসের settings খুলতে পারেনি। Bluetooth/Wi-Fi চালু করে আবার চেষ্টা করুন।") }
    }
    fun result(request:Int):Boolean {
        if(request!=REQUEST) return false
        if(pending!=null && ready!=null) { returned=true;deadline=SystemClock.elapsedRealtime()+5000 }
        return true
    }
    fun foreground() {
        if(closed || !allowed() || ready==null || (!returned && !legacyWait)) return
        if(SystemClock.elapsedRealtime()-began !in 0..120_000L) { cancel();return }
        val step=pending ?: return
        if(NearbySetupRules.completed(step,bluetoothOn(),locationOn(),wifiOn())) {
            pending=null;returned=false;legacyWait=false;next();return
        }
        if(step==NearbySetupRules.Step.WIFI && SystemClock.elapsedRealtime()<deadline &&
            (legacyWait || manager?.wifiState==WifiManager.WIFI_STATE_ENABLING)) {
            handler.postDelayed({ foreground() },150);return
        }
        fail("Nearby setup was not enabled. Your files stay selected; return to Sharing and retry.","কাছের ডিভাইসের setup চালু হয়নি। বাছা ফাইল থাকবে; Sharing-এ ফিরে আবার চেষ্টা করুন।")
    }
    private fun fail(en:String,bn:String) { cancel();message(en,bn) }
    private fun cancel() { handler.removeCallbacksAndMessages(null);ready=null;pending=null;returned=false;legacyWait=false }
    override fun close() { closed=true;cancel() }
    companion object { const val REQUEST=56 }
}
