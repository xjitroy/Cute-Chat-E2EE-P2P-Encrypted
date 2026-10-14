package app.cutiepie.cutie_pie

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.cutiepie.cutie_pie.core.AppUpdateRules
import app.cutiepie.cutie_pie.core.AppUpdateDownload
import com.google.android.material.snackbar.Snackbar
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** No GitHub credential, private-chat owner, radio, microphone or silent installer. */
internal class NativeAppUpdates(private val activity: MainActivity, private val allowed: () -> Boolean,
    private val anchor: () -> View?) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES).build()
    @Volatile private var closed = false
    @Volatile private var activeCall: Call? = null
    private var entered = false
    private var dismissed = false
    private var checking = false
    private var downloading = false
    private var lastCheck = -1L
    private var lastContent: View? = null
    private var retryAfter = 0L
    private var candidate: AppUpdateRules.Candidate? = null
    private var ready: File? = null
    private var waitingSettings = false
    private var returnedSettings = false
    private var installerOpened = false
    private var returnedInstaller = false
    private var percent = 0
    private var snackbar: Snackbar? = null
    private var bannerText: TextView? = null
    private val folder get() = File(activity.filesDir,"app-updates")
    private val variant get() = if(BuildConfig.REMOTE_CONTROL) "assist" else "normal"
    init { NativeUpdateInstaller.changed = { handler.post { foreground() } } }
    private fun text(en: String, bn: String, hi: String = en, ru: String = en) = activity.tr(en,bn,hi,ru)
    private fun notify(message: String) { if(allowed() && !closed) Toast.makeText(activity,message,Toast.LENGTH_LONG).show() }

    fun foreground() {
        if(closed || !allowed()) return
        NativeUpdateInstaller.takeResult(activity)?.let { status ->
            installerOpened=false
            if(status != android.content.pm.PackageInstaller.STATUS_SUCCESS) {
                returnedInstaller=false;dismissed=false
                notify(text("Android did not install the update. You can retry from App updates or download the APK from GitHub.","Android আপডেট ইনস্টল করেনি। App updates থেকে আবার চেষ্টা করুন অথবা GitHub থেকে APK ডাউনলোড করুন।"))
            }
        }
        NativeUpdateInstaller.takeConfirmation(activity)?.let { confirmation ->
            installerOpened=true
            try { activity.startActivityForResult(confirmation,INSTALL) }
            catch(_: Exception) { installerOpened=false;notify(text("Android could not open installation confirmation. Retry from App updates.","Android ইনস্টলের অনুমতি দেখাতে পারেনি। App updates থেকে আবার চেষ্টা করুন।")) }
            return
        }
        if(returnedInstaller) { returnedInstaller=false;entered=true;dismissed=true;return }
        if(waitingSettings && returnedSettings) {
            waitingSettings=false;returnedSettings=false
            if(installAllowed()) launchInstaller() else {
                notify(text("Installation permission was not enabled. You can retry from App updates.","ইনস্টলের অনুমতি চালু হয়নি। App updates থেকে আবার চেষ্টা করতে পারবেন।")); render()
            }
            return
        }
        if(ready != null && !installerOpened && downloading) { downloading=false;requestInstall();return }
        if(entered) { if(!dismissed) render();return }
        entered=true;dismissed=false
        if(ready != null || downloading) render() else check(false)
    }
    fun pause() { snackbar?.dismiss();snackbar=null;bannerText=null }
    fun background() { entered=false;pause() }
    fun result(request: Int): Boolean {
        if(request==PERMISSION) { returnedSettings=true;return true }
        if(request==INSTALL) { installerOpened=false;returnedInstaller=true;dismissed=true;return true }
        return false
    }
    fun manualCheck() {
        if(closed || !allowed()) return
        entered=true;dismissed=false
        if(ready != null || downloading) render() else check(true)
    }
    fun contentChanged() { if(!closed && allowed()) { if(!entered) foreground();else if(!dismissed && lastContent!==anchor()) { snackbar?.dismiss();snackbar=null;render() } } }
    fun openSource() {
        if(!allowed()) return
        try { activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(AppUpdateRules.REPOSITORY))) }
        catch(_: Exception) { notify(text("No browser available","ব্রাউজার পাওয়া যায়নি")) }
    }
    private fun check(manual: Boolean) {
        if(checking || downloading || closed) return
        val now=SystemClock.elapsedRealtime()
        if(now < retryAfter || (!manual && lastCheck>=0 && now>=lastCheck && now-lastCheck<60_000)) {
            render();if(manual && candidate==null) notify(text("Please try again later; GitHub limits update requests.","কিছুক্ষণ পরে চেষ্টা করুন; GitHub update request সীমিত করে।"));return
        }
        checking=true;lastCheck=now
        if(manual) notify(text("Checking published updates…","প্রকাশিত আপডেট পরীক্ষা হচ্ছে…","प्रकाशित अपडेट जाँचे जा रहे हैं…","Проверка опубликованных обновлений…"))
        io.execute {
            var found: AppUpdateRules.Candidate?=null;var absent=false;var failed=false
            try {
                response(AppUpdateRules.LATEST).use { r ->
                    if(r.code==404) absent=true else {
                        if(r.code in setOf(403,429)) throw Limited()
                        require(r.isSuccessful)
                        val release=JSONObject(read(r,512*1024))
                        val manifestUrl=AppUpdateRules.manifestUrl(release)
                        val manifest=response(manifestUrl).use { m -> require(m.isSuccessful);JSONObject(read(m,32768)) }
                        found=AppUpdateRules.candidate(release,manifest,variant,currentCode(),Build.VERSION.SDK_INT)
                    }
                }
            } catch(_: Limited) { handler.post { retryAfter=SystemClock.elapsedRealtime()+15*60_000 };failed=true }
            catch(_: Exception) { failed=true }
            handler.post {
                if(closed) return@post
                checking=false
                if(!failed && !downloading && ready==null) candidate=found
                if(allowed() && entered && !dismissed) render()
                if(manual) when {
                    failed -> notify(text("Update check failed. Check your network or try later; the app remains usable.","আপডেট পরীক্ষা হয়নি। নেটওয়ার্ক দেখুন বা পরে চেষ্টা করুন; অ্যাপ ব্যবহার করতে পারবেন।"))
                    absent -> notify(text("No public release has been published yet.","এখনো কোনো public release প্রকাশ করা হয়নি।","अभी कोई सार्वजनिक रिलीज़ प्रकाशित नहीं हुई है।","Публичный выпуск ещё не опубликован."))
                    found==null -> notify(text("No newer compatible public update is available.","নতুন compatible public update পাওয়া যায়নি।"))
                }
            }
        }
    }
    private fun response(initial: String): Response {
        var url=initial
        repeat(6) {
            require(!closed && AppUpdateRules.secureUrl(url))
            val client=if(initial==AppUpdateRules.LATEST || initial.endsWith("/"+AppUpdateRules.MANIFEST)) http.newBuilder().callTimeout(20,TimeUnit.SECONDS).build() else http
            val call=client.newCall(Request.Builder().url(url).header("User-Agent","Cute-Chat-Updates/1")
                .header("Accept",if(url==AppUpdateRules.LATEST) "application/vnd.github+json" else "application/octet-stream").build())
            activeCall=call
            val r=call.execute()
            if(r.code !in setOf(301,302,303,307,308)) return r
            val next=r.header("Location")?.let { r.request.url.resolve(it)?.toString() }
            r.close();require(next != null && AppUpdateRules.secureUrl(next));url=next
        }
        error("Too many redirects")
    }
    private fun read(r: Response, maximum: Int): String {
        val body=r.body ?: error("No response body")
        require(body.contentLength() <= maximum)
        return body.byteStream().use { stream ->
            val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true) { val n=stream.read(buffer);if(n<0) break;require(output.size()+n<=maximum);output.write(buffer,0,n) }
            output.toString("UTF-8")
        }
    }
    private fun render() {
        if(closed || !allowed() || dismissed || waitingSettings || installerOpened) return
        val update=candidate ?: return
        val view=anchor() ?: return
        lastContent=view
        val words=when {
            ready!=null -> text("Update ready · install ${update.version}","আপডেট প্রস্তুত · ${update.version} ইনস্টল করুন")
            downloading -> text("Downloading update · $percent%","আপডেট ডাউনলোড হচ্ছে · $percent%")
            else -> text("Update ${update.version} · latest security and features","আপডেট ${update.version} · নতুন security ও features","अपडेट ${update.version} · नई सुरक्षा और सुविधाएँ","Обновление ${update.version} · новые функции и защита")
        }
        if(snackbar?.isShown==true) { bannerText?.text=words;return }
        snackbar=Snackbar.make(view,words,Snackbar.LENGTH_INDEFINITE).apply {
            setTextMaxLines(3)
            if(!downloading) setAction(if(ready!=null) text("Install","ইনস্টল","इंस्टॉल","Установить") else text("Download","ডাউনলোড","डाउनलोड","Скачать")) {
                if(ready!=null) requestInstall() else download(update)
            } else setAction(text("Cancel","বাতিল","रद्द","Отмена")) { cancelDownload() }
            val row=this.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.parent as? LinearLayout
            bannerText=this.view.findViewById(com.google.android.material.R.id.snackbar_text)
            row?.addView(Button(activity).apply { this.text="×";contentDescription=text("Dismiss update for this visit","এইবার আপডেট বন্ধ করুন","इस बार अपडेट बंद करें","Закрыть до следующего входа")
                minWidth=activity.dp(48);minimumHeight=activity.dp(48);setOnClickListener { dismissed=true;snackbar?.dismiss();snackbar=null }
            },LinearLayout.LayoutParams(activity.dp(48),activity.dp(48)))
            show()
        }
    }
    @Volatile private var transfer=0L
    private fun download(update: AppUpdateRules.Candidate) {
        if(closed || !allowed() || downloading || ready!=null) return
        downloading=true;percent=0;val ticket=++transfer;snackbar?.dismiss();snackbar=null;render()
        io.execute {
            val partial=File(folder,"candidate.part");val complete=File(folder,"candidate.apk")
            var success=false
            try {
                require(folder.exists() || folder.mkdirs())
                require(StatFs(folder.path).availableBytes>update.bytes+16*1024*1024)
                partial.delete()
                var reportAt=0L
                response(update.url).use { r ->
                    require(r.isSuccessful)
                    val body=r.body ?: error("No APK")
                    require(body.contentLength() in setOf(-1L,update.bytes))
                    body.byteStream().use { input -> partial.outputStream().use { output ->
                        AppUpdateDownload.copy(input,output,update.bytes,update.sha256,{closed || ticket!=transfer}) { count ->
                            val now=SystemClock.elapsedRealtime()
                            if(now-reportAt>=200) { reportAt=now;val progress=(count*100/update.bytes).toInt().coerceAtMost(99)
                                handler.post { if(!closed && ticket==transfer) { percent=progress;render() } }
                            }
                        }
                        output.fd.sync()
                    } }
                }
                verifyArchive(partial,update)
                require(!closed && ticket==transfer)
                complete.delete();require(partial.renameTo(complete));success=true
            } catch(_: Exception) { partial.delete() }
            handler.post {
                if(closed || ticket!=transfer) return@post
                snackbar?.dismiss();snackbar=null
                if(success) { ready=complete;percent=100;if(allowed()) { downloading=false;requestInstall() } }
                else { downloading=false;notify(text("Download or verification failed. Nothing was installed; retry when ready.","ডাউনলোড বা যাচাই ব্যর্থ। কিছু ইনস্টল হয়নি; পরে আবার চেষ্টা করুন।"));render() }
            }
        }
    }
    private fun cancelDownload() { ++transfer;activeCall?.cancel();downloading=false;snackbar?.dismiss();snackbar=null;render() }
    @Suppress("DEPRECATION")
    private fun currentCode(): Long {
        val info=activity.packageManager.getPackageInfo(activity.packageName,0)
        return if(Build.VERSION.SDK_INT>=28) info.longVersionCode else info.versionCode.toLong()
    }
    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val signatures=if(Build.VERSION.SDK_INT>=28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }?.toSet().orEmpty()
    }
    @Suppress("DEPRECATION")
    private fun verifyArchive(file: File, update: AppUpdateRules.Candidate) {
        val pm=activity.packageManager
        val flags=if(Build.VERSION.SDK_INT>=28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val existing=pm.getPackageInfo(activity.packageName,flags)
        val archive=pm.getPackageArchiveInfo(file.path,flags) ?: error("Invalid APK")
        val code=if(Build.VERSION.SDK_INT>=28) archive.longVersionCode else archive.versionCode.toLong()
        require(archive.packageName==activity.packageName && archive.packageName==AppUpdateRules.PACKAGE)
        require(code==update.code.toLong() && code>currentCode())
        require(archive.versionName==update.version+"-"+variant)
        require(archive.applicationInfo?.minSdkVersion==update.minSdk && update.minSdk<=Build.VERSION.SDK_INT)
        require(archive.applicationInfo?.flags?.and(ApplicationInfo.FLAG_DEBUGGABLE)==0)
        require(AppUpdateRules.sameSigners(signers(existing),signers(archive)))
        // Android's installer performs its full APK signature validation and final user consent.
    }
    @Suppress("DEPRECATION")
    private fun installAllowed() = if(Build.VERSION.SDK_INT>=26) activity.packageManager.canRequestPackageInstalls()
        else Settings.Secure.getInt(activity.contentResolver,Settings.Secure.INSTALL_NON_MARKET_APPS,0)==1
    private fun requestInstall() {
        if(closed || !allowed() || ready==null || installerOpened || waitingSettings) return
        if(installAllowed()) launchInstaller() else {
            waitingSettings=true;returnedSettings=false
            notify(text("Allow installation from Cute Chat, then return. Android will ask you to confirm the update.","Cute Chat থেকে ইনস্টলের অনুমতি দিয়ে ফিরে আসুন। Android আপডেট নিশ্চিত করতে বলবে।"))
            try { activity.startActivityForResult(if(Build.VERSION.SDK_INT>=26) Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${activity.packageName}")) else Intent(Settings.ACTION_SECURITY_SETTINGS),PERMISSION) }
            catch(_: Exception) { waitingSettings=false;notify(text("Open Android settings to allow installation from this app.","Android settings থেকে এই অ্যাপের ইনস্টল অনুমতি দিন।"));render() }
        }
    }
    private fun launchInstaller() {
        if(closed || !allowed() || installerOpened || !installAllowed()) return
        val file=ready ?: return;val update=candidate ?: return
        // Recheck private-file bytes/archive immediately before each attempt, off the UI thread.
        installerOpened=true;snackbar?.dismiss();snackbar=null
        io.execute {
            val valid=runCatching {
                require(file.length()==update.bytes)
                val digest=MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input -> val buffer=ByteArray(65536);while(true) { val n=input.read(buffer);if(n<0) break;digest.update(buffer,0,n) } }
                require(AppUpdateRules.digestMatches(digest.digest(),update.sha256));verifyArchive(file,update)
            }.isSuccess
            val staged = valid && runCatching {
                NativeUpdateInstaller.stage(activity.applicationContext,file,update) { closed }
            }.isSuccess
            handler.post {
                if(closed) return@post
                if(!valid) { installerOpened=false;ready=null;file.delete();notify(text("Update verification failed","আপডেট যাচাই ব্যর্থ"));render();return@post }
                if(!staged) { installerOpened=false;notify(text("Android could not prepare installation. Check free storage and retry from App updates.","Android ইনস্টল প্রস্তুত করতে পারেনি। খালি storage দেখুন এবং App updates থেকে আবার চেষ্টা করুন।"));render();return@post }
                if(!allowed()) { installerOpened=false;return@post }
                foreground()
            }
        }
    }
    override fun close() {
        closed=true;++transfer;activeCall?.cancel();snackbar?.dismiss();handler.removeCallbacksAndMessages(null);io.shutdownNow()
        NativeUpdateInstaller.changed=null
        // Do not remove a verified APK while Android's installer may still be reading its URI.
        http.connectionPool.evictAll()
    }
    private class Limited : Exception()
    companion object { const val PERMISSION=72;const val INSTALL=73 }
}
