package app.cutiepie.cutie_pie

import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cutiepie.cutie_pie.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference
import java.io.File

/** Run AFTER LauncherFlowTest on a disposable fixture. Refuses real profiles.
 * Never removes FLAG_SECURE or exports drafts/keys/real chat screenshots. */
@RunWith(AndroidJUnit4::class)
class UiLayoutRegressionTest {
    @org.junit.Before fun phoneSecurityFixture() { NativeTestUnlock.prepare() }
    @Test fun everyEntryWaitsForRealPhoneAuthenticationBeforeShowingPrivateScreens() {
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        check(context.getSharedPreferences("native_settings",0).getString("name","") == "Native test ♥")
        context.getSharedPreferences("native_settings",0).edit().putInt("viewLockMinutes",0).commit() // This case tests strict mode, not the default five-minute grace.
        val field=MainActivity::class.java.getDeclaredField("unlocked").apply { isAccessible=true }
        repeat(2) {
            val activity=instrument.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            instrument.runOnMainSync { assertFalse("New entry is locked",field.getBoolean(activity)); assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertTrue("Branded unlock page remains behind native phone authentication",views(activity.window.decorView).any { it.tag == "private-unlock-page" })
                assertFalse("App must never collect the phone password",views(activity.window.decorView).any { it is EditText })
                MainActivity::class.java.getDeclaredMethod("settings",String::class.java).apply { isAccessible=true }.invoke(activity,"account")
                assertFalse("Delayed private navigation cannot replace lock",views(activity.window.decorView).filterIsInstance<android.widget.TextView>().any { it.text.toString() == "Account" })
            }
            NativeTestUnlock.unlock(activity)
            instrument.runOnMainSync { assertTrue("Only real Android credential success unlocks",field.getBoolean(activity));activity.finish() }
            instrument.waitForIdleSync()
        }
    }
    @Test fun phoneContactExportUsesReadableIdAndDoesNotExportKeysOrTelephoneNumber() {
        val id="MAYA-0123456789"
        val intent=PhoneContacts.insert("Maya",id)
        assertEquals(android.provider.ContactsContract.Intents.Insert.ACTION,intent.action)
        assertEquals("Maya",intent.getStringExtra(android.provider.ContactsContract.Intents.Insert.NAME))
        assertTrue(intent.getStringExtra(android.provider.ContactsContract.Intents.Insert.NOTES)!!.contains(id))
        assertFalse(intent.hasExtra(android.provider.ContactsContract.Intents.Insert.PHONE))
        val data=intent.getParcelableArrayListExtra<android.content.ContentValues>(android.provider.ContactsContract.Intents.Insert.DATA)!!
        assertEquals(id,data.single().getAsString(android.provider.ContactsContract.CommonDataKinds.Im.DATA))
        assertEquals("Cute Chat",data.single().getAsString(android.provider.ContactsContract.CommonDataKinds.Im.CUSTOM_PROTOCOL))
        assertFalse(intent.extras.toString().contains("CP1."))
    }
    @Test fun bottomTabsShowEncryptedCallHistoryAndPrivateOrdinaryMedia() {
        val instrument=InstrumentationRegistry.getInstrumentation(); val context=instrument.targetContext
        check(context.getSharedPreferences("native_settings",0).getString("name","") == "Native test ♥") { "Synthetic profile required; no real vault changes" }
        val activity=instrument.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        NativeTestUnlock.unlock(activity)
        val app=context.applicationContext as CutieApplication
        await("Fixture owner ready") { app.owner.ready }
        val friend=app.owner.contacts.firstOrNull() ?: error("Synthetic contact fixture required")
        val id=WireCodec.id(); app.owner.recordCallStart(id,friend.uid,false,false); app.owner.recordCallConnected(id); app.owner.recordCallEnd(id,65000,"ended")
        await("Call history committed") { app.owner.callHistory.any { it.id == id && it.duration == 65000L } }
        instrument.runOnMainSync {
            MainActivity::class.java.getDeclaredMethod("home").apply { isAccessible=true }.invoke(activity)
            val navigation=views(activity.window.decorView).filterIsInstance<com.google.android.material.bottomnavigation.BottomNavigationView>().single()
            assertEquals(4,navigation.menu.size());assertNull(navigation.menu.findItem(905)); navigation.menu.performIdentifierAction(902,0)
            assertTrue(views(activity.window.decorView).filterIsInstance<android.widget.TextView>().any { it.text.toString().contains("01:05") })
            val callsNavigation=views(activity.window.decorView).filterIsInstance<com.google.android.material.bottomnavigation.BottomNavigationView>().single()
            callsNavigation.menu.performIdentifierAction(903,0)
            assertTrue(views(activity.window.decorView).any { it.tag == "private-media-timeline" })
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            activity.finish()
        }
    }
    @Test fun qrIsAnAuxiliaryPageWithCameraAndCodeTogetherWithoutContactHubButtons() {
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        check(context.getSharedPreferences("native_settings",0).getString("name","")=="Native test ♥")
        val activity=instrument.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        NativeTestUnlock.unlock(activity)
        val app=context.applicationContext as CutieApplication
        await("Fixture owner ready") { app.owner.ready }
        instrument.runOnMainSync {
            // Defer a new permission dialog, never grant CAMERA or bypass its runtime check.
            MainActivity::class.java.getDeclaredField("qrPermissionAsked").apply { isAccessible=true }.setBoolean(activity,true)
            MainActivity::class.java.getDeclaredMethod("contacts").apply { isAccessible=true }.invoke(activity)
            val tree=views(activity.window.decorView)
            val navigation=tree.filterIsInstance<com.google.android.material.bottomnavigation.BottomNavigationView>().single()
            assertEquals(4,navigation.menu.size());assertNull(navigation.menu.findItem(905))
            val pair=tree.single { it.tag=="qr-camera-and-code" }
            assertTrue(views(pair).any { it is NativeQrScanner })
            assertTrue(views(pair).any { it.tag=="my-contact-qr" })
            assertFalse(tree.filterIsInstance<android.widget.TextView>().any { it.text.toString() in setOf("Add by private ID / phone Contacts","Save my ID in phone Contacts") })
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0)
            activity.finish()
        }
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun await(message: String, predicate: () -> Boolean) {
        val end = System.nanoTime() + 45_000_000_000L
        while (!predicate() && System.nanoTime() < end) Thread.sleep(100)
        assertTrue(message, predicate())
    }
    @Test fun settingsCategoriesAndKeyboardKeepComposerVisibleWithoutLosingDraft() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        check(context.getSharedPreferences("native_settings", 0).getString("name", "") == "Native test ♥") { "Synthetic launcher profile required; real vault preserved" }
        context.getSharedPreferences("native_settings", 0).edit().putBoolean("reduceMotion", true).putString("visualStyle", CuteUi.STYLE_MATERIAL).commit()
        val activity = instrument.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        NativeTestUnlock.unlock(activity)
        val app = context.applicationContext as CutieApplication
        await("Fixture owner ready") { app.owner.ready }
        instrument.runOnMainSync {
            views(activity.window.decorView).first { it.contentDescription?.toString() == "Settings" }.performClick()
            renderSyntheticSettings(activity, "overview")
            assertTrue(views(activity.window.decorView).any { it.contentDescription?.toString()?.startsWith("Notifications.") == true })
            views(activity.window.decorView).first { it.contentDescription?.toString()?.startsWith("Appearance.") == true }.performClick()
            assertTrue(views(activity.window.decorView).filterIsInstance<android.widget.TextView>().any { it.text.toString() == "Reduce animations" })
            renderSyntheticSettings(activity, "appearance")
            activity.onBackPressedDispatcher.onBackPressed()
            assertTrue(views(activity.window.decorView).any { it.contentDescription?.toString()?.startsWith("Notifications.") == true })
            activity.onBackPressedDispatcher.onBackPressed()
        }
        val fixture = NativeTestContext("layout" + System.nanoTime().toString())
        val invitation = NativeVault.createFresh(fixture).use { vault -> NativeIdentity.loadExisting(vault).use { it.contactCode("Layout test peer") } }
        val peer = AtomicReference<String>()
        app.owner.addContact(invitation) { peer.set(it) }
        await("Synthetic contact saved") { peer.get() != null }
        instrument.runOnMainSync {
            MainActivity::class.java.getDeclaredMethod("openChat", String::class.java).apply { isAccessible = true }.invoke(activity, peer.get())
        }
        instrument.waitForIdleSync()
        lateinit var input: EditText
        instrument.runOnMainSync {
            input = views(activity.window.decorView).filterIsInstance<EditText>().single { it.tag == "chat-composer" }
            input.setText("Synthetic draft")
        }
        await("Chat window is focused and laid out") {
            var ready = false
            instrument.runOnMainSync { ready = input.isAttachedToWindow && input.isLaidOut && activity.window.decorView.hasWindowFocus() }
            ready
        }
        val editor = Rect()
        instrument.runOnMainSync { assertTrue(input.getGlobalVisibleRect(editor)) }
        // Use an actual, grounded editor tap, rather than racing showSoftInput
        // before Android has served the replacement content view.
        val time = android.os.SystemClock.uptimeMillis()
        instrument.sendPointerSync(android.view.MotionEvent.obtain(time, time, android.view.MotionEvent.ACTION_DOWN, editor.exactCenterX(), editor.exactCenterY(), 0))
        instrument.sendPointerSync(android.view.MotionEvent.obtain(time, time + 40, android.view.MotionEvent.ACTION_UP, editor.exactCenterX(), editor.exactCenterY(), 0))
        fun keyboardShown(): Boolean {
            var shown = false
            instrument.runOnMainSync { shown = ViewCompat.getRootWindowInsets(input)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            return shown
        }
        await("Real Android keyboard opens") { keyboardShown() }
        instrument.runOnMainSync { }
        instrument.runOnMainSync {
            val ime = ViewCompat.getRootWindowInsets(input)!!.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val rectangle = Rect(); assertTrue(input.getGlobalVisibleRect(rectangle))
            assertTrue("Composer is above the real keyboard", rectangle.bottom <= activity.window.decorView.height - ime)
            assertEquals("Draft survives keyboard resize", "Synthetic draft", input.text.toString())
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            activity.onBackPressedDispatcher.onBackPressed()
        }
        await("Back dismisses keyboard") { !keyboardShown() }
        instrument.runOnMainSync {
            assertEquals("Back keeps chat and draft", "Synthetic draft", input.text.toString())
            MainActivity::class.java.getDeclaredField("unlocked").apply { isAccessible = true }.setBoolean(activity, false)
            MainActivity::class.java.getDeclaredMethod("lock").apply { isAccessible = true }.invoke(activity)
            activity.onBackPressedDispatcher.onBackPressed()
            assertTrue("Back cannot navigate around the app lock", activity.isFinishing)
        }
    }
    @Test fun mediaSharingChoicesAndContactStatusWorkWithoutOpeningKeyboard() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        check(context.getSharedPreferences("native_settings", 0).getString("name", "") == "Native test ♥") { "Synthetic launcher profile required; real vault preserved" }
        context.getSharedPreferences("native_settings", 0).edit().putBoolean("reduceMotion", true).commit()
        val activity = instrument.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        NativeTestUnlock.unlock(activity)
        val app = context.applicationContext as CutieApplication
        await("Fixture owner ready") { app.owner.ready }
        val fixture = NativeTestContext("medialayout" + System.nanoTime().toString())
        val invitation = NativeVault.createFresh(fixture).use { vault -> NativeIdentity.loadExisting(vault).use { it.contactCode("Layout media peer") } }
        val saved = AtomicReference<String>()
        app.owner.addContact(invitation) { saved.set(it) }
        await("Synthetic contact saved") { saved.get() != null }
        val peer = saved.get()
        instrument.runOnMainSync {
            MainActivity::class.java.getDeclaredMethod("home").apply { isAccessible = true }.invoke(activity)
            val status = views(activity.window.decorView).filterIsInstance<android.widget.TextView>().single { it.tag == "contact-connection-${peer}" }
            assertTrue("Contact status is visible without opening chat", status.isShown && status.text.isNotBlank())
        }
        lateinit var search: EditText
        instrument.runOnMainSync {
            search = views(activity.window.decorView).filterIsInstance<EditText>().single { it.hint?.toString() == "Search people" }
            search.setText("Layout")
        }
        app.owner.enter()
        val updated = java.util.concurrent.atomic.AtomicBoolean(false)
        app.owner.work { android.os.Handler(android.os.Looper.getMainLooper()).post { updated.set(true) } }
        await("Real owner state reaches Home") { updated.get() }
        instrument.runOnMainSync {
            assertSame("Status updates preserve the Home search field", search, views(activity.window.decorView).filterIsInstance<EditText>().single { it.hint?.toString() == "Search people" })
            assertEquals("Layout", search.text.toString())
            MainActivity::class.java.getDeclaredMethod("openChat", String::class.java).apply { isAccessible = true }.invoke(activity, peer)
        }
        // Feed a real app-private JPEG through the same selected-media read path.
        // Dialog text must not hide one/two-view controls again.
        val image = File(context.cacheDir, "native-capture/selection-fixture.jpg").apply { parentFile!!.mkdirs() }
        val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        image.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.media", image)
        instrument.runOnMainSync {
            MainActivity::class.java.getDeclaredMethod("readPickedAttachment", android.net.Uri::class.java, String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(activity, uri, peer, true, true)
        }
        var preview: android.app.Dialog? = null
        await("Photo sharing choices open") {
            instrument.runOnMainSync { preview = MainActivity::class.java.getDeclaredField("draftDialog").apply { isAccessible = true }.get(activity) as? android.app.Dialog }
            preview?.isShowing == true
        }
        instrument.runOnMainSync {
            val radios = views(preview!!.window!!.decorView).filterIsInstance<android.widget.RadioButton>().filter { it.tag?.toString()?.startsWith("media-view-limit-") == true }
            assertEquals("Normal, one-view and two-view are all present", 3, radios.size)
            radios.forEach { assertTrue("Every media choice is visible", it.isShown) }
            radios[2].performClick(); assertTrue(radios[2].isChecked)
            radios[1].performClick(); assertTrue(radios[1].isChecked)
            (preview as androidx.appcompat.app.AlertDialog).getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        }
        await("Chosen one-view mode reaches the encrypted attachment engine") {
            app.owner.messages.any { it.peer == peer && it.outgoing && it.kind == "attachment" && it.data.optInt("viewLimit") == 1 }
        }
        instrument.runOnMainSync { activity.finish() }
    }
    @Test fun composerActionsAndVoiceGesturesReleaseMicrophone() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        check(context.getSharedPreferences("native_settings", 0).getString("name", "") == "Native test ♥") { "Synthetic launcher profile required; real vault preserved" }
        check(context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) { "Grant MIC only on the disposable emulator before this test" }
        val activity = instrument.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        NativeTestUnlock.unlock(activity)
        val app = context.applicationContext as CutieApplication
        await("Fixture owner ready") { app.owner.ready }
        val fixture = NativeTestContext("voicecomposer" + System.nanoTime())
        val invitation = NativeVault.createFresh(fixture).use { vault -> NativeIdentity.loadExisting(vault).use { it.contactCode("Voice test peer") } }
        val saved = AtomicReference<String>()
        app.owner.addContact(invitation) { saved.set(it) }
        await("Synthetic contact saved") { saved.get() != null }
        val peer = saved.get()
        lateinit var mic: View
        fun recording(): Boolean {
            var active = false
            instrument.runOnMainSync { active = MainActivity::class.java.getDeclaredField("recorder").apply { isAccessible = true }.get(activity) != null }
            return active
        }
        fun audioMessages() = app.owner.messages.count { it.peer == peer && it.outgoing && it.kind == "attachment" && it.data.optString("mime") == "audio/mp4" }
        val intercepted = mutableListOf<Intent>()
        val monitor = object : android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): android.app.Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_OPEN_DOCUMENT || intent.component?.className == ChatCameraActivity::class.java.name) {
                    intercepted.add(intent); return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null)
                }
                return null
            }
        }
        instrument.addMonitor(monitor)
        try {
            instrument.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("openChat", String::class.java).apply { isAccessible = true }.invoke(activity, peer)
            }
            await("Composer laid out") {
                var shown = false
                instrument.runOnMainSync { shown = views(activity.window.decorView).any { it.tag == "chat-voice" && it.isLaidOut } }
                shown
            }
            instrument.runOnMainSync {
                val all = views(activity.window.decorView)
                listOf("chat-camera", "chat-gallery", "chat-audio-file", "chat-files", "chat-voice").forEach { tag ->
                    val action = all.single { it.tag == tag }
                    assertTrue("$tag visible", action.isShown); assertTrue("$tag touch target", action.width >= (48 * context.resources.displayMetrics.density).toInt())
                }
                mic = all.single { it.tag == "chat-voice" }
                (all.single { it.tag == "chat-composer" } as EditText).setText("Voice must preserve this draft")
            }
            // Intercepted picker/camera results are delivered asynchronously.
            // Wait for the actual resumed Activity, never bypass foreground MIC.
            instrument.waitForIdleSync()
            await("Chat resumed after cancelled pickers") {
                var allowed = false
                instrument.runOnMainSync { allowed = MainActivity::class.java.getDeclaredMethod("canRecordVoice", String::class.java).apply { isAccessible = true }.invoke(activity, peer) as Boolean }
                allowed
            }
            instrument.runOnMainSync {
                mic.performClick()
                println("Synthetic voice start: recorder=" + (MainActivity::class.java.getDeclaredField("recorder").apply { isAccessible = true }.get(activity) != null) + " failure=" + MainActivity::class.java.getDeclaredField("voiceStartFailure").apply { isAccessible = true }.get(activity))
            }
            await("Tap starts real native MIC recording") { recording() }
            Thread.sleep(1400)
            instrument.runOnMainSync { mic.performClick() }
            await("Tap sends the real AAC attachment to the encrypted local outbox") { audioMessages() == 1 }
            assertFalse("MIC released after send", recording())
            await("Voice cache cleaned after send") { context.cacheDir.listFiles()?.none { it.name.startsWith("native-voice-") } == true }
            fun touch(action: Int, x: Float) {
                instrument.runOnMainSync {
                    val now = android.os.SystemClock.uptimeMillis()
                    android.view.MotionEvent.obtain(now, now, action, x, 24f, 0).also { event -> mic.dispatchTouchEvent(event); event.recycle() }
                }
            }
            touch(android.view.MotionEvent.ACTION_DOWN, 24f)
            await("Hold begins recording") { recording() }
            Thread.sleep(700)
            touch(android.view.MotionEvent.ACTION_MOVE, -120 * context.resources.displayMetrics.density)
            touch(android.view.MotionEvent.ACTION_UP, -120 * context.resources.displayMetrics.density)
            assertFalse("Slide cancellation releases MIC", recording()); assertEquals("Slide cancellation sends nothing", 1, audioMessages())
            assertTrue("Cancelled file deleted", context.cacheDir.listFiles()?.none { it.name.startsWith("native-voice-") } == true)
            touch(android.view.MotionEvent.ACTION_DOWN, 24f)
            await("Second hold records") { recording() }
            Thread.sleep(1400)
            touch(android.view.MotionEvent.ACTION_UP, 24f)
            await("Hold release sends audio", { audioMessages() == 2 })
            assertFalse("Release stops MIC", recording())
            instrument.runOnMainSync {
                assertEquals("Text draft intact", "Voice must preserve this draft", (views(activity.window.decorView).single { it.tag == "chat-composer" } as EditText).text.toString())
                val all = views(activity.window.decorView)
                all.single { it.tag == "chat-audio-file" }.performClick()
                all.single { it.tag == "chat-files" }.performClick()
                all.single { it.tag == "chat-camera" }.performClick()
                assertTrue(intercepted.any { it.action == Intent.ACTION_OPEN_DOCUMENT && it.type == "audio/*" })
                assertTrue(intercepted.any { it.action == Intent.ACTION_OPEN_DOCUMENT && it.type == "*/*" })
                assertTrue(intercepted.any { it.component?.className == ChatCameraActivity::class.java.name })
            }
            instrument.waitForIdleSync()
            await("Chat resumed before lifecycle recording") {
                var allowed = false
                instrument.runOnMainSync { allowed = MainActivity::class.java.getDeclaredMethod("canRecordVoice", String::class.java).apply { isAccessible = true }.invoke(activity, peer) as Boolean }
                allowed
            }
            instrument.runOnMainSync {
                mic.performClick()
            }
            await("Record again before lifecycle cancellation") { recording() }
            instrument.runOnMainSync { activity.finish() }
            await("Leaving Activity releases microphone") { !recording() }
            assertEquals("Leaving does not send recording", 2, audioMessages())
            assertTrue("Leaving deletes voice cache", context.cacheDir.listFiles()?.none { it.name.startsWith("native-voice-") } == true)
        } finally {
            instrument.removeMonitor(monitor)
            instrument.runOnMainSync { activity.finish() }
        }
    }
    @Test fun homeSecurityPanelHidesAndRevealsWithoutReplacingSearch() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        check(context.getSharedPreferences("native_settings", 0).getString("name", "") == "Native test ♥") { "Synthetic launcher profile required; real vault preserved" }
        val preferences = context.getSharedPreferences("native_settings", 0)
        preferences.edit().putBoolean("home.securityCollapsed", false).putBoolean("reduceMotion", true).commit()
        val activity = instrument.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        NativeTestUnlock.unlock(activity)
        val app = context.applicationContext as CutieApplication
        await("Fixture owner ready") { app.owner.ready }
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        app.owner.work { android.os.Handler(android.os.Looper.getMainLooper()).post { settled.set(true) } }
        await("Initial owner refresh reaches Home") { settled.get() }
        lateinit var header: View
        lateinit var panel: View
        lateinit var title: android.widget.TextView
        lateinit var search: EditText
        await("Home header attached and laid out") {
            var laidOut = false
            instrument.runOnMainSync { laidOut = views(activity.window.decorView).any { it.tag == "home-security-header" && it.isLaidOut && it.width > 0 } }
            laidOut
        }
        instrument.runOnMainSync {
            val all = views(activity.window.decorView)
            header = all.single { it.tag == "home-security-header" }; panel = all.single { it.tag == "home-security-panel" }
            title = all.single { it.tag == "home-brand-title" } as android.widget.TextView
            search = all.filterIsInstance<EditText>().single { it.hint?.toString() == "Search people" }
            search.setText("Keep this search")
            assertTrue(panel.isShown)
            assertFalse(all.filterIsInstance<android.widget.TextView>().any { it.text.toString().contains("Private by design") })
            assertTrue(all.filterIsInstance<android.widget.TextView>().any { it.text.toString() == "Secure · P2P · End-to-end encrypted" })
            renderSyntheticSettings(activity, "home-expanded")
            all.single { it.tag == "home-security-close" }.performClick()
            assertEquals(View.GONE, panel.visibility); assertTrue(preferences.getBoolean("home.securityCollapsed", false))
            renderSyntheticSettings(activity, "home-collapsed")
        }
        fun swipe(from: Float, to: Float) {
            instrument.runOnMainSync {
                val time = android.os.SystemClock.uptimeMillis()
                listOf(android.view.MotionEvent.ACTION_DOWN to from, android.view.MotionEvent.ACTION_MOVE to to, android.view.MotionEvent.ACTION_UP to to).forEach { (action, y) ->
                    android.view.MotionEvent.obtain(time, time + 50, action, 110 * context.resources.displayMetrics.density, y * context.resources.displayMetrics.density, 0).also { event -> header.dispatchTouchEvent(event); event.recycle() }
                }
            }
        }
        swipe(30f, 95f)
        instrument.runOnMainSync {
            assertTrue("Pull reveals panel", panel.isShown); assertFalse(preferences.getBoolean("home.securityCollapsed", true))
            assertEquals(34f, title.textSize / context.resources.displayMetrics.scaledDensity, .1f)
            assertSame(search, views(activity.window.decorView).single { it === search }); assertEquals("Keep this search", search.text.toString())
        }
        swipe(60f, -5f)
        instrument.runOnMainSync {
            assertEquals("Up swipe hides panel", View.GONE, panel.visibility)
            assertEquals(26f, title.textSize / context.resources.displayMetrics.scaledDensity, .1f)
            preferences.edit().putBoolean("reduceMotion", false).commit()
            views(activity.window.decorView).single { it.tag == "home-security-toggle" }.performClick()
        }
        await("Animated title reaches its enlarged size") {
            var complete = false
            instrument.runOnMainSync { complete = panel.isShown && kotlin.math.abs(title.textSize / context.resources.displayMetrics.scaledDensity - 34f) < .1f }
            complete
        }
        lateinit var scroll: HomeChatScrollView
        var clicks = 0
        instrument.runOnMainSync {
            preferences.edit().putBoolean("reduceMotion",true).commit()
            scroll = views(activity.window.decorView).filterIsInstance<HomeChatScrollView>().single()
            scroll.removeAllViews()
            val rows = android.widget.LinearLayout(context).apply { orientation = android.widget.LinearLayout.VERTICAL }
            repeat(30) { rows.addView(android.widget.Button(context).apply { text = "Synthetic person $it"; setOnClickListener { clicks++ } },android.widget.LinearLayout.LayoutParams(-1,(72*context.resources.displayMetrics.density).toInt())) }
            scroll.addView(rows)
        }
        instrument.waitForIdleSync()
        fun rowGesture(from: Float,to: Float) {
            instrument.runOnMainSync {
                val time=android.os.SystemClock.uptimeMillis()
                listOf(android.view.MotionEvent.ACTION_DOWN to from,android.view.MotionEvent.ACTION_MOVE to to,android.view.MotionEvent.ACTION_UP to to).forEachIndexed { i,(action,y) ->
                    android.view.MotionEvent.obtain(time,time+i*80,action,90*context.resources.displayMetrics.density,y*context.resources.displayMetrics.density,0).also { scroll.dispatchTouchEvent(it);it.recycle() }
                }
            }
        }
        rowGesture(70f,15f)
        instrument.runOnMainSync {
            assertEquals("Browsing later rows hides the panel",View.GONE,panel.visibility)
            scroll.scrollTo(0,(600*context.resources.displayMetrics.density).toInt())
        }
        rowGesture(15f,100f)
        instrument.runOnMainSync { assertEquals("Returning through earlier rows must not reveal before the top",View.GONE,panel.visibility); scroll.scrollTo(0,0) }
        instrument.waitForIdleSync()
        rowGesture(15f,100f)
        instrument.runOnMainSync {
            assertTrue("Pull starting on a clickable row reveals the panel",panel.isShown)
            assertEquals("Pull must cancel the row click",0,clicks)
        }
        rowGesture(15f,15f)
        instrument.runOnMainSync { assertEquals("Ordinary row taps still work",1,clicks); assertEquals("Keep this search",search.text.toString()); activity.finish() }
        preferences.edit().putBoolean("reduceMotion", true).commit()
    }
    private fun renderSyntheticSettings(activity: MainActivity, name: String) {
        // Only disposable Settings/Home, never chat, QR, keys or lock contents.
        val decor = activity.window.decorView
        views(decor).forEach { it.animate().cancel(); it.alpha = 1f; it.translationY = 0f }
        decor.measure(View.MeasureSpec.makeMeasureSpec(decor.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(decor.height, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
        val bitmap = android.graphics.Bitmap.createBitmap(decor.width, decor.height, android.graphics.Bitmap.Config.ARGB_8888)
        decor.draw(android.graphics.Canvas(bitmap))
        File(activity.getExternalFilesDir(null), "native-ui-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
