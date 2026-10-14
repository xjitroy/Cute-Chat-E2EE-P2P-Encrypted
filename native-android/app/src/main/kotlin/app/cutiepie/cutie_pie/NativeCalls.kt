package app.cutiepie.cutie_pie

import android.app.*
import android.content.*
import android.media.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.telecom.*

/** Public call presentation metadata only. NativeOwner remains the sole crypto owner. */
object NativeCalls {
    data class State(val id: String, val name: String, val video: Boolean, var status: String,
        var alerts: Boolean, var background: Boolean, val privateName: String,
        val answer: String, val decline: String, val incoming: String,
        var expires: Long, var connectedAt: Long = 0, var privateLabel: Boolean = true, var ringback: Boolean = false)
    const val NOTICE = 7321
    const val INCOMING = "native_incoming_calls_v1"
    const val ONGOING = "native_ongoing_calls_v1"
    private val handler = Handler(Looper.getMainLooper())
    var current: State? = null; private set
    var connection: Connection? = null
    private var pending: Map<String, String>? = null
    private var ctx: Context? = null
    private var sequence = 0L
    private var bridgeToken = ""
    fun initialize(context: Context): String {
        ctx = context.applicationContext
        current?.let { finish(it.id) }
        sequence = 0L; bridgeToken = java.util.UUID.randomUUID().toString()
        register(context); return bridgeToken
    }
    private var attemptedId = ""
    private val closed = LinkedHashSet<String>()
    var telecomRegistered = false; private set
    private fun handle(context: Context) = PhoneAccountHandle(ComponentName(context, NativeCallConnectionService::class.java), "cutie-private-voip")

