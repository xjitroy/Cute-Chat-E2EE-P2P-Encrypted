package app.cutiepie.cutie_pie.core

import android.content.Context
import app.cutiepie.cutie_pie.MainActivity
import app.cutiepie.cutie_pie.NativeMediaCalls
import app.cutiepie.cutie_pie.CutieApplication
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real WebRTC + SQLCipher + Signal, two disposable owners in one emulator.
 * Uses the authorized coordination Worker for synthetic contact/setup metadata only.
 * This is not evidence for two physical phones or microphone/video capture. */
@RunWith(AndroidJUnit4::class)
class NativeDirectPeerTest {
    private class Isolated(base: Context, private val slot: String) : ContextWrapper(base) {
        override fun getPackageName() = super.getPackageName() + "." + slot
        override fun getFilesDir() = File(super.getFilesDir(), slot).apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences("$slot.$name", mode)
        override fun getApplicationContext(): Context = this
    }
    private fun await(message: String, seconds: Int = 60, condition: () -> Boolean) {
        val until = System.nanoTime() + seconds * 1_000_000_000L
        while (!condition() && System.nanoTime() < until) Thread.sleep(100)
        assertTrue(message, condition())
    }
    @Test fun directEncryptedMessagesAndFileDoNotBlockEachOther() {
        val context = NativeTestContext("peers")
        check(context.packageName.endsWith(".test"))
        val suffix = System.nanoTime().toString()
        val a = NativeOwner(Isolated(context, "a$suffix")); val b = NativeOwner(Isolated(context, "b$suffix"))
        a.enter(); b.enter(); a.setup("Alice", true); b.setup("Bob", true)
        try {
            try { await("Fresh peer owners") { a.ready && b.ready } } catch (failure: AssertionError) { throw AssertionError("Fresh peer owners: ${a.status} / ${b.status}", failure) }
            val signalField = NativeOwner::class.java.getDeclaredField("signal").apply { isAccessible = true }
            val sa = signalField.get(a) as SignedSignaling; val sb = signalField.get(b) as SignedSignaling
            a.addContact(b.code) {}; b.addContact(a.code) {}
            await("Signed mutual contacts") { a.contacts.isNotEmpty() && b.contacts.isNotEmpty() }
            val peerA = b.contacts.single().uid; val peerB = a.contacts.single().uid
            try { await("Authenticated P2P") { peerB in a.online && peerA in b.online } } catch (failure: AssertionError) { fun diagnostic(owner: NativeOwner): String = owner.executor.submit<String> {
                    fun field(name: String): Any? = NativeOwner::class.java.getDeclaredField(name).apply { isAccessible = true }.get(owner)
                    val accepted = field("remoteAccepted") as Set<*>
                    val accepting = field("accepting") as Set<*>
                    val targets = field("connectUntil") as Map<*, *>
                    val channels = field("channels") as Map<*, *>
                    "foreground=${owner.foreground}, lease=${owner.hasLease()}, wanted=${field("wanted")}, accepted=${accepted.size}, accepting=${accepting.size}, targets=${targets.size}, channels=${channels.size}, readyChannels=${channels.values.count { (it as NativePeerChannel).ready }}"
                }.get(5, java.util.concurrent.TimeUnit.SECONDS)
                val da = diagnostic(a); val db = diagnostic(b)
                throw AssertionError("Authenticated P2P: ${a.status} / ${b.status}; coordination sockets ${sa.connected}/${sb.connected}; $da / $db", failure) }
            a.foregroundPeer(peerB); b.foregroundPeer(peerA)
            a.send(peerB, "native direct first", null)
            await("Signal plaintext arrives only at receiver") { b.messages.any { it.data.optString("text") == "native direct first" && !it.outgoing } }
            val bytes = ByteArray(180001) { (it % 251).toByte() }
            a.sendAttachment(peerB, "synthetic.bin", "application/octet-stream", bytes)
            b.send(peerA, "text during file", null)
            await("Bidirectional text during file") { a.messages.any { it.data.optString("text") == "text during file" } }
            await("Native file delivery", 90) { b.messages.any { it.kind == "attachment" && it.data.optString("name") == "synthetic.bin" } }
            a.send(peerB, "native direct after file", null)
            await("Post-file messages still deliver") { b.messages.any { it.data.optString("text") == "native direct after file" } }
            await("Authenticated delivery receipt") { a.messages.any { it.data.optString("text") == "native direct after file" && it.status >= 2 } }
            // An online alert is metadata only; names come from the local alias.
            a.renameContact(peerB, "My saved Bob")
            await("Local alias persisted") { a.contacts.single().name == "My saved Bob" }
            val arrivals=java.util.concurrent.atomic.AtomicInteger()
            a.onOnlineAlert={ contact -> assertEquals("My saved Bob",a.contacts.single { it.uid==contact }.name); arrivals.incrementAndGet() }
            val arrival=org.json.JSONObject().put("kind","ping").put("sender",peerB).put("requestId",WireCodec.id())
                .put("expiresAt",System.currentTimeMillis()+60000).put("initiator",peerA).put("onlineAlert",true)
            a.push(arrival); a.push(arrival)
            await("Foreground Online Ping") { arrivals.get()==1 }
            a.executor.submit {}.get(5,java.util.concurrent.TimeUnit.SECONDS)
            assertEquals("Duplicate socket/push alert suppressed",1,arrivals.get())
            // Real audio offer/answer + ICE are transported by the same Signal
            // channel. Only OS call presentation is replaced, since one emulator
            // cannot represent two independently registered Telecom phone calls.
            val instrument=InstrumentationRegistry.getInstrumentation()
            val target=instrument.targetContext
            val host=instrument.startActivitySync(android.content.Intent(target,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            val app=target.applicationContext as CutieApplication
            val viewless=object:NativeMediaCalls.Presentation {
                override fun sync(raw:Map<String,Any>) {}
                override fun prepareAudio(id:String)=false
                override fun finish(id:String) {}
            }
            val ca=NativeMediaCalls(app,a,viewless)
            val cb=NativeMediaCalls(app,b,viewless)
            val audio=target.getSystemService(android.media.AudioManager::class.java)
            val interruption=android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
                .setOnAudioFocusChangeListener {}.build()
            try {
                a.executor.submit { ca.start(peerB,false) }.get(15,java.util.concurrent.TimeUnit.SECONDS)
                await("Encrypted incoming audio offer",25) { cb.status=="incoming" }
                b.executor.submit { cb.answer() }.get(15,java.util.concurrent.TimeUnit.SECONDS)
                await("Answer retains both media peers",30) { ca.status=="connected" && cb.status=="connected" }
                assertTrue(ca.connectedAt>0 && cb.connectedAt>0)
                instrument.runOnMainSync { assertEquals(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED,audio.requestAudioFocus(interruption)) }
                await("Receiver audio focus interruption observed",10) { cb.audioInterrupted }
                Thread.sleep(1800)
                assertTrue("Focus loss must not send call_end",ca.active && cb.active)
                // A late-created Telecom connection must restore the paused
                // microphone and ignore callbacks from the abandoned request.
                // API27 permits only one recorder at a time. The receiver is
                // the successful recorder (the caller's concurrent capture is
                // rejected by the OS), so resume that receiver, matching the
                // single-recorder ownership on each real physical phone.
                b.executor.submit { cb.adoptTelecomAudio(cb.id) }.get(5,java.util.concurrent.TimeUnit.SECONDS)
                assertFalse("Late Telecom handoff restores receiver audio",cb.audioInterrupted)
                a.send(peerB,"text during answered audio call",null)
                await("Chat remains usable during call") { b.messages.any { it.data.optString("text")=="text during answered audio call" } }
                a.executor.submit { ca.end() }.get(15,java.util.concurrent.TimeUnit.SECONDS)
                await("Authenticated hangup reaches receiver",15) { !cb.active }
            } finally {
                audio.abandonAudioFocusRequest(interruption)
                a.executor.submit { ca.end() }.get(15,java.util.concurrent.TimeUnit.SECONDS)
                b.executor.submit { cb.end() }.get(15,java.util.concurrent.TimeUnit.SECONDS)
                instrument.runOnMainSync { host.finish() }
            }
            a.blockContact(peerB)
            await("Local block closes authenticated peer without deleting history") {
                a.contacts.isEmpty() && a.blockedContacts.singleOrNull()?.uid == peerB && peerB !in a.online
            }
            a.push(org.json.JSONObject(arrival.toString()).put("requestId", WireCodec.id()))
            a.executor.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals("Blocked peer cannot deliver an Online Ping", 1, arrivals.get())
            assertTrue("Block retains encrypted history", a.messages.any { it.data.optString("text") == "native direct first" })
            a.unblockContact(peerB)
            await("Explicit unblock preserves the signed contact") { a.contacts.singleOrNull()?.uid == peerB && a.blockedContacts.isEmpty() }
        } finally { a.stop(); b.stop() }
    }
}
