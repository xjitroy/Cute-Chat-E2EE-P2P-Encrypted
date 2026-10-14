package app.cutiepie.cutie_pie

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.ToneGenerator
import android.media.AudioManager
import android.os.*
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Rational
import android.view.*
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import app.cutiepie.cutie_pie.core.*
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.EncodeHintType
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Native launcher and UI. No Activity owns a vault, a ratchet or a capture session. */
class MainActivity : androidx.appcompat.app.AppCompatActivity() {
    private val app get() = application as CutieApplication
    internal val owner get() = app.owner
    internal val calls get() = app.calls
    private val handler = Handler(Looper.getMainLooper())
    private val mediaIo = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val appUpdates by lazy { NativeAppUpdates(this,
        { visible && unlocked && owner.preferences.getBoolean("setup",false) && !calls.active && page!="lock" && !isInPictureInPictureModeCompat() },
        { contentHost }) }
    private val nearbySetup by lazy { NativeNearbySetup(this, { visible && unlocked && !calls.active && (page=="sharing" || sharingRouteRequested) && !owner.quickBatchBusy && owner.largeTransfers.none { it.state !in setOf("done","failed") } }) }
    private var returnPage="home"
    private var returnPeer:String?=null
    private var returnSettings=""
    private val viewSession get()=(application as CutieApplication).viewSession
    private fun lockMinutes()=owner.preferences.getInt("viewLockMinutes",5).coerceIn(0,60)
    private fun usableView()=viewSession.usable(SystemClock.elapsedRealtime(),lockMinutes(),getSystemService(KeyguardManager::class.java).isDeviceLocked)
    private fun acceptedPrivateAction(action:()->Unit) {
        if(!visible || !unlocked || !usableView()) return
        // A positive gesture in our own secure dialog is still a foreground private view.
        owner.viewAccess(true,if(page=="chat") peer else null);action()
    }
    private fun rememberPage() { if(page!="lock") { returnPage=page;returnPeer=peer;returnSettings=settingsCategory } }
    private fun restorePage() {
        when(returnPage) {
            "sharing"->sharingPage()
            "calls","gallery"->libraryPage(returnPage)
            "chat"->if(returnPeer!=null && owner.contacts.any { it.uid==returnPeer }) openChat(returnPeer!!) else home()
            "settings"->settings(returnSettings)
            "contacts"->contacts()
            "people"->phoneContacts()
            "call"->if(calls.active) callScreen() else libraryPage("calls")
            else->home()
        }
    }
    private fun expireView() {
        if(!owner.preferences.getBoolean("setup",false) || !unlocked || usableView()) return
        rememberPage();unlocked=false;owner.viewAccess(false)
        if(!isInPictureInPictureModeCompat()) lock()
    }
    private var page = "home"
    private var peer: String? = null
    private var pendingPair: String? = null
    private var reply: String? = null
    private var unlocked = false
    private var visible = false
    internal val remoteUiAllowed get() = visible && unlocked
    private var permissionCall: (() -> Unit)? = null
    private var resumedPermission: (() -> Unit)? = null
    private var projectionCall: String? = null
    private var pendingProjection: Pair<String, Intent>? = null
    private var revealGesture = false
    private var savingAttachment: Pair<String, String>? = null
    private var pickingPeer: String? = null
    private var googleSetupName: String? = null
    private var pendingProfileUri: android.net.Uri? = null
    private var profileDraftBio:String?=null
    private var qrScanner:NativeQrScanner?=null
    private var qrPageReady=false
    private var qrPermissionAsked=false
    private var qrNumberVisible=false
    private var qrIdButton:MaterialButton?=null
    private var securityArrivalPending=true
    private var contentHost:FrameLayout?=null
    private var ownQrDialog:Dialog?=null
    private var qrBitmap:Bitmap?=null
    private var qrProfileBitmap:Bitmap?=null
    private var swipeStartX=0f;private var swipeStartY=0f;private var swipePage="";private var swipingTabs=false
    private var swipeVelocity:VelocityTracker?=null;private var swipeRoot:View?=null;private var tabMotionDirection=0
    private var privateProfileDialog: Dialog? = null
    private val privateDialogs=mutableSetOf<Dialog>()
    private val pendingDialogs=java.util.ArrayDeque<Dialog>()
    private val profilePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> pendingProfileUri = uri; consumeProfileSelection() }
    private var attachmentDraft: ByteArray? = null
    private var draftDialog: Dialog? = null
    private var limitedViewer: Pair<String, String>? = null
    private var viewingAttachment: Pair<String,String>? = null
    private var viewerDeletionRevision = -1L
    private var limitedTimer: Runnable? = null
    private var externalCache: java.io.File? = null
    private var captureRegistration: Activity.ScreenCaptureCallback? = null
    private var captureSequence = 0L
    private var captureAt = 0L
    private data class PickedMedia(val uri: android.net.Uri, val peer: String, val mediaOnly: Boolean, val captured: Boolean)
    private var pendingMedia: PickedMedia? = null
    private var sharingPickerArmed=false
    private var sharingRecipients:List<String> = emptyList()
    private var sharingRouteRequested=false
    private var batchSending=false
    private var pendingFileSave:Triple<String,String,android.net.Uri>?=null
    private var pendingQuickFolder:android.net.Uri?=null
    private var quickPickerArmed=false
    private var quickSelection=false
    private var quickFiles:List<android.net.Uri> = emptyList()
    private var quickTarget:String?=null
    private var quickEndpoint:String?=null
    private var quickConnecting=false
    private var quickBatchConfirmed=false
    private var readingSelection=false
    private val pickedQueue=java.util.ArrayDeque<PickedMedia>()
    private var sharingList:LinearLayout?=null
    private var sharingStatus:TextView?=null
    private var sharingSnapshot=""
    private var sharingSteps:SharingStepsView?=null
    private val sharingProgress=mutableMapOf<String,Triple<TextView,TextView,ProgressBar>>()
    private var localPermissionAction:(()->Unit)?=null
    private val multipleMediaPicker=registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(LocalSharingRules.MAX_SELECTION)) { uris ->
        val target=pickingPeer;pickingPeer=null
        if(target!=null) queuePicked(uris,target,true,false)
    }
    private var mediaOpening = false
    private var mediaGeneration = 0
    private val mediaPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val target = pickingPeer; pickingPeer = null
        if (uri != null && target != null) { pendingMedia = PickedMedia(uri, target, true, false); consumePickedMedia() }
    }
    private var chatList: LinearLayout? = null
    private var chatStatus: TextView? = null
    private var callLabel: TextView? = null
    private var callKey = ""
    private var shownCall = ""
    private var messageKey = ""
    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var renderedLocal: VideoTrack? = null
    private var renderedRemote: VideoTrack? = null
    private val selfPreview = SelfPreviewPolicy()
    private val restoreSelfPreview = Runnable { bindCallVideo() }
    private var callHeading: View? = null
    private var callFooter: View? = null
    private var callLightTop: View? = null
    private var callLightBottom: View? = null
    private var callLightCap: View? = null
    private var callFrame: FrameLayout? = null
    private var shownCallFailure = 0L
    private var lightPreview: Pair<Int,Int>? = null
    private var preCallBrightness: Float? = null
    private var callChromeVisible = true
    private val hideCallChrome = Runnable { setCallChrome(false) }
    private var recorder: android.media.MediaRecorder? = null
    private var recordingFile: java.io.File? = null
    private var recordingPeer: String? = null
    private var recordingStarted = 0L
    private var voiceStartFailure = ""
    private val voiceGesture by lazy { VoiceMessageGesture(dp(80).toFloat()) }
    private var voiceButton: MaterialButton? = null
    private var voiceStatus: TextView? = null
    private var voiceRow: View? = null
    private val recordingDisabled = mutableListOf<View>()
    private val armVoiceHold = Runnable { if (voiceGesture.holdDeadline() == VoiceMessageGesture.Action.START_HOLD) startVoiceRecording(peer ?: return@Runnable) }
    private val voiceTick = object : Runnable {
        override fun run() {
            if (recorder == null) return
            val seconds = (SystemClock.elapsedRealtime() - recordingStarted) / 1000
            if (seconds >= 60) { cancelRecording(); toast(tr("One-minute limit reached; recording cancelled", "এক মিনিট হয়েছে; রেকর্ড বাতিল")); return }
            voiceStatus?.text = "%02d:%02d · ".format(seconds / 60, seconds % 60) + if (voiceGesture.holding)
                tr("Release to send · slide left to cancel", "ছেড়ে পাঠান · বাঁয়ে টেনে বাতিল", "भेजने के लिए छोड़ें · रद्द करने के लिए बाएँ खींचें", "Отпустите для отправки · влево для отмены")
            else tr("Tap mic again to send", "পাঠাতে মাইকে আবার ট্যাপ করুন", "भेजने के लिए फिर टैप करें", "Нажмите ещё раз для отправки")
            handler.postDelayed(this, 200)
        }
    }
    private var mediaCache: java.io.File? = null
    private var mediaDialog: Dialog? = null
    private var root: LinearLayout? = null
    private var chatExtras: View? = null
    private var imeVisible = false
    private var settingsCategory = ""
    internal val remoteConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if(result.resultCode==Activity.RESULT_OK) result.data?.getStringExtra("ticket")?.let { (application as CutieApplication).assist.grant(it) }
    }
    private var contactsPermissionPending = false
    private var contactsHubRequested = false
    private var phoneContactEntries: List<PhoneContacts.Entry> = emptyList()
    private var contactHubList: LinearLayout? = null
    private var contactHubSearch: EditText? = null
    private var homeStatus: TextView? = null
    private var homeHeader: HomeSecurityHeader? = null
    private val authSession=EntryAuthSession()
    private var credentialToken=0L
    private var authInFlight = false
    private var authAttempted = false
    private var authPendingRoute = false
    private var credentialPending = false
    private var authCancellation: CancellationSignal? = null
    private val homePeerStatuses = mutableMapOf<String, TextView>()
    private var libraryList: LinearLayout? = null
    private var galleryTimeline: PrivateMediaTimeline? = null
    private var galleryFiles: com.google.android.material.button.MaterialButton? = null
    private var galleryEmpty: TextView? = null
    private var gallerySnapshot: List<PrivateLibrary.Media>? = null
    private var libraryKey = ""
    private val observer: () -> Unit = { update() }
    private val clock = object : Runnable {
        override fun run() {
            expireView()
            if (calls.active && visible && unlocked && page == "call" && !isInPictureInPictureModeCompat()) {
                val seconds = if (calls.connectedAt == 0L) 0 else (SystemClock.elapsedRealtime() - calls.connectedAt) / 1000
                val stage=if(calls.audioInterrupted) tr("Audio interrupted","অডিও সাময়িক বন্ধ") else if(calls.status=="calling" && calls.remoteRinging) tr("Ringing","রিং হচ্ছে") else calls.status
                callLabel?.text="$stage · %02d:%02d\n${if(calls.status=="connected") "P2P · E2EE" else tr("Connecting encrypted media","Encrypted media সংযোগ হচ্ছে")} · ${calls.captureQuality.take(36)}".format(seconds/60,seconds%60)
                callLabel?.contentDescription=callLabel?.text.toString()+". "+calls.quality

                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else if (recorder != null && visible && unlocked) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            handler.postDelayed(this, 1000)
        }
    }
    // DecorView may retain its pre-overlay context; use the Activity's current
    // dynamic theme for custom views as well as Material widgets.
    private val themeAnchor by lazy { View(this) }
    private val glass get() = owner.preferences.getString("visualStyle", CuteUi.STYLE_MATERIAL) == CuteUi.STYLE_GLASS
    private val purple get() = if (glass) CuteUi.INDIGO else MaterialYou.color(themeAnchor, androidx.appcompat.R.attr.colorPrimary)
    private val surface get() = if (glass) CuteUi.glassSurface(themeAnchor) else MaterialYou.color(themeAnchor, com.google.android.material.R.attr.colorSurface)
    private val container get() = if (glass) CuteUi.glassContainer(themeAnchor) else MaterialYou.color(themeAnchor, com.google.android.material.R.attr.colorSurfaceContainer)
    private val ink get() = if (glass) CuteUi.glassInk(themeAnchor) else MaterialYou.color(themeAnchor, com.google.android.material.R.attr.colorOnSurface)
    private val subtle get() = if (glass) CuteUi.glassSubtle(themeAnchor) else MaterialYou.color(themeAnchor, com.google.android.material.R.attr.colorOnSurfaceVariant)
    private var homeKey = ""
    private var homeSearch = ""
    private var lastPage = ""
    private var fullCall = false
    private fun field() = TextInputEditText(this).apply {
        setTextColor(ink); setHintTextColor(subtle); setPadding(dp(16), dp(12), dp(16), dp(12))
        background = CuteUi.card(this, glass, dp(22).toFloat())
    }
    internal fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    internal fun tr(en: String, bn: String, hi: String = en, ru: String = en): String = when (owner.preferences.getString("language", "en")) { "bn" -> bn; "hi" -> hi; "ru" -> ru; else -> en }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        returnPage=state?.getString("returnPage") ?: state?.getString("mainTab") ?: "home"
        returnPeer=state?.getString("returnPeer")
        returnSettings=state?.getString("settingsCategory") ?: ""
        page=returnPage;peer=returnPeer;settingsCategory=returnSettings
        pickingPeer = state?.getString("pickingPeer")
        sharingRecipients=state?.getStringArrayList("sharingRecipients")?.take(12).orEmpty()
        googleSetupName = state?.getString("googleSetupName")
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (imeVisible) { androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.ime()); return }
                if (!unlocked) { finish(); return }
                if (page == "settings" && settingsCategory.isNotEmpty()) settings()
                else if (page == "home") finish() else home()
            }
        })
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        owner.observe(observer); calls.onChanged = observer; handler.post(clock)
        if (owner.preferences.getBoolean("setup", false)) owner.preferences.edit().putBoolean("lock", true).remove("pinHash").remove("pinSalt").apply()
        unlocked = !owner.preferences.getBoolean("setup", false) || usableView()
        if(!owner.preferences.getBoolean("home.panel.v041",false)) owner.preferences.edit().putBoolean("home.panel.v041",true).putBoolean("home.securityCollapsed",true).apply()
        if (!owner.preferences.contains("language")) language() else if (!owner.preferences.getBoolean("setup", false)) onboarding() else {
            owner.setup(owner.preferences.getString("name", "Cute Chat")!!, false)
            if (unlocked) restorePage() else lock()
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        pickingPeer?.let { outState.putString("pickingPeer", it);if(sharingRecipients.isNotEmpty()) outState.putStringArrayList("sharingRecipients",ArrayList(sharingRecipients)) }
        rememberPage();outState.putString("returnPage",returnPage);outState.putString("returnPeer",returnPeer)
        outState.putString("settingsCategory",returnSettings)
        googleSetupName?.let { outState.putString("googleSetupName", it) }
        if (page == "settings") outState.putString("settingsCategory", settingsCategory)
        if (page in setOf("home", "calls", "gallery", "sharing")) outState.putString("mainTab", page)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume(); visible = true
        if(owner.preferences.getBoolean("setup",false) && unlocked && !viewSession.resume(SystemClock.elapsedRealtime(),lockMinutes(),getSystemService(KeyguardManager::class.java).isDeviceLocked)) { rememberPage();unlocked=false }
        if(unlocked && page=="lock") restorePage()
        if(unlocked && page=="gallery" && galleryTimeline==null) libraryPage("gallery")
        owner.viewAccess(unlocked,if(page == "chat") peer else null)
        if (unlocked && owner.preferences.getBoolean("setup",false)) owner.enter()
        if (authPendingRoute && unlocked) { authPendingRoute=false; afterUnlock() }
        if (unlocked && googleSetupName != null && !owner.preferences.getBoolean("setup",false) && com.google.firebase.auth.FirebaseAuth.getInstance().currentUser != null) finishGoogleSetup()
        if(unlocked && page == "chat") peer?.let(owner::requestChatWindow)
        externalCache?.delete(); externalCache = null
        applyCapturePolicy()
        if (calls.active) owner.work { calls.resumeAudio() }
        if (unlocked) {
            applyCallBrightness(page == "call" && calls.video && !calls.screenSharing && owner.preferences.getBoolean("callLight", false))
            consumeProjection()
        }
        if(unlocked) { val action=resumedPermission; resumedPermission=null; action?.invoke() }
        if (owner.preferences.getBoolean("lock", false) && !unlocked) lock() else if (unlocked && owner.ready) { consumeCall(); update() }
        consumePickedMedia()
        consumeProfileSelection()
        if(page=="contacts") startQrPreview()
        contentHost?.let { showSecurityArrival(it) };appUpdates.foreground();nearbySetup.foreground()
    }
    override fun onWindowFocusChanged(hasFocus:Boolean) {
        super.onWindowFocusChanged(hasFocus)
        owner.viewAccess(hasFocus && visible && unlocked,if(page == "chat") peer else null)
        if(!hasFocus) qrScanner?.close() else if(page=="contacts") startQrPreview()
    }
    override fun onPause() { resetTabGesture(false);appUpdates.pause();qrScanner?.close();qrNumberVisible=false;qrIdButton?.text=tr("Private ID hidden · tap to reveal","Private ID লুকানো · দেখতে ট্যাপ করুন");securityArrivalPending=true;visible = false; owner.viewAccess(false); window.addFlags(WindowManager.LayoutParams.FLAG_SECURE); privateDialogs.toList().forEach { it.dismiss() }; privateDialogs.clear(); privateProfileDialog?.dismiss(); privateProfileDialog=null; cancelRecording(); resetSelfPreview(); restoreCallBrightness(); super.onPause() }
    private fun dispatchCallTouch(event: MotionEvent): Boolean {
        if (revealGesture) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) revealGesture = false
            return true
        }
        if (page == "call" && calls.video && !isInPictureInPictureModeCompat() && event.actionMasked == MotionEvent.ACTION_DOWN) {
            val previewBounds = Rect()
            if (localRenderer?.getGlobalVisibleRect(previewBounds) == true && previewBounds.contains(event.rawX.toInt(), event.rawY.toInt()))
                return super.dispatchTouchEvent(event)
            if (!callChromeVisible) { revealGesture = true; setCallChrome(true); scheduleCallChrome(); return true }
            fun hits(view: View?): Boolean { val rect = Rect(); return view?.visibility == View.VISIBLE && view.getGlobalVisibleRect(rect) && rect.contains(event.rawX.toInt(), event.rawY.toInt()) }
            if (calls.status == "connected" && !hits(callHeading) && !hits(callFooter) && !hits(localRenderer)) { revealGesture = true; setCallChrome(false); handler.removeCallbacks(hideCallChrome); return true }
            scheduleCallChrome()
        }
        return super.dispatchTouchEvent(event)
    }
    override fun onStop() {
        appUpdates.background()
        mediaGeneration++; mediaOpening = false
        galleryTimeline?.dispose(); galleryTimeline=null; gallerySnapshot=null
        if (Build.VERSION.SDK_INT >= 34) captureRegistration?.let { unregisterScreenCaptureCallback(it); captureRegistration = null }
        cancelRecording()
        attachmentDraft?.fill(0); attachmentDraft = null; draftDialog?.dismiss(); draftDialog = null
        mediaDialog?.dismiss(); mediaDialog = null; mediaCache?.delete(); mediaCache = null
        limitedViewer = null
        viewingAttachment = null
        owner.leave()
        owner.preferences.edit().putBoolean("home.securityCollapsed",true).apply()
        if (owner.preferences.getBoolean("setup", false)) {
            rememberPage();viewSession.leave(SystemClock.elapsedRealtime());owner.viewAccess(false)
            if (!credentialPending) { authSession.invalidate(); authCancellation?.cancel(); authCancellation=null; authInFlight=false;authAttempted=false }
            expireView()
        }
        super.onStop()
    }
    override fun onStart() {
        super.onStart()
        expireView()
        if (Build.VERSION.SDK_INT >= 34) {
            val callback = Activity.ScreenCaptureCallback { screenshotDetected() }
            registerScreenCaptureCallback(mainExecutorCompat(), callback); captureRegistration = callback
        }
    }
    override fun onDestroy() { resetTabGesture(false);appUpdates.close();nearbySetup.close();qrScanner?.close();qrBitmap?.recycle();qrBitmap=null;qrProfileBitmap?.recycle();qrProfileBitmap=null;privateDialogs.toList().forEach { it.dismiss() }; privateDialogs.clear(); pendingDialogs.clear(); cancelRecording(); mediaIo.shutdownNow(); attachmentDraft?.fill(0); externalCache?.delete(); mediaCache?.delete(); owner.unobserve(observer); if (calls.onChanged === observer) calls.onChanged = null; handler.removeCallbacksAndMessages(null); releaseRenderers(); super.onDestroy() }
    private fun applyCapturePolicy() {
        val target = if (page == "call" && calls.active) calls.peer else if (page == "chat") peer else null
        if (visible && unlocked && limitedViewer == null && target != null && owner.preferences.getBoolean("capture.allow.$target", false)) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    private fun screenshotDetected() {
        if (!unlocked || !visible) return
        val now = SystemClock.elapsedRealtime()
        if (now - captureAt < 1500) return
        captureAt = now
        limitedViewer?.let { owner.expireMedia(it.first, it.second); mediaDialog?.dismiss() }
        val target = if (page == "call" && calls.active) calls.peer else if (page == "chat") peer else null
        if (target != null) { owner.reportCapture(target, if (page == "call" && calls.active) calls.id else null); toast(tr("Screenshot detected · private notice queued", "স্ক্রিনশট শনাক্ত হয়েছে · ব্যক্তিগত খবর পাঠানো হচ্ছে")) }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); if (unlocked && visible) { consumeCall(); update() } }
    private fun consumeCall() {
        consumeProjection()
        if (visible && unlocked) { val resume=resumedPermission; resumedPermission=null; resume?.invoke() }
        val action = NativeCalls.consume()
        if (action?.get("action") == "open") page = "call"
        if (action?.get("action") == "answer" && calls.status == "incoming") permission(true, calls.video) { owner.work { calls.answer() } }
        if(intent.getBooleanExtra("sharing",false) && visible && unlocked && !calls.active) { intent.removeExtra("sharing");intent.removeExtra("peer");sharingPage();return }
        val requested = intent.getStringExtra("peer") ?: if(intent.getStringExtra("kind") == "ping") intent.getStringExtra("sender") else null
        if (!calls.active && requested != null && owner.contacts.any { it.uid == requested }) openChat(requested)
        intent.removeExtra("peer")
    }
    private fun normalBars() {
        // PhoneWindow's controller getter dereferences its decor. First launch
        // has no content yet; force decor creation before touching bars.
        val decor = window.decorView
        fullCall = false
        if (Build.VERSION.SDK_INT >= 31) setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
        if (Build.VERSION.SDK_INT >= 30) {
            decor.windowInsetsController?.show(WindowInsets.Type.systemBars())
            decor.windowInsetsController?.setSystemBarsAppearance(if (resources.configuration.uiMode and 0x30 == 0x20) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
        } else { @Suppress("DEPRECATION") window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            (if (resources.configuration.uiMode and 0x30 == 0x20) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) }
    }
    private fun animationsAllowed() = !owner.preferences.getBoolean("reduceMotion", false) &&
        (Build.VERSION.SDK_INT < 26 || android.animation.ValueAnimator.areAnimatorsEnabled())
    private fun securityHeading()=tr("E2EE · Direct P2P","E2EE · সরাসরি P2P","E2EE · सीधा P2P","E2EE · Прямое P2P")
    private fun securitySummary()=tr("End-to-end encrypted messages, calls & files. Directly between your devices.","End-to-end এনক্রিপ্টেড মেসেজ, কল ও ফাইল। সরাসরি আপনাদের ডিভাইসের মধ্যে।","एंड-टू-एंड एन्क्रिप्टेड संदेश, कॉल और फ़ाइलें। सीधे आपके डिवाइसों के बीच।","Сквозное шифрование сообщений, звонков и файлов. Напрямую между устройствами.")
    private fun enterMotion(view: View) {
        val direction=tabMotionDirection;tabMotionDirection=0
        if(lastPage==page) return
        lastPage=page
        if(!animationsAllowed()) return
        view.animate().cancel();view.alpha=if(direction==0) .92f else .86f
        view.translationY=if(direction==0) dp(6).toFloat() else 0f
        view.translationX=direction*dp(52).toFloat()
        view.animate().alpha(1f).translationX(0f).translationY(0f).setDuration(if(direction==0) 160 else 220)
            .setInterpolator(android.view.animation.PathInterpolator(.2f,0f,0f,1f)).start()
    }
    /** One inset owner for edge-to-edge and legacy adjustResize. Never add IME
     * and navigation heights together: the keyboard already overlaps the bar. */
    private fun showContent(content: View) {
        ownQrDialog?.dismiss();ownQrDialog=null
        if(page!="contacts") { qrScanner?.close();qrScanner=null;qrBitmap?.recycle();qrBitmap=null;qrProfileBitmap?.recycle();qrProfileBitmap=null;qrNumberVisible=false }
        // Deferred callbacks (including Google linking) cannot replace the
        // lock screen with private UI before this entry was authenticated.
        if(!unlocked && owner.preferences.getBoolean("setup",false) && page != "lock") { lock(); return }

        applyCapturePolicy()
        val immersive = fullCall
        val host = FrameLayout(this).apply { setBackgroundColor(if (immersive) Color.BLACK else surface); addView(content) }
        ViewCompat.setOnApplyWindowInsetsListener(host) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val shown = insets.isVisible(WindowInsetsCompat.Type.ime())
            view.setPadding(if (immersive) 0 else bars.left, if (immersive) 0 else bars.top,
                if (immersive) 0 else bars.right, if (immersive) 0 else maxOf(bars.bottom, ime.bottom))
            if (immersive) {
                callHeading?.setPadding(maxOf(dp(12), bars.left + dp(8)), maxOf(dp(20), bars.top + dp(8)), maxOf(dp(12), bars.right + dp(8)), dp(16))
                callFooter?.setPadding(maxOf(dp(16), bars.left + dp(8)), dp(40), maxOf(dp(16), bars.right + dp(8)), maxOf(dp(32), bars.bottom + dp(8)))
            }
            if (!immersive && shown != imeVisible) {
                imeVisible = shown
                chatExtras?.visibility = if (shown) View.GONE else View.VISIBLE
                chatStatus?.maxLines = if (shown) 1 else 4
                // Keep the focused composer visible, without rebuilding or
                // clearing its draft. The weighted timeline takes the resize.
                if (shown && page == "chat") (chatList?.parent as? ScrollView)?.post {
                    (chatList?.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN)
                }
            }
            WindowInsetsCompat.CONSUMED
        }
        setContentView(host); ViewCompat.requestApplyInsets(host)
        contentHost=host;showSecurityArrival(host);handler.post { appUpdates.contentChanged() }
    }
    private fun showSecurityArrival(host:FrameLayout) {
        if(securityArrivalPending && !fullCall && page!="lock" && visible && unlocked) {
            securityArrivalPending=false
            val badge=SecurityArrivalView(this,securityHeading(),securitySummary(),animationsAllowed())
            host.addView(badge,FrameLayout.LayoutParams(-1,-2,Gravity.TOP).apply { leftMargin=dp(16);rightMargin=dp(16);topMargin=dp(12) })
            handler.postDelayed({ if(badge.parent===host) { if(animationsAllowed()) badge.animate().alpha(0f).setDuration(140).withEndAction { host.removeView(badge) }.start() else host.removeView(badge) } },900)
        }
    }
    private fun base(title: String, back: (() -> Unit)? = null): LinearLayout {
        normalBars()
        restoreCallBrightness()
        handler.removeCallbacks(hideCallChrome)
        cancelRecording()
        resetSelfPreview()
        galleryTimeline?.dispose(); galleryTimeline=null; galleryFiles=null; galleryEmpty=null; gallerySnapshot=null
        voiceButton = null; voiceStatus = null; voiceRow = null; recordingDisabled.clear()
        if(page!="contacts") { qrScanner?.close();qrScanner=null;qrBitmap?.recycle();qrBitmap=null;qrProfileBitmap?.recycle();qrProfileBitmap=null;qrNumberVisible=false }
        releaseRenderers(); callKey = ""; chatList = null; chatStatus = null; callLabel = null; messageKey = ""
        callHeading = null; callFooter = null; callLightTop = null; callLightBottom = null; callLightCap=null;callFrame=null; chatExtras = null; homeStatus = null; homeHeader=null; libraryList = null; libraryKey = ""
        root?.animate()?.cancel()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(10)); background = CuteUi.background(this, glass) }
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(64) }
        if (back != null) heading.addView(iconButton("back", tr("Back", "ফিরুন", "वापस", "Назад")) { back() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        else heading.addView(logo(40), LinearLayout.LayoutParams(dp(44), dp(44)))
        heading.addView(label(title, 26f).apply { setTypeface(typeface, Typeface.BOLD); setPadding(dp(10), 0, 0, 0) }, LinearLayout.LayoutParams(0, dp(60), 1f))
        if (page in setOf("home", "calls", "gallery", "sharing")) heading.addView(iconButton("more", tr("Settings", "সেটিংস", "सेटिंग्स", "Настройки")) { settings() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        if (page == "home") {
            // A revealable local info panel; authenticated connection badges
            // remain separate in each contact row.
            homeHeader = HomeSecurityHeader(this, logo(40), iconButton("more", tr("Settings", "সেটিংস", "सेटिंग्स", "Настройки")) { settings() },
                securityHeading(),
                securitySummary()+"\n"+tr("Private chat history is encrypted on this phone.","এই ফোনে ব্যক্তিগত চ্যাটের ইতিহাস এনক্রিপ্টেড থাকে।","निजी चैट इतिहास इस फ़ोन पर एन्क्रिप्टेड है।","Личная история чата зашифрована на этом телефоне."),
                tr("Services coordinate connections and notifications, not chat content. Quick Share saves readable copies to Downloads.","পরিষেবা সংযোগ ও নোটিফিকেশন সমন্বয় করে; চ্যাটের বিষয়বস্তু নয়। Quick Share সাধারণ কপি Downloads-এ সেভ করে।","सेवाएँ कनेक्शन और सूचनाएँ समन्वित करती हैं, चैट सामग्री नहीं। Quick Share की प्रतियाँ Downloads में पढ़ने योग्य हैं।","Сервисы координируют соединения и уведомления, а не содержимое чата. Quick Share сохраняет обычные копии в Downloads."),
                tr("Hide security panel", "সিকিউরিটি প্যানেল লুকান", "सुरक्षा पैनल छिपाएँ", "Скрыть панель защиты"),
                tr("Show security panel. Tap or pull down.", "সিকিউরিটি প্যানেল দেখান। ট্যাপ করুন বা নিচে টানুন।", "सुरक्षा पैनल दिखाएँ। टैप करें या नीचे खींचें।", "Показать защиту. Нажмите или потяните вниз."),
                tr("Hide security panel. Tap or swipe up.", "সিকিউরিটি প্যানেল লুকান। ট্যাপ করুন বা উপরে টানুন।", "सुरक्षा पैनल छिपाएँ। टैप करें या ऊपर खींचें।", "Скрыть защиту. Нажмите или проведите вверх."),
                !owner.preferences.getBoolean("home.securityCollapsed", true), ::animationsAllowed,
                { show -> owner.preferences.edit().putBoolean("home.securityCollapsed", !show).apply() })
            layout.addView(homeHeader)
        } else layout.addView(heading)
        showContent(layout); root = layout; enterMotion(layout)
        owner.viewAccess(visible && unlocked,if(page == "chat") peer else null)
        return layout
    }
    internal fun label(text: String, size: Float = 16f) = TextView(this).apply { this.text = text; textSize = size; setTextColor(ink); setPadding(0, dp(8), 0, dp(8)); includeFontPadding = false }
    internal fun button(title: String, click: () -> Unit) = MaterialButton(this).apply {
        text = title; isAllCaps = false; cornerRadius = dp(24); minHeight = dp(48)
        insetTop = dp(4); insetBottom = dp(4); setPadding(dp(14), 0, dp(14), 0)
        maxLines = 2; textSize = 14f
        setOnClickListener {
            // Material ripple provides immediate feedback without extra
            // animation callbacks delaying actions or outliving navigation.
            click()
        }; contentDescription = title
        if (glass) { backgroundTintList = MaterialYou.tint(purple); setTextColor(Color.WHITE) }
    }
    private fun iconButton(iconName: String, title: String, selected: Boolean = false, click: () -> Unit) = button("", click).apply {
        icon = MaterialYou.icon(this@MainActivity, iconName, purple); iconTint = MaterialYou.tint(purple)
        iconPadding = 0; iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START; iconSize = dp(24)
        contentDescription = title; if (Build.VERSION.SDK_INT >= 26) tooltipText = title
        backgroundTintList = MaterialYou.tint(if (selected) MaterialYou.color(this, com.google.android.material.R.attr.colorPrimaryContainer) else container)
        setPadding(dp(12), 0, dp(12), 0); minWidth = 0; minimumWidth = 0
    }
    private fun card(body: View): MaterialCardView = MaterialCardView(this).apply {
        radius = dp(24).toFloat(); cardElevation = if (glass) dp(2).toFloat() else 0f
        strokeWidth = if (glass) dp(1) else 0; strokeColor = if (glass) 0x33FFFFFF else Color.TRANSPARENT
        setCardBackgroundColor(container)
        addView(body); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(6), 0, dp(10)) }
    }
    private fun avatar(name: String, size: Int = 52) = label(name.trim().take(1).uppercase().ifEmpty { "♥" }, (size/2).toFloat()).apply {
        gravity = Gravity.CENTER; setPadding(0,0,0,0); setTextColor(MaterialYou.color(this, com.google.android.material.R.attr.colorOnPrimaryContainer))
        background = MaterialYou.shape(MaterialYou.color(this, com.google.android.material.R.attr.colorPrimaryContainer), dp(size).toFloat())
    }
    private fun logo(size: Int) = ImageView(this).apply {
        setImageResource(R.drawable.cute_chat_logo); adjustViewBounds = true; background=MaterialYou.shape(Color.TRANSPARENT,dp(18).toFloat()); clipToOutline=true; contentDescription = "Cute Chat heart lock logo"
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply { gravity = Gravity.CENTER_HORIZONTAL }
    }
    private fun language() {
        page = "language"; val layout = base("Cute Chat ♥")
        layout.addView(logo(180)); layout.addView(label("Choose your language / ভাষা বেছে নিন"))
        listOf("en" to "English", "bn" to "বাংলা", "hi" to "हिन्दी", "ru" to "Русский").forEach { (code, name) ->
            layout.addView(button(name) { owner.preferences.edit().putString("language", code).apply(); if (owner.preferences.getBoolean("setup", false)) settings() else onboarding() })
        }
    }
    private fun onboarding() {
        page = "setup"; unlocked = true
        val layout = base("Cute Chat"); layout.addView(logo(150))
        layout.addView(label(tr("Your private space for two", "দুজনের ব্যক্তিগত কথা", "दो लोगों की निजी बातचीत", "Личное общение для двоих"), 22f))
        layout.addView(label(guide()))
        val why = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18),dp(12),dp(18),dp(14)) }
        why.addView(label(tr("Why Cute Chat?", "কেন Cute Chat?", "Cute Chat क्यों?", "Почему Cute Chat?"), 18f).apply { setTypeface(typeface,Typeface.BOLD) })
        why.addView(label(tr(
            "✓ No server chat mailbox\n✓ Encrypted history stays on your devices\n✓ Direct-only live chat and calls\n✓ Signed QR identity verification",
            "✓ Server chat mailbox নেই\n✓ Encrypted history আপনার device-এ\n✓ Direct-only live chat ও call\n✓ Signed QR দিয়ে পরিচয় যাচাই",
            "✓ कोई server chat mailbox नहीं\n✓ encrypted history आपके device पर\n✓ direct-only live chat और calls\n✓ signed QR identity verification",
            "✓ Нет серверного хранилища чатов\n✓ История остаётся на устройствах\n✓ Прямые чаты и звонки\n✓ Проверка личности по QR"),14f))
        why.addView(label(tr(
            "Compared with WhatsApp: both can protect personal conversations with end-to-end encryption. Cute Chat chooses no offline content mailbox or cloud chat recovery, so both phones must be connected; WhatsApp is designed for broader offline delivery and optional backups.",
            "WhatsApp-এর সঙ্গে তুলনা: দুটিই personal conversation E2EE দিয়ে সুরক্ষিত করতে পারে। Cute Chat offline content mailbox বা cloud chat recovery রাখে না—তাই দুই ফোনের connection লাগে; WhatsApp বেশি বিস্তৃত offline delivery ও optional backup-এর জন্য তৈরি।"),12f).apply { setTextColor(subtle) })
        layout.addView(card(why))
        val name = field().apply { hint = tr("Your display name", "আপনার নাম", "आपका नाम", "Ваше имя"); setSingleLine(); filters = arrayOf(android.text.InputFilter.LengthFilter(80)) }
        layout.addView(name)
        val accept = com.google.android.material.checkbox.MaterialCheckBox(this).apply { text = tr("I accept the privacy notice and terms below", "নিচের গোপনীয়তা ও শর্তগুলি গ্রহণ করছি", "मैं गोपनीयता सूचना और शर्तें स्वीकार करता/करती हूँ", "Я принимаю условия и уведомление о конфиденциальности") }
        layout.addView(accept)
        layout.addView(button(tr("Privacy & terms", "গোপনীয়তা ও শর্ত", "गोपनीयता और शर्तें", "Конфиденциальность и условия")) { legal() })
        fun validSetup(): Boolean {
            val clean = name.text.toString().trim()
            if (!accept.isChecked || clean.isEmpty() || clean.any { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }) {
                toast(tr("Enter a name and accept the terms", "নাম দিন ও শর্ত গ্রহণ করুন")); return false
            }
            return true
        }
        layout.addView(button(tr("Continue with Google", "Google দিয়ে শুরু করুন", "Google से जारी रखें", "Продолжить с Google")) {
            if (validSetup()) {
                googleSetupName = name.text.toString().trim()
                if (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser != null) finishGoogleSetup() else googleAccount()
            }
        }.apply { icon = MaterialYou.icon(this@MainActivity,"account",currentTextColor); tag = "setup-google" })
        layout.addView(label(tr("Optional sign-in. Google receives sign-in information. Chats, calls and files stay on your devices. Google contact backup and identity recovery are not enabled in this version.", "Sign-in ঐচ্ছিক। Google sign-in তথ্য পায়। Chat, call ও file আপনার device-এ থাকে। এই version-এ Google contact backup বা identity recovery চালু নেই।", "साइन-इन वैकल्पिक है। Google को साइन-इन जानकारी मिलती है। चैट, कॉल और फ़ाइलें आपके डिवाइस पर रहती हैं। इस संस्करण में Google संपर्क बैकअप और पहचान रिकवरी चालू नहीं हैं।", "Вход необязателен. Google получает сведения о входе. Чаты, звонки и файлы остаются на устройствах. Резервное копирование контактов и восстановление личности через Google в этой версии не включены."),12f))
        layout.addView(button(tr("Continue without login", "লগইন ছাড়া শুরু করুন", "बिना लॉगिन जारी रखें", "Продолжить без входа")) {
            if (!validSetup()) return@button
            googleSetupName = null
            owner.setup(name.text.toString(), true); owner.preferences.edit().putBoolean("lock",true).apply(); unlocked=false; authAttempted=false; lock()
        })
        scrollBody(layout)
    }
    private fun scrollBody(layout: LinearLayout) {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        while (layout.childCount > 1) { val child = layout.getChildAt(1); layout.removeViewAt(1); body.addView(child) }
        layout.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }
    private fun guide() = tr(
        "Messages and regular private files are end-to-end encrypted and kept in encrypted phone storage. Quick Share uses E2EE in transit, then saves readable copies in the receiver’s Downloads after approval. Both phones must be connected for delivery. Optional Online Ping stores contact subscriptions and uses app availability metadata. Cloudflare coordinates connections and FCM sends generic Ping notifications; they are not chat storage. Direct connections can fail on restrictive networks. No cloud chat recovery or guaranteed background wake.",
        "মেসেজ ও সাধারণ private file E2EE এবং ফোনের encrypted storage-এ থাকে। Quick Share E2EE-তে যায়, অনুমোদনের পরে প্রাপকের Downloads-এ readable copy সেভ হয়। পৌঁছানোর জন্য দুই ফোনের সংযোগ প্রয়োজন। ঐচ্ছিক Online Ping-এ contact subscription ও অ্যাপে উপস্থিতির metadata লাগে। Cloudflare সংযোগের metadata ও FCM সাধারণ Ping notification সামলায়; chat জমা রাখে না। কিছু নেটওয়ার্ক direct connection আটকাতে পারে। Cloud chat recovery বা নিশ্চিত background wake নেই।",
        "संदेश और सामान्य निजी फ़ाइलें E2EE तथा फ़ोन के encrypted storage में रहती हैं। Quick Share E2EE में भेजता है; स्वीकृति के बाद Downloads में पढ़ने योग्य प्रतियाँ सहेजता है। डिलीवरी के लिए दोनों फ़ोन जुड़े होने चाहिए। वैकल्पिक Online Ping संपर्क सदस्यता और ऐप उपलब्धता metadata इस्तेमाल करता है। Cloudflare connection metadata और FCM सामान्य Ping सूचनाएँ संभालते हैं; chat storage नहीं। कुछ नेटवर्क direct connection रोक सकते हैं। Cloud chat recovery या guaranteed background wake नहीं।",
        "Сообщения и обычные личные файлы защищены E2EE и хранятся в зашифрованной базе. Quick Share передаёт E2EE и после согласия сохраняет читаемые копии в Downloads получателя. Для доставки оба телефона должны подключиться. Опциональный Online Ping использует подписки контактов и метаданные доступности. Cloudflare координирует соединение, FCM отправляет обычные Ping-уведомления; переписка там не хранится. Некоторые сети блокируют прямое соединение. Восстановление чатов из облака и фоновое пробуждение не гарантируются.") + "\n\n" + tr(
        "Private ID lookup and requests use public identity keys, numeric routing IDs, consent/block state and request times on the coordination service. Saved names are local. Phone Contacts exports are readable, outside app encryption, and may sync through the contact account you choose. Google sign-in is optional; this version has no Google contact backup or new-phone identity recovery. Profiles are shared only with accepted, connected contacts over E2EE.",
        "Private ID খোঁজা ও request-এর জন্য coordination service-এ public identity key, numeric routing ID, consent/block এবং request-এর সময় থাকে। সেভ করা নাম নিজের ফোনে থাকে। Phone Contacts-এ সেভ করা তথ্য পড়া যায়, অ্যাপের encryption-এর বাইরে থাকে এবং আপনার বেছে নেওয়া contact account-এ sync হতে পারে। Google sign-in ঐচ্ছিক; এই version-এ Google contact backup বা নতুন ফোনে identity recovery নেই। Profile শুধু গ্রহণ করা, সংযুক্ত contact-এর সঙ্গে E2EE-তে শেয়ার হয়।",
        "निजी ID खोज और अनुरोध के लिए समन्वय सेवा में सार्वजनिक पहचान कुंजी, संख्यात्मक ID, अनुमति/ब्लॉक स्थिति और अनुरोध समय रहते हैं। सहेजे गए नाम स्थानीय हैं। फ़ोन Contacts में निर्यात ऐप एन्क्रिप्शन के बाहर पढ़ने योग्य है और चुने गए खाते से सिंक हो सकता है। Google साइन-इन वैकल्पिक है; इस संस्करण में संपर्क बैकअप या नए फ़ोन पर पहचान रिकवरी नहीं है। प्रोफ़ाइल केवल स्वीकृत, जुड़े संपर्कों से E2EE के माध्यम से साझा होती है।",
        "Поиск личного ID и запросы используют открытые ключи, числовые ID, согласие/блокировку и время запроса на службе координации. Сохранённые имена остаются локальными. Экспорт в Contacts читаемый, вне шифрования приложения, и может синхронизироваться через выбранный аккаунт. Вход через Google необязателен; резервное копирование контактов и восстановление личности на новом телефоне не включены. Профиль передаётся только принятым подключённым контактам через E2EE.")
    private fun legal() {
        MaterialAlertDialogBuilder(this).setTitle(tr("Privacy & terms", "গোপনীয়তা ও শর্ত")).setMessage(guide()+"\n\n"+tr("Nearby sharing is optional. Wi-Fi/hotspot transfers preserve original files and stay Signal-encrypted. Bluetooth shares one photo at a time and uses8kHz PCMU for speech calls; it is not lossless/HD speech. Discovery exposes app presence/public identity locally; app name sharing is off by default. Bluetooth system device names may still be visible. Older Android needs Location permission for nearby Wi-Fi/Bluetooth setup, but Cute Chat never reads GPS coordinates. Original photos/files may contain embedded metadata. Quick Share receiving is optional and saves readable files outside the app, automatically to Downloads/Cute Chat on Android10+ or a chosen folder. Android approval is required for automatic hotspot/Wi-Fi setup; it can interrupt Internet. Offline push/cold-app wake is not guaranteed.","Nearby sharing ঐচ্ছিক। Wi-Fi/hotspot-এ মূল ফাইল Signal-encrypted অবস্থায় যায়। Bluetooth-এ একটি করে ছবি ও8kHz PCMU speech call চলে; speech lossless/HD নয়। Discovery-তে অ্যাপের উপস্থিতি/public ID দেখা যায়; অ্যাপের নাম ডিফল্টে লুকানো। Bluetooth-এর system device name দৃশ্যমান হতে পারে। পুরনো Android-এ Wi-Fi/Bluetooth setup-এ Location permission লাগে, কিন্তু Cute Chat GPS coordinate পড়ে না। মূল ফাইলে embedded metadata থাকতে পারে। Quick Share receiving ঐচ্ছিক; readable ফাইল অ্যাপের বাইরে Android10+-এ Downloads/Cute Chat বা বাছা folder-এ সেভ হয়। Hotspot/Wi-Fi setup-এ Android অনুমতি লাগে; Internet ব্যাহত হতে পারে। Offline push/cold-app wake নিশ্চিত নয়.")+"\n\n"+tr(
            "The recipient can copy or share decrypted content. A compromised phone can expose messages. Lost keys, uninstalling or damaged devices may permanently lose chat history. Service availability and absolute security cannot be guaranteed. Liability exclusions apply only where permitted by law and do not remove mandatory consumer rights. Camera/microphone are used for calls you start or answer, or photos, videos and voice messages you explicitly record; background chat does not record audio. Photos/files use Android pickers. Saved gallery or external-viewer copies are outside encrypted app storage. Screenshot blocking/detection depends on Android and cannot prevent every capture method. Screen sharing starts only after Android's capture consent and may reveal everything visible, including notifications, until stopped. This native build remains under testing.",
            "অপর পক্ষ decrypted তথ্য কপি বা শেয়ার করতে পারে। হ্যাক হওয়া ফোনে তথ্য প্রকাশ হতে পারে। Key হারানো, uninstall বা ফোন নষ্ট হলে chat স্থায়ীভাবে হারাতে পারে। পরিষেবা বা শতভাগ নিরাপত্তা নিশ্চিত নয়। দায় সীমিত করার শর্ত প্রযোজ্য আইন ও বাধ্যতামূলক অধিকার মেনে চলে। আপনার শুরু/গ্রহণ করা call অথবা নিজে ছবি, ভিডিও ও voice message রেকর্ড করলেই camera/microphone লাগে; background chat অডিও রেকর্ড করে না। ছবি/ফাইল Android picker দিয়ে নেয়া হয়। Gallery বা অন্য viewer-এ রাখা কপি অ্যাপের encrypted storage-এর বাইরে থাকে। Screenshot বন্ধ/শনাক্ত করা Android-এর উপর নির্ভর করে এবং সব পদ্ধতি আটকানো সম্ভব নয়। Android capture consent-এর পরেই screen sharing শুরু হয় এবং বন্ধ না করা পর্যন্ত notification-সহ দৃশ্যমান সবকিছু প্রকাশ পেতে পারে। Native build এখনও পরীক্ষাধীন।" ) + "\n\n" + (if(BuildConfig.REMOTE_CONTROL) tr("Calls can be explicitly answered in a restricted call-only screen without unlocking private app screens. Chat/profile/media entry still requires Android authentication. Remote assistance is optional: Android Accessibility is manually enabled, selected apps and fresh device authentication are required, and control uses encrypted direct peer messages during a live screen share. Window/node metadata is inspected locally for safety only while granted. No keyboard/password, system/security or unattended control. Custom apps and Android restrictions can limit support; no universal sensitive-screen or uninterrupted-connection guarantee.","Private app screen না খুলেও সীমিত call-only screen থেকে কল গ্রহণ করতে পারেন; chat/profile/media খুলতে Android authentication লাগে। Remote assistance ঐচ্ছিক: Android Accessibility নিজে চালু করতে হয়, বেছে দেওয়া অ্যাপ ও নতুন device authentication লাগে, এবং live screen share-এ encrypted direct peer message দিয়ে control চলে। অনুমতি থাকা অবস্থাতেই নিরাপত্তার জন্য window/node metadata স্থানীয়ভাবে পরীক্ষা হয়। Keyboard/password, system/security বা unattended control নয়। Custom app ও Android-এর সীমায় support কমতে পারে; সব sensitive screen শনাক্ত বা নিরবচ্ছিন্ন connection নিশ্চিত নয়।") else tr("Calls can be answered without unlocking private app screens. Chat/profile/media entry requires Android authentication. Normal supports screen sharing but has no remote device control or Accessibility service.","Private app screen না খুলেও কল গ্রহণ করা যায়। Chat/profile/media খুলতে Android authentication লাগে। Normal-এ screen sharing আছে, remote device control বা Accessibility service নেই।"))).setPositiveButton("OK", null).secureShow()
    }
    private fun home() {
        val key = "${owner.ready}:${owner.contacts.map { it.uid + it.name }}:${owner.incomingRequests.map { it.uid }}:${calls.id}"
        if (page == "home" && root != null && homeKey == key) {
            homeStatus?.text = owner.status
            homePeerStatuses.forEach { (uid, view) -> val text=homeContactStatus(uid); if(view.text.toString() != text) { view.text=text; view.setTextColor(statusColour(uid)) } }
            return
        }
        homeKey = key; page = "home"; peer = null; owner.foregroundPeer(null)
        val layout = base("Cute Chat")
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        actions.addView(button(tr("My QR · Scan a friend", "আমার QR · বন্ধুকে স্ক্যান", "मेरा QR · दोस्त को स्कैन करें", "Мой QR · Сканировать друга")) { contacts() }.apply {
            icon = MaterialYou.icon(this@MainActivity,"qr",currentTextColor); iconTint = MaterialYou.tint(currentTextColor)
        }, LinearLayout.LayoutParams(0,dp(56),1f))
        actions.addView(iconButton("ping", tr("Ping selected friends", "নির্বাচিত বন্ধুদের Ping")) {
            val friends = owner.contacts.take(20); val checked = BooleanArray(friends.size)
            if (friends.isEmpty()) { contacts(); return@iconButton }
            MaterialAlertDialogBuilder(this).setMultiChoiceItems(friends.map { it.name }.toTypedArray(),checked) { _,i,value -> checked[i] = value }
                .setPositiveButton("Ping") { _,_ -> friends.forEachIndexed { i, contact -> if (checked[i]) owner.ping(contact.uid) } }.setNegativeButton(tr("Cancel","বাতিল"),null).secureShow()
        }, LinearLayout.LayoutParams(dp(56),dp(56)))
        layout.addView(actions)
        val addPeople = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        addPeople.addView(label(tr("Your people", "আপনার আপনজন", "आपके लोग", "Ваши близкие"),18f), LinearLayout.LayoutParams(0,-2,1f))
        addPeople.addView(iconButton("plus", tr("Add a private contact", "নতুন private contact যোগ করুন", "निजी संपर्क जोड़ें", "Добавить личный контакт"), true) { phoneContacts() }.apply { cornerRadius=dp(20); iconSize=dp(28); elevation=dp(3).toFloat(); backgroundTintList=MaterialYou.tint(purple); iconTint=MaterialYou.tint(Color.WHITE) },LinearLayout.LayoutParams(dp(56),dp(56)))
        layout.addView(addPeople)
        if (owner.incomingRequests.isNotEmpty()) layout.addView(button(tr("Contact requests (${owner.incomingRequests.size})", "Contact request (${owner.incomingRequests.size})")) { incomingContactRequests() })
        if (calls.active) layout.addView(button(tr("Return to call", "কলে ফিরুন")) { callScreen() })
        val search = field().apply { hint = tr("Search people", "বন্ধু খুঁজুন", "लोग खोजें", "Поиск людей"); setSingleLine(); setText(homeSearch) }
        layout.addView(search, LinearLayout.LayoutParams(-1,dp(54)).apply { setMargins(0,dp(12),0,dp(8)) })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(HomeChatScrollView(this,homeHeader).apply { addView(list) },LinearLayout.LayoutParams(-1,0,1f))
        fun showPeople() {
            list.removeAllViews(); homePeerStatuses.clear()
            if (!owner.ready) list.addView(label(tr("Preparing encrypted storage…","নিরাপদ storage তৈরি হচ্ছে…")))
            else if (owner.contacts.isEmpty()) {
                val empty = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; setPadding(dp(16),dp(28),dp(16),dp(28)) }
                empty.addView(logo(130)); empty.addView(label(tr("Start something lovely", "একটি সুন্দর কথা শুরু হোক", "एक सुंदर शुरुआत करें", "Начните что-то прекрасное"),21f))
                empty.addView(label(tr("Scan each other's signed QR codes to connect privately.", "দুজনের signed QR scan করে ব্যক্তিগতভাবে যুক্ত হন।"),14f).apply { gravity=Gravity.CENTER; setTextColor(subtle) }); list.addView(empty)
            }
            owner.contacts.filter { it.name.contains(homeSearch,ignoreCase=true) }.forEach { friend ->
                val row = LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(14),dp(12),dp(14),dp(12)); minimumHeight=dp(86) }
                row.addView(avatar(friend.name).apply { isClickable=true; isFocusable=true; contentDescription=tr("View profile: ","Profile দেখুন: ") + friend.name; setOnClickListener { showPrivateProfile(friend.uid) } }, LinearLayout.LayoutParams(dp(52),dp(52)))
                val text = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(14),0,dp(8),0) }
                text.addView(label(friend.name,18f).apply { setTypeface(typeface,Typeface.BOLD); setPadding(0,0,0,dp(7)) })
                text.addView(label(homeContactStatus(friend.uid),12f).apply { tag = "contact-connection-${friend.uid}"; setPadding(0,0,0,0); setTextColor(statusColour(friend.uid)); homePeerStatuses[friend.uid] = this })
                row.addView(text,LinearLayout.LayoutParams(0,-2,1f))
                row.addView(iconButton("call", tr("Call ", "অডিও কল: ") + friend.name) { startContactCall(friend.uid, false) }, LinearLayout.LayoutParams(dp(48), dp(48)))
                row.addView(iconButton("video", tr("Video call ", "ভিডিও কল: ") + friend.name) { startContactCall(friend.uid, true) }, LinearLayout.LayoutParams(dp(48), dp(48)))
                val tile=card(row).apply { isClickable=true; isFocusable=true; contentDescription=friend.name; setOnClickListener { openChat(friend.uid) } }; list.addView(tile)
            }
        }
        search.addTextChangedListener(object:android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { homeSearch=s.toString(); showPeople() }
            override fun afterTextChanged(s:android.text.Editable?) {}
        }); showPeople()
        homeStatus = label(owner.status,11f).apply { setTextColor(subtle); maxLines=2 }; layout.addView(homeStatus)
        addMainTabs(layout, "home")
    }
    private fun addMainTabs(layout: LinearLayout, current: String) {
        val navigation = com.google.android.material.bottomnavigation.BottomNavigationView(this).apply {
            tag = "main-bottom-tabs"; labelVisibilityMode = com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_LABELED
            setBackgroundColor(container); itemIconTintList = MaterialYou.tint(purple);isItemHorizontalTranslationEnabled=false
            menu.add(0, 901, 0, tr("Chats", "চ্যাট", "चैट", "Чаты")).icon = MaterialYou.icon(this@MainActivity, "chat", purple)
            menu.add(0, 902, 1, tr("Calls", "কল", "कॉल", "Звонки")).icon = MaterialYou.icon(this@MainActivity, "call", purple)
            menu.add(0, 903, 2, tr("Media", "মিডিয়া", "मीडिया", "Медиа")).icon = MaterialYou.icon(this@MainActivity, "gallery", purple)
            menu.add(0,904,3,tr("Sharing","শেয়ারিং","साझा करें","Обмен")).icon=MaterialYou.icon(this@MainActivity,"share",purple)
            getOrCreateBadge(904).isVisible=!owner.preferences.getBoolean("local.seen",false)
            selectedItemId = when(current) { "calls" -> 902; "gallery" -> 903; "sharing" -> 904; else -> 901 }
            setOnItemReselectedListener { if(current=="contacts") navigateMain("home") }
            setOnItemSelectedListener { item ->
                val next=when(item.itemId) { 901->"home";902->"calls";903->"gallery";else->"sharing" }
                navigateMain(next);true
            }
        }
        layout.addView(navigation, LinearLayout.LayoutParams(-1, dp(76)).apply { topMargin = dp(6) })
    }
    private fun internetSharing()=owner.preferences.getBoolean("sharing.internet",false)
    private fun sharingIdentity(uid:String,name:String?=null)=LiveFileRules.identity(name ?: owner.contacts.find { it.uid==uid }?.name.orEmpty(),uid)
    private fun sharingPage() {
        sharingRouteRequested=false
        owner.preferences.edit().putBoolean("local.seen",true).apply()
        page="sharing";peer=null;owner.foregroundPeer(null);sharingSnapshot="";sharingProgress.clear()
        val layout=base(tr("File sharing","ফাইল শেয়ারিং","फ़ाइल साझा करें","Обмен файлами"))
        layout.addView(label(tr("Original files · live P2P · E2EE","মূল ফাইল · live P2P · E2EE","मूल फ़ाइलें · लाइव P2P · E2EE","Оригиналы · прямой поток · E2EE"),13f).apply { setTextColor(subtle) })
        layout.addView(MaterialSwitch(this).apply {
            text=tr("Internet file sharing","ইন্টারনেট ফাইল শেয়ারিং","इंटरनेट फ़ाइल साझा करें","Обмен через интернет");isChecked=internetSharing()
            setOnCheckedChangeListener { _,enabled ->
                if(quickFiles.isNotEmpty() || owner.quickBatchBusy || owner.largeTransfers.any { it.state !in setOf("done","failed") }) { toast(tr("Finish or cancel the selection/transfer before changing mode","Mode বদলানোর আগে নির্বাচন বা transfer শেষ/বাতিল করুন"));setOnCheckedChangeListener(null);isChecked=internetSharing();post { if(page=="sharing") sharingPage() } }
                else { owner.preferences.edit().putBoolean("sharing.internet",enabled).apply();sharingPage() }
            }
        })
        val content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        layout.addView(ScrollView(this).apply { addView(content) },LinearLayout.LayoutParams(-1,0,1f))
        sharingSteps=SharingStepsView(this,listOf(tr("Choose","বাছুন","चुनें","Выбор"),tr("Connect","সংযোগ","जोड़ें","Связь"),tr("Transfer","পাঠান","भेजें","Передача"),tr("Saved","সেভ","सहेजा","Готово")),purple,ink)
        content.addView(sharingSteps,LinearLayout.LayoutParams(-1,dp(88)))
        val actions=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        actions.addView(SlideSharingAction(this,if(internetSharing()) tr("Send","পাঠান","भेजें","Отправить") else tr("Send · Wi-Fi","পাঠান · Wi-Fi","भेजें · Wi-Fi","Отправить · Wi-Fi"),tr("Slide up or tap · choose files","উপরে slide বা tap · ফাইল বাছুন","ऊपर खिसकाएँ या टैप · फ़ाइल चुनें","Сдвиньте или нажмите · файлы"),-1,purple,container,ink) { quickFilePicker() }.apply { tag="quick-send" },LinearLayout.LayoutParams(-1,dp(140)))
        actions.addView(SlideSharingAction(this,if(owner.quickShare.enabled) tr("Stop receiving","গ্রহণ বন্ধ","प्राप्त करना बंद करें","Остановить приём") else if(internetSharing()) tr("Receive","গ্রহণ","प्राप्त करें","Получить") else tr("Receive · Hotspot","গ্রহণ · Hotspot","प्राप्त · Hotspot","Приём · Hotspot"),tr("Slide down · accept a sender","নিচে slide করুন · sender গ্রহণ করুন","नीचे खिसकाएँ · भेजने वाले को स्वीकारें","Сдвиньте вниз · примите отправителя"),1,purple,container,ink) { quickReceive() }.apply { tag="quick-receive" },LinearLayout.LayoutParams(-1,dp(140)).apply { topMargin=dp(12) })
        content.addView(actions)
        if(!internetSharing()) content.addView(card(label(tr("1  Receiver: tap Receive and accept the sender.\n2  Sender: choose files, then the receiver’s Cute Chat name.\n3  Automatic setup: receiver creates a hotspot, sender joins its Wi-Fi. If both phones already share Wi-Fi/hotspot, reuse it. If Android cannot set up automatically, turn on the receiver’s hotspot and connect the sender in Wi-Fi settings, then return here. Approve Android prompts; verified originals save to Downloads/Cute Chat.","১  প্রাপক: Receive ট্যাপ করে sender গ্রহণ করুন।\n২  প্রেরক: ফাইল বেছে প্রাপকের Cute Chat নাম বাছুন।\n৩  অটো setup: প্রাপক hotspot তৈরি করবেন, প্রেরক Wi-Fi-তে যোগ দেবেন। আগে থেকেই একই Wi-Fi/hotspot-এ থাকলে সেটিই চলবে। Android অটো setup না দিলে প্রাপকের hotspot চালু করে প্রেরকের Wi-Fi settings থেকে যুক্ত করুন, তারপর এখানে ফিরুন। Android prompt অনুমোদনের পরে যাচাই করা মূল ফাইল Downloads/Cute Chat-এ যাবে।"),13f).apply { setPadding(dp(16),dp(14),dp(16),dp(14)) }))
        content.addView(label(if(internetSharing()) tr("Both phones need a live connection. Unlimited 5G or fast Wi-Fi is recommended for large files. Upload and download run together; speed adapts to the real connection without blocking chats or calls.","দুই ফোনের live connection দরকার। বড় ফাইলে unlimited 5G বা দ্রুত Wi-Fi recommended। Upload ও download একসঙ্গে হবে; আসল connection অনুযায়ী গতি বদলাবে, chat/call আটকে দেবে না।") else tr("No Internet needed on the same Wi-Fi/hotspot. 5 GHz Wi-Fi is recommended when both phones support it; Android chooses an available band. Bluetooth discovers Cute Chat users and requests a Wi-Fi handoff with Android permission. Your app name and ID suffix are visible to nearby app users while local sharing is enabled.","একই Wi-Fi/hotspot-এ Internet লাগে না। দুই ফোন support করলে 5 GHz Wi-Fi recommended; Android উপযুক্ত band বাছবে। Bluetooth Cute Chat user খুঁজে Android অনুমতি নিয়ে Wi-Fi handoff করবে। Local sharing চালু থাকলে আপনার app নাম ও ID-এর শেষ চার অঙ্ক কাছের app user দেখবেন।"),13f).apply { setTextColor(subtle) })
        content.addView(label(tr("Received files save automatically in Downloads/Cute Chat.","পাওয়া ফাইল Downloads/Cute Chat-এ অটোমেটিক সেভ হবে।"),12f).apply { setTextColor(subtle) })
        if(quickFiles.isNotEmpty()) {
            content.addView(label(quickFiles.size.toString()+tr(" files selected · choose a nearby phone","টি ফাইল বাছা · কাছের ফোন বাছুন"),16f))
            content.addView(button(tr("Cancel","বাতিল")) { quickFiles=emptyList();quickTarget=null;quickEndpoint=null;quickConnecting=false;quickBatchConfirmed=false;owner.quickHandoffStop();sharingPage() })
        }
        sharingStatus=label("",12f).apply { setTextColor(subtle) };content.addView(sharingStatus)
        sharingList=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;tag="nearby-sharing-devices" };content.addView(sharingList)
        addMainTabs(layout,"sharing");updateSharing()
    }
    private fun quickReceive() {
        if(!visible || !unlocked) return
        if(owner.quickShare.enabled) { owner.quickShare.disable();owner.quickHandoffStop();sharingPage();return }
        MaterialAlertDialogBuilder(this).setTitle(tr("Receive files","ফাইল গ্রহণ"))
            .setMessage(tr("Only local sharing needs nearby permissions. Your Cute Chat name and ID suffix identify you to nearby app users. Accept a sender to receive their files into Downloads/Cute Chat as readable copies outside the app. Other apps/backups may access saved files. Android may ask to enable Bluetooth or approve Wi-Fi/hotspot setup.","শুধু local sharing-এ কাছের ডিভাইসের permission লাগে। Cute Chat নাম ও ID-এর শেষ চার অঙ্ক দিয়ে app user আপনাকে চিনবেন। Sender-কে Accept করলে ফাইল Downloads/Cute Chat-এ অ্যাপের বাইরের readable copy হিসেবে সেভ হবে। অন্য app/backup সেভ করা ফাইল পড়তে পারে। Android Bluetooth চালু বা Wi-Fi/hotspot-এর অনুমতি চাইতে পারে।"))
            .setPositiveButton(tr("Continue","এগিয়ে যান")) { _,_->acceptedPrivateAction {
                if(internetSharing()) enableQuickReceive() else localPermissions(true,true,receiving=true) { enableQuickReceive() }
            } }.setNegativeButton(tr("Cancel","বাতিল"),null).secureShow()
    }
    private fun enableQuickReceive() {
        if(Build.VERSION.SDK_INT>=29) owner.quickShare.enableDownloads() else if(!owner.quickShare.enableSavedDestination()) { chooseQuickFolder();return }
        sharingPage()
        if(!internetSharing() && owner.local.bluetooth) {
            try { startActivityForResult(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,120),53) }
            catch(_:Exception) { toast(tr("Allow Bluetooth visibility in Android to receive from a new nearby phone; same-Wi-Fi sharing remains available.","নতুন কাছের ফোন থেকে গ্রহণ করতে Android-এ Bluetooth visibility অনুমোদন করুন; একই Wi-Fi-তে sharing চলবে।")) }
        }
    }
    private fun chooseQuickFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),54)
    }
    private fun quickFilePicker() {
        if(!visible || !unlocked || calls.active || owner.quickBatchBusy || batchSending || readingSelection || attachmentDraft!=null || pickedQueue.isNotEmpty()) return
        quickTarget=null;quickEndpoint=null;quickConnecting=false;quickBatchConfirmed=false;quickFiles=emptyList()
        sharingRouteRequested=true
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true),55)
    }
    private fun chooseQuickTarget(target:String) {
        if(!visible || !unlocked || calls.active || quickFiles.isEmpty()) return
        if(quickConnecting && quickTarget!=target) { toast(tr("Cancel the current connection first","আগের সংযোগ আগে বাতিল করুন"));return }
        MaterialAlertDialogBuilder(this).setTitle(tr("Send selected files?","বেছে নেওয়া ফাইল পাঠাবেন?"))
            .setMessage(sharingIdentity(target)+" · "+quickFiles.size+tr(" files. Original bytes are encrypted in transit, then saved outside the recipient's app. Bluetooth securely delivers the temporary hotspot password automatically; approve Android's connection prompt. Keep receiving enabled on the other phone.","টি ফাইল। মূল bytes encrypted ভাবে যাবে, তারপর প্রাপকের অ্যাপের বাইরে সেভ হবে। দরকার হলে Bluetooth Wi-Fi সংযোগ তৈরি করবে; Android prompt অনুমোদন করুন। অন্য ফোনে গ্রহণ চালু রাখুন।"))
            .setPositiveButton(tr("Send","পাঠান")) { _,_->acceptedPrivateAction { quickTarget=target;quickConnecting=false;quickBatchConfirmed=true;advanceQuickShare() } }
            .setNegativeButton(tr("Cancel","বাতিল"),null).secureShow()
    }
    private fun advanceQuickShare() {
        if(!visible || !unlocked || calls.active || quickFiles.isEmpty()) return
        if(quickTarget==null && quickEndpoint!=null && !quickBatchConfirmed) {
            val target=owner.localEndpoints[quickEndpoint]
            if(target!=null && target in owner.connected) { quickEndpoint=null;chooseQuickTarget(target) }
        }
        val target=quickTarget ?: return
        if(!quickBatchConfirmed) return
        if(owner.canQuickSend(target,internetSharing())) {
            val files=quickFiles;quickFiles=emptyList();quickTarget=null;quickEndpoint=null;quickConnecting=false
            owner.sendQuickFiles(target,files);sharingSnapshot=""
        } else if(!internetSharing() && target in owner.connected && owner.transports[target]=="bluetooth" && owner.canQuickHandoff(target) && !quickConnecting) {
            quickConnecting=true;owner.quickConnect(target)
        }
    }
    private fun chooseSharingPeople() {
        val quick=quickPickerArmed;quickPickerArmed=false
        val people=owner.contacts.filter { it.uid in owner.connected && owner.transports[it.uid]=="wifi" }
        if(people.isEmpty()) { toast(tr("Connect people over Wi-Fi first","আগে Wi-Fi-তে contact connect করুন"));return }
        val checked=BooleanArray(people.size)
        fun choose(media:Boolean) {
            val targets=people.filterIndexed { i,_->checked[i] }.map { it.uid }
            if(targets.isEmpty()) { toast(tr("Choose at least one person","অন্তত একজন বেছে নিন"));return }
            if(targets.size>12) { toast(tr("Up to12 people per batch","এক batch-এ সর্বোচ্চ১২জন"));return }
            openChat(targets.first());sharingRecipients=targets;sharingPickerArmed=true;quickPickerArmed=quick;pickAttachment(targets.first(),if(media) 0 else 2)
        }
        MaterialAlertDialogBuilder(this).setTitle(tr("Choose connected Wi-Fi people","Connected Wi-Fi contact বেছে নিন"))
            .setMultiChoiceItems(people.map { it.name }.toTypedArray(),checked) { _,i,value->checked[i]=value }
            .setPositiveButton(tr("Files","ফাইল")) { _,_->choose(false) }
            .setNeutralButton(tr("Photos / videos","ছবি / ভিডিও")) { _,_->choose(true) }
            .setNegativeButton(tr("Cancel","বাতিল"),null).secureShow()
    }
    private fun sendChosen(target:String,name:String,mime:String,bytes:ByteArray,limit:Int=0,completed:(Boolean)->Unit) {
        val chosen=sharingRecipients.takeIf { target in it }?.toList().orEmpty()
        if(chosen.isEmpty()) { owner.sendAttachment(target,name,mime,bytes,limit,completed);return }
        val remaining=java.util.ArrayDeque(chosen)
        fun next() {
            if(remaining.isEmpty()) { bytes.fill(0);completed(true);return }
            if(calls.active) { bytes.fill(0);toast(tr("Remaining selection stopped for the call; already queued files stay queued","কলের জন্য বাকি নির্বাচন বন্ধ; queue-তে থাকা ফাইল থাকবে"));completed(false);return }
            val contact=remaining.removeFirst()
            if(contact !in owner.connected || owner.transports[contact]!="wifi") {
                bytes.fill(0);toast(tr("A selected person disconnected; remaining files were not sent","নির্বাচিত contact বিচ্ছিন্ন হয়েছে; বাকি ফাইল পাঠানো হয়নি"));completed(false);return
            }
            owner.sendAttachment(contact,name,mime,bytes.copyOf(),limit) { ok ->
                if(ok) next() else { bytes.fill(0);completed(false) }
            }
        }
        next()
    }
    private fun localPermissions(wifi:Boolean,value:Boolean,receiving:Boolean=false,after:(()->Unit)?=null) {
        if(!visible || !unlocked) return
        val withWifi=if(wifi) value else owner.preferences.getBoolean("local.wifi",false)
        val withBluetooth=if(after!=null) getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter!=null else if(!wifi) value else owner.preferences.getBoolean("local.bluetooth",false)
        val needed=mutableListOf<String>()
        if(withWifi && Build.VERSION.SDK_INT<33 && after!=null) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if(withWifi && Build.VERSION.SDK_INT>=33) needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if(withWifi && Build.VERSION.SDK_INT>=37) needed.add("android.permission.ACCESS_LOCAL_NETWORK")
        if(withBluetooth) {
            if(Build.VERSION.SDK_INT>=31) {
                needed.addAll(listOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN))
                if(NearbySetupRules.receivingPermissions(Build.VERSION.SDK_INT,withBluetooth,receiving)) needed.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            else needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val action=action@{ if(visible && unlocked && !calls.active) {
            val adapter=getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
            if(withBluetooth && adapter==null) { toast(tr("This device has no Bluetooth adapter","এই ডিভাইসে Bluetooth নেই"));sharingPage();return@action }
            nearbySetup.begin(withWifi,withBluetooth,receiving) {
                owner.localSettings(withWifi,withBluetooth)
                owner.work { handler.post { if(visible && unlocked && !calls.active) after?.invoke() } }
            }
        } }
        val missing=needed.distinct().filter { checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED }.toMutableList()
        if(Build.VERSION.SDK_INT in 31..32 && Manifest.permission.ACCESS_FINE_LOCATION in missing) missing.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if(missing.isNotEmpty()) sharingRouteRequested=true
        if(missing.isEmpty()) action() else {
            localPermissionAction=action
            MaterialAlertDialogBuilder(this).setTitle(tr("Nearby devices permission","কাছাকাছি ডিভাইসের অনুমতি"))
                .setMessage(tr("Only needed for local sharing. Cute Chat will show your app name and ID suffix to nearby app users. Older Android requires Location permission for nearby Wi-Fi/Bluetooth setup; Cute Chat does not collect or upload your location. Denying it leaves Internet chat available.","শুধু local sharing-এর জন্য লাগে। Cute Chat app নাম ও ID-এর শেষ চার অঙ্ক কাছের app user-কে দেখাবে। পুরনো Android Wi-Fi/Bluetooth setup-এ Location permission চায়; Cute Chat location সংগ্রহ বা upload করে না। না দিলে Internet chat চলবে।"))
                .setPositiveButton(tr("Continue","এগিয়ে যান")) { _,_ -> requestPermissions(missing.toTypedArray(),17) }
                .setNegativeButton(tr("Cancel","বাতিল")) { _,_->localPermissionAction=null;sharingPage() }.secureShow()
        }
    }
    private fun localManualDialog() {
        if(!owner.local.wifi) { toast(tr("Enable Wi-Fi sharing first","আগে Wi-Fi sharing চালু করুন"));return }
        val input=field().apply { hint="192.168.43.1:54321";inputType=android.text.InputType.TYPE_CLASS_TEXT;filters=arrayOf(android.text.InputFilter.LengthFilter(64)) }
        val view=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(20),0,dp(20),0) }
        view.addView(label(tr("Enter the address shown on the OTHER phone. Both must share the same Wi-Fi or hotspot.","অন্য ফোনে দেখানো ঠিকানা দিন। দুজন একই Wi-Fi বা hotspot-এ থাকবেন।"),13f));view.addView(input)
        MaterialAlertDialogBuilder(this).setTitle(tr("Connect by Wi-Fi address","Wi-Fi ঠিকানা দিয়ে connect")).setView(view)
            .setPositiveButton(tr("Connect","Connect")) { _,_->val value=input.text.toString().trim().split(':');if(value.size==2 && value[1].toIntOrNull()!=null) owner.localManual(value[0],value[1].toInt()) else toast(tr("Use IP:port format","IP:port format দিন")) }
            .setNegativeButton(tr("Cancel","বাতিল"),null).secureShow()
    }
    private fun updateSharing() {
        if(page!="sharing") return
        advanceQuickShare()
        val model=owner.quickShare.enabled.toString()+quickFiles.toString()+quickTarget+owner.wifiHandoff.offer.toString()+owner.wifiHandoff.status+owner.localRequests.toString()+owner.local.devices.toString()+owner.connected.toString()+owner.receivingPeers.toString()+owner.quickBatchBusy+owner.transports.toString()+owner.largeTransfers.map { listOf(it.id,it.peer,it.name,it.size,it.state,it.outgoing,it.quick,it.paused,it.locallyPaused,it.medium) }.toString()
        sharingStatus?.text=owner.wifiHandoff.status.ifBlank { if(owner.quickShare.enabled) tr("Ready to receive · waiting for a sender","গ্রহণের জন্য প্রস্তুত · sender-এর অপেক্ষা") else if(quickFiles.isNotEmpty()) tr("Finding nearby phones…","কাছের ফোন খোঁজা হচ্ছে…") else if(owner.quickBatchBusy) tr("Preparing/transferring selected files · background sharing active","নির্বাচিত file প্রস্তুত/transfer চলছে · background sharing চালু") else tr("Choose Send or Receive","Send অথবা Receive বাছুন") }
        sharingSteps?.setStage(when { owner.quickBatchBusy || owner.largeTransfers.any { it.state in setOf("sending","receiving","confirming") } -> 2;quickFiles.isNotEmpty() || owner.wifiHandoff.setupPending || owner.wifiHandoff.offer!=null || owner.localRequests.isNotEmpty() || owner.largeTransfers.any { it.state=="offered" } -> 1;owner.largeTransfers.any { it.state=="done" } -> 3;owner.quickShare.enabled -> 1;else -> 0 })
        refreshSharingProgress()
        if(model==sharingSnapshot) return
        sharingSnapshot=model;val list=sharingList ?: return;list.removeAllViews();sharingProgress.clear()
        if(owner.quickBatchBusy) list.addView(button(tr("Cancel selected batch","নির্বাচিত batch বাতিল")) { acceptedPrivateAction { owner.cancelQuickBatch() } })
        owner.wifiHandoff.offer?.let { request ->
            val person=sharingIdentity(request.peer)
            val row=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(12),dp(16),dp(12)) }
            row.addView(label(person+tr(" wants to send files"," ফাইল পাঠাতে চান"),16f))
            row.addView(label(tr("Create a temporary local hotspot? Android may disconnect the current Wi-Fi. Files save automatically after verification; your phone stays in control.","অস্থায়ী local hotspot তৈরি করবেন? Android বর্তমান Wi-Fi বিচ্ছিন্ন করতে পারে। যাচাই শেষে ফাইল অটোমেটিক সেভ হবে; ফোন আপনার নিয়ন্ত্রণেই থাকবে।"),13f))
            row.addView(button(tr("Allow & receive","অনুমতি দিয়ে নিন")) { owner.quickHandoffAccept(request.id,true) })
            row.addView(button(tr("Decline","না")) { owner.quickHandoffAccept(request.id,false) });list.addView(card(row))
        }
        for(request in owner.localRequests) {
            val row=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(12),dp(16),dp(12)) }
            row.addView(label(sharingIdentity(request.peer,request.name)+" · "+request.medium,16f));row.addView(label(tr("New contact · compare this pairing code on both phones: ","নতুন contact · দুটো ফোনে এই pairing code মিলিয়ে নিন: ")+request.safety,12f))
            val buttons=LinearLayout(this);buttons.addView(button(tr("Accept","Accept")) { owner.localAccept(request.id,true);if(quickFiles.isNotEmpty()) { quickEndpoint=request.endpoint;quickTarget=null;quickConnecting=false } },LinearLayout.LayoutParams(0,dp(48),1f));buttons.addView(button(tr("Decline","না")) { owner.localAccept(request.id,false) },LinearLayout.LayoutParams(0,dp(48),1f));row.addView(buttons);list.addView(card(row))
        }
        for(transfer in owner.largeTransfers) {
            val row=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(12),dp(16),dp(12)) }
            val name=sharingIdentity(transfer.peer)
            row.addView(label(name+" · "+transfer.name,16f))
            val stateLabel=label("",13f);val statistics=label("",12f)
            val progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=1000;isIndeterminate=transfer.state=="offered" }
            row.addView(stateLabel);row.addView(statistics);row.addView(progress)
            sharingProgress[transfer.id]=Triple(stateLabel,statistics,progress)
            paintSharingProgress(transfer,Triple(stateLabel,statistics,progress),false)
            if(transfer.state=="offered" && !transfer.outgoing) row.addView(button(tr("Accept","গ্রহণ করুন")) {
                if(transfer.quick && !owner.quickShare.enabled) { quickReceive();return@button }
                MaterialAlertDialogBuilder(this).setTitle(transfer.name).setMessage(tr("Accept this sender’s files? Quick Share originals save to Downloads/Cute Chat. Subsequent files from this same verified session can receive automatically while receiving stays on (two-minute idle approval). Regular chat files remain private. Keep the connection active. Approved transfers continue under a visible background notification. Pauses expire after ten minutes.","এই sender-এর ফাইল গ্রহণ করবেন? Quick Share ফাইল Downloads/Cute Chat-এ সেভ হবে। গ্রহণ চালু থাকলে একই verified session-এর পরের ফাইল অটোমেটিক আসবে (দুই মিনিট idle approval)। সাধারণ chat file private থাকবে। সংযোগ চালু রাখুন। অনুমোদিত transfer দৃশ্যমান background notification সহ চলবে। দশ মিনিট pause থাকলে বাতিল হবে।"))
                    .setPositiveButton(tr("Receive","নিন")) { _,_->acceptedPrivateAction { owner.acceptLargeFile(transfer.id,true) } }.setNegativeButton(tr("Cancel","বাতিল"),null).secureShow()
            })
            if(transfer.quick && transfer.state in setOf("sending","receiving")) row.addView(button(if(transfer.locallyPaused) tr("Resume","আবার চালান") else tr("Pause","বিরতি দিন")) { acceptedPrivateAction { owner.pauseLargeFile(transfer.id,!transfer.locallyPaused) } })
            if(transfer.state !in setOf("done","failed")) row.addView(button(tr("Cancel transfer","Transfer বাতিল")) { owner.cancelLargeFile(transfer.id) })
            list.addView(card(row))
        }
        val peers=owner.contacts.filter { it.uid in owner.connected && owner.transports[it.uid] in (if(internetSharing()) setOf("internet","wifi") else setOf("wifi","bluetooth")) && (quickFiles.isNotEmpty() || owner.quickShare.enabled) }
        if(peers.isNotEmpty()) list.addView(label(tr("Connected people","সংযুক্ত মানুষ"),16f))
        for(friend in peers) {
            val row=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(12),dp(16),dp(12)) }
            row.addView(label(sharingIdentity(friend.uid),16f));row.addView(label(owner.transports[friend.uid]+" · E2EE · "+if(friend.uid in owner.receivingPeers) tr("Ready to receive","গ্রহণের জন্য প্রস্তুত") else tr("Ask them to slide Receive","তাঁকে Receive slide করতে বলুন"),12f))
            if(quickFiles.isNotEmpty() && friend.uid in owner.receivingPeers) row.addView(button(tr("Send here","এখানে পাঠান")) { chooseQuickTarget(friend.uid) })
            list.addView(card(row))
        }
        if(!internetSharing() && (quickFiles.isNotEmpty() || owner.quickShare.enabled) && owner.wifiHandoff.status.contains("same Wi-Fi",true)) list.addView(button(tr("Open Wi-Fi settings","Wi-Fi settings খুলুন")) { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) })
        if(peers.isEmpty() && owner.localRequests.isEmpty()) list.addView(label(if(internetSharing()) tr("Both accepted contacts must be connected. Ask the receiver to slide Receive; then select their Cute Chat name and ID suffix.","দুই accepted contact সংযুক্ত থাকবেন। অপর ফোনে Receive slide করতে বলুন; তারপর Cute Chat নাম ও ID-এর শেষ অঙ্ক মিলিয়ে বাছুন।") else tr("Slide Send to choose files, or Receive to become visible. Cute Chat users appear after signed identity verification, not by Bluetooth phone names. New contacts must accept on both phones and compare the pairing code.","ফাইল বাছতে Send slide করুন, দৃশ্যমান হতে Receive। Signed পরিচয় যাচাইয়ের পর Cute Chat user দেখাবে, Bluetooth ফোনের নাম নয়। নতুন contact দুটো ফোনে গ্রহণ করবেন ও pairing code মিলিয়ে নেবেন।"),14f))
    }

    private fun refreshSharingProgress() {
        owner.largeTransfers.forEach { transfer -> sharingProgress[transfer.id]?.let { paintSharingProgress(transfer,it,true) } }
    }
    private fun paintSharingProgress(transfer:NativeLargeFiles.View,views:Triple<TextView,TextView,ProgressBar>,animated:Boolean) {
        val percent=if(transfer.size==0L) if(transfer.state=="done") 100 else 0 else (transfer.bytes*100/transfer.size).toInt().coerceIn(0,if(transfer.state=="done") 100 else 99)
        val state=if(transfer.paused) tr("Paused","বিরতি") else when(transfer.state) {
            "offered"->tr("Waiting for acceptance","গ্রহণের অপেক্ষা");"sending"->tr("Sending live","সরাসরি পাঠাচ্ছে");"receiving"->tr("Receiving live","সরাসরি আসছে");"confirming"->tr("Verifying completion","শেষ যাচাই চলছে");"done"->tr("Saved · verified","সেভ হয়েছে · যাচাই সম্পূর্ণ");"failed"->tr("Stopped · select again to retry","থেমেছে · retry করতে আবার বাছুন");else->tr("Preparing","প্রস্তুত হচ্ছে") }
        val eta=transfer.remaining?.let { tr("About ","প্রায় ")+if(it<60) "$it s" else "${(it+59)/60} min" }.orEmpty()
        val rate=if(transfer.speed>0) android.text.format.Formatter.formatFileSize(this,transfer.speed)+"/s" else ""
        views.first.text="$percent% · $state · ${transfer.medium} · E2EE"
        views.second.text=android.text.format.Formatter.formatFileSize(this,transfer.bytes)+" / "+android.text.format.Formatter.formatFileSize(this,transfer.size)+"  "+rate+"  "+eta
        val next=if(transfer.size>0) (transfer.bytes*1000/transfer.size).toInt().coerceIn(0,1000) else if(transfer.state=="done") 1000 else 0
        views.third.setProgress(next,animated)
    }
    private fun libraryPage(tab: String) {
        page = tab; peer = null; owner.foregroundPeer(null)
        val layout = base(if(tab == "calls") tr("Calls", "কল", "कॉल", "Звонки") else tr("Private media", "প্রাইভেট মিডিয়া", "निजी मीडिया", "Приватная медиатека"))
        layout.addView(label(tr("Encrypted storage on this phone", "এই ফোনে এনক্রিপ্টেড স্টোরেজ", "इस फ़ोन पर एन्क्रिप्टेड स्टोरेज", "Зашифрованное хранилище на телефоне"), 12f).apply { setTextColor(subtle) })
        if(calls.active) layout.addView(button(tr("Return to call", "কলে ফিরুন")) { callScreen() })
        if(tab == "gallery") {
            layout.addView(label(tr("Photo and video timeline · private on this phone", "ফটো ও ভিডিও timeline · এই ফোনেই প্রাইভেট"),12f).apply { setTextColor(subtle) })
            galleryFiles=button(tr("Files & audio · Open with…","ফাইল ও অডিও · Open with…")) { galleryOtherFiles() }
            layout.addView(galleryFiles)
            galleryEmpty=label(tr("Your shared photos and videos will appear here. Limited-view media stays in its chat.","আদানপ্রদান করা সাধারণ ফটো ও ভিডিও এখানে থাকবে। Limited-view media চ্যাটেই থাকবে।"),14f).apply { setTextColor(subtle) }
            layout.addView(galleryEmpty)
            galleryTimeline=PrivateMediaTimeline(this,owner) { media -> if(visible && unlocked) viewMedia(media.peer,media.id,media.mime) }
            layout.addView(galleryTimeline,LinearLayout.LayoutParams(-1,0,1f))
            addMainTabs(layout,tab); updateLibrary(); return
        }
        libraryList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = if(tab == "calls") "private-call-history" else "private-media-gallery" }
        layout.addView(ScrollView(this).apply { addView(libraryList) }, LinearLayout.LayoutParams(-1, 0, 1f))
        addMainTabs(layout, tab); updateLibrary()
    }
    private fun updateLibrary() {
        if(page == "gallery") {
            val media=owner.mediaLibrary
            if(gallerySnapshot !== media) {
                gallerySnapshot=media; galleryTimeline?.update(media)
                galleryEmpty?.visibility=if(media.none { GalleryTimeline.visual(it.mime) }) View.VISIBLE else View.GONE
                val count=media.count { !GalleryTimeline.visual(it.mime) }
                galleryFiles?.text=tr("Files & audio · $count · Open with…","ফাইল ও অডিও · $count · Open with…")
            }
            return
        }
        val list = libraryList ?: return
        val records = if(page == "calls") owner.callHistory.toString() else owner.mediaLibrary.toString()
        val key = records + owner.contacts.map { it.uid + it.name }.toString()
        if(key == libraryKey) return
        libraryKey = key; list.removeAllViews()
        fun time(at: Long) = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(at))
        if (page == "calls") {
            if(owner.callHistory.isEmpty()) list.addView(label(tr("Your encrypted call history will appear here after calls.", "কলের পরে এখানে এনক্রিপ্টেড কল হিস্ট্রি দেখা যাবে।"), 15f))
            owner.callHistory.forEach { call ->
                val friend = (owner.contacts + owner.blockedContacts).find { it.uid == call.peer }
                val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
                val title = (if(call.incoming) "↙ " else "↗ ") + (friend?.name ?: tr("Private contact", "প্রাইভেট কন্টাক্ট"))
                column.addView(label(title, 17f).apply { setTypeface(typeface, Typeface.BOLD) })
                val duration = if(call.connected == null) "" else call.duration?.let { "%02d:%02d".format(it / 60000, it / 1000 % 60) } ?: tr("Duration unavailable", "সময়কাল পাওয়া যায়নি")
                column.addView(label((if(call.video) tr("Video call", "ভিডিও কল") else tr("Audio call", "অডিও কল")) + " · " + callOutcome(call.outcome) + "\n" + time(call.started) + if(call.ended == null) " · " + tr("Ongoing", "চলছে") else if(duration.isNotEmpty()) " · $duration" else "", 12f).apply { setTextColor(subtle) })
                if (friend != null && owner.contacts.any { it.uid == call.peer }) {
                    val actions = LinearLayout(this)
                    actions.addView(iconButton("call", tr("Audio call", "অডিও কল")) { startContactCall(call.peer, false) }, LinearLayout.LayoutParams(dp(48),dp(48)))
                    actions.addView(iconButton("video", tr("Video call", "ভিডিও কল")) { startContactCall(call.peer, true) }, LinearLayout.LayoutParams(dp(48),dp(48)))
                    actions.addView(button(tr("Chat", "চ্যাট")) { openChat(call.peer) }, LinearLayout.LayoutParams(-2,dp(48))); column.addView(actions)
                }
                list.addView(card(column))
            }
        } else {
            if(owner.mediaLibrary.isEmpty()) list.addView(label(tr("Photos, videos, audio and files you exchange will appear here privately.", "আদানপ্রদান করা ফটো, ভিডিও, অডিও ও ফাইল এখানে ব্যক্তিগতভাবে থাকবে।"),15f))
            owner.mediaLibrary.forEach { media ->
                val column=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(12),dp(16),dp(12)) }
                column.addView(label(media.name,16f).apply { maxLines=2; setTypeface(typeface,Typeface.BOLD) })
                val contact=(owner.contacts+owner.blockedContacts).find { it.uid==media.peer }?.name ?: tr("Private contact","প্রাইভেট কন্টাক্ট")
                column.addView(label((if(media.outgoing) tr("Sent · ","পাঠানো · ") else tr("Received · ","পাওয়া · ")) + contact + "\n" + time(media.at) + " · " + android.text.format.Formatter.formatShortFileSize(this,media.size),12f).apply { setTextColor(subtle) })
                column.addView(button(tr("Open", "খুলুন")) { openLibraryMedia(media) })
                if (!media.mime.startsWith("image/") && !media.mime.startsWith("video/") && !media.mime.startsWith("audio/"))
                    column.addView(button(tr("Open in another app", "অন্য অ্যাপে খুলুন")) { openExternalFile(media.peer,media.id,media.mime,media.name) })
                list.addView(card(column))
            }
        }
    }
    private fun galleryOtherFiles() {
        val files=owner.mediaLibrary.filterNot { GalleryTimeline.visual(it.mime) }
        if(files.isEmpty()) { toast(tr("No other files yet","এখনও অন্য ফাইল নেই")); return }
        val labels=files.map { it.name+" · "+java.text.DateFormat.getDateInstance().format(java.util.Date(it.at)) }
        MaterialAlertDialogBuilder(this).setTitle(tr("Files & audio","ফাইল ও অডিও"))
            .setItems(labels.toTypedArray()) { _,index -> val file=files[index]; openExternalFile(file.peer,file.id,file.mime,file.name) }
            .setNegativeButton(tr("Close","বন্ধ করুন"),null).secureShow()
    }
    private fun callOutcome(value: String) = when(value) {
        "ended" -> tr("Completed", "সম্পূর্ণ")
        "missed" -> tr("Missed", "মিসড")
        "declined" -> tr("Declined", "প্রত্যাখ্যাত")
        "cancelled" -> tr("Cancelled", "বাতিল")
        "failed" -> tr("Failed", "ব্যর্থ")
        "unanswered" -> tr("Unanswered", "উত্তর আসেনি")
        "interrupted" -> tr("Interrupted", "বন্ধ হয়ে গিয়েছিল")
        else -> tr("Ongoing", "চলছে")
    }
    private fun openLibraryMedia(media: PrivateLibrary.Media) {
        if(media.mime.startsWith("image/") || media.mime.startsWith("video/") || media.mime.startsWith("audio/")) viewMedia(media.peer, media.id, media.mime)
        else if(media.mime == "application/pdf" || media.mime.startsWith("text/") || media.mime == "application/json") openPrivateDocument(media)
        else openExternalFile(media.peer, media.id, media.mime, media.name)
    }
    private fun statusColour(uid:String)=MaterialYou.color(themeAnchor,if(uid in owner.online) com.google.android.material.R.attr.colorTertiary else com.google.android.material.R.attr.colorOnSurfaceVariant)
    private fun homeContactStatus(uid:String)=contactStatus(uid)+if(uid in owner.online) "" else lastSeenLabel(uid)?.let { "\n$it" }.orEmpty()
    private fun lastSeenLabel(uid:String):String? {
        val at=owner.lastSeen[uid]?.coerceAtMost(System.currentTimeMillis()) ?: return null
        if(at <= 0) return null
        val locale=java.util.Locale.forLanguageTag(owner.preferences.getString("language","en")!!)
        val date=java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM,locale).format(java.util.Date(at))
        val time=java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT,locale).format(java.util.Date(at))
        return tr("Last seen · $date · $time","শেষ দেখা · $date · $time","अंतिम बार · $date · $time","Был(а) в сети · $date · $time")
    }
    private fun contactStatus(uid: String): String = (owner.transports[uid]?.takeIf { it!="internet" }?.let { it+" · " } ?: "")+when (owner.connectionStates[uid]) {
        ContactConnectionState.CONNECTED -> if(uid in owner.online) tr("● Online · E2EE", "● অনলাইন · E2EE", "● ऑनलाइन · E2EE", "● В сети · E2EE")
            else tr("● Connected · E2EE", "● সংযুক্ত · E2EE", "● जुड़ा हुआ · E2EE", "● Подключено · E2EE")
        ContactConnectionState.CONNECTING -> tr("◌ Connecting…", "◌ সংযোগের চেষ্টা হচ্ছে…", "◌ जुड़ रहा है…", "◌ Подключение…")
        ContactConnectionState.OFFLINE -> tr("○ Offline", "○ অফলাইন", "○ ऑफलाइन · चुपचाप जाँच", "○ Не в сети · тихая проверка")
        ContactConnectionState.RETRYING -> tr("◌ Connection unavailable", "◌ সংযোগ পাওয়া যায়নি")
        ContactConnectionState.WAITING -> tr("◌ Waiting for a connection slot", "◌ সংযোগের জন্য অপেক্ষা হচ্ছে")
        else -> tr("◌ Checking availability…", "◌ অনলাইন আছেন কি না যাচাই হচ্ছে…", "◌ उपलब्धता की जाँच…", "◌ Проверка доступности…")
    }
    private fun contacts() {
        qrPageReady=false
        ownQrDialog?.dismiss();ownQrDialog=null
        qrScanner?.close();qrScanner=null;qrBitmap?.recycle();qrBitmap=null;qrProfileBitmap?.recycle();qrProfileBitmap=null;qrNumberVisible=false
        page="contacts";peer=null;owner.foregroundPeer(null)
        val layout=base(tr("Scan & my ID","স্ক্যান ও আমার ID","स्कैन और मेरी ID","Сканер и мой ID")) { home() }
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        layout.addView(ScrollView(this).apply { addView(body) },LinearLayout.LayoutParams(-1,0,1f))
        if(!owner.ready || owner.code.isEmpty()) { body.addView(label(owner.status));addMainTabs(layout,"contacts");return }
        val profile=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),dp(6),dp(12),dp(6)) }
        val ownPhoto=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP;setImageResource(R.drawable.cute_chat_logo);contentDescription=tr("Change my private profile photo","আমার private profile photo বদলান");setOnClickListener { showPrivateProfile(null) };background=MaterialYou.shape(container,dp(18).toFloat());clipToOutline=true }
        profile.addView(ownPhoto,LinearLayout.LayoutParams(dp(48),dp(48)))
        owner.readProfile(null) { _,photo -> if(visible && unlocked && page=="contacts" && ownPhoto.isAttachedToWindow) { photo?.let { bytes -> profileBitmap(bytes)?.let { bitmap -> qrProfileBitmap?.recycle();qrProfileBitmap=bitmap;ownPhoto.setImageBitmap(bitmap) } } } }
        profile.addView(label(owner.preferences.getString("name","Cute Chat")!!,18f),LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(12) })
        profile.addView(iconButton("camera",tr("Change profile photo","Profile photo বদলান")) { showPrivateProfile(null) },LinearLayout.LayoutParams(dp(48),dp(48)));body.addView(card(profile))
        body.addView(label(tr("Scan a friend · Show your QR","বন্ধুকে স্ক্যান · নিজের QR দেখান","दोस्त को स्कैन करें · अपना QR दिखाएँ","Сканируйте друга · Покажите свой QR"),15f))
        val pair=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.TOP;tag="qr-camera-and-code" }
        val cameraColumn=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        cameraColumn.addView(label(tr("Scan","স্ক্যান","स्कैन","Сканер"),14f))
        qrScanner=NativeQrScanner(this,{ code -> if(page=="contacts" && visible && unlocked && usableView()) acceptedPrivateAction { acceptCode(code) } },{ if(page=="contacts" && visible) toast(tr("Camera unavailable. Retry Camera or use the system scanner in options.","ক্যামেরা খোলা যাচ্ছে না। Camera আবার চেষ্টা করুন বা options থেকে system scanner খুলুন।")) }).also {
            cameraColumn.addView(it,LinearLayout.LayoutParams(-1,dp(172)))
        }
        pair.addView(card(cameraColumn),LinearLayout.LayoutParams(0,-2,1f).apply { rightMargin=dp(6) })
        val qr=MultiFormatWriter().encode(owner.code,BarcodeFormat.QR_CODE,640,640,mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,EncodeHintType.MARGIN to 4))
        val bitmap=Bitmap.createBitmap(640,640,Bitmap.Config.ARGB_8888);qrBitmap=bitmap
        bitmap.setPixels(IntArray(640*640) { i -> if(qr[i%640,i/640]) Color.BLACK else Color.WHITE },0,640,0,0,640,640)
        val qrColumn=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        qrColumn.addView(label(tr("My QR","আমার QR","मेरा QR","Мой QR"),14f))
        qrColumn.addView(ImageView(this).apply {
            setImageBitmap(bitmap);setBackgroundColor(Color.WHITE);setPadding(dp(6),dp(6),dp(6),dp(6))
            contentDescription=tr("My contact QR. Tap to enlarge.","আমার contact QR। বড় করতে ট্যাপ করুন।","मेरा QR। बड़ा करने के लिए टैप करें।","Мой QR. Нажмите, чтобы увеличить.")
            tag="my-contact-qr";scaleType=ImageView.ScaleType.FIT_CENTER;isClickable=true;isFocusable=true;setOnClickListener { showOwnQr() }
        },LinearLayout.LayoutParams(-1,dp(172)))
        pair.addView(card(qrColumn),LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(6) });body.addView(pair)
        val tools=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        tools.addView(button(tr("Camera","ক্যামেরা","कैमरा","Камера")) { qrPermissionAsked=false;qrScanner?.close();startQrPreview() }.apply {
            icon=MaterialYou.icon(this@MainActivity,"camera",currentTextColor);iconTint=MaterialYou.tint(currentTextColor)
        },LinearLayout.LayoutParams(0,dp(52),1f))
        lateinit var scannerOptions:MaterialButton
        scannerOptions=iconButton("more",tr("Scanner options","স্ক্যানারের options","स्कैनर विकल्प","Настройки сканера")) {
            PopupMenu(this,scannerOptions).apply {
                menu.add(tr("Use system QR scanner","System QR scanner ব্যবহার করুন","सिस्टम QR स्कैनर","Системный QR-сканер"))
                setOnMenuItemClickListener { openSystemQr();true };show()
            }
        }
        tools.addView(scannerOptions,LinearLayout.LayoutParams(dp(52),dp(52)))
        body.addView(tools)
        body.addView(button(tr("Private ID hidden · tap to reveal","Private ID লুকানো · দেখতে ট্যাপ করুন","निजी ID छिपी है · देखने के लिए टैप करें","ID скрыт · нажмите, чтобы показать")) { }.apply {
            qrIdButton=this
            setOnClickListener { qrNumberVisible=!qrNumberVisible;text=if(qrNumberVisible) friendlyId()+" · "+tr("Hide","লুকান") else tr("Private ID hidden · tap to reveal","Private ID লুকানো · দেখতে ট্যাপ করুন") }
        })
        body.addView(label(tr("Tap your QR to enlarge it. Your profile is shared only after mutual acceptance.","QR বড় করতে ট্যাপ করুন। দুজন গ্রহণ করার পরেই profile শেয়ার হয়।","QR बड़ा करने के लिए टैप करें। दोनों की स्वीकृति के बाद ही प्रोफ़ाइल साझा होती है।","Нажмите QR для увеличения. Профиль доступен после взаимного принятия."),12f).apply { setTextColor(subtle) })
        body.addView(button(tr("Copy / share my contact","আমার contact কপি / শেয়ার")) { shareContactChoices() })
        body.addView(button(tr("Paste contact code","Contact code দিন")) {
            val input=field().apply { hint="CP1.…";filters=arrayOf(android.text.InputFilter.LengthFilter(4096)) }
            MaterialAlertDialogBuilder(this).setTitle(tr("Contact code","Contact code")).setView(input).setPositiveButton(tr("Add","যোগ করুন")) { _,_->acceptedPrivateAction { acceptCode(input.text.toString()) } }.setNegativeButton(tr("Cancel","বাতিল"),null).secureShow()
        })
        qrPageReady=true;addMainTabs(layout,"contacts");handler.post { startQrPreview() }
    }
    private fun openSystemQr() {
        if(!visible || !unlocked || !usableView() || calls.active) return
        qrScanner?.close()
        GmsBarcodeScanning.getClient(this,GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()).startScan()
            .addOnSuccessListener { result -> result.rawValue?.let { code -> if(visible && unlocked) acceptedPrivateAction { acceptCode(code) } } }
            .addOnFailureListener { if(visible) toast(tr("Scanner unavailable; paste the signed contact code","Scanner নেই; signed contact code ব্যবহার করুন")) }
    }
    private fun showOwnQr() {
        if(!visible || !unlocked || !usableView() || qrBitmap==null) return
        val copy=qrBitmap?.copy(Bitmap.Config.ARGB_8888,false) ?: return
        val image=ImageView(this).apply { setImageBitmap(copy);setBackgroundColor(Color.WHITE);scaleType=ImageView.ScaleType.FIT_CENTER;setPadding(dp(12),dp(12),dp(12),dp(12)) }
        val size=minOf(resources.displayMetrics.widthPixels-dp(64),resources.displayMetrics.heightPixels-dp(220)).coerceAtLeast(dp(160))
        val frame=FrameLayout(this).apply { addView(image,FrameLayout.LayoutParams(-1,size)) }
        ownQrDialog?.dismiss()
        val dialog=MaterialAlertDialogBuilder(this).setTitle(tr("My contact QR","আমার contact QR","मेरा QR","Мой QR"))
            .setView(frame).setPositiveButton(tr("Done","সম্পন্ন","हो गया","Готово"),null).create()
        dialog.setOnDismissListener { image.setImageDrawable(null);copy.recycle();privateDialogs.remove(dialog);if(ownQrDialog===dialog) ownQrDialog=null }
        ownQrDialog=dialog;dialog.secureShow()
    }
    private fun startQrPreview() {
        if(page!="contacts" || !visible || !unlocked || !usableView() || calls.active) { qrScanner?.close();return }
        if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) qrScanner?.start()
        else if(!qrPermissionAsked) { qrPermissionAsked=true;requestPermissions(arrayOf(Manifest.permission.CAMERA),18) }
    }
    private fun navigateMain(next:String,direction:Int=0) {
        if(!visible || !unlocked || !usableView() || next==page) return
        val before=TabSwipeRules.pages.indexOf(page);val after=TabSwipeRules.pages.indexOf(next)
        tabMotionDirection=if(direction!=0) direction else if(before>=0 && after>=0) if(after>before) 1 else -1 else 0
        when(next) { "home"->home();"calls","gallery"->libraryPage(next);"sharing"->sharingPage();"contacts"->contacts() }
    }
    private fun blocksTabSwipe(view:View,event:MotionEvent):Boolean {
        if(view.visibility!=View.VISIBLE) return false
        val bounds=Rect();if(!view.getGlobalVisibleRect(bounds) || !bounds.contains(event.rawX.toInt(),event.rawY.toInt())) return false
        if(view is EditText || view is SeekBar || view is HorizontalScrollView || view is android.widget.CompoundButton || view.tag=="main-bottom-tabs") return true
        if(view is ViewGroup) for(i in view.childCount-1 downTo 0) if(blocksTabSwipe(view.getChildAt(i),event)) return true
        return false
    }
    private fun resetTabGesture(animate:Boolean) {
        val target=swipeRoot;swipeRoot=null;swipingTabs=false;swipePage=""
        swipeVelocity?.recycle();swipeVelocity=null
        target?.animate()?.cancel()
        if(target!=null) {
            if(animate && animationsAllowed()) target.animate().translationX(0f).alpha(1f).setDuration(180).setInterpolator(android.view.animation.PathInterpolator(.2f,0f,0f,1f)).start()
            else { target.translationX=0f;target.alpha=1f }
        }
    }
    override fun dispatchTouchEvent(event:MotionEvent):Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {
            resetTabGesture(false)
            swipeStartX=event.x;swipeStartY=event.y
            swipePage=if(visible && unlocked && usableView() && (page in TabSwipeRules.pages || page=="chat" && event.y<dp(140)) && root?.let { !blocksTabSwipe(it,event) }==true) page else ""
            if(swipePage.isNotEmpty()) { swipeRoot=root;swipeVelocity=VelocityTracker.obtain() }
        }
        swipeVelocity?.addMovement(event)
        val dx=event.x-swipeStartX;val dy=event.y-swipeStartY
        if(event.actionMasked==MotionEvent.ACTION_MOVE && swipePage==page && swipePage.isNotEmpty() && kotlin.math.abs(dx)>maxOf(dp(24),ViewConfiguration.get(this).scaledTouchSlop*2) && kotlin.math.abs(dy)<kotlin.math.abs(dx)*.45f) {
            if(!swipingTabs) { val cancel=MotionEvent.obtain(event);cancel.action=MotionEvent.ACTION_CANCEL;super.dispatchTouchEvent(cancel);cancel.recycle();swipingTabs=true;swipeRoot?.animate()?.cancel() }
            if(animationsAllowed()) swipeRoot?.let { v -> val width=v.width.coerceAtLeast(dp(240));v.translationX=(dx*.3f).coerceIn(-width*.22f,width*.22f);v.alpha=1f-(kotlin.math.abs(dx)/width).coerceAtMost(1f)*.12f }
            return true
        }
        if(event.actionMasked==MotionEvent.ACTION_UP && swipingTabs) {
            swipeVelocity?.computeCurrentVelocity(1000);val velocity=(swipeVelocity?.xVelocity ?: 0f)/resources.displayMetrics.density
            val width=swipeRoot?.width?.toFloat() ?: 0f
            val next=if(swipePage==page && visible && unlocked && usableView()) TabSwipeRules.next(page,dx,velocity,width,maxOf(dp(56).toFloat(),minOf(dp(88).toFloat(),width*.2f)),window.decorView.layoutDirection==View.LAYOUT_DIRECTION_RTL) else null
            resetTabGesture(next==null)
            if(next!=null) navigateMain(next,if(dx<0) 1 else -1)
            return true
        }
        if(event.actionMasked==MotionEvent.ACTION_CANCEL) { val consumed=swipingTabs;resetTabGesture(true);if(consumed) return true }
        else if(event.actionMasked==MotionEvent.ACTION_UP) resetTabGesture(false)
        return dispatchCallTouch(event)
    }
    private fun exportPhoneContact(name: String, id: String) {
        MaterialAlertDialogBuilder(this).setTitle(tr("Save readable contact", "স্বাভাবিক Contact সেভ করুন"))
            .setMessage(tr("The name and Cute Chat ID will be saved normally, without app encryption, in your phone's Contacts. Choose the account in the Contacts editor; Google or another account may sync it according to your phone settings. This does not export chats, keys or media.", "নাম ও Cute Chat ID ফোনের Contacts-এ স্বাভাবিকভাবে, অ্যাপের encryption ছাড়াই সেভ হবে। Contacts editor-এ account বেছে নিন; ফোনের settings অনুযায়ী Google বা অন্য account-এ sync হতে পারে। Chat, key বা media এখানে যায় না।"))
            .setPositiveButton(tr("Open Contacts editor", "Contacts editor খুলুন")) { _,_ -> try { startActivity(PhoneContacts.insert(name,id)) } catch (_: ActivityNotFoundException) { toast(tr("No Contacts editor is installed", "Contacts editor নেই")) } }
            .setNegativeButton(tr("Cancel","বাতিল"),null).secureShow()
    }
    private fun phoneContacts() {
        page="people"; val layout=base(tr("Private contacts","প্রাইভেট কন্টাক্ট","निजी संपर्क","Личные контакты")) { home() }
        contactHubList=null
        if(checkSelfPermission(Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED) phoneContactEntries=emptyList()
        val hero=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(12),dp(18),dp(12)) }
        val title=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        title.addView(ImageView(this).apply { setImageDrawable(MaterialYou.icon(this@MainActivity,"shield",purple)); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(36),dp(36)))
        title.addView(label(tr("Your private number","আপনার প্রাইভেট নাম্বার"),14f),LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(12) })
        title.addView(iconButton("share",tr("Share ID · with or without name","নামসহ বা নাম ছাড়া ID শেয়ার করুন")) { shareContactChoices() },LinearLayout.LayoutParams(dp(48),dp(48)))
        hero.addView(title)
        hero.addView(label(FriendlyContactId.number(friendlyId()).orEmpty(),26f).apply { setTypeface(typeface,Typeface.BOLD); letterSpacing=.06f })
        hero.addView(label(tr("A Cute Chat ID, not a mobile phone number. Your address book stays on this phone.","এটি Cute Chat ID, মোবাইল ফোন নম্বর নয়। Address book এই ফোনেই থাকে।"),12f).apply { setTextColor(subtle) })
        layout.addView(card(hero))
        val input=field().apply { hint=tr("Search name or enter 10-digit private ID","নাম খুঁজুন বা ১০ সংখ্যার private ID লিখুন"); setSingleLine(); filters=arrayOf(android.text.InputFilter.LengthFilter(80)); imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
        contactHubSearch=input; layout.addView(input)
        val actions=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        actions.addView(button(tr("Find / add / chat","খুঁজুন / যোগ করুন / চ্যাট")) { findPrivateContact(input.text.toString(),"") }.apply { icon=MaterialYou.icon(this@MainActivity,"search",purple) },LinearLayout.LayoutParams(0,dp(56),1f))
        actions.addView(iconButton("qr",tr("QR connection","QR দিয়ে যুক্ত করুন")) { contacts() },LinearLayout.LayoutParams(dp(56),dp(56)))
        layout.addView(actions)
        input.setOnEditorActionListener { _,action,_ -> if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { findPrivateContact(input.text.toString(),""); true } else false }
        if(owner.incomingRequests.isNotEmpty()) layout.addView(button(tr("Contact requests","কন্টাক্ট রিকোয়েস্ট")+" · ${owner.incomingRequests.size}") { incomingContactRequests() }.apply { icon=MaterialYou.icon(this@MainActivity,"account",purple) })
        if(checkSelfPermission(Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED) layout.addView(button(tr("Find IDs saved on this phone","ফোনে সেভ করা ID খুঁজুন")) { askContactsPermission(true) }.apply { icon=MaterialYou.icon(this@MainActivity,"account",purple) })
        contactHubList=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }; layout.addView(contactHubList)
        input.addTextChangedListener(object: android.text.TextWatcher {
            override fun beforeTextChanged(v: CharSequence?,a:Int,c:Int,f:Int) {}
            override fun onTextChanged(v: CharSequence?,a:Int,b:Int,c:Int) { renderContactHub() }
            override fun afterTextChanged(v: android.text.Editable?) {}
        })
        renderContactHub(); scrollBody(layout)
        if(checkSelfPermission(Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED) showPhoneContacts()
    }
    private fun renderContactHub() {
        if(page!="people" || !visible || !unlocked) return
        val list=contactHubList ?: return; list.removeAllViews()
        val query=contactHubSearch?.text?.toString()?.trim().orEmpty()
        data class Row(val name:String,val number:String,val uid:String?)
        val rows=linkedMapOf<String,Row>()
        owner.contacts.forEach { val n=FriendlyContactId.number(friendlyId(it.name,it.uid)) ?: return@forEach; rows[n]=Row(it.name,n,it.uid) }
        phoneContactEntries.forEach { val n=FriendlyContactId.number(it.id) ?: return@forEach; if(n !in rows) rows[n]=Row(it.name,n,null) }
        val matches=rows.values.filter { query.isEmpty() || it.name.contains(query,true) || it.number.contains(query) }.sortedWith(compareBy<Row> { it.uid==null }.thenBy { it.name.lowercase(java.util.Locale.ROOT) })
        list.addView(label(tr("Private numbers","প্রাইভেট নাম্বার"),14f).apply { setTypeface(typeface,Typeface.BOLD) })
        matches.take(200).forEach { person ->
            val row=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(12),dp(10),dp(10),dp(10)); minimumHeight=dp(80) }
            row.addView(label(person.name.take(1).uppercase(),21f).apply { gravity=Gravity.CENTER; background=MaterialYou.shape(purple and 0x00ffffff or 0x18000000,dp(24).toFloat()); setTextColor(purple); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(48),dp(48)))
            row.addView(LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),0,dp(4),0); addView(label(person.name,16f).apply { maxLines=2 }); addView(label(person.number,13f).apply { setTextColor(subtle) }); addView(label(if(person.uid!=null) contactStatus(person.uid) else tr("Saved on this phone · request to connect","ফোনে সেভ আছে · যুক্ত হতে request দিন"),11f).apply { setTextColor(subtle) }) },LinearLayout.LayoutParams(0,-2,1f))
            val act={ if(person.uid!=null) openChat(person.uid) else findPrivateContact(person.number,person.name) }
            row.addView(iconButton(if(person.uid!=null) "chat" else "plus",if(person.uid!=null) tr("Chat with","চ্যাট করুন")+" ${person.name}" else tr("Add contact","কন্টাক্ট যোগ করুন")+" ${person.name}",true,act),LinearLayout.LayoutParams(dp(48),dp(48)))
            row.setOnClickListener { act() }; list.addView(card(row))
        }
        if(matches.size>200) list.addView(label(tr("Type a name or ID to narrow these contacts.","আরও কন্টাক্ট খুঁজতে নাম বা ID লিখুন।"),13f))
        if(matches.isEmpty()) list.addView(label(tr("No matching saved ID. Enter a private number above, or connect using QR.","সেভ করা ID পাওয়া যায়নি। উপরে private নাম্বার দিন অথবা QR দিয়ে যুক্ত হোন।"),14f).apply { setTextColor(subtle) })
    }
    private fun askContactsPermission(openHub: Boolean = false) {
        if(!visible || !unlocked || calls.active || contactsPermissionPending || checkSelfPermission(Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED) return
        if(!openHub && owner.preferences.getBoolean("contactsPermissionAsked",false)) return
        owner.preferences.edit().putBoolean("contactsPermissionAsked",true).apply(); contactsPermissionPending=true
        requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS),if(openHub) 15 else 16)
    }
    private fun showPhoneContacts() {
        mediaIo.execute {
            val entries=try { PhoneContacts.read(this) } catch (_: Exception) { emptyList() }
            handler.post { if(!visible || !unlocked || page!="people") return@post; phoneContactEntries=entries; renderContactHub() }
        }
    }
    private fun shareContactChoices() {
        MaterialAlertDialogBuilder(this).setTitle(tr("Share your contact","আপনার কন্টাক্ট শেয়ার করুন"))
            .setItems(arrayOf(tr("Number only · no name","শুধু নাম্বার · নাম ছাড়া"),tr("Name and signed contact code","নাম ও signed contact code"))) { _,index ->
                val value=if(index==0) FriendlyContactId.shareNumber(owner.localUid) else shareCode()
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,value),tr("Share private ID","Private ID শেয়ার করুন")))
            }.secureShow()
    }
    private fun findPrivateContact(value: String, savedName: String) {
        val number = FriendlyContactId.number(value)
        if (number == null || !owner.ready) { toast(tr("Enter a valid private app ID", "সঠিক private app ID দিন")); return }
        val existing = owner.contacts.find { FriendlyContactId.number(friendlyId(it.name,it.uid)) == number }
        if (existing != null) { openChat(existing.uid); return }
        toast(tr("Checking private ID…","Private ID যাচাই হচ্ছে…"))
        owner.findContact(number) { bundle ->
            if (!visible || !unlocked) return@findContact
            if (bundle == null) { toast(tr("ID unavailable. Check the ID, connection or a block.","ID পাওয়া যায়নি। ID, connection বা block চেক করুন।")); return@findContact }
            val name = field().apply { setText(savedName.ifEmpty { value.trim() }); filters=arrayOf(android.text.InputFilter.LengthFilter(80)) }
            MaterialAlertDialogBuilder(this).setTitle(tr("Send a contact request?","Contact request পাঠাবেন?"))
                .setMessage(tr("Chat, calls, profile and files are unavailable until they accept. Requests can be retried after 48 hours. This saved name stays on your phone. Verify the signed QR before sharing sensitive information.","তিনি গ্রহণ না করলে chat, call, profile বা file চালু হবে না। ৪৮ ঘণ্টা পরে আবার request করা যায়। এই সেভ করা নাম আপনার ফোনে থাকে। সংবেদনশীল তথ্যের আগে signed QR যাচাই করুন।"))
                .setView(name).setPositiveButton(tr("Request","Request")) { _,_ -> owner.requestContact(bundle,name.text.toString().trim()) { ok -> if(visible && unlocked) toast(if(ok) tr("Request sent","Request পাঠানো হয়েছে") else tr("Request unavailable; check connection or retry after 48 hours","Request পাঠানো যায়নি; connection দেখুন বা ৪৮ ঘণ্টা পরে চেষ্টা করুন")) } }
                .setNegativeButton(tr("Cancel","বাতিল"),null).secureShow()
        }
    }
    private fun incomingContactRequests() {
        val requests = owner.incomingRequests
        MaterialAlertDialogBuilder(this).setTitle(tr("Contact requests","Contact request"))
            .setItems(requests.map { it.name }.toTypedArray()) { _,index ->
                val request = requests[index]
                val name = field().apply { setText(request.name); filters=arrayOf(android.text.InputFilter.LengthFilter(80)) }
                MaterialAlertDialogBuilder(this).setTitle(tr("Save as / request action","নাম সেভ / request-এর সিদ্ধান্ত"))
                    .setMessage(tr("Accept only people you recognize. No messages, calls or profile content are delivered before acceptance.","পরিচিত ব্যক্তিকেই গ্রহণ করুন। গ্রহণের আগে message, call বা profile-এর তথ্য পৌঁছায় না।"))
                    .setView(name).setPositiveButton(tr("Accept","গ্রহণ করুন")) { _,_ -> owner.respondRequest(request.uid,"accept",name.text.toString().trim()) { home() } }
                    .setNeutralButton(tr("Block","Block")) { _,_ -> owner.respondRequest(request.uid,"block",request.name) { home() } }
                    .setNegativeButton(tr("Decline","গ্রহণ করবেন না")) { _,_ -> owner.respondRequest(request.uid,"decline",request.name) { home() } }.secureShow()
            }.secureShow()
    }
    private fun friendlyId(name: String = owner.preferences.getString("name", "CUTE") ?: "CUTE", uid: String = owner.localUid) =
        FriendlyContactId.create(name, uid)
    private fun shareCode() = "Cute Chat ID: ${friendlyId()}\nSecure Contact Code:\n${owner.code}"
    private fun acceptCode(code: String) {
        val secure = FriendlyContactId.extract(code)
        owner.addContact(secure) { id -> pendingPair = id; toast(tr("Contact saved. Waiting for mutual connection…", "Contact সেভ হয়েছে। দুজনের সংযোগের অপেক্ষা…")); update() }
    }
    private fun celebration(id: String) {
        pendingPair = null; page = "entry"
        val layout = base("Cute Chat ♥"); val symbol = label("♥  🔒  ♥", 58f).apply { gravity = Gravity.CENTER }
        layout.addView(symbol, LinearLayout.LayoutParams(-1, 0, 1f)); layout.addView(label(tr("Love · private · authenticated", "ভালোবাসা · ব্যক্তিগত · পরিচয় যাচাই হয়েছে"), 20f))
        if (animationsAllowed()) { symbol.alpha = 0f; symbol.scaleX = .7f; symbol.scaleY = .7f
            symbol.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(650).start() }
        if (owner.preferences.getBoolean("sound", true)) try { val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 30); tone.startTone(ToneGenerator.TONE_PROP_ACK, 160); handler.postDelayed({ tone.release() }, 400) } catch (_: Exception) { }
        handler.postDelayed({ if (page == "entry" && unlocked) openChat(id) }, if (owner.preferences.getBoolean("fast", false)) 200 else 3000)
    }
    private fun openChat(id: String) {
        page = "chat"; peer = id; reply = null; owner.foregroundPeer(id)
        val friend = owner.contacts.find { it.uid == id } ?: return
        owner.requestChatWindow(id)
        val layout = base(friend.name) { home() }
        chatStatus = label("", 13f); layout.addView(chatStatus)
        val actions = LinearLayout(this)
        actions.addView(button(owner.labels[id] ?: "Ping") { owner.ping(id) }.apply { setOnLongClickListener {
            val input = field().apply { hint = "Miss You / custom label"; filters = arrayOf(android.text.InputFilter.LengthFilter(32)) }
            MaterialAlertDialogBuilder(this@MainActivity).setView(input).setPositiveButton("Save") { _, _ -> owner.setLabel(id, input.text.toString()) }.setNegativeButton("Cancel", null).secureShow(); true
        } }, LinearLayout.LayoutParams(0, dp(52), 1f))
        actions.addView(iconButton("call", tr("Audio call", "অডিও কল")) { permission(false, false) { owner.work { calls.start(id, false) } } }, LinearLayout.LayoutParams(0, dp(52), 1f))
        actions.addView(iconButton("video", tr("Video call", "ভিডিও কল")) { permission(false, true) { owner.work { calls.start(id, true) } } }, LinearLayout.LayoutParams(0, dp(52), 1f))
        layout.addView(actions)
        val onlineOption = MaterialSwitch(this).apply {
            text = tr("Online → Ping me", "অনলাইন হলে → Ping দিন", "ऑनलाइन आएँ → Ping करें", "В сети → Ping мне")
            isChecked = owner.onlineWatch(id); setPadding(dp(8),0,dp(8),0); textSize=14f
            setOnCheckedChangeListener { _,enabled -> owner.setOnlineWatch(id,enabled)
                if (enabled) toast(tr("You get a Ping when they newly return online. They receive your arrival only if they enable Ping me for you. Saved names stay on each phone; Android delivery limits apply.", "তিনি নতুন করে অনলাইনে ফিরলে আপনি Ping পাবেন। আপনার জন্য তিনি Ping me চালু রাখলেই আপনার ফেরার খবর পাবেন। নাম থাকে নিজের ফোনে; Android-এর সীমা প্রযোজ্য।")) }
        }
        chatExtras = onlineOption; onlineOption.visibility = if (imeVisible) View.GONE else View.VISIBLE
        layout.addView(onlineOption, LinearLayout.LayoutParams(-1,dp(52)))
        actions.addView(iconButton("more", tr("Chat options", "চ্যাট অপশন")) {
            val menu = arrayOf(tr("Schedule a Ping","সময় দিয়ে Ping পাঠান"), tr("Rename contact","Contact-এর নাম বদলান"),
                tr("Save to phone Contacts", "ফোনের Contacts-এ সেভ"), tr("Copy Cute Chat ID", "Cute Chat ID কপি"), tr("Block contact", "Contact block করুন"), tr("Screenshot privacy", "স্ক্রিনশটের গোপনীয়তা"), tr("Private profile","Private profile"))
            MaterialAlertDialogBuilder(this).setItems(menu) { _,choice ->
                when (choice) {
                0 -> {
                    val now=java.util.Calendar.getInstance()
                    TimePickerDialog(this,{ _,hour,minute -> val at=java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY,hour); set(java.util.Calendar.MINUTE,minute); set(java.util.Calendar.SECOND,0); if(timeInMillis<=System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_MONTH,1) }; owner.schedule(id,at.timeInMillis); toast(tr("Scheduling request sent","সময়ের অনুরোধ পাঠানো হয়েছে")) },now.get(java.util.Calendar.HOUR_OF_DAY),now.get(java.util.Calendar.MINUTE),true).secureShow()
                }
                1 -> { val input=field().apply { setText(friend.name); filters=arrayOf(android.text.InputFilter.LengthFilter(80)) }
                    MaterialAlertDialogBuilder(this).setTitle(tr("Saved name on this phone","এই ফোনে সেভ করা নাম")).setView(input).setPositiveButton(tr("Save","সেভ")) { _,_ -> owner.renameContact(id,input.text.toString()) { if(page=="chat" && peer==id) openChat(id) } }.setNegativeButton(tr("Cancel","বাতিল"),null).secureShow()
                }
                2 -> {
                    exportPhoneContact(friend.name, friendlyId(friend.name,friend.uid))
                }
                3 -> {
                    val contactId = friendlyId(friend.name, friend.uid)
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Cute Chat ID", contactId)); toast(tr("Cute Chat ID copied", "Cute Chat ID কপি হয়েছে"))
                }
                4 -> MaterialAlertDialogBuilder(this).setTitle(tr("Block ${friend.name}?", "${friend.name}-কে block করবেন?"))
                    .setMessage(tr("Direct connections, Ping and calls will stop. Chat history stays encrypted on this phone. You can unblock later in Settings.", "Direct connection, Ping ও call বন্ধ হবে। Chat history এই ফোনে encrypted থাকবে। Settings থেকে পরে unblock করতে পারবেন।"))
                    .setPositiveButton(tr("Block", "Block")) { _,_ -> owner.blockContact(id) { home() } }.setNegativeButton(tr("Cancel","বাতিল"),null).secureShow()
                5 -> MaterialAlertDialogBuilder(this).setTitle(tr("Screenshots on this phone", "এই ফোনে স্ক্রিনশট"))
                    .setMessage(tr("Limited-view media always blocks screenshots. For ordinary chat and calls, choose whether this phone allows screenshots. Android 14+ can report detected screenshots to your contact over E2EE; blocked attempts, older Android, recordings and external cameras cannot be reliably detected.", "সীমিত ভিউয়ের মিডিয়ায় স্ক্রিনশট বন্ধ। সাধারণ chat ও call-এ এই ফোনে স্ক্রিনশট অনুমতি বেছে নিন। Android 14+ শনাক্ত স্ক্রিনশটের খবর E2EE-তে পাঠায়; blocked চেষ্টা, পুরনো Android, recording ও বাইরের camera নির্ভরযোগ্যভাবে শনাক্ত হয় না।"))
                    .setPositiveButton(tr("Allow", "অনুমতি দিন")) { _, _ -> owner.preferences.edit().putBoolean("capture.allow.$id", true).apply(); applyCapturePolicy() }
                    .setNeutralButton(tr("Block", "বন্ধ রাখুন")) { _, _ -> owner.preferences.edit().putBoolean("capture.allow.$id", false).apply(); applyCapturePolicy() }
                    .setNegativeButton(tr("Cancel", "বাতিল"), null).secureShow()
                6 -> showPrivateProfile(id)
                }
            }.secureShow()
        },LinearLayout.LayoutParams(dp(48),dp(52)))
        chatList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(ScrollView(this).apply { addView(chatList) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val replyLabel = label("", 12f).apply { visibility = View.GONE; background = CuteUi.card(this, glass, dp(14).toFloat()); setPadding(dp(12),dp(8),dp(12),dp(8)) }
        layout.addView(replyLabel); replyLabel.setOnClickListener { reply = null; replyLabel.text = ""; replyLabel.visibility = View.GONE }
        val composer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4),dp(2),dp(4),dp(4)); background = CuteUi.card(this, glass, dp(26).toFloat()) }
        val share = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; tag = "chat-share-actions" }
        fun shareAction(icon: String, name: String, tagName: String, choice: Int) {
            val action = iconButton(icon, name) { pickAttachment(id, choice) }.apply { tag = tagName }
            recordingDisabled.add(action)
            share.addView(action, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        shareAction("camera", tr("Camera", "ক্যামেরা", "कैमरा", "Камера"), "chat-camera", 1)
        shareAction("gallery", tr("Photos & videos", "ফটো ও ভিডিও", "फ़ोटो और वीडियो", "Фото и видео"), "chat-gallery", 0)
        shareAction("music", tr("Audio or music file", "অডিও বা গানের ফাইল", "ऑडियो या संगीत फ़ाइल", "Аудио или музыка"), "chat-audio-file", 3)
        shareAction("attach", tr("Files", "ফাইল", "फ़ाइलें", "Файлы"), "chat-files", 2)
        composer.addView(share)
        val recording = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; tag = "voice-recording-row" }
        voiceRow = recording
        voiceStatus = label("", 12f).apply { tag = "voice-recording-status"; setTextColor(CuteUi.DANGER); maxLines = 2 }
        recording.addView(voiceStatus, LinearLayout.LayoutParams(0, -2, 1f))
        recording.addView(button(tr("Cancel", "বাতিল", "रद्द", "Отмена")) { cancelRecording() }.apply { tag = "voice-cancel" }, LinearLayout.LayoutParams(-2, dp(48)))
        composer.addView(recording)
        val compose = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val input = field().apply {
            hint = tr("Message", "মেসেজ", "संदेश", "Сообщение"); maxLines = 4; minLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            filters = arrayOf(android.text.InputFilter.LengthFilter(4000)); tag = "chat-composer"
        }
        compose.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        recordingDisabled.add(input)
        voiceButton = iconButton("mic", tr("Voice message: hold to record, release to send; tap to start and tap again to send", "ভয়েস: ধরে রেকর্ড, ছেড়ে পাঠান; ট্যাপ করে শুরু, আবার ট্যাপে পাঠান", "वॉइस: दबाकर रिकॉर्ड, छोड़कर भेजें; टैप करके शुरू और फिर टैप करके भेजें", "Голос: удерживайте для записи, отпустите для отправки; нажатие — старт, повторное — отправка"), true) { handleVoiceAction(id, voiceGesture.click()) }.apply {
            tag = "chat-voice"
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val allowed = canRecordVoice(id) && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        voiceGesture.press(event.rawX, allowed)
                        if (allowed) { view.parent.requestDisallowInterceptTouchEvent(true); handler.postDelayed(armVoiceHold, 220) }
                        else if (canRecordVoice(id)) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 13)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> { if (voiceGesture.move(event.rawX) == VoiceMessageGesture.Action.CANCEL) cancelRecording(); true }
                    MotionEvent.ACTION_UP -> { handler.removeCallbacks(armVoiceHold); view.parent.requestDisallowInterceptTouchEvent(false); handleVoiceAction(id, voiceGesture.release()); true }
                    MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> { cancelRecording(); true }
                    else -> true
                }
            }
        }
        compose.addView(voiceButton, LinearLayout.LayoutParams(dp(48), dp(54)))
        val send = iconButton("send", tr("Send message", "মেসেজ পাঠান"), true) { val text = input.text.toString(); if (text.trim().isNotEmpty()) { owner.send(id, text, reply); input.setText(""); reply = null; replyLabel.text = ""; replyLabel.visibility = View.GONE } }
        recordingDisabled.add(send); compose.addView(send, LinearLayout.LayoutParams(dp(48), dp(54)))
        composer.addView(compose)
        layout.addView(composer, LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,dp(6),0,0) })
        replyUi = { message -> reply = message.id; replyLabel.text = "↩ " + message.data.optString("text", message.data.optString("name")).take(70) + "  ×"; replyLabel.visibility = View.VISIBLE }
        updateChat()
    }
    private var replyUi: ((NativeOwner.Message) -> Unit)? = null
    private fun updateChat() {
        val id = peer ?: return
        chatStatus?.text = contactStatus(id) + if(id in owner.online) "" else lastSeenLabel(id)?.let { "\n$it" }.orEmpty()
        val key = owner.largeTransfers.filter { it.peer==peer }.toString()+owner.messages.joinToString { "${it.id}:${it.status}:${it.reactions}:${it.data}" }
        if (key == messageKey) return
        messageKey = key; val list = chatList ?: return
        val scroll=list.parent as? ScrollView; val oldY=scroll?.scrollY ?: 0
        val atBottom=list.childCount==0 || oldY + (scroll?.height ?: 0) >= list.height-dp(100)
        list.removeAllViews()
        owner.largeTransfers.filter { it.peer==id && it.state !in setOf("done","failed") && owner.messages.none { message -> message.id==it.id } }.forEach { transfer ->
            val row=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(12),dp(14),dp(12)) }
            row.addView(label(transfer.name+" · "+android.text.format.Formatter.formatFileSize(this,transfer.size),15f))
            row.addView(label(transfer.state+" · "+(transfer.bytes*100/transfer.size.coerceAtLeast(1))+"%",12f))
            if(!transfer.outgoing && transfer.state=="offered") row.addView(button(tr("Receive encrypted file","Encrypted file নিন")) { owner.acceptLargeFile(transfer.id,true) })
            row.addView(button(tr("Cancel transfer","Transfer বাতিল")) { owner.cancelLargeFile(transfer.id) });list.addView(card(row))
        }
        owner.messages.forEach { message ->
            if(message.kind == "call_event") {
                val data=message.data
                val event=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; setPadding(dp(16),dp(10),dp(16),dp(10)); tag="chat-call-event" }
                event.addView(label((if(data.optBoolean("video")) tr("Video call","ভিডিও কল") else tr("Audio call","অডিও কল")) + " · " + callOutcome(data.optString("outcome")),14f).apply {
                    gravity=Gravity.CENTER; setTextColor(if(data.optString("outcome") == "missed") MaterialYou.color(this,androidx.appcompat.R.attr.colorError) else subtle)
                    setCompoundDrawablesRelativeWithIntrinsicBounds(MaterialYou.icon(this@MainActivity,if(data.optBoolean("video")) "video" else "call",currentTextColor),null,null,null)
                    compoundDrawablePadding=dp(8)
                })
                val duration=data.optLong("duration")
                event.addView(label(java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(message.time)) + if(data.optString("outcome") == "ended") " · %02d:%02d".format(duration/60000,duration/1000%60) else "",11f).apply { gravity=Gravity.CENTER; setTextColor(subtle) })
                event.isClickable=true; event.isFocusable=true; event.setOnClickListener { libraryPage("calls") }
                list.addView(card(event)); return@forEach
            }
            val limited = message.data.optInt("viewLimit") > 0
            val expired = message.data.optBoolean("expired")
            val contents = if(message.kind == "deleted") tr("Message deleted", "মেসেজ মুছে ফেলা হয়েছে") else if (message.kind == "attachment") {
                if (!message.outgoing && expired) tr("Media no longer available", "মিডিয়াটি আর পাওয়া যাবে না")
                else (if (message.data.optString("mime").startsWith("image/")) "▧ " else "📎 ") + message.data.optString("name")
            } else message.data.optString("text")
            val bubble = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(12), dp(8)); isClickable = message.kind == "attachment"; isFocusable = true
                background = GradientDrawable().apply {
                    setColor(if (message.outgoing) MaterialYou.color(themeAnchor, com.google.android.material.R.attr.colorPrimaryContainer) else container)
                    val small = dp(6).toFloat(); val large = dp(20).toFloat()
                    cornerRadii = if (message.outgoing) floatArrayOf(large,large,small,small,large,large,large,large)
                        else floatArrayOf(small,small,large,large,large,large,large,large)
                    if (glass) setStroke(dp(1), 0x26FFFFFF)
                }
                if (message.data.has("replyTo")) {
                    val quoted = owner.messages.find { it.id == message.data.getString("replyTo") }?.data?.optString("text")?.take(72) ?: tr("Message", "মেসেজ")
                    addView(label("↩  $quoted", 13f).apply {
                        maxWidth = resources.displayMetrics.widthPixels - dp(116); setTextColor(subtle)
                        setPadding(dp(9), dp(6), dp(9), dp(6)); background = MaterialYou.shape(0x1A5965FF, dp(10).toFloat())
                    })
                }
                addView(label(contents, 16.5f).apply { maxWidth = resources.displayMetrics.widthPixels - dp(100); setPadding(0, dp(3), 0, dp(5)) })
                if(message.data.optBoolean("large")) {
                    addView(label(android.text.format.Formatter.formatFileSize(this@MainActivity,message.data.optLong("size")),12f).apply { setTextColor(subtle) })
                    owner.largeTransfers.find { it.id==message.id && it.peer==id }?.let { transfer ->
                        addView(label(transfer.state+" · "+(if(transfer.size>0) transfer.bytes*100/transfer.size else 0)+"%",12f).apply { setTextColor(subtle) })
                        if(transfer.state !in setOf("done","failed")) addView(button(tr("Cancel","বাতিল")) { owner.cancelLargeFile(transfer.id) })
                    }
                }
                if (limited && !expired) addView(label(if (message.outgoing) {
                    "${message.data.optInt("viewLimit")}-view · " + if (message.data.optBoolean("peerExpired")) tr("Deleted on recipient's device", "প্রাপকের ফোনে মুছে গেছে") else tr("Recipient deletion not confirmed", "প্রাপকের মুছে যাওয়া নিশ্চিত হয়নি")
                } else tr("Limited view", "সীমিত ভিউ"), 12f).apply { setTextColor(subtle) })
                val meta = LinearLayout(this@MainActivity).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
                if (message.reactions.isNotBlank()) meta.addView(label(message.reactions, 13f).apply { setPadding(0, 0, dp(10), 0) })
                val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(message.time))
                val state = if (message.outgoing) when (message.status) { 0 -> "  ◷"; 1 -> "  ✓"; 2 -> "  ✓✓"; else -> "  ✓✓" } else ""
                meta.addView(label(time + state, 11f).apply { setTextColor(subtle); setPadding(0, 0, 0, 0) })
                addView(meta)
                contentDescription = contents + if (message.outgoing) ", sent $time" else ", received $time"
            }
            val params = LinearLayout.LayoutParams(-2, -2).apply { gravity = if (message.outgoing) Gravity.END else Gravity.START; setMargins(0, dp(6), 0, dp(6)) }
            list.addView(bubble, params); if (message.time > System.currentTimeMillis()-1200 && animationsAllowed()) { bubble.alpha=0f; bubble.translationY=dp(8).toFloat(); bubble.animate().alpha(1f).translationY(0f).setDuration(180).start() }
            var downX = 0f
            bubble.setOnTouchListener { view, event ->
                when (event.actionMasked) { MotionEvent.ACTION_DOWN -> downX = event.x
                    MotionEvent.ACTION_UP -> if (message.kind != "deleted" && event.x - downX > dp(50)) { view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK); replyUi?.invoke(message); return@setOnTouchListener true } }
                false
            }
            bubble.setOnLongClickListener {
                if(message.kind == "deleted") return@setOnLongClickListener true
                val emojis = ProtocolRules.emojis.toList()
                val items = listOf("↩ Reply") + emojis + if(message.outgoing) listOf(tr("Delete for everyone","সবার জন্য মুছুন")) else emptyList()
                MaterialAlertDialogBuilder(this).setItems(items.toTypedArray()) { _, choice ->
                    if (choice == 0) replyUi?.invoke(message)
                    else if(choice <= emojis.size) owner.react(id, message.id, emojis[choice - 1])
                    else if(id !in owner.deletePeers) toast(tr("Connect to an updated peer first to enable Delete for everyone", "সবার জন্য মুছতে আগে updated peer-এর সঙ্গে সংযুক্ত হন"))
                    else MaterialAlertDialogBuilder(this).setTitle(tr("Delete for everyone?","সবার জন্য মুছবেন?"))
                        .setMessage(tr("An encrypted deletion request will apply when the other phone reconnects. Copies saved in galleries, exported files or screenshots cannot be removed. This cannot guarantee erasure on modified devices.","অন্য ফোন আবার সংযুক্ত হলে encrypted deletion request কার্যকর হবে। Gallery-তে সেভ করা copy, export করা file বা screenshot মুছতে পারে না। পরিবর্তিত device-এ মুছে যাওয়া নিশ্চিত নয়।"))
                        .setPositiveButton(tr("Delete","মুছুন")) { _,_ -> owner.deleteForEveryone(id,message.id) }
                        .setNegativeButton(tr("Cancel","বাতিল"),null).secureShow()
                }.secureShow(); true
            }
            if (message.kind == "attachment") bubble.setOnClickListener {
                val mime = message.data.optString("mime", "application/octet-stream")
                if (!message.outgoing && expired) return@setOnClickListener
                if (limited) { viewMedia(id, message.data.getString("id"), mime, !message.outgoing); return@setOnClickListener }
                val playable = mime.startsWith("image/") || mime.startsWith("audio/") || mime.startsWith("video/")
                val gallery = Build.VERSION.SDK_INT >= 29 && (mime.startsWith("image/") || mime.startsWith("video/"))
                val choices = mutableListOf(tr("Open", "খুলুন"), tr("Save file", "ফাইল সেভ"))
                if (gallery) choices.add(tr("Save to gallery", "গ্যালারিতে সেভ"))
                MaterialAlertDialogBuilder(this).setItems(choices.toTypedArray()) { _, choice ->
                    if (choice == 0) { if (playable) viewMedia(id, message.data.getString("id"), mime)
                        else if(mime == "application/pdf" || mime.startsWith("text/") || mime == "application/json") openPrivateDocument(PrivateLibrary.Media(message.data.getString("id"),id,message.outgoing,message.data.getString("name"),mime,message.data.getLong("size"),message.time))
                        else openExternalFile(id, message.data.getString("id"), mime, message.data.getString("name")) }
                    else if (choice == 2) saveGallery(id, message.data.getString("id"), mime, message.data.getString("name"))
                    else {
                        savingAttachment = id to message.data.getString("id")
                        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType(mime).putExtra(Intent.EXTRA_TITLE, message.data.getString("name")), 22)
                    }
                }.secureShow()
            }
        }
        scroll?.post { if(atBottom) scroll.fullScroll(View.FOCUS_DOWN) else scroll.scrollTo(0,oldY) }
    }
    private fun settingsRow(title: String, detail: String = "", iconName: String = "", click: () -> Unit): View {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(72); setPadding(dp(16), dp(12), dp(14), dp(12)) }
        if (iconName.isNotEmpty()) row.addView(ImageView(this).apply {
            setImageDrawable(MaterialYou.icon(this@MainActivity, iconName, purple))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = MaterialYou.shape(container, dp(16).toFloat())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(14) })
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        words.addView(label(title, 16f).apply { setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, 0) })
        if (detail.isNotEmpty()) words.addView(label(detail, 13f).apply { setTextColor(subtle); setPadding(0, dp(5), 0, 0) })
        row.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(ImageView(this).apply {
            setImageDrawable(MaterialYou.icon(this@MainActivity, "chevron", subtle))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(8) })
        return card(row).apply { isClickable = true; isFocusable = true; contentDescription = title + ". " + detail; setOnClickListener { click() } }
    }
    private fun lockTimeoutSettings() {
        val value=field().apply { inputType=android.text.InputType.TYPE_CLASS_NUMBER;setText(lockMinutes().toString());filters=arrayOf(android.text.InputFilter.LengthFilter(2)) }
        MaterialAlertDialogBuilder(this).setTitle(tr("Auto-lock delay","অটো লক-এর সময়")).setMessage(tr("Minutes outside the app:0–60. Default5. Zero locks immediately. Phone screen lock and cold start still require authentication.","অ্যাপের বাইরে থাকার মিনিট:০–৬০। ডিফল্ট৫। শূন্য দিলে সঙ্গে সঙ্গে লক। ফোনের screen lock ও cold start-এ authentication লাগবে।"))
            .setView(value).setPositiveButton(tr("Save","সেভ")) { _,_->
                val minutes=value.text.toString().toIntOrNull()
                if(minutes==null || minutes !in 0..60) toast(tr("Use0–60 minutes","০–৬০ মিনিট দিন"))
                else { owner.preferences.edit().putInt("viewLockMinutes",minutes).apply();settings("privacy") }
            }.setNegativeButton(tr("Cancel","বাতিল"),null).secureShow()
    }
    private fun settings(category: String = "") {
        settingsCategory = category; page = "settings"
        val titles = mapOf(
            "appearance" to tr("Appearance", "ডিজাইন"),
            "notifications" to tr("Notifications", "নোটিফিকেশন"),
            "privacy" to tr("Privacy & app lock", "গোপনীয়তা ও অ্যাপ লক"),
            "calls" to tr("Calls", "কল"),
            "media" to tr("Photos & files", "ফটো ও ফাইল"),
            "availability" to tr("Background availability", "ব্যাকগ্রাউন্ড"),
            "account" to tr("Account & contacts", "অ্যাকাউন্ট ও কন্টাক্ট"),
            "about" to tr("About Cute Chat", "Cute Chat সম্পর্কে"))
        val layout = base(titles[category] ?: tr("Settings", "সেটিংস", "सेटिंग्स", "Настройки")) {
            if (category.isEmpty()) home() else settings()
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(12)) }
        layout.addView(ScrollView(this).apply { isFillViewport = true; clipToPadding = false; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        fun action(title: String, detail: String = "", icon: String = "", click: () -> Unit) { content.addView(settingsRow(title, detail, icon, click)) }
        fun note(text: String) { content.addView(label(text, 13f).apply { setTextColor(subtle); setPadding(dp(4), dp(8), dp(4), dp(12)) }) }
        fun option(key: String, title: String, detail: String, default: Boolean = false, changed: (() -> Unit)? = null) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(76); setPadding(dp(16), dp(12), dp(12), dp(12)) }
            val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            words.addView(label(title, 16f).apply { setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, 0) })
            words.addView(label(detail, 13f).apply { setTextColor(subtle); setPadding(0, dp(5), dp(8), 0) })
            row.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
            val control = MaterialSwitch(this).apply {
                contentDescription = title; isChecked = owner.preferences.getBoolean(key, default)
                minimumWidth = dp(48); minimumHeight = dp(48)
                setOnCheckedChangeListener { _, value ->
                    owner.preferences.edit().putBoolean(key, value).apply(); changed?.invoke()
                }
            }
            row.addView(control); row.setOnClickListener { control.isChecked = !control.isChecked }
            content.addView(card(row))
        }
        if (category.isEmpty()) {
            val profile = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(16)) }
            val name = owner.preferences.getString("name", "Cute Chat") ?: "Cute Chat"
            profile.addView(avatar(name), LinearLayout.LayoutParams(dp(52), dp(52)))
            profile.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0)
                addView(label(name, 20f).apply { setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, dp(5)) })
                addView(label(tr("Your private space", "আপনার ব্যক্তিগত জায়গা"), 13f).apply { setTextColor(subtle); setPadding(0, 0, 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            content.addView(card(profile))
            action(titles.getValue("appearance"), tr("Theme, colours, language & motion", "থিম, রং, ভাষা ও অ্যানিমেশন"), "palette") { settings("appearance") }
            action(titles.getValue("notifications"), tr("Ping, sound & message previews", "Ping, sound ও মেসেজ preview"), "ping") { settings("notifications") }
            action(titles.getValue("privacy"), tr("App lock, read receipts & last seen", "অ্যাপ লক, read receipt ও last seen"), "shield") { settings("privacy") }
            action(titles.getValue("calls"), tr("Picture-in-Picture & screen light", "Picture-in-Picture ও স্ক্রিন লাইট"), "video") { settings("calls") }
            action(titles.getValue("media"), tr("Gallery storage & limited views", "গ্যালারি সেভ ও সীমিত ভিউ"), "attach") { settings("media") }
            action(titles.getValue("availability"), tr("How long you stay connected", "কতক্ষণ সংযুক্ত থাকবেন"), "clock") { settings("availability") }
            action(titles.getValue("account"), tr("Google link & blocked contacts", "Google link ও blocked contact"), "account") { settings("account") }
            action(titles.getValue("about"), tr("Privacy notice, help & app version", "গোপনীয়তা, সহায়তা ও অ্যাপের version"), "info") { settings("about") }
            return
        }
        when (category) {
            "media" -> {
                if (Build.VERSION.SDK_INT >= 29) option("galleryAutoSave", tr("Save received photos & videos", "আসা ফটো ও ভিডিও সেভ করুন"), tr("Off by default. Ordinary media is copied to your gallery, outside encrypted app storage. Limited media is excluded.", "ডিফল্টে বন্ধ। সাধারণ মিডিয়া encrypted app-এর বাইরে আপনার gallery-তে সেভ হবে। সীমিত মিডিয়া সেভ হবে না।"))
                note(tr("Photos & videos use Android's picker; Files uses the document picker. No broad storage permission. Limited photos allow one or two openings within 30 seconds of the first opening. Videos only follow the view count. No recipient countdown is shown. Screenshot blocking cannot prevent an external camera or a modified device.", "Photos ও videos Android picker; Files document picker ব্যবহার করে। অতিরিক্ত storage permission নয়। সীমিত ছবি প্রথমবার খোলার ৩০ সেকেন্ডের মধ্যে এক বা দুইবার খোলা যায়। ভিডিওতে শুধু ভিউ সংখ্যা। প্রাপক countdown দেখেন না। বাইরের camera বা পরিবর্তিত ফোনে capture রোধ নিশ্চিত নয়।"))
            }
            "appearance" -> {
                action(tr("Visual style", "ভিজ্যুয়াল স্টাইল"), if (glass) "Soft Glass" else "Material You", "palette") {
                    MaterialAlertDialogBuilder(this).setTitle(tr("Visual style", "ভিজ্যুয়াল স্টাইল"))
                        .setSingleChoiceItems(arrayOf("Material You", "Soft Glass"), if (glass) 1 else 0) { dialog, which ->
                            owner.preferences.edit().putString("visualStyle", if (which == 1) CuteUi.STYLE_GLASS else CuteUi.STYLE_MATERIAL).apply()
                            dialog.dismiss(); settings(category)
                        }.secureShow()
                }
                val themes = arrayOf(tr("Follow phone", "ফোনের মতো"), tr("Light", "হালকা"), tr("Dark", "ডার্ক"))
                action(tr("Colour mode", "কালার মোড"), themes[owner.preferences.getInt("theme", 0).coerceIn(0, 2)]) {
                    MaterialAlertDialogBuilder(this).setTitle(tr("Colour mode", "কালার মোড"))
                        .setSingleChoiceItems(themes, owner.preferences.getInt("theme", 0).coerceIn(0, 2)) { dialog, which ->
                            owner.preferences.edit().putInt("theme", which).apply(); dialog.dismiss()
                            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(listOf(
                                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
                                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO,
                                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)[which])
                            if (visible) settings(category)
                        }.secureShow()
                }
                action(tr("Language", "ভাষা", "भाषा", "Язык"), when (owner.preferences.getString("language", "en")) {
                    "bn" -> "বাংলা"; "hi" -> "हिन्दी"; "ru" -> "Русский"; else -> "English"
                }) { language() }
                option("reduceMotion", tr("Reduce animations", "অ্যানিমেশন কমান"), tr("Immediate screens with less movement", "কম নড়াচড়া, সরাসরি screen খুলবে"))
                option("fast", tr("Fast pairing entry", "দ্রুত pairing entry"), tr("Skip the long pairing celebration", "Pairing-এর দীর্ঘ অ্যানিমেশন এড়িয়ে যান"))
                option("sound", tr("Pairing sound", "Pairing sound"), tr("A short sound when your private chat connects", "চ্যাট সংযুক্ত হলে ছোট sound"), true)
            }
            "notifications" -> {
                option("alerts", tr("Message & Ping alerts", "মেসেজ ও Ping alert"), tr("Android notification settings also apply", "Android notification settings-ও প্রযোজ্য"), true) { owner.refreshPushRegistration() }
                option("preview", tr("Show message previews", "মেসেজ preview দেখান"), tr("Off by default. When enabled, previews can appear while the phone is unlocked, even before app authentication.", "ডিফল্টে বন্ধ। চালু করলে ফোন আনলক থাকলেই অ্যাপের যাচাইয়ের আগেও নোটিফিকেশনে লেখা দেখা যেতে পারে।"))
                action(tr("Android notification settings", "Android notification settings"), tr("Sound, pop-up & notification channels", "Sound, pop-up ও notification channel"), "ping") { openAppSettings() }
            }
            "privacy" -> {
                action(tr("Auto-lock after leaving","অ্যাপের বাইরে গেলে অটো লক"),lockMinutes().toString()+tr(" minutes · fingerprint / phone password"," মিনিট · fingerprint / ফোনের password"),"shield") { lockTimeoutSettings() }
                note(tr("Default5minutes outside the app. Returning sooner keeps your tab and avoids another prompt. Cold start or screen lock requires authentication. Background connections and notification visibility are separate.","ডিফল্টে অ্যাপের বাইরে৫মিনিট থাকলে লক। আগে ফিরলে একই tab থাকে, আবার prompt আসে না। Cold start বা ফোনের screen lock হলে authentication লাগে। Background connection ও notification আলাদা।"))
                option("read", tr("Read receipts", "পড়ার রসিদ"), tr("Let your contact know when you read a message", "মেসেজ পড়লে আপনার contact জানতে পারবেন"), true)
                option("lastSeen", tr("Share last seen", "Last seen শেয়ার করুন"), tr("Optional server timestamp metadata; not E2EE", "ঐচ্ছিক server timestamp metadata; E2EE নয়")) { owner.sharePresence() }
                note(tr("Your local chat remains encrypted. App lock controls access to this app's screens.", "আপনার ফোনের চ্যাট encrypted থাকে। App lock দিয়ে screen-এ প্রবেশ নিয়ন্ত্রণ হয়।"))
            }
            "calls" -> {
                option("pip", tr("Video Picture-in-Picture", "ভিডিও Picture-in-Picture"), tr("Keep your call in a small window when you leave", "অ্যাপের বাইরে গেলে ছোট window-তে কল রাখুন"), true)
                option("callLight", tr("Remember screen light", "স্ক্রিন লাইট মনে রাখুন"), tr("Use your display as a light in video calls", "ভিডিও কলে display-এর আলো ব্যবহার করুন"))
                action(tr("Screen light width", "স্ক্রিন লাইটের প্রস্থ"), "${owner.preferences.getInt("lightWidth", 8)}%", "light") { lightWidth() }
                if(BuildConfig.REMOTE_CONTROL) action(tr("Remote control settings","রিমোট কন্ট্রোল সেটিংস"),tr("Optional screen-share assistance · local credentials only","ঐচ্ছিক screen-share সাহায্য · credentials স্থানীয়"),"shield") { remoteSettings() }
                action(tr("Call diagnostics","কলের সমস্যা পরীক্ষা"),tr("Private error codes · no SDP, keys or contact IDs","নিরাপদ error code · SDP, key বা contact ID নেই"),"info") {
                    MaterialAlertDialogBuilder(this).setTitle(tr("Call diagnostics","কলের সমস্যা পরীক্ষা"))
                        .setMessage("Android ${Build.VERSION.SDK_INT}\n${calls.status}\n"+owner.preferences.getString("callFailure","No recorded call failure"))
                        .setPositiveButton("OK",null).secureShow()
                }
                action(tr("Full-screen incoming calls", "Full-screen incoming call"), tr("Manage Android's call permission", "Android-এর call permission নিয়ন্ত্রণ করুন"), "call") { NativeCalls.permissionSettings(this) }
            }
            "availability" -> {
                option("chatWake",tr("Receive when a contact opens my chat","Contact আমার chat খুললে সংযুক্ত হওয়ার চেষ্টা"),
                    tr("Accepted contacts only. Requests a silent, visible one-minute background connection; no idle microphone. Android may refuse or delay it. Real messages and calls use your alert settings.","শুধু গ্রহণ করা contact। নীরব, দৃশ্যমান এক মিনিটের background connection চায়; idle microphone চালু নয়। Android আটকে বা দেরি করাতে পারে। আসল message ও call আপনার alert settings মেনে চলে।"),true) { owner.syncBackgroundWindow() }
                action(tr("Stay online after leaving", "বের হওয়ার পরে অনলাইন থাকুন"), owner.preferences.getInt("grace", 5).toString() + tr(" minutes", " মিনিট"), "clock") {
                    MaterialAlertDialogBuilder(this).setTitle(tr("Background idle time", "ব্যাকগ্রাউন্ডে সময়"))
                        .setItems(arrayOf("1 min", "5 min", "10 min", "30 min")) { _, index ->
                            owner.preferences.edit().putInt("grace", listOf(1, 5, 10, 30)[index]).apply(); settings(category)
                        }.secureShow()
                }
                action(tr("Stay available until…", "এই সময় পর্যন্ত চালু রাখুন…"), tr("Choose a time within 6 hours", "৬ ঘণ্টার মধ্যে সময় বেছে নিন")) {
                    val now = java.util.Calendar.getInstance()
                    TimePickerDialog(this, { _, hour, minute ->
                        val at = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, hour); set(java.util.Calendar.MINUTE, minute); set(java.util.Calendar.SECOND, 0); if (timeInMillis <= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_MONTH, 1) }
                        if (at.timeInMillis - System.currentTimeMillis() in 60000..21600000) { owner.availableUntil(at.timeInMillis); toast(tr("Availability requested; Android limits still apply", "উপলব্ধ থাকার অনুরোধ হয়েছে; Android-এর সীমা প্রযোজ্য")) }
                        else toast(tr("Choose a time within six hours", "৬ ঘণ্টার মধ্যে সময় বেছে নিন"))
                    }, now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE), true).secureShow()
                }
                action(tr("Background & auto-start setup","Background ও auto-start setup"),tr("Review Android/OEM battery and auto-start settings","Android/OEM battery ও auto-start settings দেখুন")) { backgroundSetupGuide() }
                note(tr("The active connection notification stays visible. Android may still stop the app; availability is not guaranteed.", "সংযোগ চললে notification দৃশ্যমান থাকবে। Android অ্যাপ থামাতে পারে; সর্বদা অনলাইন থাকা নিশ্চিত নয়।"))
            }
            "account" -> {
                val account = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                action(tr("My private profile","আমার private profile"),tr("Photo & bio shared only with accepted, connected contacts","শুধু গ্রহণ করা, connected contact-এর সঙ্গে photo ও bio শেয়ার হয়"),"account") { showPrivateProfile(null) }
                action(tr("Save my ID in phone Contacts","ফোনের Contacts-এ আমার ID সেভ করুন"),tr("Save through your phone’s Contacts editor","ফোনের Contacts editor থেকে সেভ করুন"),"account") { exportPhoneContact(owner.preferences.getString("name","Cute Chat")!!,friendlyId()) }
                action(tr("Google account", "Google account"), account?.displayName ?: tr("Optional · not linked", "ঐচ্ছিক · যুক্ত হয়নি"), "account") { googleAccount() }
                action(tr("Blocked contacts", "Blocked contact"), owner.blockedContacts.size.toString()) {
                    val blocked = owner.blockedContacts.toList()
                    if (blocked.isEmpty()) { toast(tr("No blocked contacts", "কোনো blocked contact নেই")); return@action }
                    MaterialAlertDialogBuilder(this).setTitle(tr("Blocked contacts", "Blocked contact"))
                        .setItems(blocked.map { it.name }.toTypedArray()) { _, which ->
                            val selected = blocked[which]
                            MaterialAlertDialogBuilder(this).setMessage(tr("Unblock ", "Unblock করবেন: ") + selected.name + "?")
                                .setPositiveButton(tr("Unblock", "Unblock")) { _, _ -> owner.unblockContact(selected.uid) { settings(category) } }
                                .setNegativeButton(tr("Cancel", "বাতিল"), null).secureShow()
                        }.secureShow()
                }
                note(tr("Google linking does not upload chats, back up contacts or recover lost encryption keys in this version. Phone Contacts exports remain readable and may sync through the account you choose. App contacts stay encrypted here; the coordination service stores consent and block metadata.", "এই version-এ Google link chat upload, contact backup বা হারানো key উদ্ধার করে না। Phone Contacts-এ সেভ করা তথ্য পড়া যায় এবং আপনার বেছে নেওয়া account-এ sync হতে পারে। App contact এখানে encrypted থাকে; coordination service-এ consent ও block metadata থাকে।"))
            }
            "about" -> {
                action(tr("App updates", "অ্যাপ আপডেট", "ऐप अपडेट", "Обновления"), tr("Check security and feature updates", "Security ও feature update পরীক্ষা করুন"), "shield") { appUpdates.manualCheck() }
                action(tr("Source & security on GitHub", "GitHub-এ source ও security", "GitHub पर स्रोत और सुरक्षा", "Исходный код и безопасность на GitHub"), "xjitroy/Cute-Chat-E2EE-P2P-Encrypted", "info") { appUpdates.openSource() }
                note(tr("Update checks contact public GitHub; it may receive your IP address. No contact ID, Google token, message or private key is sent. APK downloads start only when you tap Download. Android confirms installation; the existing app and chats are retained.","Update পরীক্ষা public GitHub-এ যায়; সেখানে আপনার IP address পৌঁছাতে পারে। Contact ID, Google token, message বা private key পাঠানো হয় না। Download ট্যাপ করলেই APK নামবে। Android ইনস্টল নিশ্চিত করবে; আগের অ্যাপ ও chat থাকবে।"))
                action(tr("Privacy & terms", "গোপনীয়তা ও শর্ত"), tr("How your content & metadata are handled", "আপনার তথ্য ও metadata কীভাবে ব্যবহৃত হয়"), "shield") { legal() }
                note(guide()); note("Native Kotlin · " + packageManager.getPackageInfo(packageName, 0).versionName + "\n" + owner.status)
            }
        }
    }
    private fun backgroundSetupGuide() {
        MaterialAlertDialogBuilder(this).setTitle(tr("Reliable background delivery","Background delivery সেটআপ"))
            .setMessage(tr("Allow notifications and background data. In Android/OEM app settings, review Battery → Unrestricted and Auto-start if your phone provides it. This is optional and may use more battery. Cute Chat cannot enable or reliably read every manufacturer's auto-start setting. After restarting the phone, unlock it once; FCM/app startup may then reconnect. Powered-off, force-stopped or network-off phones cannot receive calls. Ongoing connections use a silent visible notification.","Notifications ও background data অনুমতি দিন। Android/OEM app settings-এ Battery → Unrestricted এবং থাকলে Auto-start দেখুন। এটি optional, battery বেশি ব্যবহার করতে পারে। Cute Chat সব ফোনের auto-start নিজে চালু বা নির্ভরযোগ্যভাবে পড়তে পারে না। Restart-এর পরে একবার ফোন unlock করুন; তখন FCM/app startup reconnect করতে পারে। ফোন বন্ধ, force-stop বা network বন্ধ থাকলে call আসতে পারে না। চলমান connection-এর silent visible notification থাকবে।"))
            .setPositiveButton(tr("Open app settings","App settings খুলুন")) { _,_->openAppSettings() }.setNegativeButton(tr("Later","পরে"),null).secureShow()
    }
    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
    }
    private fun profileBitmap(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        if(bounds.outWidth !in 1..20000 || bounds.outHeight !in 1..20000) return null
        val options = BitmapFactory.Options().apply { inSampleSize=ProfilePhotoRules.sample(bounds.outWidth,bounds.outHeight) }
        return BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
    }
    private fun showPrivateProfile(target: String?, selected: ByteArray? = null) {
        owner.readProfile(target) { bio, photo ->
            if (!visible || !unlocked) { selected?.fill(0); return@readProfile }
            val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20),dp(12),dp(20),dp(12)) }
            val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription=tr("Private profile photo","Private profile photo") }
            val bitmap = (selected ?: photo)?.let { profileBitmap(it) }
            if(bitmap != null) image.setImageBitmap(bitmap) else image.setImageResource(R.drawable.cute_chat_logo)
            content.addView(image,LinearLayout.LayoutParams(-1,dp(180)))
            val text = field().apply { setText(if(target==null && selected!=null) profileDraftBio ?: bio else bio); hint = tr("Bio","বায়ো"); filters=arrayOf(android.text.InputFilter.LengthFilter(240)); maxLines=4 }
            if(target == null) {
                content.addView(text)
                fun choosePhoto() { profileDraftBio=text.text.toString();profilePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                image.contentDescription=tr("Change private profile photo","Private profile photo বদলান");image.setOnClickListener { choosePhoto() }
                content.addView(button(tr("Change profile photo","Profile photo বদলান")) { choosePhoto() })
                content.addView(label(tr("Kept encrypted on this phone. Shared through E2EE after acceptance and direct connection. Google does not receive this profile from the app.","এই ফোনে encrypted থাকে। গ্রহণ ও direct connection-এর পরে E2EE-তে শেয়ার হয়। অ্যাপ থেকে এই profile Google-এ যায় না।"),12f))
            } else content.addView(label(bio.ifEmpty { tr("No bio shared yet","এখনও বায়ো শেয়ার হয়নি") }))
            val builder = MaterialAlertDialogBuilder(this).setTitle(if(target == null) tr("My profile","আমার profile") else owner.contacts.find { it.uid == target }?.name ?: "Cute Chat")
                .setView(ScrollView(this).apply { addView(content) }).setNegativeButton(tr("Close","বন্ধ"),null)
            if(target == null) builder.setPositiveButton(tr("Save","সেভ")) { _,_ -> acceptedPrivateAction {
                owner.saveProfile(text.text.toString().trim(),selected?.clone()) { profileDraftBio=null;if(visible && unlocked) { toast(tr("Private profile saved","Private profile সেভ হয়েছে"));if(page=="contacts") contacts() } }
            } }
                .setNeutralButton(tr("Remove photo","ফটো সরান")) { _,_ -> acceptedPrivateAction { owner.saveProfile(text.text.toString().trim(),clearPhoto=true) { profileDraftBio=null;if(visible && unlocked) { toast(tr("Profile photo removed","Profile photo সরানো হয়েছে"));if(page=="contacts") contacts() } } } }
            privateProfileDialog?.dismiss()
            privateProfileDialog = builder.create().apply {
                setOnDismissListener { selected?.fill(0); image.setImageDrawable(null); bitmap?.recycle() }
                window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE); show(); window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
    private fun consumeProfileSelection() {
        if(!visible || !unlocked || !owner.ready) return
        val uri = pendingProfileUri ?: return; pendingProfileUri = null
        mediaIo.execute {
            var bytes: ByteArray? = null
            try {
                val output = ByteArrayOutputStream()
                contentResolver.openInputStream(uri)!!.use { stream -> val buffer=ByteArray(8192); try { while(true) { val count=stream.read(buffer); if(count<0) break; check(output.size()+count <= ProtocolRules.MAX_ATTACHMENT); output.write(buffer,0,count) } } finally { buffer.fill(0) } }
                val original = output.toByteArray()
                val bitmap = try { profileBitmap(original) } finally { original.fill(0) }
                checkNotNull(bitmap)
                bytes = try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG,85,it) }.toByteArray() } finally { bitmap.recycle() }
                check(bytes.size in 1..512000); val selected=bytes; bytes=null
                handler.post { if(visible && unlocked) showPrivateProfile(null,selected) else selected.fill(0) }
            } catch (_: Exception) { bytes?.fill(0); handler.post { if(visible) toast(tr("Profile photo could not be opened","Profile photo খোলা যায়নি")) } }
        }
    }
    private fun googleAccount() {
        val account = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        if (account != null) {
            MaterialAlertDialogBuilder(this).setMessage(tr("Unlink Google? Your local identity and chats stay on this phone.", "Google unlink করবেন? পরিচয় ও চ্যাট এই ফোনেই থাকবে।"))
                .setPositiveButton(tr("Unlink", "Unlink")) { _, _ -> com.google.firebase.auth.FirebaseAuth.getInstance().signOut(); settings("account") }
                .setNegativeButton(tr("Cancel", "বাতিল"), null).secureShow()
        } else {
            MaterialAlertDialogBuilder(this).setMessage(tr("Optional account linking does not upload chats or restore lost encryption keys. Google receives the sign-in request. Continue?", "ঐচ্ছিক account linking chat upload বা হারানো key restore করে না। Google sign-in request পায়। এগোবেন?"))
                .setPositiveButton(tr("Continue", "এগিয়ে যান")) { _, _ ->
                    val config = org.json.JSONObject(assets.open("firebase-public.json").bufferedReader().use { it.readText() })
                    val options = com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder(com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN)
                        .requestIdToken(config.getString("GOOGLE_SERVER_CLIENT_ID")).requestEmail().build()
                    startActivityForResult(com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(this, options).signInIntent, 41)
                }.setNegativeButton(tr("Cancel", "বাতিল"), null).secureShow()
        }
    }
    private fun finishGoogleSetup() {
        val name = googleSetupName ?: return
        googleSetupName = null
        owner.setup(name,true); owner.preferences.edit().putBoolean("lock",true).apply(); unlocked=false; authAttempted=false; lock()
    }
    private fun permission(answer: Boolean, video: Boolean, action: () -> Unit) {
        if (recorder != null) { toast("Finish or cancel your voice recording first"); return }
        if (!unlocked || !visible || !owner.ready) return
        val needed = listOf(Manifest.permission.RECORD_AUDIO) + if (video) listOf(Manifest.permission.CAMERA) else emptyList()
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        val token=calls.id; val target=peer
        val guarded = { if(unlocked && visible && ((answer && calls.id==token && calls.status=="incoming") || (!answer && !calls.active && peer==target))) action() }
        if (missing.isEmpty()) guarded() else { permissionCall = guarded; requestPermissions(missing.toTypedArray(), 12) }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if(requestCode==18 && results.isNotEmpty() && results.all { it==PackageManager.PERMISSION_GRANTED }) { if(visible && unlocked && page=="contacts") startQrPreview() else resumedPermission={ if(page=="contacts") startQrPreview() } }
        if (requestCode == 12) { val action = permissionCall; permissionCall = null; if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) { if(visible && unlocked) action?.invoke() else resumedPermission=action } else toast("Call permission denied; camera and microphone stay off") }
        if (requestCode == 13) {
            cancelRecording()
            toast(if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) tr("Microphone ready. Press the mic again to record.", "মাইক প্রস্তুত। রেকর্ড করতে আবার মাইক চাপুন।") else tr("Microphone permission denied; nothing recorded", "মাইকের অনুমতি নেই; কিছু রেকর্ড হয়নি"))
        }
        if (requestCode == 14 && results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) {
            if (visible && unlocked) openCamera() else resumedPermission = { openCamera() }
        }
        if(requestCode==17) {
            val action=localPermissionAction;localPermissionAction=null
            if(results.isNotEmpty() && results.all { it==PackageManager.PERMISSION_GRANTED }) { if(visible && unlocked) acceptedPrivateAction { action?.invoke() } else resumedPermission={ acceptedPrivateAction { action?.invoke() } } }
            else { toast(tr("Nearby permission denied; Internet chat remains available","কাছের ডিভাইসের অনুমতি নেই; Internet chat চালু আছে"));if(visible && unlocked) sharingPage() }
        }
        if(requestCode==15 || requestCode==16) {
            contactsPermissionPending=false
            if(results.isNotEmpty() && results.all { it==PackageManager.PERMISSION_GRANTED } && requestCode==15) {
                contactsHubRequested=true
                if(visible && unlocked) { contactsHubRequested=false; phoneContacts() }
            }
        }
    }
    private fun callScreen() {
        qrScanner?.close()
        shownCall = calls.id; page = "call"
        val key = currentCallKey()
        if (callKey == key) return
        cancelRecording(); releaseRenderers(); callKey=key; chatList=null; chatStatus=null; root=null
        fullCall=true
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(window.decorView.windowToken,0)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 30) { window.insetsController?.hide(WindowInsets.Type.systemBars()); window.insetsController?.systemBarsBehavior=WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        else { @Suppress("DEPRECATION") window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY }
        window.decorView.requestApplyInsets()
        val frame=FrameLayout(this).apply { background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(29,22,42),Color.rgb(65,42,79),Color.rgb(24,31,44))) }
        callFrame=frame;callLightTop=null;callLightBottom=null;callLightCap=null
        val lightInset = if (calls.video && !calls.screenSharing && calls.status != "incoming" && owner.preferences.getBoolean("callLight", false)) resources.displayMetrics.widthPixels * lightSide() / 100 else 0
        val topLightInset=if(lightInset>0) resources.displayMetrics.heightPixels*lightTop()/100 else 0
        if(calls.video && calls.status!="incoming") {
            remoteRenderer=SurfaceViewRenderer(this).also { renderer ->
                renderer.init(calls.egl.eglBaseContext,null); renderer.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FILL); renderer.setEnableHardwareScaler(true)
                frame.addView(renderer,FrameLayout.LayoutParams(-1,-1).apply { leftMargin = lightInset; rightMargin = lightInset;topMargin=topLightInset })
            }
            localRenderer=SurfaceViewRenderer(this).also { renderer ->
                renderer.init(calls.egl.eglBaseContext,null); renderer.setMirror(!calls.screenSharing); renderer.setZOrderMediaOverlay(true); renderer.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                frame.addView(renderer,FrameLayout.LayoutParams(dp(96),dp(144),Gravity.TOP or Gravity.END).apply { topMargin=dp(126)+topLightInset; rightMargin=dp(20)+lightInset })
                renderer.setOnClickListener {
                    val expanded = selfPreview.tap(calls.id, mayPreviewSelf(), SystemClock.elapsedRealtime())
                    handler.removeCallbacks(restoreSelfPreview); bindCallVideo()
                    if (expanded) handler.postDelayed(restoreSelfPreview, 10_000)
                }
                var startX=0f; var startY=0f; var oldX=0f; var oldY=0f; var dragged=false
                val slop = ViewConfiguration.get(this).scaledTouchSlop
                renderer.setOnTouchListener { view,event -> when(event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { startX=event.rawX; startY=event.rawY; oldX=view.translationX; oldY=view.translationY; dragged=false; true }
                    MotionEvent.ACTION_MOVE -> { if (kotlin.math.abs(event.rawX-startX)>slop || kotlin.math.abs(event.rawY-startY)>slop) dragged=true
                        if (dragged) { val side=callLightTop?.width ?: lightInset;val cap=callLightCap?.height ?: topLightInset
                            view.translationX=(oldX+event.rawX-startX).coerceIn((-frame.width+dp(136)+2*side).coerceAtMost(0).toFloat(),0f); view.translationY=(oldY+event.rawY-startY).coerceIn(0f,(frame.height-dp(370)-cap).coerceAtLeast(0).toFloat()) }; true }
                    MotionEvent.ACTION_UP -> { if (!dragged) view.performClick(); true }
                    MotionEvent.ACTION_CANCEL -> { dragged=true; true }; else -> false }
                }
            }
            bindCallVideo()
            if (lightInset > 0) {
                callLightTop = View(this).apply { setBackgroundColor(Color.WHITE) }
                callLightBottom = View(this).apply { setBackgroundColor(Color.WHITE) }
                frame.addView(callLightTop, FrameLayout.LayoutParams(lightInset, -1, Gravity.START))
                frame.addView(callLightBottom, FrameLayout.LayoutParams(lightInset, -1, Gravity.END))
                callLightCap=View(this).apply { setBackgroundColor(Color.WHITE);tag="call-light-top-cap" }
                frame.addView(callLightCap,FrameLayout.LayoutParams(-1,topLightInset,Gravity.TOP))
            }
        } else {
            val center=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER }
            val name=owner.contacts.find { it.uid==calls.peer }?.name ?: tr("Private caller","ব্যক্তিগত কল")
            center.addView(avatar(name,116),LinearLayout.LayoutParams(dp(116),dp(116)))
            center.addView(label(tr("Just the two of you", "শুধু আপনাদের দুজন", "बस आप दोनों", "Только вы двое"),17f).apply { setTextColor(Color.rgb(218,203,232)); gravity=Gravity.CENTER; setPadding(0,dp(24),0,0) })
            frame.addView(center,FrameLayout.LayoutParams(-1,-1))
            if(animationsAllowed() && calls.status in setOf("calling","incoming")) center.getChildAt(0).animate().scaleX(1.05f).scaleY(1.05f).setDuration(700).withEndAction { center.getChildAt(0).animate().scaleX(1f).scaleY(1f).setDuration(700).start() }.start()
        }
        val heading=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(20),dp(12),dp(16)); background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0xcc16111d.toInt(),Color.TRANSPARENT)) }
        val top=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        top.addView(iconButton("back",tr("Return to chats; keep call active","চ্যাটে ফিরুন; কল চালু থাকবে")) { home() },LinearLayout.LayoutParams(dp(48),dp(48)))
        top.addView(label(owner.contacts.find { it.uid==calls.peer }?.name ?: "Cute Chat",20f).apply { setTextColor(Color.WHITE); setTypeface(typeface,Typeface.BOLD); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(12),0,0,0) },LinearLayout.LayoutParams(0,-2,1f))
        val tools=LinearLayout(this).apply { gravity=Gravity.END or Gravity.CENTER_VERTICAL }
        if (calls.video && calls.status != "incoming" && !calls.screenSharing) tools.addView(iconButton("light", tr("Screen light · hold to resize", "স্ক্রিন লাইট · প্রস্থ বদলাতে চেপে ধরুন"), owner.preferences.getBoolean("callLight", false)) { toggleCallLight() }.apply { setOnLongClickListener { lightWidth(); true } }, LinearLayout.LayoutParams(dp(48), dp(48)))
        if(lightInset>0) tools.addView(iconButton("resize",tr("Resize screen light","স্ক্রিন লাইটের আকার বদলান","स्क्रीन लाइट का आकार बदलें","Размер подсветки")) { lightWidth() },LinearLayout.LayoutParams(dp(48),dp(48)))
        if(calls.video && calls.status!="incoming" && !calls.screenSharing) tools.addView(iconButton("quality",tr("Video resolution and frame rate","ভিডিও রেজোলিউশন ও ফ্রেমরেট")) { videoQuality() },LinearLayout.LayoutParams(dp(48),dp(48)))
        if(calls.active && (Build.VERSION.SDK_INT >= 26 || calls.video)) tools.addView(iconButton("more",tr("Call tools","কল টুলস")) { callTools() },LinearLayout.LayoutParams(dp(48),dp(48)))
        heading.addView(top)
        heading.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ ->
            (localRenderer?.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                val lower=maxOf(dp(126),heading.height+dp(12))+topLightInset
                if(lp.topMargin<lower) { lp.topMargin=lower; localRenderer?.layoutParams=lp }
            }
        }
        if(tools.childCount>0) heading.addView(tools)
        callLabel=label("",13f).apply { setTextColor(Color.rgb(230,222,238)); gravity=Gravity.CENTER;maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(0,dp(8),0,0);setOnClickListener { MaterialAlertDialogBuilder(this@MainActivity).setTitle(tr("Call connection details","কলের connection তথ্য")).setMessage(calls.quality+"\n"+calls.captureQuality).setPositiveButton(tr("Close","বন্ধ"),null).secureShow() } }; heading.addView(callLabel)
        callHeading = heading
        frame.addView(heading,FrameLayout.LayoutParams(-1,-2,Gravity.TOP).apply { leftMargin = lightInset; rightMargin = lightInset;topMargin=topLightInset })
        val footer=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; setPadding(dp(16),dp(40),dp(16),dp(32)); background=GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,intArrayOf(0xeb16111d.toInt(),Color.TRANSPARENT)) }
        footer.addView(label(tr("Encrypted media · verified contact", "Encrypted media · পরিচয় যাচাই করা", "एन्क्रिप्टेड मीडिया · सत्यापित संपर्क", "Защищённое медиа · контакт проверен"),12f).apply { setTextColor(Color.rgb(208,237,223)); gravity=Gravity.CENTER })
        val controls=LinearLayout(this).apply { gravity=Gravity.CENTER }
        fun control(icon:String,title:String,selected:Boolean=false,color:Int?=null,action:()->Unit) {
            val column=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER }
            column.addView(iconButton(icon,title,selected,action).apply { color?.let { backgroundTintList=MaterialYou.tint(it); iconTint=MaterialYou.tint(Color.WHITE) } },LinearLayout.LayoutParams(dp(56),dp(56)))
            column.addView(label(title,11f).apply { setTextColor(Color.WHITE); gravity=Gravity.CENTER; maxLines=2 },LinearLayout.LayoutParams(-1,dp(42)))
            controls.addView(column,LinearLayout.LayoutParams(0,-2,1f))
        }
        if(calls.status=="incoming") control("call",tr("Answer","ধরুন","जवाब दें","Ответить"),color=Color.rgb(30,140,98)) { permission(true,calls.video) { owner.work { calls.answer() } } }
        else {
            control("mic",if(calls.muted) tr("Unmute","মাইক চালু") else tr("Mute","মাইক বন্ধ"),calls.muted) { owner.work { calls.toggleMute() } }
            control("speaker",tr("Speaker","স্পিকার"),calls.speaker) { owner.work { calls.toggleSpeaker() } }
            if(calls.video) {
                control("video",tr("Camera","ক্যামেরা"),calls.cameraOff) { owner.work { calls.toggleCamera() } }
                control("swap",tr("Flip","ঘোরান")) { owner.work { calls.switchCamera() } }
            }
        }
        control("end",tr("End","শেষ করুন","समाप्त","Завершить"),color=Color.rgb(201,57,81)) { owner.work { calls.end() } }
        if (controls.childCount * dp(56) > resources.displayMetrics.widthPixels - 2 * lightInset - dp(32)) {
            val wrapped = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val children = (0 until controls.childCount).map { controls.getChildAt(it) }; controls.removeAllViews()
            children.chunked(3).forEach { group ->
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
                group.forEach { row.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }; wrapped.addView(row)
            }; footer.addView(wrapped)
        } else footer.addView(controls)
        if(calls.audioInterrupted) footer.addView(button(tr("Resume call audio", "কলের অডিও আবার চালু করুন")) { owner.work { calls.resumeAudio() } })
        callFooter = footer
        frame.addView(footer,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply { leftMargin = lightInset; rightMargin = lightInset }); showContent(frame); enterMotion(heading)
        applyCallBrightness(calls.video && owner.preferences.getBoolean("callLight", false))
        setCallChrome(true, false); scheduleCallChrome()
        val frameCallId = calls.id
        if (Build.VERSION.SDK_INT >= 31) frame.post {
            if (page != "call" || !unlocked || calls.id != frameCallId || !calls.active || isInPictureInPictureModeCompat()) return@post
            val source = Rect(); (remoteRenderer ?: frame).getGlobalVisibleRect(source)
            setPictureInPictureParams(PictureInPictureParams.Builder().setAspectRatio(if (calls.video) Rational(9, 16) else Rational(1, 1))
                .setSourceRectHint(source).setAutoEnterEnabled(owner.preferences.getBoolean("pip", true)).build())
        }
    }
    private fun scheduleCallChrome() {
        handler.removeCallbacks(hideCallChrome)
        if (calls.active && calls.video && calls.status == "connected" && !isInPictureInPictureModeCompat())
            handler.postDelayed(hideCallChrome, 5000)
    }
    private fun setCallChrome(show: Boolean, animate: Boolean = true) {
        callChromeVisible = show
        if (page == "call" && !isInPictureInPictureModeCompat()) {
            if (Build.VERSION.SDK_INT >= 30) { if (show) window.insetsController?.show(WindowInsets.Type.systemBars()) else window.insetsController?.hide(WindowInsets.Type.systemBars()) }
            else { @Suppress("DEPRECATION") window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or (if (show) 0 else View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY) }
        }
        listOfNotNull(callHeading, callFooter).forEach { view ->
            view.animate().cancel()
            if (show) {
                view.visibility = View.VISIBLE
                if (animate && animationsAllowed()) { view.alpha = 0f; view.animate().alpha(1f).setDuration(160).start() }
                else view.alpha = 1f
            } else if (animate && animationsAllowed()) {
                view.animate().alpha(0f).setDuration(220).withEndAction { if (!callChromeVisible) view.visibility = View.INVISIBLE }.start()
            } else { view.alpha = 0f; view.visibility = View.INVISIBLE }
        }
    }
    private fun toggleCallLight() {
        if (!calls.video) return
        val enabled = !owner.preferences.getBoolean("callLight", false)
        owner.preferences.edit().putBoolean("callLight", enabled).apply()
        callKey = ""; callScreen()
        toast(if (enabled) tr("Screen light on · display brightness raised", "স্ক্রিন লাইট চালু · display brightness বাড়ানো হয়েছে") else tr("Screen light off", "স্ক্রিন লাইট বন্ধ"))
    }
    private fun lightWidth() {
        var side=lightSide();var cap=lightTop()
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(12)) }
        val state=label("",14f).apply { gravity=Gravity.CENTER }
        lateinit var pad: LightResizePad
        fun preview(w:Int,t:Int) {
            side=w.coerceIn(5,15);cap=t.coerceIn(4,15);lightPreview=side to cap
            state.text=tr("Sides $side% · Top $cap%","দুই পাশ $side% · উপরে $cap%","किनारे $side% · ऊपर $cap%","Стороны $side% · Сверху $cap%")
            pad.size(side,cap);resizeCallLight()
        }
        pad=LightResizePad(this,side,cap,::preview).apply { contentDescription=tr("Drag left/right for sides; up/down for the top light","দুই পাশের জন্য বাঁয়ে/ডানে; উপরের আলোর জন্য উপরে/নিচে টানুন") }
        body.addView(pad,LinearLayout.LayoutParams(-1,dp(160)));body.addView(state)
        val row=LinearLayout(this).apply { gravity=Gravity.CENTER }
        listOf(tr("Sides −","পাশ −") to {preview(side-1,cap)},tr("Sides +","পাশ +") to {preview(side+1,cap)},tr("Top −","উপরে −") to {preview(side,cap-1)},tr("Top +","উপরে +") to {preview(side,cap+1)}).forEach { (name,action) ->
            row.addView(button(name,action),LinearLayout.LayoutParams(0,dp(56),1f))
        }
        body.addView(row);preview(side,cap)
        val dialog=MaterialAlertDialogBuilder(this).setTitle(tr("Screen light size","স্ক্রিন লাইটের আকার","स्क्रीन लाइट का आकार","Размер подсветки"))
            .setView(body).setPositiveButton(tr("Apply","প্রয়োগ করুন")) { _,_ -> owner.preferences.edit().putInt("lightWidth",side).putInt("lightTopHeight",cap).apply() }
            .setNegativeButton(tr("Cancel","বাতিল"),null).create()
        dialog.setOnDismissListener { lightPreview=null;resizeCallLight() }
        dialog.secureShow()
    }
    private fun lightSide()=(lightPreview?.first ?: owner.preferences.getInt("lightWidth",8)).coerceIn(5,15)
    private fun lightTop()=(lightPreview?.second ?: owner.preferences.getInt("lightTopHeight",8)).coerceIn(4,15)
    private fun currentCallKey()="${resources.configuration.orientation}:${calls.id}:${calls.status}:${calls.localVideo?.hashCode()}:${calls.remoteVideo?.hashCode()}:${calls.muted}:${calls.speaker}:${calls.cameraOff}:${calls.audioInterrupted}:${calls.screenSharing}:${owner.preferences.getBoolean("callLight",false)}:${lightSide()}:${lightTop()}"
    private fun resizeCallLight() {
        val frame=callFrame ?: return
        if(callLightCap == null) return
        val side=(frame.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels)*lightSide()/100
        val cap=(frame.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels)*lightTop()/100
        listOf(callLightTop,callLightBottom).forEach { it?.layoutParams=it?.layoutParams?.apply { width=side } }
        callLightCap?.layoutParams=callLightCap?.layoutParams?.apply { height=cap }
        listOf(remoteRenderer,callHeading).forEach { view -> (view?.layoutParams as? FrameLayout.LayoutParams)?.let { it.leftMargin=side;it.rightMargin=side;it.topMargin=cap;view.layoutParams=it } }
        (localRenderer?.layoutParams as? FrameLayout.LayoutParams)?.let { it.rightMargin=side+dp(20);it.topMargin=cap+maxOf(dp(126),(callHeading?.height ?: 0)+dp(12));localRenderer?.layoutParams=it }
        (callFooter?.layoutParams as? FrameLayout.LayoutParams)?.let { it.leftMargin=side;it.rightMargin=side;callFooter?.layoutParams=it }
        callKey=currentCallKey()
    }
    private fun videoQuality() {
        val token=calls.id
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),dp(12)) }
        body.addView(label(tr("Camera target. Sent quality adapts to both phones, the codec and available network. Default: 1080p / 30fps when supported.","ক্যামেরার target। পাঠানোর quality দুই ফোন, codec ও network অনুযায়ী বদলায়। সাপোর্ট থাকলে ডিফল্ট 1080p / 30fps।"),13f))
        body.addView(label(tr("Video is compressed once here and decoded on the other phone. Prefer compatible hardware H.265, then VP9, with fallback when unsupported. Each direction uses one codec. This is lossy compression; more compression alone does not mean better quality.","এই ফোনে একবার compress হয়, অন্য ফোনে decode হয়। সমর্থিত hardware H.265 আগে, তারপর VP9; support না থাকলে fallback। প্রতি direction-এ একটি codec চলে। এটি lossy compression; শুধু বেশি compress করলেই quality বাড়ে না।","वीडियो एक बार compress होता है। समर्थित hardware H.265, फिर VP9; उपलब्ध न हो तो fallback। हर दिशा में एक codec। अधिक compression से quality बढ़ना निश्चित नहीं।","Видео сжимается один раз. Предпочтение аппаратному H.265, затем VP9, с совместимым резервным кодеком. Один кодек на направление. Больше сжатия не означает лучшее качество."),13f))
        if(calls.videoProtection) body.addView(label(tr("Battery below 50% or phone heating: capped at 720p / 30fps. Your chosen quality returns after safe recovery.","ব্যাটারি ৫০%-এর নিচে বা ফোন গরম: সর্বোচ্চ 720p / 30fps। নিরাপদ হলে বেছে নেওয়া quality ফিরবে।"),13f))
        val dialog=MaterialAlertDialogBuilder(this).setTitle(tr("Video quality","ভিডিও কোয়ালিটি")).setView(ScrollView(this).apply { addView(body) }).setNegativeButton(tr("Close","বন্ধ করুন"),null).create()
        body.addView(label(tr("Compression preference","কমপ্রেশন পছন্দ","संकुचन विकल्प","Режим сжатия"),16f))
        body.addView(label(tr("This changes video sent by this phone. Incoming quality depends on the other phone and its connection.","এটি এই ফোন থেকে পাঠানো ভিডিও বদলায়। পাওয়া ভিডিওর quality অন্য ফোন ও তার connection-এর উপর নির্ভর করে।","यह इस फोन से भेजे जाने वाले वीडियो को बदलता है। मिलने वाले वीडियो की quality दूसरे फोन पर निर्भर करती है।","Меняется видео, отправляемое этим телефоном. Качество входящего видео зависит от другого телефона и его соединения."),13f))
        VideoCompressionPolicy.Mode.entries.forEach { mode ->
            val title=when(mode) {
                VideoCompressionPolicy.Mode.DETAIL -> tr("Detail · recommended","Detail · প্রস্তাবিত","विवरण · अनुशंसित","Детали · рекомендуется")
                VideoCompressionPolicy.Mode.SMOOTH -> tr("Smooth motion","মসৃণ গতিবিধি","सहज गति","Плавное движение")
                VideoCompressionPolicy.Mode.DATA_SAVER -> tr("Data Saver · up to 720p / 30fps","Data Saver · সর্বোচ্চ 720p / 30fps","डेटा बचत · अधिकतम 720p / 30fps","Экономия трафика · до 720p / 30fps")
            }
            body.addView(button(title+if(mode==calls.compressionMode) " ✓" else "") {
                if(calls.id==token && visible && unlocked) owner.work { if(calls.id==token) calls.selectCompressionMode(mode) }
                dialog.dismiss()
            }.apply { isEnabled=calls.active && calls.status!="incoming" })
        }
        body.addView(label(tr("Detail favours resolution on a healthy connection. Smooth favours frame rate. Weak network or heat still lowers quality; Data Saver uses a smaller bitrate budget.","ভালো connection-এ Detail resolution-কে প্রাধান্য দেয়। Smooth frame rate-কে প্রাধান্য দেয়। দুর্বল network বা গরমে quality কমতে পারে; Data Saver কম bitrate ব্যবহার করে।","Detail अच्छे connection पर resolution को और Smooth frame rate को प्राथमिकता देता है। कमजोर network या गर्मी में quality घट सकती है।","Детали отдают приоритет разрешению при хорошем соединении, плавность — частоте кадров. При слабой сети или перегреве качество снижается."),13f))
        body.addView(label(tr("Resolution and frame rate","রেজোলিউশন ও ফ্রেমরেট","रिज़ॉल्यूशन और फ्रेम दर","Разрешение и частота кадров"),16f))
        body.addView(label(tr("60fps becomes available after the active codec is measured and its hardware supports that size/rate. Received fps may still be lower.","বর্তমান codec জানা গেলে এবং তার hardware ওই size/rate সমর্থন করলে 60fps পাওয়া যাবে। পাওয়া ভিডিওর fps তবুও কম হতে পারে।"),13f))
        VideoQualityPolicy.presets.forEach { preset ->
            body.addView(button(preset.label+if(preset !in calls.videoPresets) " · "+tr("Unavailable","সাপোর্ট নেই") else if(preset==calls.requestedVideoPreset) " · "+tr("Selected","বেছে নেওয়া") else "") {
                if(calls.id==token && visible && unlocked) owner.work { if(calls.id==token) calls.selectVideoPreset(preset) }; dialog.dismiss()
            }.apply { icon=MaterialYou.icon(this@MainActivity,"quality",purple); isEnabled=preset in calls.videoPresets })
        }
        dialog.secureShow()
    }
    private fun callTools() {
        if (!calls.active) return
        val labels = mutableListOf<String>(); val actions = mutableListOf<() -> Unit>()
        if (Build.VERSION.SDK_INT >= 26) { labels += tr("Picture-in-Picture", "ছোট Picture-in-Picture"); actions += ::privatePip }
        if (calls.video && !calls.screenSharing) {
            labels += if (owner.preferences.getBoolean("callLight", false)) tr("Turn screen light off", "স্ক্রিন লাইট বন্ধ করুন") else tr("Low-light screen light", "লো-লাইট স্ক্রিন লাইট")
            actions += ::toggleCallLight
            labels += tr("Adjust screen light width", "স্ক্রিন লাইটের প্রস্থ বদলান"); actions += ::lightWidth
        }
        if (calls.video && calls.status == "connected") {
            labels += if (calls.screenSharing) tr("Stop sharing screen", "স্ক্রিন শেয়ার বন্ধ করুন") else tr("Share my screen", "আমার স্ক্রিন শেয়ার করুন")
            actions += {
                if (calls.screenSharing) owner.work { calls.stopScreenShare() }
                else {
                    MaterialAlertDialogBuilder(this).setTitle(tr("Share your screen?", "স্ক্রিন শেয়ার করবেন?"))
                        .setMessage(tr("Android will show a capture notice. Everything visible on your screen may be seen by this verified contact until you stop sharing. Sensitive notifications may also appear.", "Android capture notice দেখাবে। বন্ধ না করা পর্যন্ত স্ক্রিনে দৃশ্যমান সবকিছু এই verified contact দেখতে পারেন; sensitive notification-ও দেখা যেতে পারে।"))
                        .setPositiveButton(tr("Continue", "এগিয়ে যান")) { _, _ ->
                            if (!visible || !unlocked || !calls.active || calls.status != "connected" || calls.screenSharing) return@setPositiveButton
                            projectionCall = calls.id
                            val manager = getSystemService(android.media.projection.MediaProjectionManager::class.java)
                            startActivityForResult((if(Build.VERSION.SDK_INT>=34) manager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent()), 51)
                        }.setNegativeButton(tr("Cancel", "বাতিল"), null).secureShow()
                }
            }
        }
        if(BuildConfig.REMOTE_CONTROL && calls.status=="connected") {
            if(calls.screenSharing) {
                labels+=tr("Remote control settings / grant","রিমোট কন্ট্রোল সেটিংস / অনুমতি দিন");actions+={ remoteSettings() }
                if((application as CutieApplication).assist.hostGranted) { labels+=tr("Stop control · keep sharing","কন্ট্রোল বন্ধ · শেয়ার চালু থাকবে");actions+={ (application as CutieApplication).assist.stopLocal() } }
            }
            val offer=(application as CutieApplication).assist.remoteEpoch
            if(offer.isNotEmpty()) { labels+=tr("Control their shared screen","সামনের ফোনের শেয়ার করা স্ক্রিন কন্ট্রোল");actions+={ openRemoteScreen(calls.id,offer) } }
        }
        MaterialAlertDialogBuilder(this).setTitle(tr("Call tools", "কল টুলস")).setItems(labels.toTypedArray()) { _, which -> actions[which]() }.secureShow()
    }
    private fun applyCallBrightness(enabled: Boolean) {
        if (preCallBrightness == null) preCallBrightness = window.attributes.screenBrightness
        val params = window.attributes
        params.screenBrightness = if (enabled) 1f else preCallBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = params
    }
    private fun restoreCallBrightness() {
        val previous = preCallBrightness ?: return
        val params = window.attributes; params.screenBrightness = previous; window.attributes = params
        preCallBrightness = null
    }
    override fun onConfigurationChanged(configuration: android.content.res.Configuration) {
        super.onConfigurationChanged(configuration); homeKey=""; callKey=""
        if(unlocked && visible && !isInPictureInPictureModeCompat()) update()
    }
    private fun mayPreviewSelf() = visible && unlocked && page == "call" && calls.active && calls.video && calls.status == "connected" && !calls.screenSharing && !calls.cameraOff && calls.localVideo != null && !isInPictureInPictureModeCompat()
    private fun bindCallVideo() {
        val large = remoteRenderer ?: return
        val small = localRenderer ?: return
        val expanded = selfPreview.expanded(calls.id, mayPreviewSelf(), SystemClock.elapsedRealtime())
        val largeTrack = if (expanded) calls.localVideo else calls.remoteVideo
        val smallTrack = if (expanded) calls.remoteVideo else calls.localVideo
        if (renderedRemote !== largeTrack) {
            try { renderedRemote?.removeSink(large) } catch (_: IllegalStateException) { }
            renderedRemote = null
            try { largeTrack?.addSink(large); renderedRemote = largeTrack } catch (_: IllegalStateException) { }
        }
        if (renderedLocal !== smallTrack) {
            try { renderedLocal?.removeSink(small) } catch (_: IllegalStateException) { }
            renderedLocal = null
            try { smallTrack?.addSink(small); renderedLocal = smallTrack } catch (_: IllegalStateException) { }
        }
        large.setMirror(expanded && !calls.screenSharing); small.setMirror(!expanded && !calls.screenSharing)
        small.contentDescription = if (expanded) tr("Other person's video. Tap to restore the main view.", "অপরজনের ভিডিও। বড় ভিউ ফিরিয়ে আনতে ট্যাপ করুন।") else tr("Your video. Tap to enlarge for ten seconds.", "আপনার ভিডিও। দশ সেকেন্ড বড় করতে ট্যাপ করুন।")
    }
    private fun resetSelfPreview() { handler.removeCallbacks(restoreSelfPreview); selfPreview.reset(); bindCallVideo() }
    private fun releaseRenderers() {
        localRenderer?.let { renderer -> try { renderedLocal?.removeSink(renderer) } catch (_: Exception) { }; renderer.release() }
        remoteRenderer?.let { renderer -> try { renderedRemote?.removeSink(renderer) } catch (_: Exception) { }; renderer.release() }
        localRenderer = null; remoteRenderer = null; renderedLocal = null; renderedRemote = null
    }
    private fun privatePip() {
        if (Build.VERSION.SDK_INT < 26 || !CallUiPolicy.mayEnterPip(calls.id, visible, unlocked)) return
        // Never shrink a home/chat/Settings window into PiP.
        if (page != "call") { callKey = ""; callScreen() }
        resetSelfPreview()
        restoreCallBrightness()
        setCallChrome(false, false)
        localRenderer?.visibility = View.GONE
        val source = Rect(); (remoteRenderer ?: window.decorView).getGlobalVisibleRect(source)
        val builder = PictureInPictureParams.Builder().setAspectRatio(if (calls.video) Rational(9, 16) else Rational(1, 1)).setSourceRectHint(source)
        if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(true)
        enterPictureInPictureMode(builder.build())
    }
    override fun onUserLeaveHint() {
        if (calls.active && visible && unlocked && owner.preferences.getBoolean("pip", true)) {
            if (Build.VERSION.SDK_INT >= 31 && page == "call") { resetSelfPreview(); setCallChrome(false, false); localRenderer?.visibility = View.GONE }
            else privatePip()
        }
        super.onUserLeaveHint()
    }
    override fun onPictureInPictureModeChanged(inPip: Boolean, configuration: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(inPip, configuration)
        if (inPip) { resetSelfPreview(); handler.removeCallbacks(hideCallChrome); setCallChrome(false, false); localRenderer?.visibility = View.GONE; restoreCallBrightness() }
        else { localRenderer?.visibility = View.VISIBLE; callKey = ""; unlocked = false; authAttempted=false; owner.viewAccess(false); lock() }
    }
    private fun isInPictureInPictureModeCompat() = Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode
    private fun update() {
        if(calls.failureRevision != shownCallFailure && calls.lastFailure.isNotEmpty()) {
            shownCallFailure=calls.failureRevision;toast(tr("Call could not connect: ","কল সংযুক্ত হয়নি: ")+calls.lastFailure)
        }
        val viewed=viewingAttachment
        if(viewed != null && viewerDeletionRevision != owner.deletionRevision) {
            viewerDeletionRevision = owner.deletionRevision
            owner.checkMediaDeleted(viewed.first,viewed.second) { deleted ->
                if(deleted && viewingAttachment == viewed) { mediaGeneration++; mediaDialog?.dismiss(); mediaCache?.delete(); mediaCache=null; finishMediaViewer() }
            }
        }
        if (!unlocked || !visible || isInPictureInPictureModeCompat()) return
        if(owner.ready) { consumeProjection(); consumeProfileSelection(); drainPendingDialogs() }
        if (owner.ready && pendingMedia != null && !calls.active) { consumePickedMedia(); return }
        owner.captureNotice?.let { notice -> if (captureSequence != notice.first) {
            captureSequence = notice.first
            if (peer == notice.second || calls.active && calls.peer == notice.second) {
                val name = owner.contacts.find { it.uid == notice.second }?.name ?: "Contact"
                com.google.android.material.snackbar.Snackbar.make(window.decorView, "$name · " + tr("Screenshot detected", "স্ক্রিনশট শনাক্ত হয়েছে"), com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
            }
        } }
        if (calls.active && (page == "call" || calls.id != shownCall)) { consumeCall(); callScreen(); return }
        if (page == "call") { home(); return }
        val pair = pendingPair; if (pair != null && pair in owner.connected) { celebration(pair); return }
        when (page) { "home" -> home(); "chat" -> updateChat(); "calls", "gallery" -> updateLibrary(); "contacts" -> if (owner.ready && owner.code.isNotEmpty() && !qrPageReady) contacts(); "people" -> renderContactHub(); "sharing" -> updateSharing() }
    }
    private fun showPrivateDialog(dialog:Dialog) {
        if(isFinishing || isDestroyed) { dialog.dismiss(); return }
        if(!visible || (!unlocked && owner.preferences.getBoolean("setup",false))) {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            while(pendingDialogs.size >= 8) pendingDialogs.removeFirst().dismiss()
            pendingDialogs.add(dialog); return
        }
        privateDialogs.removeAll { !it.isShowing }
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show(); dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        privateDialogs.add(dialog)
    }
    private fun drainPendingDialogs() {
        if(!visible || !unlocked || isFinishing || isDestroyed) return
        while(pendingDialogs.isNotEmpty()) showPrivateDialog(pendingDialogs.removeFirst())
    }
    internal fun MaterialAlertDialogBuilder.secureShow():androidx.appcompat.app.AlertDialog = create().also(::showPrivateDialog)
    private fun Dialog.secureShow() = showPrivateDialog(this)
    internal fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    private fun canRecordVoice(to: String) = visible && unlocked && page == "chat" && peer == to && owner.ready && !calls.active
    private fun handleVoiceAction(to: String, action: VoiceMessageGesture.Action) {
        when (action) {
            VoiceMessageGesture.Action.START_HOLD, VoiceMessageGesture.Action.START_TAP -> startVoiceRecording(to)
            VoiceMessageGesture.Action.SEND -> finishVoiceRecording()
            VoiceMessageGesture.Action.CANCEL -> cancelRecording()
            else -> Unit
        }
    }
    private fun startVoiceRecording(to: String) {
        if(owner.transports[to]=="bluetooth") { cancelRecording();toast(tr("Bluetooth supports live speech calls; use Wi-Fi for voice messages","Bluetooth-এ live audio call চলে; voice message-এ Wi-Fi ব্যবহার করুন"));return }
        voiceStartFailure = ""
        if (!canRecordVoice(to) || recorder != null) { voiceStartFailure = "foreground_or_call_guard"; cancelRecording(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            voiceStartFailure = "permission_required"
            cancelRecording(); requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 13); return
        }
        try {
            recordingFile = java.io.File.createTempFile("native-voice-", ".m4a", cacheDir); recordingPeer = to
            // Assign before prepare/start so every partial failure releases MIC.
            recorder = if (Build.VERSION.SDK_INT >= 31) android.media.MediaRecorder(this) else android.media.MediaRecorder()
            recorder!!.apply {
                setAudioSource(android.media.MediaRecorder.AudioSource.MIC); setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC); setAudioEncodingBitRate(64000); setAudioSamplingRate(44100)
                setMaxDuration(60000); setOutputFile(recordingFile!!.path)
                setOnErrorListener { _, _, _ -> cancelRecording(); toast(tr("Recording failed; nothing sent", "রেকর্ড হয়নি; কিছু পাঠানো হয়নি")) }
                setOnInfoListener { _, what, _ -> if (what == android.media.MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) { cancelRecording(); toast(tr("One-minute limit reached; recording cancelled", "এক মিনিট হয়েছে; রেকর্ড বাতিল")) } }
                prepare(); start()
            }
            recordingStarted = SystemClock.elapsedRealtime()
            recordingDisabled.forEach { it.isEnabled = false }; voiceRow?.visibility = View.VISIBLE
            voiceButton?.isSelected = true
            voiceButton?.icon = MaterialYou.icon(this, if (voiceGesture.holding) "mic" else "send", purple)
            voiceButton?.contentDescription = tr("Recording. Release to send or slide left to cancel. In tap mode, tap again to send.", "রেকর্ড হচ্ছে। ছেড়ে পাঠান বা বাঁয়ে টেনে বাতিল। ট্যাপ মোডে আবার ট্যাপ করলে পাঠাবে।")
            voiceButton?.announceForAccessibility(tr("Recording started", "রেকর্ড শুরু হয়েছে"))
            handler.post(voiceTick)
        } catch (failure: Exception) { voiceStartFailure = failure.javaClass.simpleName; cancelRecording(); toast(tr("Cannot start microphone", "মাইক চালু করা যায়নি")) }
    }
    private fun finishVoiceRecording() {
        val file = recordingFile ?: return
        val recipient = recordingPeer ?: run { cancelRecording(); return }
        if (!canRecordVoice(recipient) || SystemClock.elapsedRealtime() - recordingStarted < 500) {
            cancelRecording(); toast(tr("Recording too short; nothing sent", "রেকর্ড খুব ছোট; কিছু পাঠানো হয়নি")); return
        }
        try { recorder!!.stop() } catch (_: Exception) { cancelRecording(); toast(tr("Recording failed; nothing sent", "রেকর্ড হয়নি; কিছু পাঠানো হয়নি")); return }
        try { recorder?.release() } catch (_: Exception) { }
        recorder = null; recordingFile = null; recordingPeer = null
        resetVoiceUi()
        // The deliberate send is app-owned; Activity destruction cannot abandon
        // its temporary file. No audio capture continues while this is queued.
        owner.work {
            var bytes: ByteArray? = null
            try {
                check(file.length() in 1..ProtocolRules.MAX_ATTACHMENT.toLong())
                bytes = file.readBytes()
                owner.sendAttachment(recipient, "voice.m4a", "audio/mp4", bytes)
                bytes = null
                handler.post { if (!isDestroyed) toast(tr("Voice message queued", "ভয়েস মেসেজ পাঠানোর জন্য প্রস্তুত")) }
            } finally { bytes?.fill(0); file.delete() }
        }
    }
    private fun resetVoiceUi() {
        handler.removeCallbacks(armVoiceHold); handler.removeCallbacks(voiceTick); voiceGesture.cancel()
        recordingStarted = 0L; voiceRow?.visibility = View.GONE; voiceButton?.isSelected = false
        voiceButton?.icon = MaterialYou.icon(this, "mic", purple)
        voiceButton?.contentDescription = tr("Voice message: hold to record, release to send; tap to start and tap again to send", "ভয়েস: ধরে রেকর্ড, ছেড়ে পাঠান; ট্যাপ করে শুরু, আবার ট্যাপে পাঠান")
        recordingDisabled.forEach { it.isEnabled = true }
        if (!calls.active) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun cancelRecording() {
        resetVoiceUi()
        try { recorder?.stop() } catch (_: Exception) { }
        try { recorder?.release() } catch (_: Exception) { }
        recorder = null; recordingFile?.delete(); recordingFile = null; recordingPeer = null
    }
    private fun startContactCall(id: String, video: Boolean) {
        if(video && owner.transports[id]=="bluetooth") { toast(tr("Use Wi-Fi for video calls","ভিডিও কলে Wi-Fi ব্যবহার করুন"));return }
        peer = id
        permission(false, video) { owner.work { calls.start(id, video) } }
    }
    private fun pickAttachment(target: String, choice: Int) {
        val quick=quickPickerArmed;quickPickerArmed=false
        if (!unlocked || !visible || calls.active || recorder != null) return
        if(batchSending || readingSelection || attachmentDraft!=null || pickedQueue.isNotEmpty()) { toast(tr("Finish or cancel this selection first","আগের নির্বাচন শেষ বা বাতিল করুন"));return }
        quickSelection=quick;quickBatchConfirmed=false
        if(!sharingPickerArmed) sharingRecipients=emptyList()
        sharingPickerArmed=false
        pickingPeer = target
        if(owner.transports[target]=="bluetooth" && choice in setOf(2,3)) { pickingPeer=null;toast(tr("Bluetooth: photos and chat only; use Wi-Fi for files","Bluetooth-এ ছবি ও chat; ফাইলে Wi-Fi ব্যবহার করুন"));return }
        when (choice) {
            0 -> if(owner.transports[target]=="bluetooth") mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) else multipleMediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            1 -> if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera() else requestPermissions(arrayOf(Manifest.permission.CAMERA), 14)
            else -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(if (choice == 3) "audio/*" else "*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true), 21)
        }
    }
    private fun openCamera() {
        if (visible && unlocked && pickingPeer != null && !calls.active) startActivityForResult(Intent(this, ChatCameraActivity::class.java).putExtra("photoOnly",owner.transports[pickingPeer]=="bluetooth"), 23)
    }
    private fun queuePicked(uris:List<android.net.Uri>,target:String,mediaOnly:Boolean,captured:Boolean) {
        pickedQueue.clear()
        val count=if(owner.transports[target]=="bluetooth") 1 else LocalSharingRules.MAX_SELECTION
        uris.distinct().take(count).forEach { pickedQueue.add(PickedMedia(it,target,mediaOnly,captured)) }
        if(uris.size>count) toast(tr("Selection limited to ","নির্বাচন সীমা ")+count)
        nextPicked()
    }
    private fun nextPicked() {
        if(pendingMedia!=null || readingSelection || batchSending || attachmentDraft!=null) return
        if(pickedQueue.isEmpty()) { sharingRecipients=emptyList();return }
        pendingMedia=pickedQueue.removeFirst();consumePickedMedia()
    }
    private fun consumePickedMedia() {
        if (!visible || !unlocked || calls.active || !owner.ready) return
        val item = pendingMedia ?: return
        pendingMedia = null
        if (owner.contacts.none { it.uid == item.peer }) return
        if(!quickSelection && (page != "chat" || peer != item.peer)) openChat(item.peer)
        readPickedAttachment(item.uri, item.peer, item.mediaOnly, item.captured)
    }
    private fun afterUnlock() {
        if(getSystemService(KeyguardManager::class.java).isDeviceLocked) { viewSession.invalidate();unlocked=false;lock();return }
        viewSession.authenticated()
        owner.preferences.edit().putBoolean("lock",true).remove("pinHash").remove("pinSalt").apply()
        owner.viewAccess(visible && unlocked,if(page == "chat") peer else null)
        if (!visible) { viewSession.leave(SystemClock.elapsedRealtime());authPendingRoute=true; return }
        authPendingRoute=false
        owner.enter()
        if(page=="lock") restorePage()
        pendingQuickFolder?.let { folder -> pendingQuickFolder=null;runCatching { owner.quickShare.enable(folder) }.onFailure { toast(tr("Choose the save folder again","Save folder আবার বেছে নিন")) } }
        consumeFileSave()
        val deferred=resumedPermission; resumedPermission=null; deferred?.invoke()
        consumeProjection(); consumeProfileSelection()
        consumeCall()
        if (calls.active) callScreen() else if (pendingMedia != null) consumePickedMedia() else if(contactsHubRequested) { contactsHubRequested=false; phoneContacts() } else if(sharingRouteRequested) { sharingRouteRequested=false;sharingPage() } else if(page=="lock") restorePage()
        drainPendingDialogs();appUpdates.foreground();nearbySetup.foreground()
        handler.postDelayed({ if(visible && unlocked && page in setOf("home","people")) {
            if(!owner.preferences.getBoolean("backgroundSetupSeen",false)) { owner.preferences.edit().putBoolean("backgroundSetupSeen",true).apply();backgroundSetupGuide() }
            else askContactsPermission()
        } },500)
    }
    private fun readPickedAttachment(uri: android.net.Uri, target: String, mediaOnly: Boolean, captured: Boolean = false) {
        readingSelection=true
        mediaIo.execute {
            var bytes: ByteArray? = null
            var capturedStream=false
            try {
                val size=contentResolver.query(uri,arrayOf(OpenableColumns.SIZE),null,null,null)?.use { if(it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L } ?: -1L
                val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else "file" } ?: "file"
                val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                val media = mime.startsWith("image/") || mime.startsWith("video/"); check(!mediaOnly || media)
                if(owner.transports[target]=="bluetooth") check(mime.startsWith("image/"))
                val quickMode=quickSelection
                if(size>ProtocolRules.MAX_ATTACHMENT || quickMode) {
                    require(size>0) { "Choose a file with a known size for streaming" }
                    check(owner.transports[target]=="wifi" && size<=LargeFileRules.MAX_SIZE)
                    capturedStream=captured
                    handler.post {
                        readingSelection=false
                        if(!unlocked || !visible || calls.active || (!quickSelection && peer!=target)) { pickedQueue.clear();if(captured) runCatching { contentResolver.delete(uri,null,null) };return@post }
                        fun startTransfer() {
                                batchSending=true
                                val recipients=java.util.ArrayDeque(sharingRecipients.ifEmpty { listOf(target) })
                                fun next() {
                                    if(recipients.isEmpty()) { batchSending=false;sharingRecipients=emptyList();if(captured) runCatching { contentResolver.delete(uri,null,null) };nextPicked();return }
                                    if(!visible || !unlocked || isDestroyed || calls.active) { batchSending=false;pickedQueue.clear();if(captured) runCatching { contentResolver.delete(uri,null,null) };return }
                                    val contact=recipients.removeFirst()
                                    owner.sendLargeFile(contact,name.take(120),mime,size,quick=quickMode,open={ contentResolver.openInputStream(uri) ?: error("Source unavailable") }) { ok -> handler.post {
                                        if(ok) next() else { batchSending=false;pickedQueue.clear();sharingRecipients=emptyList();if(captured) runCatching { contentResolver.delete(uri,null,null) };if(!isDestroyed) toast(tr("Transfer stopped. Check Sharing and free storage; select the file to retry.","Transfer থেমেছে। Sharing ও খালি storage দেখুন; retry করতে file আবার select করুন।")) }
                                    } }
                                }
                                next()
                        }
                        if(quickMode && quickBatchConfirmed) startTransfer()
                        else MaterialAlertDialogBuilder(this).setTitle(if(quickMode) "Quick Share" else tr("Send large encrypted file?","বড় encrypted file পাঠাবেন?"))
                            .setMessage(name.take(120)+" · "+android.text.format.Formatter.formatFileSize(this,size)+"\n"+tr("Original bytes, no recompression. Quick Share files save outside the recipient's app when receiving is enabled. Regular large files require receiver approval and private storage.","মূল bytes, recompression হবে না। গ্রহণ চালু থাকলে Quick Share ফাইল প্রাপকের অ্যাপের বাইরে সেভ হবে। সাধারণ বড় ফাইলে প্রাপকের অনুমতি ও private storage লাগবে।"))
                            .setPositiveButton(tr("Send","পাঠান")) { _,_-> startTransfer() }
                            .setNegativeButton(tr("Cancel","বাতিল")) { _,_->pickedQueue.clear();sharingRecipients=emptyList();if(captured) runCatching { contentResolver.delete(uri,null,null) } }.secureShow()
                    }
                    return@execute
                }
                val buffer = ByteArrayOutputStream()
                contentResolver.openInputStream(uri)!!.use { stream -> val chunk = ByteArray(12000); try {
                    while (true) { check(!Thread.currentThread().isInterrupted); val n = stream.read(chunk); if (n < 0) break; check(buffer.size() + n <= if(owner.transports[target]=="bluetooth") LocalSharingRules.BLUETOOTH_PHOTO_LIMIT else ProtocolRules.MAX_ATTACHMENT); buffer.write(chunk, 0, n) }
                } finally { chunk.fill(0) } }
                bytes = buffer.toByteArray(); check(bytes.isNotEmpty()); val selected = bytes; bytes = null
                handler.post {
                    readingSelection=false
                    if (!unlocked || !visible || peer != target || calls.active) { selected.fill(0);pickedQueue.clear(); return@post }
                    if (!media) { batchSending=true;sendChosen(target,name.take(120),mime,selected,completed={ ok -> batchSending=false;if(ok) nextPicked() else pickedQueue.clear() }); return@post }
                    attachmentDraft?.fill(0); draftDialog?.dismiss(); attachmentDraft = selected
                    var limit = 0
                    // AlertDialog's message panel hides its single-choice list.
                    // Use one explicit, scrollable view so all choices are visible.
                    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(8)) }
                    content.addView(label(name.take(120), 13f).apply { maxLines = 2; setTextColor(subtle) })
                    val choices = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL; tag = "media-view-choices" }
                    val notes = label("", 13f).apply { setTextColor(subtle); setPadding(0, dp(12), 0, 0) }
                    listOf(tr("Normal · can be saved", "সাধারণ · সেভ করা যাবে"), tr("View once", "একবার দেখা"), tr("View twice", "দুইবার দেখা")).forEachIndexed { index, title ->
                        choices.addView(com.google.android.material.radiobutton.MaterialRadioButton(this).apply {
                            id = View.generateViewId(); tag = "media-view-limit-$index"; text = title; minimumHeight = dp(48)
                            setTextColor(ink); setOnCheckedChangeListener { _, checked -> if (checked) {
                                limit = index
                                notes.text = if (index == 0) tr("Your contact can keep or save this media.", "প্রাপক এই মিডিয়া রাখতে বা সেভ করতে পারবেন।")
                                else if (mime.startsWith("image/")) tr("App only. Photo expires 30 seconds after first opening. Screenshot protection depends on Android.", "শুধু অ্যাপে দেখা যাবে। প্রথমবার খোলার ৩০ সেকেন্ড পরে ছবি মুছে যাবে। স্ক্রিনশট সুরক্ষা Android-এর উপর নির্ভর করে।")
                                else tr("App only. Video closes after playback; no photo timer. Screenshot protection depends on Android.", "শুধু অ্যাপে দেখা যাবে। ভিডিও শেষ হলে বন্ধ হবে; ছবির টাইমার নেই। স্ক্রিনশট সুরক্ষা Android-এর উপর নির্ভর করে।")
                            } }
                        }, RadioGroup.LayoutParams(-1, -2))
                    }
                    choices.check(choices.getChildAt(0).id); content.addView(choices); content.addView(notes)
                    val sendTitle=(if(mime.startsWith("image/")) tr("Send photo","ছবি পাঠান") else tr("Send video","ভিডিও পাঠান"))+
                        if(sharingRecipients.isNotEmpty()) " · "+sharingRecipients.size+tr(" people"," জন") else ""
                    draftDialog = MaterialAlertDialogBuilder(this).setTitle(sendTitle)
                        .setView(ScrollView(this).apply { addView(content) })
                        .setPositiveButton(tr("Send", "পাঠান")) { _, _ ->
                            if (attachmentDraft === selected && unlocked && visible && peer == target) { attachmentDraft = null; batchSending=true;sendChosen(target,name.take(120),mime,selected,limit) { ok -> batchSending=false;if(ok) nextPicked() else pickedQueue.clear() } }
                        }.setNegativeButton(tr("Cancel", "বাতিল"), null).create().apply {
                            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                            setOnDismissListener { if (attachmentDraft === selected) { selected.fill(0); attachmentDraft = null;pickedQueue.clear() }; draftDialog = null; if(attachmentDraft==null) handler.post { nextPicked() } }; show()
                        }
                }
            } catch (_: Exception) { handler.post { readingSelection=false;pickedQueue.clear();toast(tr("Could not read/send this file · check source access, size and Wi-Fi connection", "ফাইল পড়া/পাঠানো যায়নি · source permission, size ও Wi-Fi connection দেখুন")) } }
            finally { bytes?.fill(0); if (captured && !capturedStream) try { contentResolver.delete(uri, null, null) } catch (_: Exception) { } }
        }
    }
    private fun saveGallery(from: String, id: String, mime: String, name: String) {
        if(id in owner.largeIds) {
            val uri=NativeMediaFiles.beginGallery(this,name,mime)
            owner.exportFile(from,id,{ contentResolver.openOutputStream(uri,"w") ?: error("Gallery unavailable") }) { ok ->
                val saved=runCatching { NativeMediaFiles.finishGallery(this,uri,ok);ok }.getOrElse { runCatching { NativeMediaFiles.finishGallery(this,uri,false) };false };handler.post { toast(if(saved) tr("Saved to gallery","গ্যালারিতে সেভ হয়েছে") else tr("Could not save file","ফাইল সেভ হয়নি")) }
            };return
        }
        owner.exportAttachment(from, id, onFailure = { handler.post { if(visible && unlocked) toast(tr("Could not save this media", "মিডিয়াটি সেভ করা যায়নি")) } }) { bytes ->
            try { NativeMediaFiles.saveToGallery(this, name, mime, bytes); handler.post { toast(tr("Saved to gallery", "গ্যালারিতে সেভ হয়েছে")) } } finally { bytes.fill(0) }
        }
    }
    private fun savePrivateGalleryMedia(media: PrivateLibrary.Media) {
        if(!visible || !unlocked) return
        MaterialAlertDialogBuilder(this).setTitle(tr("Save a gallery copy?","গ্যালারিতে কপি সেভ করবেন?","गैलरी में कॉपी सहेजें?","Сохранить копию в галерею?"))
            .setMessage(tr("This copy will be outside Cute Chat's encrypted storage and may sync through your gallery app. Limited-view media cannot be saved.","এই কপি Cute Chat-এর এনক্রিপ্টেড স্টোরেজের বাইরে থাকবে এবং গ্যালারি অ্যাপের মাধ্যমে sync হতে পারে। Limited-view মিডিয়া সেভ করা যায় না।","यह कॉपी Cute Chat के एन्क्रिप्टेड स्टोरेज से बाहर रहेगी और गैलरी ऐप से सिंक हो सकती है। सीमित-व्यू मीडिया सहेजा नहीं जा सकता।","Копия будет вне зашифрованного хранилища Cute Chat и может синхронизироваться галереей. Медиа с ограниченным просмотром сохранить нельзя."))
            .setPositiveButton(tr("Save","সেভ","सहेजें","Сохранить")) { _,_ ->
                if(!visible || !unlocked) return@setPositiveButton
                if(Build.VERSION.SDK_INT >= 29) saveGallery(media.peer,media.id,media.mime,media.name)
                else {
                    mediaDialog?.dismiss()
                    savingAttachment = media.peer to media.id
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(media.mime).putExtra(Intent.EXTRA_TITLE,media.name),22)
                }
            }.setNegativeButton(tr("Cancel","বাতিল"),null).secureShow()
    }
    private fun openPrivateDocument(media: PrivateLibrary.Media) {
        if(media.id in owner.largeIds) {
            val file=java.io.File.createTempFile("native-view-document-",if(media.mime=="application/pdf") ".pdf" else ".txt",cacheDir)
            owner.exportFile(media.peer,media.id,{ java.io.FileOutputStream(file) }) { ok -> handler.post {
                if(!ok || !visible || !unlocked) { file.delete();return@post }
                startActivity(Intent(this,PrivateDocumentActivity::class.java).setType(media.mime).putExtra("file",file.path).putExtra("name",media.name).putExtra("peer",media.peer).putExtra("id",media.id))
            } };return
        }
        owner.exportAttachment(media.peer, media.id) { bytes ->
            val file = java.io.File.createTempFile("native-view-document-", if(media.mime == "application/pdf") ".pdf" else ".txt", cacheDir)
            try { file.writeBytes(bytes) } catch (failure: Exception) { file.delete(); throw failure } finally { bytes.fill(0) }
            handler.post {
                if(!visible || !unlocked) { file.delete(); return@post }
                startActivity(Intent(this,PrivateDocumentActivity::class.java).setType(media.mime).putExtra("file",file.path).putExtra("name",media.name).putExtra("peer",media.peer).putExtra("id",media.id))
            }
        }
    }
    private fun openExternalFile(from: String, id: String, mime: String, name: String) {
        MaterialAlertDialogBuilder(this).setMessage(tr("Another app will receive an unencrypted temporary copy to open this file. Continue?", "ফাইল খুলতে অন্য অ্যাপ একটি সাময়িক decrypted কপি পাবে। এগোবেন?"))
            .setPositiveButton(tr("Open", "খুলুন")) { _, _ -> owner.exportFile(from,id,{
                val directory=java.io.File(cacheDir,"native-external").apply { mkdirs() }
                externalCache?.delete();externalCache=java.io.File.createTempFile("view-", "."+name.substringAfterLast('.',"bin").replace(Regex("[^a-zA-Z0-9]"),"").take(10),directory)
                java.io.FileOutputStream(externalCache!!)
            }) { ok ->
                val file=externalCache ?: return@exportFile
                if(!ok) { file.delete();return@exportFile }
                handler.post {
                    if (!visible || !unlocked) { file.delete(); return@post }
                    val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.media", file)
                    try { externalCache = file; startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                    catch (_: ActivityNotFoundException) { file.delete(); toast(tr("No installed app supports this format. Use Save file.", "এই format খুলতে অ্যাপ নেই। Save file ব্যবহার করুন।")) }
                }
            } }.setNegativeButton(tr("Cancel", "বাতিল"), null).secureShow()
    }
    private fun viewLargeMedia(from:String,id:String,mime:String) {
        if(calls.active || recorder!=null || !unlocked || !visible || mediaDialog!=null || mediaOpening) return
        val entry=owner.mediaLibrary.find { it.id==id && it.peer==from } ?: return
        if(StatFs(cacheDir.path).availableBytes<entry.size+LargeFileRules.RESERVE) { toast(tr("Not enough space for a temporary preview; use Save file instead.","সাময়িক preview-র জন্য storage কম; Save file ব্যবহার করুন।"));return }
        mediaOpening=true;val generation=mediaGeneration;viewingAttachment=from to id
        val file=java.io.File.createTempFile("native-view-",".media",cacheDir)
        owner.exportFile(from,id,{ java.io.FileOutputStream(file) }) { ok -> handler.post {
            if(!ok || !visible || !unlocked || generation!=mediaGeneration) { file.delete();if(generation==mediaGeneration) finishMediaViewer();return@post }
            mediaOpening=false;mediaCache?.delete();mediaCache=file
            lateinit var dialog:Dialog
            val frame=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.BLACK) }
            var bitmap:Bitmap?=null;var player:VideoView?=null
            if(mime.startsWith("image/")) {
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeFile(file.path,bounds)
                var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>2048) sample*=2
                bitmap=BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply { inSampleSize=sample })
                frame.addView(ImageView(this).apply { setImageBitmap(bitmap);adjustViewBounds=true },LinearLayout.LayoutParams(-1,0,1f))
            } else {
                player=VideoView(this).apply { setVideoPath(file.path);setMediaController(MediaController(this@MainActivity).also { it.setAnchorView(this) });setOnErrorListener { _,_,_->dialog.dismiss();toast(tr("Unsupported media format","অসমর্থিত media format"));true } }
                frame.addView(player,LinearLayout.LayoutParams(-1,0,1f))
            }
            val controls=LinearLayout(this)
            controls.addView(button(tr("Close","বন্ধ করুন")) { dialog.dismiss() },LinearLayout.LayoutParams(0,dp(56),1f))
            if(GalleryTimeline.visual(mime)) controls.addView(button(tr("Save to gallery","গ্যালারিতে সেভ")) { savePrivateGalleryMedia(entry) },LinearLayout.LayoutParams(0,dp(56),1f))
            frame.addView(controls)
            dialog=Dialog(this).apply { setContentView(frame);window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            dialog.setOnDismissListener { player?.stopPlayback();bitmap?.recycle();file.delete();if(mediaCache===file) mediaCache=null;finishMediaViewer() }
            mediaDialog=dialog;dialog.secureShow();dialog.window?.setLayout(-1,-1);player?.start()
        } }
    }
    private fun viewMedia(from: String, id: String, mime: String, limited: Boolean = false) {
        if(id in owner.largeIds) { viewLargeMedia(from,id,mime);return }
        if (calls.active || recorder != null || !unlocked || !visible || mediaDialog != null || mediaOpening) return
        mediaOpening = true; val generation = mediaGeneration
        // Library membership excludes limited/profile/incomplete transfers. The
        // engine independently rechecks export eligibility at the actual read.
        val savable = if(!limited) owner.mediaLibrary.find { it.peer == from && it.id == id && GalleryTimeline.visual(it.mime) } else null
        viewingAttachment = from to id; viewerDeletionRevision = -1L
        if (limited) { limitedViewer = from to id; applyCapturePolicy() }
        owner.openMedia(from, id, onFailure = { handler.post { if (generation == mediaGeneration) { finishMediaViewer(); toast(tr("Media is unavailable or cannot be opened", "মিডিয়া পাওয়া যাচ্ছে না অথবা খোলা যায়নি")) } } }) { bytes ->
            if (mime.startsWith("image/")) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0)
                var sample = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }); bytes.fill(0)
                handler.post { if (unlocked && visible && generation == mediaGeneration) {
                    mediaOpening = false
                    val builder = MaterialAlertDialogBuilder(this).setView(ImageView(this).apply { setImageBitmap(bitmap); adjustViewBounds = true }).setPositiveButton(tr("Close","বন্ধ করুন"), null)
                    if(savable != null) builder.setNeutralButton(tr("Save to gallery","গ্যালারিতে সেভ","गैलरी में सहेजें","Сохранить в галерею")) { _,_ -> savePrivateGalleryMedia(savable) }
                    mediaDialog = builder.create().apply {
                        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE); setOnDismissListener { bitmap?.recycle(); finishMediaViewer() }; show()
                    }
                    if (limited) {
                        val deadline = owner.messages.find { it.id == id && it.peer == from }?.data?.optLong("viewDeadline", 0) ?: 0
                        val remaining = if (deadline > 0) (deadline - System.currentTimeMillis()).coerceIn(0, 30000) else 30000L
                        val timer = Runnable { owner.expireMedia(from, id); mediaDialog?.dismiss() }; limitedTimer = timer; handler.postDelayed(timer, remaining)
                    }
                } else { bitmap?.recycle(); if (generation == mediaGeneration) finishMediaViewer() } }
            } else if(limited || owner.messages.any { it.peer == from && it.id == id && it.data.optInt("viewLimit") > 0 }) {
                val memory = bytes.copyOf()
                handler.post {
                    if(!visible || !unlocked || generation != mediaGeneration) { memory.fill(0); if(generation == mediaGeneration) finishMediaViewer(); return@post }
                    mediaOpening = false
                    lateinit var dialog: Dialog
                    val player = LimitedVideoView(this,memory,{ dialog.dismiss() },{
                        dialog.dismiss(); toast(tr("This device cannot play this format","এই ফোনে format-টি চালানো যায় না"))
                    })
                    val frame = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
                    frame.addView(player,LinearLayout.LayoutParams(-1,0,1f))
                    frame.addView(button(tr("Close","বন্ধ করুন")) { dialog.dismiss() },LinearLayout.LayoutParams(-1,dp(56)))
                    dialog = Dialog(this).apply { setContentView(frame); window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
                    dialog.setOnDismissListener { player.close(); finishMediaViewer() }
                    mediaDialog = dialog; dialog.secureShow(); dialog.window?.setLayout(-1,-1)
                }
            } else {
                val file = java.io.File.createTempFile("native-view-", ".media", cacheDir)
                try { file.writeBytes(bytes) } finally { bytes.fill(0) }
                handler.post {
                    if (!unlocked || !visible || generation != mediaGeneration) { file.delete(); if (generation == mediaGeneration) finishMediaViewer(); return@post }
                    mediaOpening = false
                    mediaCache?.delete(); mediaCache = file
                    val player = VideoView(this).apply {
                        setVideoPath(file.path)
                        if (!limited) setMediaController(MediaController(this@MainActivity).also { it.setAnchorView(this) })
                    }
                    val frame = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
                    frame.addView(player,LinearLayout.LayoutParams(-1,0,1f))
                    val controls = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(12),dp(8),dp(12),dp(16)) }
                    lateinit var dialog: Dialog
                    controls.addView(button(tr("Close","বন্ধ করুন")) { dialog.dismiss() },LinearLayout.LayoutParams(0,-2,1f))
                    if(savable != null) controls.addView(button(tr("Save to gallery","গ্যালারিতে সেভ","गैलरी में सहेजें","Сохранить в галерею")) { savePrivateGalleryMedia(savable) },LinearLayout.LayoutParams(0,-2,1f))
                    frame.addView(controls)
                    dialog = Dialog(this).apply { setContentView(frame); window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
                    dialog.setOnDismissListener { player.stopPlayback(); file.delete(); if (mediaCache === file) mediaCache = null; finishMediaViewer() }
                    mediaDialog = dialog; dialog.secureShow(); dialog.window?.setLayout(-1,-1); player.start()
                    if (limited) player.setOnCompletionListener { dialog.dismiss() }
                    player.setOnErrorListener { _, _, _ -> dialog.dismiss(); toast(tr("This device cannot play this format", "এই ফোনে format-টি চালানো যায় না")); true }
                }
            }
        }
    }
    private fun finishMediaViewer() {
        limitedTimer?.let { handler.removeCallbacks(it) }; limitedTimer = null
        limitedViewer = null; viewingAttachment=null; mediaDialog = null; mediaOpening = false; applyCapturePolicy()
    }
    private fun lock() {
        rememberPage()
        page="lock"; window.addFlags(WindowManager.LayoutParams.FLAG_SECURE); owner.viewAccess(false)
        val layout=base("Cute Chat")
        val secure=getSystemService(KeyguardManager::class.java).isDeviceSecure
        layout.addView(PrivateUnlockView(this,logo(116),
            tr("Your encrypted space", "আপনার এনক্রিপ্টেড জগৎ", "आपकी एन्क्रिप्टेड जगह", "Ваше защищённое пространство"),
            tr("Unlock with your phone's fingerprint, face or password. Android verifies you locally; Cute Chat never receives your biometric data or device password.","ফোনের ফিঙ্গারপ্রিন্ট, মুখ বা পাসওয়ার্ড দিয়ে আনলক করুন। Android ফোনেই যাচাই করে; Cute Chat আপনার বায়োমেট্রিক তথ্য বা ফোনের পাসওয়ার্ড পায় না।","फ़ोन के फ़िंगरप्रिंट, चेहरे या पासवर्ड से खोलें। Android स्थानीय रूप से जाँचता है; Cute Chat को बायोमेट्रिक डेटा या फ़ोन का पासवर्ड नहीं मिलता।","Используйте отпечаток, лицо или пароль телефона. Android проверяет их локально; Cute Chat не получает биометрические данные или пароль."),
            if(secure) tr("Unlock with phone security","ফোনের security দিয়ে আনলক","फ़ोन की सुरक्षा से खोलें","Открыть через защиту телефона") else tr("Set a phone screen lock","ফোনে screen lock সেট করুন","फ़ोन पर स्क्रीन लॉक लगाएँ","Установить блокировку телефона"),
            if(secure) tr("Notifications arrive without unlocking. Opening private app screens requires verification; background connections keep their own settings.","আনলক ছাড়াই নোটিফিকেশন আসবে। অ্যাপের ব্যক্তিগত অংশ খুলতে যাচাই লাগবে; ব্যাকগ্রাউন্ড সংযোগ তার নিজস্ব সেটিং অনুযায়ী চলবে।","सूचनाएँ बिना ऐप खोले आती हैं। निजी स्क्रीन खोलने के लिए सत्यापन ज़रूरी है; बैकग्राउंड कनेक्शन अपनी सेटिंग के अनुसार चलते हैं।","Уведомления приходят без разблокировки. Для личных экранов нужна проверка; фоновые соединения используют свои настройки.")
            else tr("Set a device PIN or password first. Your private screens stay locked until Android can verify you.","আগে ফোনে PIN বা পাসওয়ার্ড দিন। Android যাচাই না করা পর্যন্ত ব্যক্তিগত স্ক্রিন লক থাকবে।","पहले फ़ोन का PIN या पासवर्ड सेट करें। Android सत्यापन तक निजी स्क्रीन लॉक रहेंगी।","Сначала установите PIN или пароль телефона. Личные экраны останутся закрыты до проверки Android."),
            securityHeading(),securitySummary(),animationsAllowed()) {
            if(secure) { authAttempted=false; requestPhoneAuthentication() }
            else { authAttempted=false; startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
        },LinearLayout.LayoutParams(-1,0,1f))
        if(visible && secure && !authAttempted && !authInFlight) handler.post { requestPhoneAuthentication() }
    }
    private fun requestPhoneAuthentication() {
        if(!visible || unlocked || authInFlight || isFinishing || isDestroyed) return
        if(!getSystemService(KeyguardManager::class.java).isDeviceSecure) return
        authAttempted=true; authInFlight=true
        val request=authSession.begin()
        if(Build.VERSION.SDK_INT >= 28) try {
            val builder=android.hardware.biometrics.BiometricPrompt.Builder(this).setTitle("Cute Chat")
                .setSubtitle(tr("Unlock with your phone security","ফোনের security দিয়ে আনলক করুন"))
            if(Build.VERSION.SDK_INT >= 30) builder.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            else if(Build.VERSION.SDK_INT >= 29) builder.setDeviceCredentialAllowed(true)
            else builder.setNegativeButton(tr("Device password","ফোনের password"),mainExecutorCompat()) { _,_ -> if(authSession.isCurrent(request)) deviceCredential() }
            val cancellation=CancellationSignal(); authCancellation=cancellation
            builder.build().authenticate(cancellation,mainExecutorCompat(),object:android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result:android.hardware.biometrics.BiometricPrompt.AuthenticationResult) {
                    if(!authSession.finish(request)) return
                    authInFlight=false; authCancellation=null
                    if(!isFinishing && !isDestroyed) { unlocked=true; afterUnlock() }
                }
                override fun onAuthenticationError(code:Int,message:CharSequence) {
                    if(!authSession.finish(request)) return
                    authInFlight=false; authCancellation=null
                    if(visible && !unlocked && code !in setOf(5,10,13)) deviceCredential()
                }
            })
        } catch (_: Exception) { authInFlight=false; deviceCredential() }
        else deviceCredential()
    }
    private fun mainExecutorCompat() = java.util.concurrent.Executor { handler.post(it) }
    private fun deviceCredential() {
        if(!visible || unlocked || credentialPending) return
        val intent=getSystemService(KeyguardManager::class.java).createConfirmDeviceCredentialIntent("Cute Chat",tr("Unlock your private space","আপনার private chat আনলক করুন"))
        if(intent == null) { authInFlight=false; return }
        credentialToken=authSession.begin(); credentialPending=true; authInFlight=true
        try { startActivityForResult(intent,31) } catch (_: Exception) { credentialPending=false; authInFlight=false }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if(appUpdates.result(requestCode)) return
        if(nearbySetup.result(requestCode)) return
        if (requestCode == 51) {
            val token = projectionCall; projectionCall = null
            if (resultCode == RESULT_OK && data != null && token != null && token == calls.id)
                pendingProjection = token to Intent(data)
            consumeProjection()
            return
        }
        if (requestCode == 41) {
            try {
                val account = com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(data).getResult(com.google.android.gms.common.api.ApiException::class.java)
                val token = account.idToken ?: error("No Google ID token")
                com.google.firebase.auth.FirebaseAuth.getInstance().signInWithCredential(com.google.firebase.auth.GoogleAuthProvider.getCredential(token, null))
                    .addOnSuccessListener { if (visible && unlocked) { toast(tr("Google linked; chats stay local. Contact backup is not enabled.","Google যুক্ত হয়েছে; chat ফোনে থাকে। Contact backup চালু নেই।")); if(googleSetupName != null) finishGoogleSetup() else settings("account") } }
                    .addOnFailureListener { googleSetupName=null; toast("Google linking failed. Check Firebase provider and this APK's signing fingerprint.") }
            } catch (error: com.google.android.gms.common.api.ApiException) { googleSetupName=null; if (error.statusCode != 12501) toast("Google sign-in error ${error.statusCode}. Check this APK's SHA fingerprints in Firebase.") }
            catch (_: Exception) { googleSetupName=null; toast("Google sign-in did not complete") }
            return
        }
        if (requestCode == 31) {
            val expected=credentialPending && authSession.finish(credentialToken)
            if(!expected) return
            credentialPending=false; authInFlight=false; authCancellation=null
            if(expected && resultCode == RESULT_OK && !isFinishing && !isDestroyed) { unlocked=true; afterUnlock() }
            return
        }
        val uri = data?.data
        if(requestCode==55) {
            if(resultCode==RESULT_OK) {
                val items=if(data?.clipData!=null) (0 until data.clipData!!.itemCount).map { data.clipData!!.getItemAt(it).uri } else listOfNotNull(uri)
                quickFiles=items.distinct().take(LocalSharingRules.MAX_SELECTION);sharingRouteRequested=true
                val go={ if(internetSharing()) sharingPage() else localPermissions(true,true) { sharingPage() } }
                if(visible && unlocked) go() else resumedPermission=go
            } else quickFiles=emptyList()
            return
        }
        if(requestCode==54) {
            if(resultCode==RESULT_OK && uri!=null) {
                try {
                    val flags=(data?.flags ?: 0) and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    check(flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
                    if(flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    else contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    if(visible && unlocked) owner.quickShare.enable(uri) else { pendingQuickFolder=uri;sharingRouteRequested=true }
                } catch(_:Exception) { toast(tr("Choose a writable folder","Writable folder বেছে নিন")) }
            }
            if(visible && unlocked) sharingPage();return
        }
        if(requestCode==52) {
            val action=localPermissionAction;localPermissionAction=null
            if(resultCode==RESULT_OK) { if(visible && unlocked) acceptedPrivateAction { action?.invoke() } else resumedPermission={ acceptedPrivateAction { action?.invoke() } } } else if(visible && unlocked) sharingPage()
            return
        }
        if (requestCode == 21 || requestCode == 23) {
            val to = pickingPeer; pickingPeer = null
            if(resultCode==RESULT_OK && to!=null) {
                val items=if(data?.clipData!=null) (0 until data.clipData!!.itemCount).map { data.clipData!!.getItemAt(it).uri } else listOfNotNull(uri)
                queuePicked(items,to,requestCode==23,requestCode==23)
            } else { sharingRecipients=emptyList();pickedQueue.clear() }
            return
        }
        if (resultCode != RESULT_OK || uri == null) { if (requestCode == 22) savingAttachment = null; return }
        if (requestCode == 22) {
            val selected = savingAttachment ?: return; savingAttachment = null
            pendingFileSave=Triple(selected.first,selected.second,uri)
            consumeFileSave()
        }
    }
    /** A document-picker return is another private entry; wait for native phone authentication. */
    private fun consumeFileSave() {
        if(!visible || !unlocked || !owner.viewUnlocked) return
        val selected=pendingFileSave ?: return;pendingFileSave=null
        owner.exportFile(selected.first,selected.second,{ contentResolver.openOutputStream(selected.third,"w") ?: error("Destination unavailable") }) { ok ->
            if(!ok) runCatching { contentResolver.delete(selected.third,null,null) }
            handler.post { if(!isDestroyed) toast(if(ok) tr("Saved to selected location","নির্বাচিত জায়গায় সেভ হয়েছে") else tr("Could not save file","ফাইল সেভ হয়নি")) }
        }
    }
    private fun consumeProjection() {
        if (!visible || !unlocked) return
        val pending = pendingProjection ?: return
        pendingProjection = null
        owner.work {
            // Bind consent to the exact call, not whichever call is current later.
            if (!CallUiPolicy.mayProject(pending.first, calls.id, calls.video, calls.status, owner.foreground)) return@work
            try { calls.startScreenShare(pending.second) }
            catch (_: Exception) { handler.post { toast(tr("Screen sharing could not start; camera call retained", "স্ক্রিন শেয়ার শুরু হয়নি; camera call চালু আছে")) } }
        }
    }
}