    fun capabilities(context: Context): Map<String, Boolean> = mapOf(
        "fullScreen" to (Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()),
        "telecom" to telecomRegistered,
        "active" to (current != null),
        "telecomActive" to (connection != null),
        "incoming" to (current?.status == "incoming"),
        "notification" to context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == NOTICE },
        "notifications" to context.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
    fun permissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= 34) context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun register(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        return try {
            val account = PhoneAccount.builder(handle(context), "Cute Chat")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_SIP)).build()
            context.getSystemService(TelecomManager::class.java).registerPhoneAccount(account)
            telecomRegistered = true; true
        } catch (_: Exception) { false }
    }
    fun sync(context: Context, raw: Map<*, *>) {
        ctx = context.applicationContext
        if (raw["bridge"] != bridgeToken || bridgeToken.isEmpty()) return
        val seq = (raw["sequence"] as? Number)?.toLong() ?: return
        if (seq <= sequence) return
        sequence = seq
        val id = raw["id"] as? String ?: return
        if (id.isEmpty()) { finish(current?.id ?: ""); return }
        if (!Regex("^[A-Za-z0-9_-]{16,128}$").matches(id) || closed.contains(id)) return
        val status = raw["status"] as? String ?: return
        if (status !in listOf("incoming", "preparing", "calling", "connecting", "connected", "reconnecting")) return
        val before = current
        if (before != null && before.id != id) finish(before.id)
        val now = SystemClock.elapsedRealtime()
        val state = if (current?.id == id) current!! else State(id,
            (raw["name"] as? String ?: "Cute Chat").filter { !it.isISOControl() }.take(120),
            raw["video"] == true, status, raw["alerts"] == true, raw["background"] == true,
            raw["privateName"] as? String ?: "Private caller", raw["answer"] as? String ?: "Answer",
            raw["decline"] as? String ?: "Decline", raw["incoming"] as? String ?: "Incoming private call", now + 60000)
        if (current == null) {
            current = state
        }
        // NativeMediaCalls owns the stage-aware deadline. A second timer from
        // "preparing" used to cut off a call even after the user answered it.
        if (status == "incoming") state.expires = now + ((raw["ringRemainingMs"] as? Number)?.toLong() ?: 60000L).coerceIn(1,60000)
        state.ringback = raw["ringback"] == true && status == "calling"
        state.status = status
        state.privateLabel = raw["private"] == true
        state.alerts = raw["alerts"] == true; state.background = raw["background"] == true
        if (status == "connected" && state.connectedAt == 0L) state.connectedAt = System.currentTimeMillis()
        if (status == "connecting" || status == "connected" || status == "reconnecting") {
            NativeRinger.stop(); NativeRingback.stop()
            try { connection?.setActive() } catch (_: Exception) { /* Telecom presentation must not crash the media owner. */ }
        }
        if (status == "incoming" && state.alerts || status != "incoming") {
            try {
                val service = Intent(context, NativeCallService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service) else context.startService(service)
            }
            catch (_: Exception) { post(context, state); sound(context, state) }
        }
        if (attemptedId != id && (status != "incoming" || state.alerts) && register(context)) {
            attemptedId = id
            try {
                val extra = Bundle().apply { putString("callId", id) }
                val telecom = context.getSystemService(TelecomManager::class.java)
                if (status == "incoming") telecom.addNewIncomingCall(handle(context), extra)
                else telecom.placeCall(Uri.parse("sip:private@cutiepie.invalid"), Bundle().apply {
                    putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle(context))
                    putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, extra)
                })
            } catch (_: SecurityException) { /* Permission denied: retain native CallStyle fallback. */ }
            catch (_: Exception) { /* Native CallStyle remains available if Telecom refuses integration. */ }
        }
    }
    /** Runs the answer handoff on the same thread as ringtone/Telecom callbacks. */
    fun prepareMedia(id: String): Boolean {
        val result = java.util.concurrent.FutureTask {
            if (current?.id != id) false else {
                NativeRinger.stop(); NativeRingback.stop()
                current?.status = "connecting"
                val conn = connection
                try { conn?.setActive() } catch (_: Exception) { }
                conn != null
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) result.run() else handler.post(result)
        return result.get(3, java.util.concurrent.TimeUnit.SECONDS)
    }
    fun action(id: String, action: String) {
        val state = current ?: return
        if (state.id != id || action !in listOf("answer", "decline", "open")) return
        if (action == "answer" && state.status != "incoming") return
        if (action == "answer" || action == "open") pending = mapOf("callId" to id, "action" to action)
        if (action == "decline") (ctx as? CutieApplication)?.owner?.work { (ctx as CutieApplication).calls.end() }
        if (action == "decline") finish(id)
    }
    fun consume(): Map<String, String>? {
        val value = pending; pending = null
        return if (value?.get("callId") == current?.id && (value?.get("action") == "open" || current?.status == "incoming")) value else null
    }
    fun finish(id: String) {
        if (current?.id != id) return
        closed.add(id); if (closed.size > 64) closed.remove(closed.first())
        current = null; pending = null; attemptedId = ""
        NativeRinger.stop(); NativeRingback.stop()
        val conn = connection; connection = null
        try { conn?.setDisconnected(DisconnectCause(DisconnectCause.LOCAL)); conn?.destroy() } catch (_: Exception) {}
        ctx?.let { it.getSystemService(NotificationManager::class.java).cancel(NOTICE); it.stopService(Intent(it, NativeCallService::class.java)) }
    }
    fun intent(context: Context, state: State, action: String): PendingIntent {
        val target = if (action == "decline") Intent(context, NativeCallReceiver::class.java) else Intent(context, IncomingCallActivity::class.java)
        target.data = Uri.parse("cutiepie-call://${state.id}/$action")
        target.putExtra("callId", state.id).putExtra("callAction", action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (action == "decline") PendingIntent.getBroadcast(context, 0, target, flags)
        else PendingIntent.getActivity(context, 0, target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
    }
    fun notification(context: Context, state: State): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        val ringing = state.status == "incoming"
        val channel = if (ringing) INCOMING else ONGOING
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(INCOMING, "Incoming private calls", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null); enableVibration(false); lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            })
            manager.createNotificationChannel(NotificationChannel(ONGOING, "Ongoing private calls", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null); enableVibration(false); lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            })
        }
        val accept = intent(context, state, "answer"); val decline = intent(context, state, "decline")
        fun builder(name: String): Notification.Builder {
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, channel) else Notification.Builder(context)
            b.setSmallIcon(R.drawable.ic_stat_cutie).setContentTitle(name).setContentText(if (ringing) state.incoming+if(state.video) " · Video" else " · Audio" else "Cute Chat · Private call")
                .setCategory(Notification.CATEGORY_CALL).setOngoing(true).setOnlyAlertOnce(true)
                .setContentIntent(intent(context, state, "open")).setVisibility(Notification.VISIBILITY_PRIVATE)
            if (Build.VERSION.SDK_INT >= 31) {
                val person = Person.Builder().setName(name.ifBlank { "Cute Chat" }).setImportant(true).build()
                b.setStyle((if (ringing) Notification.CallStyle.forIncomingCall(person, decline, accept)
                    else Notification.CallStyle.forOngoingCall(person, decline)).setIsVideo(state.video))
            } else {
                b.setPriority(Notification.PRIORITY_MAX)
                b.addAction(Notification.Action.Builder(null, state.decline, decline).build())
                if (ringing) b.addAction(Notification.Action.Builder(null, state.answer, accept).build())
            }
            if (ringing && Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter((state.expires - SystemClock.elapsedRealtime()).coerceAtLeast(1))
            else if (state.connectedAt != 0L) b.setWhen(state.connectedAt).setUsesChronometer(true)
            return b
        }
        val b = builder(if (state.privateLabel) state.privateName else state.name)
        b.setPublicVersion(builder(state.privateName).setVisibility(Notification.VISIBILITY_PUBLIC).build())
        if (ringing && state.background && state.alerts && (Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent()))
            b.setFullScreenIntent(intent(context, state, "open"), true)
        return b.build()
    }
    fun post(context: Context, state: State) { context.getSystemService(NotificationManager::class.java).notify(NOTICE, notification(context, state)) }
    fun sound(context: Context, state: State) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelAllowed = Build.VERSION.SDK_INT < 26 || manager.getNotificationChannel(INCOMING)?.importance != NotificationManager.IMPORTANCE_NONE
        if (state.status == "incoming" && state.alerts && manager.areNotificationsEnabled() && channelAllowed) NativeRinger.start(context, state.id, state.expires-SystemClock.elapsedRealtime())
        else NativeRinger.stop()
        if (state.ringback) NativeRingback.start(context,state.id) else NativeRingback.stop()
    }
}

