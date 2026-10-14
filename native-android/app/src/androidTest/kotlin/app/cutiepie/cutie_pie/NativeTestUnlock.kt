package app.cutiepie.cutie_pie

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Disposable emulator ONLY. Uses the real Android credential UI, no app bypass. */
object NativeTestUnlock {
    fun prepare() {
        val instrument=InstrumentationRegistry.getInstrumentation(); val context=instrument.targetContext
        check(Build.HARDWARE in setOf("ranchu","goldfish")) { "Authentication fixture requires a disposable emulator" }
        val name=context.getSharedPreferences("native_settings",0).getString("name","")
        check(!File(context.filesDir,"cutie.db").exists() || name == "Native test ♥") { "Existing real vault/phone lock preserved" }
        val marker=instrument.context.getSharedPreferences("synthetic_device",0)
        if(!context.getSystemService(KeyguardManager::class.java).isDeviceSecure) {
            val output=ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("locksettings set-pin 246810")).use { it.readBytes().toString(Charsets.UTF_8) }
            check(!output.contains("Error",true) && !output.contains("Exception")) { "Disposable PIN setup failed" }
            marker.edit().putBoolean("pinSet",true).commit()
        }
        check(marker.getBoolean("pinSet",false)) { "Unknown device credential; refusing to change/guess it" }
    }
    fun unlock(activity:MainActivity) {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val field=MainActivity::class.java.getDeclaredField("unlocked").apply { isAccessible=true }
        var submitted=false
        val until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(60)
        while(System.nanoTime()<until) {
            var unlocked=false; instrument.runOnMainSync { unlocked=field.getBoolean(activity) }
            if(unlocked) return
            instrument.uiAutomation.serviceInfo=instrument.uiAutomation.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
            val root=instrument.uiAutomation.rootInActiveWindow
            if(root != null) {
                fun nodes(n:AccessibilityNodeInfo):List<AccessibilityNodeInfo> = listOf(n)+(0 until n.childCount).flatMap { n.getChild(it)?.let(::nodes) ?: emptyList() }
                val editable=nodes(root).firstOrNull { it.isEditable && it.packageName?.toString() in setOf("com.android.systemui","com.android.settings","android") }
                if(editable != null && !submitted) {
                    editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"246810") })
                    ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("input keyevent 66")).use { it.readBytes() }; submitted=true
                } else if(!submitted && root.packageName?.toString() == "com.android.systemui") {
                    val found=nodes(root)
                    val keys="246810".map { digit -> found.firstOrNull { it.viewIdResourceName?.endsWith("/key$digit") == true } }
                    val enter=found.firstOrNull { it.viewIdResourceName?.endsWith("/key_enter") == true }
                    if(keys.all { it != null } && enter != null) { keys.forEach { it!!.performAction(AccessibilityNodeInfo.ACTION_CLICK) };enter.performAction(AccessibilityNodeInfo.ACTION_CLICK);submitted=true }
                }
            }
            Thread.sleep(200)
        }
        error("Native Android device credential did not unlock the synthetic app")
    }
}
