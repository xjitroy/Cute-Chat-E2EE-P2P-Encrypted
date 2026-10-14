package app.cutiepie.cutie_pie.core

import org.junit.Assert.*
import org.junit.Test

class PrivateLibraryTest {
    @Test fun missedCallIsAtomicIdempotentAndDoesNotBecomeAWireMessage() {
        JdbcTestVault().use { vault ->
            vault.transaction { NativeSchema.create(it) }
            val library=PrivateLibrary(vault)
            assertNull(library.finish("never-reached-peer",300,0,"unanswered"))
            assertTrue(library.calls().isEmpty())
            library.begin("call-a","peer",true,true,100)
            val missed=library.finish("call-a",300,0,"missed")!!
            assertTrue(missed.incoming); assertNull(missed.connected)
            assertNull(library.finish("call-a",400,0,"missed"))
            val event=vault.transaction { it.rows("SELECT * FROM messages") }.single()
            assertEquals("call_event",event["kind"]); assertEquals(3L,event["status"])
            assertEquals(0L,vault.transaction { it.count("SELECT COUNT(*) FROM outbox") })
        }
    }
    @Test fun chatEventFailureRollsBackCallEndInsteadOfProducingFalseMissedAlert() {
        JdbcTestVault().use { vault ->
            vault.transaction { NativeSchema.create(it) };val library=PrivateLibrary(vault)
            library.begin("a","peer",true,false,100)
            vault.failExecute={ it.startsWith("INSERT OR IGNORE INTO messages") }
            assertThrows(IllegalStateException::class.java) { library.finish("a",300,0,"missed") }
            vault.failExecute=null; assertNull(library.calls().single().ended)
            assertTrue(vault.transaction { it.rows("SELECT * FROM messages") }.isEmpty())
        }
    }
    @Test fun interruptedUnansweredIncomingOfferRecoversAsMissedWithoutInventedDuration() {
        JdbcTestVault().use { vault ->
            vault.transaction { NativeSchema.create(it) };val library=PrivateLibrary(vault)
            library.begin("a","peer",true,false,100);library.recoverInterrupted(400)
            assertEquals("missed",library.calls().single().outcome);assertEquals(0L,library.calls().single().duration)
            library.recoverInterrupted(900)
            assertEquals(1,vault.transaction { it.rows("SELECT * FROM messages WHERE kind='call_event'") }.size)
        }
    }
    @Test fun callRecordIsIdempotentAndCrashDoesNotInventDuration() {
        JdbcTestVault().use { vault ->
            vault.transaction { NativeSchema.create(it); it.execute("INSERT INTO kv(key,value) VALUES('ratchet-sentinel','keep')") }
            val library = PrivateLibrary(vault)
            library.begin("a", "peer", false, true, 100)
            library.begin("a", "wrong-peer", true, false, 200)
            library.connected("a", 250); library.connected("a", 300)
            library.finish("a", 2000, 1750, "ended"); library.finish("a", 4000, 9999, "ended")
            val call = library.calls().single()
            assertEquals("peer", call.peer); assertEquals(250L, call.connected); assertEquals(1750L, call.duration)
            library.begin("b", "peer", true, false, 5000); library.connected("b", 6000)
            library.recoverInterrupted(7000)
            val interrupted = library.calls().first()
            assertEquals("interrupted", interrupted.outcome); assertNull(interrupted.duration)
            assertEquals("keep", vault.get("ratchet-sentinel")); assertEquals(3, vault.transaction { it.version })
        }
    }
    @Test fun failedWriteIsTransactionalAndDoesNotChangePriorCall() {
        JdbcTestVault().use { vault ->
            vault.transaction { NativeSchema.create(it) }
            val library = PrivateLibrary(vault); library.begin("a", "peer", true, false, 100)
            vault.failExecute = { it.startsWith("UPDATE private_calls") }
            assertThrows(IllegalStateException::class.java) { library.finish("a", 300, 0, "missed") }
            vault.failExecute = null
            assertNull(library.calls().single().ended)
        }
    }
    @Test fun galleryIncludesOnlyCompleteOrdinaryMediaAcrossChats() {
        JdbcTestVault().use { vault ->
            vault.transaction { NativeSchema.create(it) }
            val library = PrivateLibrary(vault)
            vault.transaction { tx ->
                for ((id, purpose, complete) in listOf(Triple("normal", "file", 1), Triple("limited", "view_once", 1), Triple("twice-video", "view_twice", 1), Triple("once-audio", "view_once", 1), Triple("pending", "file", 0))) {
                    val mime = when(id) { "twice-video" -> "video/mp4"; "once-audio" -> "audio/mp4"; else -> "image/jpeg" }
                    tx.execute("INSERT INTO transfers(id,peer,name,mime,size,hash,purpose,completed) VALUES(?,?,?,?,?,?,?,?)", id,"peer", "$id.jpg", mime, 8, "hash", purpose, complete)
                    tx.execute("INSERT INTO messages(id,peer,outgoing,kind,data,created_at) VALUES(?,?,0,'attachment',?,?)", "message-$id", "peer", "{\"id\":\"$id\"}", 100)
                }
            }
            assertEquals(listOf("normal"), library.media().map { it.id })
        }
    }
}