/** Uses ringtone audio attributes so OS volume, silent/vibrate mode and DND win. */
object NativeRinger {
    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var owner = ""
    private var vibrator: Vibrator? = null
    private var generation = 0
    fun start(context: Context, id: String, remaining: Long = 60000) {
        if (owner == id) return
        stop(); owner = id
        val audio = context.getSystemService(AudioManager::class.java)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()
        if (audio.ringerMode == AudioManager.RINGER_MODE_SILENT) return
        if (audio.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            ringtone = RingtoneManager.getRingtone(context.applicationContext, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                audioAttributes = attrs
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
            }
            val current = generation
            val loop = object : Runnable {
                override fun run() {
                    if (generation != current) return
                    if (audio.ringerMode == AudioManager.RINGER_MODE_NORMAL && ringtone?.isPlaying == false) try { ringtone?.play() } catch (_: Exception) {}
                    else if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) ringtone?.stop()
                    handler.postDelayed(this, 800)
                }
            }; handler.post(loop)
        }
        @Suppress("DEPRECATION")
        vibrator = context.getSystemService(Vibrator::class.java)
        if (Build.VERSION.SDK_INT >= 26) vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 1400), 0), attrs)
        else { @Suppress("DEPRECATION") vibrator?.vibrate(longArrayOf(0, 500, 1400), 0, attrs) }
        val current = generation; handler.postDelayed({ if (generation == current) stop() }, remaining.coerceIn(1,60000))
    }
    fun stop() {
        generation++; handler.removeCallbacksAndMessages(null)
        try { ringtone?.stop(); vibrator?.cancel() } catch (_: Exception) {}
        ringtone = null; vibrator = null; owner = ""
    }
}

