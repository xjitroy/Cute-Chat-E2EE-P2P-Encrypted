package app.cutiepie.cutie_pie

import android.app.Application
import android.os.Handler
import android.os.Looper
import app.cutiepie.cutie_pie.core.NativeOwner
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject

class CutieApplication : Application() {
    lateinit var owner: NativeOwner; private set
    lateinit var calls: NativeMediaCalls; private set
    lateinit var assist: NativeRemoteAssist; private set
    val viewSession=app.cutiepie.cutie_pie.core.AppViewSession()
    var callBridge = ""; private set
    override fun onCreate() {
        super.onCreate()
        cacheDir.listFiles()?.filter { it.name.startsWith("native-voice-") || it.name.startsWith("native-view-") || it.name.startsWith("native-thumb-") }?.forEach { it.delete() }
        listOf("native-capture", "native-external").forEach { java.io.File(cacheDir, it).deleteRecursively() }
        val screenOff=object:android.content.BroadcastReceiver() {
            override fun onReceive(context:android.content.Context,intent:android.content.Intent) { if(intent.action==android.content.Intent.ACTION_SCREEN_OFF) viewSession.invalidate() }
        }
        if(android.os.Build.VERSION.SDK_INT>=33) registerReceiver(screenOff,android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF),android.content.Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenOff,android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF))
        owner = NativeOwner(this)
        PrivateAlerts.channels(this)
        calls = NativeMediaCalls(this, owner)
        assist = NativeRemoteAssist(this)
        callBridge = NativeCalls.initialize(this)
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(listOf(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO,androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)[owner.preferences.getInt("theme",0).coerceIn(0,2)])
        com.google.android.material.color.DynamicColors.applyToActivitiesIfAvailable(this)
        owner.onActivityConnected = { peer -> PrivateAlerts.show(this,peer,null,connected=true) }
        owner.onOnlineAlert = { peer -> PrivateAlerts.show(this, peer, null, true) }
        owner.onAlert = { peer, preview -> PrivateAlerts.show(this, peer, preview) }
        owner.onFileOffer = { peer,id -> PrivateAlerts.fileOffer(this,peer,id) }
        owner.onMissedCall = { peer, id, video -> PrivateAlerts.missedCall(this,peer,id,video) }
        owner.onCaptureNotice = { peer, kind -> PrivateAlerts.show(this, peer, if (kind == "call") "A screenshot was detected during your call" else "A screenshot was detected in your chat") }
        owner.onMediaReceived = { peer, id, mime, name ->
            if (android.os.Build.VERSION.SDK_INT >= 29 && owner.preferences.getBoolean("galleryAutoSave", false) && (mime.startsWith("image/") || mime.startsWith("video/")))
                if(id in owner.largeIds) {
                    runCatching {
                        val destination=NativeMediaFiles.beginGallery(this,name,mime)
                        owner.exportFile(peer,id,{ contentResolver.openOutputStream(destination,"w") ?: error("Gallery unavailable") },automatic=true) { ok ->
                            runCatching { NativeMediaFiles.finishGallery(this,destination,ok) }.onFailure { runCatching { NativeMediaFiles.finishGallery(this,destination,false) } }
                        }
                    }
                } else owner.exportAttachment(peer, id) { bytes -> try { NativeMediaFiles.saveToGallery(this, name, mime, bytes) } finally { bytes.fill(0) } }
        }
        owner.onLease = { active ->
            Handler(Looper.getMainLooper()).post {
                if (!calls.active) {
                    if (active) try { ConnectionService.start(this, 3) } catch (_: Exception) { owner.backgroundUnavailable() }
                    else if (!active) stopService(android.content.Intent(this, ConnectionService::class.java))
                }
            }
        }
        try {
            val config = JSONObject(assets.open("firebase-public.json").bufferedReader().use { it.readText() })
            FirebaseApp.initializeApp(this, FirebaseOptions.Builder().setApiKey(config.getString("FIREBASE_API_KEY"))
                .setApplicationId(config.getString("FIREBASE_APP_ID")).setGcmSenderId(config.getString("FIREBASE_SENDER_ID"))
                .setProjectId(config.getString("FIREBASE_PROJECT_ID")).build())
            FirebaseMessaging.getInstance().token.addOnSuccessListener { owner.token(it) }
        } catch (_: Exception) { /* The native UI exposes OS push settings and connection state. */ }
    }
}
