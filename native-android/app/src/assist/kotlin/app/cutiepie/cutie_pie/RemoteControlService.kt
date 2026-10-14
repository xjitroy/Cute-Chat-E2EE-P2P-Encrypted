package app.cutiepie.cutie_pie

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.pm.ApplicationInfo
import android.graphics.Path
import android.graphics.Rect
import android.os.*
import android.view.*
import android.view.accessibility.*
import android.widget.*
import app.cutiepie.cutie_pie.core.AssistSafety
import org.json.JSONObject

/** Opt-in OS accessibility service. Idle: no window queries, capture or input. */
class RemoteControlService:AccessibilityService() {
    companion object { @Volatile var instance:RemoteControlService?=null; private set }
    data class Snapshot(val key:String,val width:Int,val height:Int,val rotation:Int,val bounds:Rect,val blocked:List<Rect>,val taps:List<Rect>,val scrolls:List<Rect>)
    private val app get()=application as CutieApplication
    private val handler=Handler(Looper.getMainLooper())
    private var revision=0L
    private var overlay:LinearLayout?=null
    private var status:TextView?=null
    private var busy=false
    private var localEpoch=""
    private val scan=Runnable { refresh() }
    override fun onServiceConnected() { instance=this; refresh() }
    override fun onAccessibilityEvent(event:AccessibilityEvent?) {
        if(!app.assist.hostGranted || event?.packageName?.toString()==packageName) return
        if(event?.eventType in setOf(AccessibilityEvent.TYPE_WINDOWS_CHANGED,AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,AccessibilityEvent.TYPE_VIEW_SCROLLED)) {
            revision++;handler.removeCallbacks(scan);handler.postDelayed(scan,50)
        }
    }
    override fun onInterrupt() { app.assist.stopHost();removeOverlay() }
    fun refresh() {
        handler.removeCallbacks(scan)
        if(!app.assist.hostGranted) { removeOverlay();return }
        if(localEpoch!=app.assist.hostEpoch) { localEpoch=app.assist.hostEpoch;busy=false }
        showOverlay(); if(status?.text?.toString()!=app.assist.hostStatus) status?.text=app.assist.hostStatus
        app.assist.snapshot(safeSnapshot())
        handler.postDelayed(scan,700)
    }
    private fun safeSnapshot():Snapshot? {
        if(app.calls.peer !in app.owner.connected || !app.assist.hostActive || !app.calls.screenSharing || app.calls.status!="connected" || app.getSystemService(KeyguardManager::class.java).isDeviceLocked) return null
        return try {
            val all=windows
            if(all.any { it.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD || (it.isFocused && it.type==AccessibilityWindowInfo.TYPE_SYSTEM) }) return null
            val window=all.firstOrNull { it.type==AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused) && (Build.VERSION.SDK_INT<30 || it.displayId==0) } ?: return null
            val root=window.root ?: return null
            try {
                val pkg=root.packageName?.toString() ?: return null
                if(pkg !in app.assist.allowedPackages) return null
                if(!RemoteAppScope.allowed(packageManager,pkg)) return null
                val metrics=android.util.DisplayMetrics();@Suppress("DEPRECATION") val display=getSystemService(WindowManager::class.java).defaultDisplay
                @Suppress("DEPRECATION") display.getRealMetrics(metrics)
                if(metrics.widthPixels!=app.calls.screenDisplayWidth || metrics.heightPixels!=app.calls.screenDisplayHeight || display.rotation!=app.calls.screenRotation) return null
                val bounds=Rect();root.getBoundsInScreen(bounds)
                val blocked=mutableListOf<Rect>();val taps=mutableListOf<Rect>();val scrolls=mutableListOf<Rect>();var visited=0;var keypad=0
                fun inspect(node:AccessibilityNodeInfo,depth:Int):Boolean {
                    if(depth>32 || ++visited>512) return false
                    if(node.isVisibleToUser && Build.VERSION.SDK_INT>=34 && node.isAccessibilityDataSensitive) return false
                    if(node.isVisibleToUser && (node.isPassword || (node.isEditable && node.isFocused))) return false
                    if(node.isVisibleToUser) {
                        val hint=(node.contentDescription?.toString().orEmpty()+" "+if(node.isPassword) "" else node.text?.toString().orEmpty()).trim()
                        if(Regex("password|passcode|credential|unlock|pin code|পাসওয়ার্ড|পিন কোড|पासवर्ड|пароль",RegexOption.IGNORE_CASE).containsMatchIn(hint)) return false
                        if(node.isClickable && listOf(node.contentDescription?.toString(),node.text?.toString()).any { it?.trim()?.matches(Regex("[0-9০-৯०-९]|(?i:zero|one|two|three|four|five|six|seven|eight|nine)"))==true }) keypad++
                        val rect=Rect();node.getBoundsInScreen(rect)
                        val name=node.className?.toString().orEmpty()
                        if(name.contains("SurfaceView") || name.contains("TextureView") || name.contains("WebView") || name.contains("Canvas") || (name=="android.view.View" && node.childCount==0 && !node.isScrollable)) blocked.add(Rect(rect))
                        if(!node.isEditable && !node.isPassword && node.isEnabled && node.isClickable && AssistSafety.clickableClass(name)) taps.add(rect)
                        if(!node.isEditable && node.isScrollable && !name.contains("WebView") && !name.contains("SurfaceView") && !name.contains("TextureView")) scrolls.add(rect)
                    }
                    if(node.isVisibleToUser && node.isEditable) { val rect=Rect();node.getBoundsInScreen(rect);blocked.add(rect) }
                    for(i in 0 until node.childCount) {
                        val child=node.getChild(i) ?: continue
                        try { if(!inspect(child,depth+1)) return false } finally { @Suppress("DEPRECATION") child.recycle() }
                    };return true
                }
                if(!inspect(root,0) || keypad>=3) return null
                overlay?.let { view -> val point=IntArray(2);view.getLocationOnScreen(point);blocked.add(Rect(point[0],point[1],point[0]+view.width,point[1]+view.height)) }
                Snapshot("${window.id}:$revision:${metrics.widthPixels}:${metrics.heightPixels}:${display.rotation}",metrics.widthPixels,metrics.heightPixels,display.rotation,bounds,blocked,taps,scrolls)
            } finally { @Suppress("DEPRECATION") root.recycle() }
        } catch (_: Exception) { null }
    }
    fun perform(epoch:String,key:String,command:JSONObject,finished:(Boolean)->Unit) {
        if(busy || !app.assist.hostActive || app.assist.hostEpoch!=epoch) { finished(false);return }
        val snapshot=safeSnapshot()
        if(snapshot==null || snapshot.key!=key) { finished(false);refresh();return }
        if(!app.assist.hostActive || app.assist.hostEpoch!=epoch || !app.calls.screenSharing || app.calls.peer !in app.owner.connected) { finished(false);return }
        val action=command.getString("action")
        if(action=="back") {
            if(!app.owner.preferences.getBoolean("assistBack",true)) { finished(false);return }
            finished(performGlobalAction(GLOBAL_ACTION_BACK));refresh();return
        }
        fun px(name:String,size:Int)=((command.getInt(name).toLong()*size)/10000).toInt().coerceIn(0,size-1)
        val x=px("x",snapshot.width);val y=px("y",snapshot.height);val x2=px("x2",snapshot.width);val y2=px("y2",snapshot.height)
        val area=Rect(minOf(x,x2),minOf(y,y2),maxOf(x,x2)+1,maxOf(y,y2)+1)
        if(!snapshot.bounds.contains(x,y) || !snapshot.bounds.contains(x2,y2) || snapshot.blocked.any { Rect.intersects(it,area) }) { finished(false);return }
        if(action=="tap" && snapshot.taps.none { it.contains(x,y) }) { finished(false);return }
        if(action=="swipe" && snapshot.scrolls.none { it.contains(x,y) && it.contains(x2,y2) }) { finished(false);return }
        val path=Path().apply { moveTo(x.toFloat(),y.toFloat());if(action=="swipe") lineTo(x2.toFloat(),y2.toFloat()) }
        if(!app.assist.hostActive || app.assist.hostEpoch!=epoch || !app.calls.screenSharing || app.calls.peer !in app.owner.connected) { finished(false);return }
        busy=true
        val gesture=GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,command.getInt("duration").coerceIn(1,600).toLong())).build()
        val accepted=try { dispatchGesture(gesture,object:GestureResultCallback() {
            override fun onCompleted(description:GestureDescription?) { busy=false;finished(app.assist.hostEpoch==epoch && app.assist.hostActive);refresh() }
            override fun onCancelled(description:GestureDescription?) { busy=false;finished(false);refresh() }
        },handler) } catch (_: Exception) { false }
        if(!accepted) { busy=false;finished(false) }
    }
    private fun showOverlay() {
        if(overlay!=null) return
        val density=resources.displayMetrics.density
        val row=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding((12*density).toInt(),(8*density).toInt(),(12*density).toInt(),(8*density).toInt())
            background=android.graphics.drawable.GradientDrawable().apply { setColor(0xee231d32.toInt());cornerRadius=18*density }
        }
        status=TextView(this).apply { textSize=12f;setTextColor(android.graphics.Color.WHITE);maxWidth=(220*density).toInt() };row.addView(status)
        row.addView(Button(this).apply { text="Stop control";isAllCaps=false;contentDescription="Stop remote control; keep screen share and call";setOnClickListener { app.assist.stopHost();removeOverlay() } })
        try {
            getSystemService(WindowManager::class.java).addView(row,WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,android.graphics.PixelFormat.TRANSLUCENT).apply { gravity=Gravity.BOTTOM or Gravity.END;y=(56*density).toInt();x=(12*density).toInt() })
            overlay=row
        } catch (_: Exception) { app.assist.stopHost() }
    }
    private fun removeOverlay() { overlay?.let { try { getSystemService(WindowManager::class.java).removeView(it) } catch (_: Exception) {} };overlay=null;status=null;localEpoch="" }
    override fun onDestroy() { if(instance===this) instance=null;handler.removeCallbacksAndMessages(null);app.assist.stopHost();removeOverlay();super.onDestroy() }
}