/** Local ringback only after the authenticated peer confirmed receipt. */
object NativeRingback {
    private val handler = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null
    private var owner = ""
    fun start(context: Context, id: String) {
        if(owner == id) return
        stop(); owner=id
        if(context.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_VOICE_CALL) == 0) return
        try { tone=ToneGenerator(AudioManager.STREAM_VOICE_CALL,55) } catch (_: RuntimeException) { return }
        val loop=object:Runnable { override fun run() {
            if(owner != id) return
            try { tone?.startTone(ToneGenerator.TONE_SUP_RINGTONE,2000) } catch (_: RuntimeException) { stop(); return }
            handler.postDelayed(this,4000)
        } }; handler.post(loop)
        handler.postDelayed({ if(owner == id) stop() },60000)
    }
    fun stop() {
        owner=""; handler.removeCallbacksAndMessages(null)
        try { tone?.stopTone(); tone?.release() } catch (_: RuntimeException) { }
        tone=null
    }
}

class NativeCallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = NativeCalls.current ?: run { stopSelf(); return START_NOT_STICKY }
        try {
            val n = NativeCalls.notification(this, state)
            if (Build.VERSION.SDK_INT >= 30) startForeground(NativeCalls.NOTICE, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
            else startForeground(NativeCalls.NOTICE, n)
            NativeCalls.sound(this, state)
        } catch (_: Exception) {
            NativeCalls.post(this, state); NativeCalls.sound(this, state); stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) { NativeCalls.action(NativeCalls.current?.id ?: "", "decline"); stopSelf() }
    override fun onDestroy() { if (NativeCalls.current == null) { NativeRinger.stop(); NativeRingback.stop() }; super.onDestroy() }
}
class NativeCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getStringExtra("callAction") == "decline") NativeCalls.action(intent.getStringExtra("callId") ?: "", "decline")
    }
}

class NativeCallConnectionService : android.telecom.ConnectionService() {
    private fun create(request: ConnectionRequest, incoming: Boolean): Connection {
        if (Build.VERSION.SDK_INT < 26) return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
        val state = NativeCalls.current
        val callId = request.extras?.getString("callId")
            ?: request.extras?.getBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS)?.getString("callId")
        if (state == null || callId != state.id) return Connection.createFailedConnection(DisconnectCause(DisconnectCause.CANCELED))
        val conn = object : Connection() {
            override fun onAnswer() { answer() }
            override fun onAnswer(videoState: Int) { answer() }
            private fun answer() {
                val context = applicationContext
                try { NativeCalls.intent(context, state, "answer").send() } catch (_: Exception) {}
            }
            override fun onReject() { NativeCalls.action(state.id, "decline") }
            override fun onDisconnect() { NativeCalls.action(state.id, "decline") }
            override fun onShowIncomingCallUi() { if (NativeCalls.current?.id == state.id && state.alerts) try { NativeCalls.post(applicationContext, state) } catch (_: Exception) {} }
        }
        conn.setConnectionProperties(Connection.PROPERTY_SELF_MANAGED)
        conn.setAudioModeIsVoip(true)
        conn.setAddress(Uri.parse("sip:private@cutiepie.invalid"), TelecomManager.PRESENTATION_RESTRICTED)
        conn.setCallerDisplayName("Cute Chat", TelecomManager.PRESENTATION_RESTRICTED)
        if (incoming) conn.setRinging() else conn.setDialing()
        NativeCalls.connection = conn
        val app = applicationContext as? CutieApplication
        app?.owner?.work { app.calls.adoptTelecomAudio(state.id) }
        if (state.status == "connecting" || state.status == "connected" || state.status == "reconnecting") conn.setActive()
        return conn
    }
    override fun onCreateIncomingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection = create(request, true)
    override fun onCreateOutgoingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection = create(request, false)
}
