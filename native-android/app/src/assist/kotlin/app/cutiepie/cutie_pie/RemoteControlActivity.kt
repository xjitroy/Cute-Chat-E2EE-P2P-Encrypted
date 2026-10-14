package app.cutiepie.cutie_pie

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import android.app.AlertDialog
import android.os.*
import android.graphics.*
import android.view.*
import android.widget.*
import org.webrtc.*
import app.cutiepie.cutie_pie.core.AssistSafety

/** Remote shared video only; no access to the local private chat/gallery UI. */
class RemoteControlActivity:ComponentActivity() {
    private val app get()=application as CutieApplication
    private val handler=Handler(Looper.getMainLooper())
    private var id="";private var epoch=""
    private var track:VideoTrack?=null;private var renderer:SurfaceViewRenderer?=null
    private var surface:View?=null;private var pointer:Pointer?=null;private var status:TextView?=null
    private var actualRatio=0f
    private var downFrame:NativeRemoteAssist.RemoteFrame?=null
    private var downX=0f;private var downY=0f;private var downAt=0L;private var armed=false
    private val tick=object:Runnable {
        override fun run() {
            if(app.calls.id!=id || !app.calls.active || app.assist.remoteEpoch!=epoch) { finish();return }
            val frame=app.assist.remoteFrame
            status?.text=when { !app.assist.remoteAccepted -> "Accept this screen-control invitation below"
                frame==null || SystemClock.elapsedRealtime()-frame.received>1800 -> "Waiting for a fresh encrypted control frame"
                !frame.active -> "Paused · host must unlock / choose an allowed app"
                else -> "Control active · ${(frame.remaining/1000)}s · tap / swipe · passwords stay local" }
            val next=app.calls.remoteVideo
            if(next!==track) { renderer?.let { try { track?.removeSink(it) } catch (_:IllegalStateException) {};try { next?.addSink(it) } catch (_:IllegalStateException) {} };track=next;actualRatio=0f }
            handler.postDelayed(this,350)
        }
    }
    override fun onCreate(saved:Bundle?) {
        super.onCreate(saved);onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true) { override fun handleOnBackPressed() { app.assist.stopRemote(epoch);finish() } });window.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        id=intent.getStringExtra("callId").orEmpty();epoch=intent.getStringExtra("epoch").orEmpty()
        if(id.isEmpty() || app.calls.id!=id || epoch.isEmpty() || app.assist.remoteEpoch!=epoch) { finish();return }
        val density=resources.displayMetrics.density
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(24,20,35));setPadding((12*density).toInt(),(24*density).toInt(),(12*density).toInt(),(20*density).toInt()) }
        status=TextView(this).apply { textSize=14f;setTextColor(Color.WHITE);setPadding(0,(12*density).toInt(),0,(12*density).toInt()) };root.addView(status)
        val video=FrameLayout(this)
        renderer=SurfaceViewRenderer(this).also {
            it.init(app.calls.egl.eglBaseContext,object:RendererCommon.RendererEvents {
                override fun onFirstFrameRendered() {}
                override fun onFrameResolutionChanged(width:Int,height:Int,rotation:Int) { handler.post { actualRatio=if(rotation%180==0) width.toFloat()/height else height.toFloat()/width } }
            });it.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);video.addView(it,FrameLayout.LayoutParams(-1,-1))
        }
        pointer=Pointer(this);surface=pointer;video.addView(pointer,FrameLayout.LayoutParams(-1,-1));root.addView(video,LinearLayout.LayoutParams(-1,0,1f))
        fun button(title:String,action:()->Unit)=Button(this).apply { text=title;isAllCaps=false;minimumHeight=(48*density).toInt();setOnClickListener { action() } }
        root.addView(button("Accept control · this live call only") { if(app.assist.remoteEpoch==epoch) app.assist.acceptRemote(epoch) })
        val row=LinearLayout(this)
        row.addView(button("Back on shared phone") {
            if(app.owner.preferences.getBoolean("assistConfirmBack",true)) AlertDialog.Builder(this).setTitle("Navigate back on the shared phone?").setMessage("This does not end the call. Protected/system screens remain local-only.").setPositiveButton("Back") { _,_ -> app.assist.input("back",0,0) }.setNegativeButton("Cancel",null).show()
            else app.assist.input("back",0,0)
        },LinearLayout.LayoutParams(0,-2,1f))
        row.addView(button("Stop control") { app.assist.stopRemote(epoch);finish() },LinearLayout.LayoutParams(0,-2,1f));root.addView(row)
        root.addView(button("Return · keep call running") { app.assist.stopRemote(epoch);finish() });setContentView(root)
        pointer?.setOnTouchListener { view,event -> touch(view,event) };handler.post(tick)
    }
    private fun normalized(view:View,x:Float,y:Float):Pair<Int,Int>? {
        val frame=app.assist.remoteFrame ?: return null
        if(!app.assist.remoteAccepted || !frame.active || SystemClock.elapsedRealtime()-frame.received !in 0..1300) return null
        return AssistSafety.normalize(view.width,view.height,frame.width,frame.height,actualRatio,x,y)
    }
    private fun touch(view:View,event:MotionEvent):Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { armed=normalized(view,event.x,event.y)!=null;downFrame=app.assist.remoteFrame;downX=event.x;downY=event.y;downAt=SystemClock.elapsedRealtime();if(armed) pointer?.point(event.x,event.y) }
            MotionEvent.ACTION_MOVE -> if(armed) pointer?.point(event.x,event.y)
            MotionEvent.ACTION_CANCEL -> armed=false
            MotionEvent.ACTION_UP -> {
                if(!armed) return true;armed=false;view.performClick()
                val first=downFrame ?: return true;val current=app.assist.remoteFrame ?: return true
                if(first.width!=current.width || first.height!=current.height || first.rotation!=current.rotation) return true
                val start=normalized(view,downX,downY) ?: return true;val end=normalized(view,event.x,event.y) ?: return true
                val moved=kotlin.math.hypot((event.x-downX).toDouble(),(event.y-downY).toDouble())>24*resources.displayMetrics.density
                app.assist.input(if(moved) "swipe" else "tap",start.first,start.second,end.first,end.second,if(moved) (SystemClock.elapsedRealtime()-downAt).toInt().coerceIn(150,600) else 80,first.token)
            }
        };return true
    }
    private inner class Pointer(context:android.content.Context):View(context) {
        private var xPos=-1f;private var yPos=-1f
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(128,211,255);style=Paint.Style.STROKE;strokeWidth=3*resources.displayMetrics.density }
        init { contentDescription="Shared screen · tap or swipe only inside the fitted video";isClickable=true }
        fun point(x:Float,y:Float) { xPos=x;yPos=y;invalidate() }
        override fun onDraw(canvas:Canvas) { super.onDraw(canvas);if(xPos>=0 && app.owner.preferences.getBoolean("assistPointer",true)) canvas.drawCircle(xPos,yPos,16*resources.displayMetrics.density,paint) }
        override fun performClick():Boolean { super.performClick();return true }
    }
    override fun onResume() { super.onResume();app.calls.callEntry.visibility(id,true);app.owner.work { if(app.calls.id==id) app.calls.resumeAudio() } }
    override fun onPause() { app.calls.callEntry.visibility(id,false);super.onPause() }
    override fun onStop() { app.assist.stopRemote(epoch);super.onStop() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null);renderer?.let { try { track?.removeSink(it) } catch (_:IllegalStateException) {};it.release() };super.onDestroy() }
}
