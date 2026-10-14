package app.cutiepie.cutie_pie

import android.app.*
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
import app.cutiepie.cutie_pie.core.AlertPolicy
import app.cutiepie.cutie_pie.core.NotificationContentPolicy

object PrivateAlerts {
    fun channels(app: CutieApplication) {
        if(Build.VERSION.SDK_INT < 26) return
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("native_alerts_v1", "Messages and Ping", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(Settings.System.DEFAULT_NOTIFICATION_URI, android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION).build())
            enableVibration(true); lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
    }
    fun show(app: CutieApplication, peer: String, preview: String?, online: Boolean = false,connected:Boolean=false) {
        val manager = app.getSystemService(NotificationManager::class.java)
        if (!app.owner.preferences.getBoolean("alerts", true) || !manager.areNotificationsEnabled()) return
        channels(app)
        val intent = Intent(app, MainActivity::class.java).putExtra("peer", peer)
        val open = PendingIntent.getActivity(app, peer.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val deviceLocked = app.getSystemService(KeyguardManager::class.java).isDeviceLocked
        val previewEnabled = app.owner.preferences.getBoolean("preview",false)
        val locked = NotificationContentPolicy.hideIdentity(deviceLocked,app.owner.viewUnlocked,previewEnabled)
        val name = app.owner.contacts.find { it.uid == peer }?.name ?: "Cute Chat"
        val text = if(connected) when(app.owner.preferences.getString("language","en")) { "bn"->"সংযোগ তৈরি হয়েছে · অপেক্ষার activity পাঠানো হচ্ছে";"hi"->"कनेक्शन तैयार · लंबित गतिविधि भेज रहे हैं";"ru"->"Соединение готово · отправка ожидающих действий";else->"Connection ready · sending queued activity" } else if (online) when (app.owner.preferences.getString("language", "en")) { "bn" -> "এখন ব্যক্তিগতভাবে সংযুক্ত হওয়ার সময় · Online Ping"; "hi" -> "अब निजी तौर पर जुड़ें · Online Ping"; "ru" -> "Можно подключиться лично · Online Ping"; else -> "Time to connect privately · Online Ping" } else if (NotificationContentPolicy.preview(previewEnabled,deviceLocked) && preview != null) preview.take(160) else "Open Cute Chat to connect privately"
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(app, "native_alerts_v1") else Notification.Builder(app)
        val hidden = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(app, "native_alerts_v1") else Notification.Builder(app))
            .setSmallIcon(R.drawable.ic_stat_cutie).setContentTitle("Cute Chat").setContentText("Private activity").build()
        try { manager.notify(peer.hashCode(), builder.setSmallIcon(R.drawable.ic_stat_cutie).setContentTitle(if (locked) "Cute Chat" else name)
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(open).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(hidden)
            .setPriority(Notification.PRIORITY_HIGH).build()) } catch (_: SecurityException) { }
    }
    fun fileOffer(app:CutieApplication,peer:String,id:String) {
        val manager=app.getSystemService(NotificationManager::class.java)
        if(!app.owner.preferences.getBoolean("alerts",true) || !manager.areNotificationsEnabled()) return
        channels(app)
        val open=PendingIntent.getActivity(app,("file.$id").hashCode(),Intent(app,MainActivity::class.java).putExtra("sharing",true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b=if(Build.VERSION.SDK_INT>=26) Notification.Builder(app,"native_alerts_v1") else Notification.Builder(app)
        val text=when(app.owner.preferences.getString("language","en")) { "bn"->"ফাইল পাঠানোর অনুরোধ · গ্রহণ করতে অ্যাপ খুলুন";"hi"->"फ़ाइल अनुरोध · स्वीकार करने के लिए ऐप खोलें";"ru"->"Запрос файла · откройте приложение";else->"File transfer request · open to accept" }
        try { manager.notify("file.$id",id.hashCode(),b.setSmallIcon(R.drawable.ic_stat_cutie).setContentTitle("Cute Chat").setContentText(text).setContentIntent(open)
            .setCategory(Notification.CATEGORY_MESSAGE).setAutoCancel(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_SECRET).setPriority(Notification.PRIORITY_HIGH).build()) } catch(_:SecurityException) { }
    }
    fun missedCall(app: CutieApplication, peer: String, callId: String, video: Boolean) {
        if(!AlertPolicy.shouldAlert(app.owner.foreground,app.owner.viewUnlocked,app.owner.visibleConversationPeer,peer)) return
        val manager=app.getSystemService(NotificationManager::class.java)
        if(!app.owner.preferences.getBoolean("alerts",true) || !manager.areNotificationsEnabled()) return
        val channel="native_missed_calls_v1"
        if(Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(channel,"Missed private calls",NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(Settings.System.DEFAULT_NOTIFICATION_URI,android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION).build())
            enableVibration(true); lockscreenVisibility=Notification.VISIBILITY_PRIVATE
        })
        val locked=app.getSystemService(KeyguardManager::class.java).isDeviceLocked || !app.owner.viewUnlocked
        val name=if(locked) "Cute Chat" else app.owner.contacts.find { it.uid == peer }?.name ?: "Cute Chat"
        val bn=app.owner.preferences.getString("language","en") == "bn"
        val text=if(video) { if(bn) "মিসড ভিডিও কল" else "Missed video call" } else { if(bn) "মিসড অডিও কল" else "Missed audio call" }
        val open=PendingIntent.getActivity(app,callId.hashCode(),Intent(app,MainActivity::class.java).putExtra("peer",peer).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder=if(Build.VERSION.SDK_INT >= 26) Notification.Builder(app,channel) else Notification.Builder(app)
        val hidden=(if(Build.VERSION.SDK_INT >= 26) Notification.Builder(app,channel) else Notification.Builder(app))
            .setSmallIcon(R.drawable.ic_stat_cutie).setContentTitle("Cute Chat").setContentText("Private activity").build()
        try { manager.notify("missed.$callId",callId.hashCode(),builder.setSmallIcon(R.drawable.ic_stat_cutie).setContentTitle(name).setContentText(text)
            .setCategory(Notification.CATEGORY_MISSED_CALL).setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(hidden).setPriority(Notification.PRIORITY_HIGH).build()) } catch (_: SecurityException) { }
    }
}

class PrivatePushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { (application as CutieApplication).owner.token(token) }
    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data; if (data["kind"] !in setOf("ping", "nudge", "chat_open")) return
        val value = JSONObject(data as Map<*, *>)
        val expiry = data["expiresAt"]?.toLongOrNull() ?: return
        if (expiry <= System.currentTimeMillis() || expiry > System.currentTimeMillis() + 90000) return
        val app = application as CutieApplication
        // High priority push may permit a short FGS. Android/OEM restrictions win.
        try { app.owner.push(value) }
        catch (_: Exception) { /* A failed background attempt is not a message or an online arrival. */ }
    }
}
