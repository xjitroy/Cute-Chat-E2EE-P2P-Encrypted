package app.cutiepie.cutie_pie

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import app.cutiepie.cutie_pie.core.WireCodec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Clean disposable emulator target only; refuses to touch an existing user vault. */
@RunWith(AndroidJUnit4::class)
class LauncherFlowTest {
    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun renderSynthetic(activity: MainActivity, name: String) {
        // Test-only view render: production FLAG_SECURE is never removed.
        // This fixture has no real contacts/messages; only its synthetic UI is exported.
        val view=activity.window.decorView
        // Layout and enter motion can still be pending immediately after a
        // synchronous navigation click. Render a settled synthetic View tree.
        all(view).forEach { it.animate().cancel(); it.alpha=1f; it.translationY=0f }
        view.measure(View.MeasureSpec.makeMeasureSpec(view.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(view.height,View.MeasureSpec.EXACTLY))
        view.layout(0,0,view.measuredWidth,view.measuredHeight)
        val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val file=File(activity.getExternalFilesDir(null),"native-qa-$name.png")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
    }
    @Test fun launcherCreatesAnEncryptedIdentityAndDisplaysSignedQrWithoutCapture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(!File(context.filesDir, "cutie.db").exists()) { "Fresh emulator required; existing target vault preserved" }
        NativeTestUnlock.prepare()
        context.getSharedPreferences("native_settings", 0).edit().putString("language", "en").commit()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        instrumentation.runOnMainSync {
            val views = all(activity.window.decorView)
            views.filterIsInstance<EditText>().first().setText("Native test ♥")
            views.filterIsInstance<CheckBox>().first().isChecked = true
            views.filterIsInstance<Button>().first { it.text.toString() == "Create my private space" }.performClick()
        }
        NativeTestUnlock.unlock(activity)
        val app = context.applicationContext as CutieApplication
        NativeTestUnlock.unlock(activity)
        val until = System.nanoTime() + 45_000_000_000L
        while (!app.owner.ready && System.nanoTime() < until) Thread.sleep(100)
        assertTrue("Fresh native owner did not become ready", app.owner.ready)
        assertTrue(app.owner.code.startsWith("CP1.")); assertTrue(File(context.filesDir, "cutie.db").isFile)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            renderSynthetic(activity,"home")
            all(activity.window.decorView).filterIsInstance<Button>().first { it.text.toString() == "My QR · Scan a friend" }.performClick()
            assertTrue(all(activity.window.decorView).filterIsInstance<Button>().any { it.text.toString() == "Open scanner" })
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { renderSynthetic(activity,"qr") }
        instrumentation.runOnMainSync {
            activity.onBackPressedDispatcher.onBackPressed()
            all(activity.window.decorView).filterIsInstance<Button>().first { it.contentDescription?.toString() == "Settings" }.performClick()
            all(activity.window.decorView).first { it.contentDescription?.toString()?.startsWith("Appearance.") == true }.performClick()
            assertTrue(all(activity.window.decorView).filterIsInstance<android.widget.TextView>().any { it.text.toString() == "Material You" })
            renderSynthetic(activity,"settings-material")
            app.owner.preferences.edit().putString("visualStyle", CuteUi.STYLE_GLASS).commit()
            activity.onBackPressedDispatcher.onBackPressed()
            activity.onBackPressedDispatcher.onBackPressed()
            renderSynthetic(activity,"home-glass")
            all(activity.window.decorView).filterIsInstance<Button>().first { it.contentDescription?.toString() == "Settings" }.performClick()
            all(activity.window.decorView).first { it.contentDescription?.toString()?.startsWith("Appearance.") == true }.performClick()
            assertTrue(all(activity.window.decorView).filterIsInstance<android.widget.TextView>().any { it.text.toString() == "Soft Glass" })
            renderSynthetic(activity,"settings-glass")
            assertTrue(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
            // Waiting for service readiness on main would deadlock Android.
            val denied = runCatching { ConnectionService.startAndAwait(context, 4) }.exceptionOrNull()
            assertTrue(denied is IllegalStateException)
        }
        // Answer hands ringtone/focus ownership to the self-managed connection
        // before media capture. This exercises the actual main-thread handoff.
        val callId=WireCodec.id()
        instrumentation.runOnMainSync {
            NativeCalls.sync(context,mapOf("bridge" to app.callBridge,"sequence" to 900000L,"id" to callId,
                "name" to "Synthetic private caller","video" to false,"status" to "incoming","alerts" to false,"background" to false,"private" to true))
            NativeCalls.connection=object:android.telecom.Connection() {}.apply { setRinging() }
        }
        assertTrue(NativeCalls.prepareMedia(callId))
        assertEquals(android.telecom.Connection.STATE_ACTIVE,NativeCalls.connection!!.state)
        assertEquals("connecting",NativeCalls.current!!.status)
        instrumentation.runOnMainSync { NativeCalls.finish(callId) }
        assertFalse(app.calls.active); assertNull(app.calls.localVideo)
        activity.finish()
    }
}
