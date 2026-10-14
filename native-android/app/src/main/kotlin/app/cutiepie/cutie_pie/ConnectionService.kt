package app.cutiepie.cutie_pie

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import app.cutiepie.cutie_pie.core.CallServiceModePolicy

/** Visible bounded native lifecycle service. Ordinary chat is dataSync, never a call. */
class ConnectionService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var mode = 3
    private var lastTransferNotice:Pair<Int,String>?=null
    private var currentNotice:Notification?=null
    private val checkLease = object : Runnable {
        override fun run() {
            val app = application as CutieApplication
            app.owner.executor.execute {
                val allowed = app.owner.hasLease()
                val progress=app.owner.transferNotification()
                handler.post {
                    if (!allowed) stopSelf() else {
                        if(mode==3 && progress!=lastTransferNotice) {
                            currentNotice?.let { old ->
                                val b=Notification.Builder.recoverBuilder(this@ConnectionService,old).setContentText(progress?.second ?: "Private connection active")
                                if(progress!=null) b.setProgress(100,progress.first,false) else b.setProgress(0,0,false)
                                val updated=b.build();getSystemService(NotificationManager::class.java).notify(7314,updated);currentNotice=updated
                            };lastTransferNotice=progress
                        }
                        handler.postDelayed(this, 1000)
                    }
                }
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as CutieApplication
        if (intent?.action == "STOP") { app.owner.work { app.calls.end() }; app.owner.stop(); stopSelf(); return START_NOT_STICKY }
        val ticket = intent?.getStringExtra("readyTicket")
        // An expired caller must not later upgrade the service/capture type.
        if (ticket != null && !pendingReady.containsKey(ticket)) return START_NOT_STICKY
        val callId=intent?.getStringExtra("callId") ?: ""
        if(ticket != null && !CallServiceModePolicy.ticket(app.calls.id,callId,app.calls.active)) {
            pendingReady.remove(ticket)?.completeExceptionally(IllegalStateException("Call changed before service start"))
            return START_NOT_STICKY
        }
        val previousMode = mode
        mode = intent?.getIntExtra("mode", 3) ?: 3
        if (mode !in 1..4) { stopSelf(); return START_NOT_STICKY }
        mode=CallServiceModePolicy.mode(previousMode,mode,app.calls.active)
        // A stale capture start cannot keep microphone foreground status alive
        // after the serialized call owner has already ended the call.
        if (mode != 3 && !app.calls.active) { mode = 3; ticket?.let { pendingReady.remove(it)?.completeExceptionally(IllegalStateException("Call ended")) } }
        if (previousMode != 3 && mode == 3) stopForeground(STOP_FOREGROUND_REMOVE)
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("native_connection_v1", "Private connection", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false); setShowBadge(false); lockscreenVisibility = Notification.VISIBILITY_SECRET
        })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, ConnectionService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "native_connection_v1") else Notification.Builder(this)
        val notice = builder.setSmallIcon(R.drawable.ic_stat_cutie).setContentTitle("Cute Chat")
            .setContentText(if (mode == 3) "Private connection active" else when (mode) {
                1 -> "Microphone and camera in use · private call"
                4 -> "Microphone and screen in use · private call"
                else -> "Microphone in use · private call"
            })
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_SECRET)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        currentNotice=notice;lastTransferNotice=null
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(7314, notice, when (mode) {
                1 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                // Retain the already-consented camera call type while its track
                // is paused so stopping projection can restore the ongoing call.
                4 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                2 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }) else startForeground(7314, notice)
        } catch (failure: Exception) {
            if (ticket != null) pendingReady.remove(ticket)?.completeExceptionally(failure)
            if (mode == 4) { mode = previousMode; return START_NOT_STICKY }
            app.owner.work { if(app.calls.id == callId && callId.isNotEmpty()) app.calls.end(outcome="failed") }
            app.owner.backgroundUnavailable(); stopSelf(); return START_NOT_STICKY
        }
        if (ticket != null) pendingReady.remove(ticket)?.complete(Unit)
        handler.removeCallbacks(checkLease); handler.post(checkLease)
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { val app = application as CutieApplication; app.owner.work { app.calls.end() }; app.owner.stop(); stopSelf() }
    override fun onTaskRemoved(rootIntent: Intent?) { val app = application as CutieApplication; app.owner.work { app.calls.end() }; app.owner.stop(); stopSelf() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
    companion object {
        private val pendingReady = ConcurrentHashMap<String, CompletableFuture<Unit>>()
        /** Capture cannot begin until Android has accepted the foreground type.
         * Called only on the serialized owner thread, never Android's main thread. */
        fun startAndAwait(context: Context, mode: Int) {
            check(Looper.myLooper() != Looper.getMainLooper())
            val ticket = java.util.UUID.randomUUID().toString()
            val ready = CompletableFuture<Unit>(); pendingReady[ticket] = ready
            try {
                val intent = Intent(context, ConnectionService::class.java).putExtra("mode", mode).putExtra("readyTicket", ticket)
                    .putExtra("callId",(context.applicationContext as CutieApplication).calls.id)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
                ready.get(8, TimeUnit.SECONDS)
            } finally { pendingReady.remove(ticket) }
        }
        fun start(context: Context, mode: Int) {
            val intent = Intent(context, ConnectionService::class.java).putExtra("mode", mode)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
