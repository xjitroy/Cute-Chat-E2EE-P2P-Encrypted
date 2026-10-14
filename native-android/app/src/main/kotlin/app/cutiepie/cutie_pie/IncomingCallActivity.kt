package app.cutiepie.cutie_pie

import android.Manifest
import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import android.app.KeyguardManager
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.*
import android.util.Rational
import android.view.*
import android.widget.*
import org.webrtc.*

/** Non-exported call-only UI. No chat/profile/vault screen or app-unlock grant. */
class IncomingCallActivity:ComponentActivity() {
    private val app get()=application as CutieApplication
    private val calls get()=app.calls
    private val handler=Handler(Looper.getMainLooper())
    private var id="";private var showing=false;private var autoAnswer=false
    private var title:TextView?=null;private var state:TextView?=null;private var actions:LinearLayout?=null
    private var renderer:SurfaceViewRenderer?=null;private var track:VideoTrack?=null
    private var stage="";private var callControls:View?=null;private var heading:View?=null
    private var controlsVisible=true
    private val hideControls=Runnable { if(calls.video && calls.status=="connected") setControls(false) }
    private var permissionId=""
    private val tick=object:Runnable {
        override fun run() {
            val public=NativeCalls.current ?: run { finish();return }
            if(!calls.active || calls.id!=id || public.id!=id) { finish();return }
            title?.text=if(getSystemService(KeyguardManager::class.java).isDeviceLocked || public.privateLabel) public.privateName else public.name
            val seconds=if(calls.connectedAt>0) (SystemClock.elapsedRealtime()-calls.connectedAt)/1000 else 0
            state?.text="${if(calls.video) "Video call" else "Audio call"} · ${calls.status} · %02d:%02d\n${calls.quality}\nEncrypted device-to-device call${if(calls.status=="incoming") "\nCamera / microphone stay off until Answer" else ""}".format(seconds/60,seconds%60)
            bindControls();bindVideo();handler.postDelayed(this,400)
        }
    }
    override fun onCreate(saved:Bundle?) {
        super.onCreate(saved);onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true) { override fun handleOnBackPressed() { moveTaskToBack(true) } });window.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if(Build.VERSION.SDK_INT>=27) { setShowWhenLocked(true);setTurnScreenOn(true) }
        else window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        bind(intent)
    }
    override fun onNewIntent(intent:Intent) { super.onNewIntent(intent);setIntent(intent);bind(intent) }
    private fun bind(intent:Intent) {
        val next=intent.getStringExtra("callId").orEmpty()
        if(!calls.active || calls.id!=next || NativeCalls.current?.id!=next) { finish();return }
        if(id.isNotEmpty() && id!=next) calls.callEntry.visibility(id,false)
        id=next;calls.callEntry.visibility(id,showing);autoAnswer=intent.getStringExtra("callAction")=="answer" && calls.status=="incoming"
        val density=resources.displayMetrics.density
        fun dp(n:Int)=(n*density).toInt()
        val frame=FrameLayout(this).apply { setBackgroundColor(Color.rgb(24,20,35)) }
        renderer?.let { try { track?.removeSink(it) } catch (_:IllegalStateException) {};it.release() };track=null
        renderer=if(calls.video) SurfaceViewRenderer(this).also { it.init(calls.egl.eglBaseContext,null);it.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);frame.addView(it,FrameLayout.LayoutParams(-1,-1)) } else null
        val column=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;setPadding(dp(20),dp(42),dp(20),dp(24));background=android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0xe9231c32.toInt(),Color.TRANSPARENT)) }
        fun text(value:String,size:Float)=TextView(this).apply { text=value;textSize=size;setTextColor(Color.WHITE);gravity=Gravity.CENTER;setPadding(0,dp(10),0,dp(10)) }
        column.addView(text("Cute Chat · Private call",18f));title=text("Private caller",26f);column.addView(title)
        state=text("Connecting…",13f);column.addView(state)
        heading=column;frame.addView(column,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        actions=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(12),dp(20),dp(28));background=android.graphics.drawable.GradientDrawable().apply { setColor(0xd9231c32.toInt()) } }
        callControls=actions;frame.addView(actions,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));setContentView(frame)
        frame.setOnClickListener { if(calls.video && calls.status=="connected") setControls(!controlsVisible) }
        stage="";handler.removeCallbacks(tick);handler.post(tick)
        if(autoAnswer && showing) { autoAnswer=false;answer() }
    }
    private fun button(name:String,icon:String,color:Int=0xff76519c.toInt(),action:()->Unit)=com.google.android.material.button.MaterialButton(this).apply {
        text=name;isAllCaps=false;setTextColor(Color.WHITE);cornerRadius=(24*resources.displayMetrics.density).toInt();minHeight=(52*resources.displayMetrics.density).toInt()
        backgroundTintList=android.content.res.ColorStateList.valueOf(color);this.icon=MaterialYou.icon(this@IncomingCallActivity,icon,Color.WHITE);iconTint=android.content.res.ColorStateList.valueOf(Color.WHITE);setOnClickListener { if(calls.id==this@IncomingCallActivity.id) action() }
    }
    private fun bindControls() {
        val key="${calls.status}:${calls.muted}:${calls.speaker}:${calls.cameraOff}:${app.assist.hostGranted}"
        if(stage==key) return;stage=key;setControls(true);val token=id;val root=actions ?: return;root.removeAllViews()
        if(calls.status=="incoming") {
            root.addView(button("Answer","call",0xff228f68.toInt()) { answer() })
            root.addView(button("Decline","end",0xffc83951.toInt()) { app.owner.work { if(calls.id==token) calls.end() };finish() })
        } else {
            val row=LinearLayout(this)
            row.addView(button(if(calls.muted) "Unmute" else "Mute","mic") { app.owner.work { if(calls.id==token) calls.toggleMute() } },LinearLayout.LayoutParams(0,-2,1f))
            row.addView(button("Speaker","speaker") { app.owner.work { if(calls.id==token) calls.toggleSpeaker() } },LinearLayout.LayoutParams(0,-2,1f));root.addView(row)
            if(calls.video) root.addView(button(if(calls.cameraOff) "Camera on" else "Camera off","video") { app.owner.work { if(calls.id==token) calls.toggleCamera() } })
            root.addView(button("End call","end",0xffc83951.toInt()) { app.owner.work { if(calls.id==token) calls.end() };finish() })
            if(app.assist.hostGranted) root.addView(button("Stop remote control","shield") { app.assist.stopLocal();stage="" })
        }
        root.addView(button("Open app · authenticate","lock") { startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP));finish() })
    }
    private fun answer() {
        if(!showing || calls.id!=id || calls.status!="incoming") return
        val token=id
        val missing=listOfNotNull(Manifest.permission.RECORD_AUDIO,if(calls.video) Manifest.permission.CAMERA else null).filter { checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED }
        if(missing.isNotEmpty()) { permissionId=token;requestPermissions(missing.toTypedArray(),12);return }
        if(calls.callEntry.accept(token,calls.id,calls.status=="incoming")) app.owner.work { if(calls.id==token && calls.status=="incoming") calls.answer() }
    }
    private fun bindVideo() {
        val view=renderer ?: return;val next=calls.remoteVideo
        if(track!==next) { try { track?.removeSink(view) } catch (_:IllegalStateException) {};track=next;try { next?.addSink(view) } catch (_:IllegalStateException) { track=null } }
    }
    override fun onResume() { super.onResume();showing=true;calls.callEntry.visibility(id,true);app.owner.work { if(calls.id==id) calls.resumeAudio() };if(autoAnswer) { autoAnswer=false;answer() } }
    override fun onPause() { showing=false;calls.callEntry.visibility(id,false);super.onPause() }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<String>,results:IntArray) { super.onRequestPermissionsResult(code,permissions,results);if(code==12 && permissionId==id && calls.id==permissionId && results.isNotEmpty() && results.all { it==PackageManager.PERMISSION_GRANTED }) { autoAnswer=true;if(showing) { autoAnswer=false;answer() } } }
    override fun onUserLeaveHint() { super.onUserLeaveHint();if(Build.VERSION.SDK_INT>=26 && calls.video && calls.status=="connected") try { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(9,16)).build()) } catch (_: Exception) {} }
    private fun setControls(show:Boolean) {
        handler.removeCallbacks(hideControls);controlsVisible=show
        val pip=Build.VERSION.SDK_INT>=26 && isInPictureInPictureMode
        callControls?.visibility=if(show && !pip) View.VISIBLE else View.GONE
        heading?.visibility=if(show && !pip) View.VISIBLE else View.GONE
        if(show && calls.video && calls.status=="connected") handler.postDelayed(hideControls,5000)
    }
    override fun onPictureInPictureModeChanged(mode:Boolean,config:android.content.res.Configuration) { super.onPictureInPictureModeChanged(mode,config);setControls(!mode) }
    override fun onDestroy() { calls.callEntry.visibility(id,false);handler.removeCallbacksAndMessages(null);renderer?.let { try { track?.removeSink(it) } catch (_:IllegalStateException) {};it.release() };super.onDestroy() }
}
