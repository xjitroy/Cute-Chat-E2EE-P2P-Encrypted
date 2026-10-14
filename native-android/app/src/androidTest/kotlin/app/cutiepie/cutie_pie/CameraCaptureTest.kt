package app.cutiepie.cutie_pie

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.view.WindowManager
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable camera fixture; no gallery reads or user media. */
@RunWith(AndroidJUnit4::class)
class CameraCaptureTest {
    private fun await(label: String, condition: () -> Boolean) {
        val end = System.nanoTime() + 120_000_000_000L
        while (!condition() && System.nanoTime() < end) Thread.sleep(100)
        assertTrue(label, condition())
    }
    @Test fun nativeCameraReturnsPhotoAndBoundedVideoAndClosesCapture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("Synthetic launcher fixture required", "Native test ♥", context.getSharedPreferences("native_settings", 0).getString("name", ""))
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.CAMERA))
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        for (isVideo in listOf(false, true)) {
            println("Native camera phase: " + if (isVideo) "video" else "photo")
            var capturedActivity: ChatCameraActivity? = null
            val scenario = ActivityScenario.launchActivityForResult<ChatCameraActivity>(Intent(context, ChatCameraActivity::class.java))
            scenario.onActivity { activity ->
                capturedActivity = activity
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
            await("Native preview starts") { capturedActivity?.isFinishing == true || ChatCameraActivity::class.java.getDeclaredField("session").apply { isAccessible = true }.get(capturedActivity) != null }
            if (capturedActivity?.isFinishing == true) { val result = scenario.result; fail("Camera start failed: " + result.resultData?.getStringExtra("captureError")) }
            scenario.onActivity { activity ->
                val field = ChatCameraActivity::class.java.getDeclaredField(if (isVideo) "video" else "photo").apply { isAccessible = true }
                (field.get(activity) as Button).performClick()
            }
            if (isVideo) {
                await("Video recorder starts") { capturedActivity?.isFinishing == true || ChatCameraActivity::class.java.getDeclaredField("recording").apply { isAccessible = true }.getBoolean(capturedActivity) }
                if (capturedActivity?.isFinishing == true) { val result = scenario.result; fail("Video start failed: " + result.resultData?.getStringExtra("captureError")) }
                Thread.sleep(10000)
                scenario.onActivity { (ChatCameraActivity::class.java.getDeclaredField("video").apply { isAccessible = true }.get(it) as Button).performClick() }
            }
            // Software emulators can deliver JPEG late while remaining responsive.
            // Await the real finish before ActivityScenario's fixed 45s result wait.
            await("Native capture finishes") { capturedActivity?.isFinishing == true }
            val result = scenario.result
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            listOf("recorder", "camera").forEach { field -> await("Capture released after finish") { ChatCameraActivity::class.java.getDeclaredField(field).apply { isAccessible = true }.get(capturedActivity) == null } }
            assertEquals("Capture result: " + result.resultData?.getStringExtra("captureError"), android.app.Activity.RESULT_OK, result.resultCode)
            val uri = result.resultData!!.data!!
            if (!isVideo) context.contentResolver.openInputStream(uri)!!.use { stream ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, options)
                assertTrue(options.outWidth > 0 && options.outHeight > 0)
            } else {
                val metadata = MediaMetadataRetriever()
                try { metadata.setDataSource(context, uri); val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong(); assertTrue(duration in 1..120000) }
                finally { metadata.release() }
            }
            context.contentResolver.delete(uri, null, null); scenario.close()
        }
        val app = context.applicationContext as CutieApplication
        assertFalse("Camera recording does not create a background call", app.calls.active)
    }
}
