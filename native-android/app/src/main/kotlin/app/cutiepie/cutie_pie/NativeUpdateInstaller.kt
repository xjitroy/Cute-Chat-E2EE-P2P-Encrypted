package app.cutiepie.cutie_pie

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import app.cutiepie.cutie_pie.core.AppUpdateDownload
import app.cutiepie.cutie_pie.core.AppUpdateRules
import java.io.File
import java.util.UUID

/** Android owns a verified byte copy before confirmation; no OEM installer must read our URI. */
internal object NativeUpdateInstaller {
    private const val ACTION = "app.cutiepie.cutie_pie.UPDATE_INSTALL_RESULT"
    private const val CHANNEL = "app_updates"
    private const val NOTIFICATION = 4816
    private const val PREFERENCES = "update-install-session"
    private var pending: Intent? = null
    var changed: (() -> Unit)? = null
    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun stage(context: Context, file: File, update: AppUpdateRules.Candidate, cancelled: () -> Boolean): Int {
        val installer = context.packageManager.packageInstaller
        val prefs = preferences(context)
        val previous = prefs.getInt("session", -1)
        if (previous >= 0) runCatching { installer.abandonSession(previous) }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(update.bytes)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        val nonce = UUID.randomUUID().toString()
        var committed = false
        try {
            installer.openSession(id).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, update.bytes).use { output ->
                        AppUpdateDownload.copy(input, output, update.bytes, update.sha256, cancelled) {}
                        session.fsync(output)
                    }
                }
                check(!cancelled())
                check(prefs.edit().clear().putInt("session", id).putString("nonce", nonce)
                    .putLong("started", SystemClock.elapsedRealtime()).commit())
                val callback = Intent(context, UpdateInstallReceiver::class.java).apply {
                    action = ACTION
                    data = Uri.parse("cutechat-update://session/$id/$nonce")
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val sender = PendingIntent.getBroadcast(context, id, callback, flags).intentSender
                session.commit(sender)
                committed = true
            }
            return id
        } finally {
            if (!committed) {
                runCatching { installer.abandonSession(id) }
                if (prefs.getInt("session", -1) == id) prefs.edit().clear().apply()
            }
        }
    }

    fun takeConfirmation(context: Context): Intent? = pending?.also {
        pending = null
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
    }

    fun takeResult(context: Context): Int? {
        val prefs = preferences(context)
        if (!prefs.contains("status")) return null
        return prefs.getInt("status", PackageInstaller.STATUS_FAILURE).also { prefs.edit().remove("status").apply() }
    }

    fun receive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val prefs = preferences(context)
        val id = prefs.getInt("session", -1)
        val nonce = prefs.getString("nonce", null) ?: return
        val started = prefs.getLong("started", -1)
        val elapsed = SystemClock.elapsedRealtime() - started
        if (id < 0 || intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -2) != id ||
            intent.data?.toString() != "cutechat-update://session/$id/$nonce" || started < 0 || elapsed !in 0..30 * 60_000L) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirmation = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirmation == null) return
            pending = Intent(confirmation)
            changed?.invoke()
            if (pending != null) notifyConfirmation(context, confirmation)
        } else {
            pending = null
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
            prefs.edit().remove("session").remove("nonce").remove("started").putInt("status", status).apply()
            changed?.invoke()
        }
    }

    private fun notifyConfirmation(context: Context, confirmation: Intent) {
        // Background never opens an Activity automatically. This is an explicit Android consent action.
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val tap = PendingIntent.getActivity(context, NOTIFICATION, Intent(confirmation).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL) else Notification.Builder(context)
        runCatching { manager.notify(NOTIFICATION, builder.setSmallIcon(R.drawable.ic_stat_cutie)
            .setContentTitle("Cute Chat update ready").setContentText("Tap to confirm installation in Android")
            .setContentIntent(tap).setAutoCancel(true).setOnlyAlertOnce(true).build()) }
    }
}

/** Non-exported. Only the explicit session-bound PendingIntent accepts Android's callback. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = NativeUpdateInstaller.receive(context, intent)
}
