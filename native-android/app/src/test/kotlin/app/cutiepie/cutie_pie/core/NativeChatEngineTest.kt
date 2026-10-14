package app.cutiepie.cutie_pie.core

import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.whispersystems.libsignal.SignalProtocolAddress

class NativeChatEngineTest {
    private lateinit var a: JdbcTestVault
    private lateinit var b: JdbcTestVault
    private lateinit var alice: NativeChatEngine
    private lateinit var bob: NativeChatEngine
    private lateinit var aid: String
    private lateinit var bid: String
    @Before fun setup() {
        val fixture = javaClass.getResourceAsStream("/migration/dart-v2.json")!!.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        assertTrue(fixture.getBoolean("synthetic"))
        aid = fixture.getString("aliceUid"); bid = fixture.getString("bobUid")
        a = JdbcTestVault(); b = JdbcTestVault()
        fun init(vault: JdbcTestVault, owner: String, other: String) = vault.transaction { db ->
            NativeSchema.create(db)
            val records = fixture.getJSONArray("${owner}Records")
            for (i in 0 until records.length()) {
                val record = records.getJSONObject(i)
                if (record.getString("kind") != "session") db.execute("INSERT INTO signal_records(kind,id,bytes) VALUES(?,?,?)",
                    record.getString("kind"), record.getString("id"), WireCodec.decode(record.getString("bytes")))
            }
            db.execute("INSERT INTO kv(key,value) VALUES('signal.registration',?)", fixture.getString("${owner}Registration"))
            db.execute("INSERT INTO contacts(uid,bundle,alias,verified) VALUES(?,?,?,1)", fixture.getString("${other}Uid"), WireCodec.canonical(fixture.getJSONObject("${other}Bundle")), other)
        }
        init(a, "alice", "bob"); init(b, "bob", "alice")
        alice = NativeChatEngine(a, fixture.getJSONObject("aliceBundle")); bob = NativeChatEngine(b, fixture.getJSONObject("bobBundle"))
    }
    @After fun close() { a.close(); b.close() }
    @Test fun encryptedOriginalFileAndChatTravelOverRealLocalSocketsWithoutInternetSignaling() {
        val executor=java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        val server=java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())
        val leftSocket=java.net.Socket(java.net.InetAddress.getLoopbackAddress(),server.localPort)
        val rightSocket=server.accept();server.close()
        val invited=java.util.concurrent.CountDownLatch(2);val fileArrived=java.util.concurrent.CountDownLatch(1);val texts=java.util.concurrent.CountDownLatch(2)
        val errors=java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        lateinit var left:LocalStreamLink;lateinit var right:LocalStreamLink
        val leftSent=mutableSetOf<String>();val rightSent=mutableSetOf<String>()
        fun flush(engine:NativeChatEngine,peer:String,link:LocalStreamLink,sent:MutableSet<String>) {
            engine.activatePending(peer);engine.pumpTransfers(peer)
            for(row in engine.pending(peer)) {
                val id=row["id"] as String
                if(id in sent) continue
                val raw=row["frame"] as String;assertFalse(raw.contains("offline.bin"))
                if(!link.send(raw,urgent=(row["priority"] as Long)<2)) break
                sent.add(id);engine.markHandedOff(row)
            }
        }
        fun packet(link:LocalStreamLink,data:JSONObject) {
            if(data.getString("type")=="invite") invited.countDown() else link.receiveFrame(data.getString("frame"))
        }
        left=LocalStreamLink("wifi","synthetic",leftSocket.getInputStream(),leftSocket.getOutputStream(),leftSocket,executor,::packet,{})
        right=LocalStreamLink("wifi","synthetic",rightSocket.getInputStream(),rightSocket.getOutputStream(),rightSocket,executor,::packet,{})
        try {
            executor.submit { left.begin("synthetic A");right.begin("synthetic B") }.get(5,java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(invited.await(5,java.util.concurrent.TimeUnit.SECONDS))
            val original=ByteArray(1_000_000) { (it*137).toByte() };var file=""
            executor.submit {
                val request=LocalSharingRules.request(left.nonce,right.nonce)
                left.bind(request,{ alice.enqueue(bid,"hello",JSONObject().put("requestId",request).put("photoHash",JSONObject.NULL)) },{ raw ->
                    try { val event=alice.receive(bid,raw,medium="wifi");if(event.kind=="text") texts.countDown();flush(alice,bid,left,leftSent) } catch(t:Throwable) { errors.add(t) }
                },{ flush(alice,bid,left,leftSent) },{})
                right.bind(request,{ bob.enqueue(aid,"hello",JSONObject().put("requestId",request).put("photoHash",JSONObject.NULL)) },{ raw ->
                    try { val event=bob.receive(aid,raw,medium="wifi");if(event.kind=="transfer_finish" && event.deliveredId!=null) fileArrived.countDown();if(event.kind=="text") texts.countDown();flush(bob,aid,right,rightSent) } catch(t:Throwable) { errors.add(t) }
                },{ flush(bob,aid,right,rightSent) },{})
                left.start();right.start()
                file=alice.stageAttachment(bid,"offline.bin","application/octet-stream",original)
                alice.enqueue(bid,"text",JSONObject().put("text","Text alongside the file"),true)
                bob.enqueue(aid,"text",JSONObject().put("text","Reply alongside the file"),true)
                flush(alice,bid,left,leftSent);flush(bob,aid,right,rightSent)
            }.get(10,java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(fileArrived.await(30,java.util.concurrent.TimeUnit.SECONDS));assertTrue(texts.await(10,java.util.concurrent.TimeUnit.SECONDS));assertTrue(errors.toString(),errors.isEmpty())
            val received=executor.submit<ByteArray> { bob.attachment(aid,file) }.get(5,java.util.concurrent.TimeUnit.SECONDS)
            assertArrayEquals(original,received);assertEquals(WireCodec.digest(original),WireCodec.digest(received))
        } finally { left.close();right.close();executor.shutdown();executor.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS);executor.shutdownNow();leftSocket.close();rightSocket.close() }
    }
    @Test fun genuineFileChunksReportProgressWithoutGeneratingContentAlerts() {
        alice.enqueue(bid,"hello",JSONObject().put("requestId",WireCodec.id()).put("photoHash",JSONObject.NULL))
        bob.enqueue(aid,"hello",JSONObject().put("requestId",WireCodec.id()).put("photoHash",JSONObject.NULL));drain()
        val id=alice.stageAttachment(bid,"offline.bin","application/octet-stream",ByteArray(50000) { (it%251).toByte() })
        var progressed=0
        drain(true) { event -> if(event.progress) { assertEquals("transfer_chunk",event.kind);assertFalse(event.content);progressed++ } }
        assertTrue(progressed>0);assertEquals(50000,bob.attachment(aid,id).size)
    }
    @Test fun bluetoothSpeechUsesSignalWithoutHistoryOutboxOrAckAccumulation() {
        alice.enqueue(bid,"hello",JSONObject().put("requestId",WireCodec.id()).put("photoHash",JSONObject.NULL))
        bob.enqueue(aid,"hello",JSONObject().put("requestId",WireCodec.id()).put("photoHash",JSONObject.NULL));drain()
        val call=WireCodec.id()
        val beforeA=a.transaction { it.count("SELECT COUNT(*) FROM inbox") };val beforeB=b.transaction { it.count("SELECT COUNT(*) FROM inbox") }
        for(i in 0 until 120) {
            val bytes=ByteArray(800) { (it+i).toByte() }
            val data=JSONObject().put("callId",call).put("seq",i).put("codec","PCMU").put("bytes",WireCodec.b64(bytes))
            val outgoing=alice.encryptLive(bid,data)
            // Random Base64 ciphertext can legitimately contain a four-letter marker.
            // Verify the public envelope contains no plaintext speech fields instead.
            val frame=JSONObject(outgoing)
            assertEquals(setOf("v","id","peer","type","cipher"),frame.keys().asSequence().toSet())
            assertFalse(frame.has("codec"));assertFalse(frame.has("data"));assertFalse(frame.has("bytes"))
            val result=bob.receive(aid,outgoing,live=true,medium="bluetooth")
            assertArrayEquals(bytes,WireCodec.decode(result.data.getString("bytes"),800));assertFalse(result.content)
            if(i==0) try { bob.receive(aid,outgoing,live=true,medium="bluetooth");fail("Live replay accepted") } catch(_:org.whispersystems.libsignal.DuplicateMessageException) { }
            val reverse=bob.encryptLive(aid,data);alice.receive(bid,reverse,live=true,medium="bluetooth")
        }
        assertEquals(beforeA,a.transaction { it.count("SELECT COUNT(*) FROM inbox") });assertEquals(beforeB,b.transaction { it.count("SELECT COUNT(*) FROM inbox") })
        assertEquals(0L,a.transaction { it.count("SELECT COUNT(*) FROM messages") });assertEquals(0L,b.transaction { it.count("SELECT COUNT(*) FROM messages") })
        assertTrue(alice.pending(bid).isEmpty());assertTrue(bob.pending(aid).isEmpty())
        alice.enqueue(bid,"text",JSONObject().put("text","Chat after speech"),true);drain()
        assertEquals(1L,b.transaction { it.count("SELECT COUNT(*) FROM messages WHERE kind='text'") })
    }
    @Test fun bluetoothRejectsNonPhotoTransferWithoutCommittingInboxOrTransfer() {
        alice.enqueue(bid,"hello",JSONObject().put("requestId",WireCodec.id()).put("photoHash",JSONObject.NULL))
        bob.enqueue(aid,"hello",JSONObject().put("requestId",WireCodec.id()).put("photoHash",JSONObject.NULL));drain()
        val before=b.transaction { it.count("SELECT COUNT(*) FROM inbox") }
        alice.stageAttachment(bid,"private.pdf","application/pdf",byteArrayOf(1,2,3))
        alice.pumpTransfers(bid)
        val row=alice.pending(bid).first()
        try { bob.receive(aid,row["frame"] as String,medium="bluetooth");fail("Bluetooth accepted a file") } catch(_:IllegalArgumentException) { }
        assertEquals(before,b.transaction { it.count("SELECT COUNT(*) FROM inbox") });assertEquals(0L,b.transaction { it.count("SELECT COUNT(*) FROM transfers") })
    }
    @Test fun assistControlsUseRealSignalAndRemainNonContentDuringFileTransfer() {
        alice.enqueue(bid,"consent_request",NativeExtensions.features(true));bob.enqueue(aid,"consent_request",NativeExtensions.features(true));drain()
        assertTrue(alice.supports(bid,"assist"));assertTrue(bob.supports(aid,"assist"))
        val call=WireCodec.id();val epoch=WireCodec.id();val frame=WireCodec.id()
        val file=alice.stageAttachment(bid,"parallel.bin","application/octet-stream",ByteArray(50000) { 7 })
        alice.enqueue(bid,"consent_request",NativeExtensions.envelope(JSONObject().put("type","assist_grant").put("callId",call).put("epoch",epoch)))
        alice.enqueue(bid,"consent_request",NativeExtensions.envelope(JSONObject().put("type","assist_input").put("callId",call).put("epoch",epoch).put("window",frame).put("seq",1).put("action","tap").put("x",200).put("y",300).put("x2",200).put("y2",300).put("duration",80)))
        val seen=mutableSetOf<String>();drain(true) { received ->
            if(received.kind=="consent_request") NativeExtensions.parse(received.data)?.let { extension -> if(extension.getString("type").startsWith("assist_")) { assertFalse(received.content);assertEquals(epoch,extension.getString("epoch"));seen.add(extension.getString("type")) } }
        }
        assertEquals(setOf("assist_grant","assist_input"),seen);assertEquals(50000,bob.attachment(aid,file).size)
        alice.enqueue(bid,"text",JSONObject().put("text","After control"),true);drain()
        assertEquals(1L,b.transaction { it.count("SELECT COUNT(*) FROM messages WHERE kind='text'") })
    }
    @Test fun encryptedCallOfferAnswerAndIceSurviveAConcurrentAttachment() {
        val call=WireCodec.id();val file=alice.stageAttachment(bid,"busy.bin","application/octet-stream",ByteArray(200000) { (it%251).toByte() })
        val sdp="v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"
        alice.enqueue(bid,"call_offer",JSONObject().put("callId",call).put("sdp",sdp).put("video",false).put("expiresAt",System.currentTimeMillis()+60000))
        alice.enqueue(bid,"call_ice",JSONObject().put("callId",call).put("candidate","candidate:fixture").put("sdpMid","0").put("sdpMLineIndex",0))
        val received=mutableSetOf<String>()
        drain(true) { event ->
            if(event.kind.startsWith("call_")) { assertFalse(event.content);assertEquals(call,event.data.getString("callId"));received.add(event.kind) }
            if(event.kind == "call_offer") bob.enqueue(aid,"call_answer",JSONObject().put("callId",call).put("sdp",sdp))
        }
        assertEquals(setOf("call_offer","call_answer","call_ice"),received)
        assertEquals(200000,bob.attachment(aid,file).size)
        alice.enqueue(bid,"text",JSONObject().put("text","Still connected"),true);drain()
        assertEquals(1L,b.transaction { it.count("SELECT COUNT(*) FROM messages WHERE kind='text'") })
    }
    @Test fun ringConfirmationTravelsInsideSignalWithoutBecomingChatContent() {
        alice.enqueue(bid,"consent_request",NativeExtensions.features()); bob.enqueue(aid,"consent_request",NativeExtensions.features()); drain()
        val call=WireCodec.id(); val data=NativeExtensions.envelope(JSONObject().put("type","call_ring").put("callId",call).put("expiresAt",123456L))
        alice.enqueue(bid,"consent_request",data)
        var confirmed=false
        drain { event -> if(event.kind == "consent_request" && NativeExtensions.parse(event.data)?.optString("type") == "call_ring") {
            assertFalse(event.content); assertEquals(call,NativeExtensions.parse(event.data)!!.getString("callId")); confirmed=true
        } }
        assertTrue(confirmed); assertEquals(0L,b.transaction { it.count("SELECT COUNT(*) FROM messages") })
        assertThrows(IllegalArgumentException::class.java) { NativeExtensions.envelope(JSONObject().put("type","call_ring").put("callId",call).put("expiresAt",-1)) }
        assertThrows(IllegalArgumentException::class.java) { NativeExtensions.envelope(JSONObject().put("type","call_ring").put("callId",call).put("expiresAt",12).put("name","should not travel")) }
    }
    @Test fun encryptedDeletionRemovesTextFilesAndCannotDeleteRecipientsOwnMessage() {
        alice.enqueue(bid,"consent_request",NativeExtensions.features()); bob.enqueue(aid,"consent_request",NativeExtensions.features()); drain()
        val text = alice.enqueue(bid,"text",JSONObject().put("text","Delete me"),true)
        val file = alice.stageAttachment(bid,"secret.bin","application/octet-stream",byteArrayOf(1,2,3)); drain(media=true)
        alice.deleteForEveryone(bid,text); alice.deleteForEveryone(bid,file); drain()
        for(v in listOf(a,b)) {
            assertEquals(2L,v.transaction { it.count("SELECT COUNT(*) FROM messages WHERE kind='deleted'") })
            assertEquals(0L,v.transaction { it.count("SELECT COUNT(*) FROM chunks WHERE transfer_id=?",file) })
        }
        val own = bob.enqueue(aid,"text",JSONObject().put("text","Recipient's own text"),true); drain()
        assertThrows(IllegalStateException::class.java) { alice.deleteForEveryone(bid,own) }
        alice.enqueue(bid,"consent_request",NativeExtensions.envelope(JSONObject().put("type","delete").put("id",own))); drain()
        assertEquals("text",b.transaction { it.rows("SELECT kind FROM messages WHERE id=?",own).single()["kind"] })
        alice.enqueue(bid,"text",JSONObject().put("text","After deletion"),true); drain()
        assertEquals(1L,b.transaction { it.count("SELECT COUNT(*) FROM messages WHERE kind='text' AND outgoing=0") })
    }
    @Test fun delayedTransferFramesDoNotResurrectDeletedMediaOrBreakRatchets() {
        alice.enqueue(bid,"consent_request",NativeExtensions.features()); bob.enqueue(aid,"consent_request",NativeExtensions.features()); drain()
        val file = alice.stageAttachment(bid,"pending.bin","application/octet-stream",ByteArray(30000) { 9 })
        alice.pumpTransfers(bid)
        val delayed = alice.pending(bid).map { it["frame"] as String }
        alice.deleteForEveryone(bid,file)
        val control = alice.pending(bid).first { it["priority"] == 1L }["frame"] as String
        assertEquals("consent_request",bob.receive(aid,control).kind)
        delayed.forEach { assertEquals("discarded",bob.receive(aid,it).kind) }
        drain(media=true)
        assertEquals(0L,b.transaction { it.count("SELECT COUNT(*) FROM transfers WHERE id=?",file) })
        alice.enqueue(bid,"text",JSONObject().put("text","Still working"),true); drain()
        assertEquals(1L,b.transaction { it.count("SELECT COUNT(*) FROM messages WHERE kind='text'") })
    }
    @Test fun deletionRequiresPeerSupportAndFailedQueueDoesNotEraseHistory() {
        val text = alice.enqueue(bid,"text",JSONObject().put("text","Preserve me"),true); drain()
        assertThrows(IllegalStateException::class.java) { alice.deleteForEveryone(bid,text) }
        bob.enqueue(aid,"consent_request",NativeExtensions.features()); drain()
        repeat(256) { alice.enqueue(bid,"text",JSONObject().put("text","Queued"),true) }
        assertThrows(IllegalStateException::class.java) { alice.deleteForEveryone(bid,text) }
        assertEquals("text",a.transaction { it.rows("SELECT kind FROM messages WHERE id=?",text).single()["kind"] })
    }
    @Test fun privateBioAndPhotoStayOutOfMessagesAndClearedPhotoCannotReturnFromDelayedTransfer() {
        val photo = byteArrayOf(1,2,3)
        alice.enqueue(bid,"consent_request",NativeExtensions.envelope(JSONObject().put("type","profile").put("bio","Hello ♥").put("photoHash",WireCodec.digest(photo))))
        alice.stageAttachment(bid,"profile.jpg","image/jpeg",photo,"photo"); drain(media=true) { assertFalse("A profile update must not trigger a message alert",it.content) }
        assertEquals(0L,b.transaction { it.count("SELECT COUNT(*) FROM messages") })
        assertArrayEquals(photo,b.transaction { it.rows("SELECT photo FROM contacts WHERE uid=?",aid).single()["photo"] as ByteArray })
        alice.enqueue(bid,"consent_request",NativeExtensions.envelope(JSONObject().put("type","profile").put("bio","").put("photoHash",JSONObject.NULL))); drain()
        alice.stageAttachment(bid,"stale.jpg","image/jpeg",photo,"photo"); drain(media=true)
        assertNull(b.transaction { it.rows("SELECT photo FROM contacts WHERE uid=?",aid).single()["photo"] })
        assertEquals(0L,b.transaction { it.count("SELECT COUNT(*) FROM transfers") })
    }
    @Test fun photoDeadlineSurvivesReopenAndDoesNotApplyToVideo() {
        val payload = byteArrayOf(8, 4, 2, 1)
        val image = alice.stageAttachment(bid, "photo.jpg", "image/jpeg", payload, "view_twice")
        val video = alice.stageAttachment(bid, "video.mp4", "video/mp4", payload, "view_twice")
        drain(media = true)
        val now = System.currentTimeMillis()
        assertArrayEquals(payload, bob.openMedia(aid, image, now))
        assertArrayEquals(payload, bob.openMedia(aid, video, now))
        assertFalse(bob.expireLimitedMedia(now + 29999))
        assertTrue(bob.expireLimitedMedia(now + 30000))
        assertThrows(IllegalStateException::class.java) { bob.openMedia(aid, image, now + 30001) }
        assertArrayEquals(payload, bob.openMedia(aid, video, now + 120000))
        bob.pumpMediaStates(aid); drain()
        val meta = a.transaction { JSONObject(it.rows("SELECT data FROM messages WHERE id=?", image).single()["data"] as String) }
        assertTrue(meta.getBoolean("peerExpired")); assertEquals(1, meta.getInt("peerViewsUsed"))
        alice.enqueue(bid, "text", JSONObject().put("text", "After expiry"), true); drain()
        assertTrue(b.transaction { it.rows("SELECT data FROM messages WHERE kind='text'").any { row -> JSONObject(row["data"] as String).getString("text") == "After expiry" } })
    }
    @Test fun deletionPersistsEvenWhenReceiptQueueIsFull() {
        val id = alice.stageAttachment(bid, "photo.jpg", "image/jpeg", byteArrayOf(1), "view_twice"); drain(media = true)
        val now = System.currentTimeMillis(); bob.openMedia(aid, id, now).fill(0)
        repeat(256) { bob.enqueue(aid, "text", JSONObject().put("text", "Queued"), true) }
        assertTrue(bob.expireLimitedMedia(now + 30000))
        assertEquals(0L, b.transaction { it.count("SELECT COUNT(*) FROM chunks WHERE transfer_id=?", id) })
        assertThrows(IllegalStateException::class.java) { bob.openMedia(aid, id, now + 30001) }
    }
    @Test fun screenshotNoticeIsAuthenticatedAndReplayDoesNotRepeatIt() {
        alice.enqueue(bid, "capture_notice", JSONObject().put("context", "call").put("callId", WireCodec.id()))
        val frame = alice.pending(bid).single()["frame"] as String
        assertEquals("capture_notice", bob.receive(aid, frame).kind)
        assertEquals("duplicate", bob.receive(aid, frame).kind)
    }
    @Test fun limitedMediaReservesViewsAndDeletesReceiverPayloadWithoutBreakingChat() {
        for (limit in 1..2) {
            val payload = byteArrayOf(1, 2, 3, 4)
            val id = alice.stageAttachment(bid, "private.png", "image/png", payload, if (limit == 1) "view_once" else "view_twice")
            drain(media = true)
            assertThrows(IllegalStateException::class.java) { bob.attachment(aid, id) }
            repeat(limit) { assertArrayEquals(payload, bob.openMedia(aid, id)) }
            assertThrows(IllegalStateException::class.java) { bob.openMedia(aid, id) }
            assertEquals(0L, b.transaction { it.count("SELECT COUNT(*) FROM chunks WHERE transfer_id=?", id) })
            val meta = b.transaction { JSONObject(it.rows("SELECT data FROM messages WHERE id=?", id).single()["data"] as String) }
            assertEquals(limit, meta.getInt("viewsUsed")); assertTrue(meta.getBoolean("expired"))
            assertArrayEquals("Sender keeps their original", payload, alice.openMedia(bid, id))
        }
        alice.enqueue(bid, "text", JSONObject().put("text", "After expiry"), true); drain()
        assertTrue(b.transaction { it.rows("SELECT data FROM messages WHERE kind='text'").any { JSONObject(it["data"] as String).getString("text") == "After expiry" } })
    }
    @Test fun limitedMediaCannotBeAppliedToArbitraryFiles() {
        assertThrows(IllegalArgumentException::class.java) { alice.stageAttachment(bid, "a.pdf", "application/pdf", byteArrayOf(1), "view_once") }
        assertEquals(0L, a.transaction { it.count("SELECT COUNT(*) FROM transfers") })
    }
    private fun drain(media: Boolean = false, received: (NativeChatEngine.Received) -> Unit = {}) {
        repeat(2000) {
            alice.activatePending(bid); bob.activatePending(aid)
            if (media) { alice.pumpTransfers(bid); bob.pumpTransfers(aid) }
            val af = alice.pending(bid); val bf = bob.pending(aid)
            if (af.isEmpty() && bf.isEmpty()) return
            for (row in af) { alice.markHandedOff(row); received(bob.receive(aid, row["frame"] as String)) }
            for (row in bf) { bob.markHandedOff(row); received(alice.receive(bid, row["frame"] as String)) }
        }
        error("Queue did not settle")
    }
    @Test fun simultaneousFirstMessagesRepliesReactionsAndReadReceipts() {
        val am = alice.enqueue(bid, "text", JSONObject().put("text", "Alice বাংলা"), true)
        val bm = bob.enqueue(aid, "text", JSONObject().put("text", "Bob Русский"), true)
        assertTrue(bob.pending(aid).isEmpty())
        drain()
        for ((vault, id) in listOf(a to am, b to bm)) assertEquals(2L, vault.transaction { it.rows("SELECT status FROM messages WHERE id=?", id).single()["status"] })
        alice.enqueue(bid, "text", JSONObject().put("text", "Reply").put("replyTo", bm), true)
        bob.react(aid, am, "❤️")
        bob.enqueue(aid, "read", JSONObject().put("ids", org.json.JSONArray().put(am)))
        drain()
        assertEquals(3L, a.transaction { it.rows("SELECT status FROM messages WHERE id=?", am).single()["status"] })
        assertEquals("❤️", JSONObject(a.get("reaction.$bid.$am")!!).getJSONObject(bid).getString("emoji"))
    }
    @Test fun multiMegabyteFileKeepsBothDirectionsAndCallControlsDeliverable() {
        alice.enqueue(bid, "text", JSONObject().put("text", "Start"), true); drain()
        val bytes = ByteArray(2_500_001) { ((it * 31 + 19) % 256).toByte() }
        val transferId = alice.stageAttachment(bid, "photo.jpg", "image/jpeg", bytes)
        alice.pumpTransfers(bid)
        val text = alice.enqueue(bid, "text", JSONObject().put("text", "During upload"), true)
        val call = alice.enqueue(bid, "call_offer", JSONObject().put("callId", WireCodec.id()).put("video", true).put("sdp", "synthetic SDP").put("expiresAt", System.currentTimeMillis() + 60000))
        val order = alice.pending(bid).map { it["id"] }
        assertTrue(order.indexOf(text) < order.indexOf(order.first { it != text && it != call }))
        val seen = mutableListOf<String>()
        drain(true) { event ->
            seen.add(event.kind)
            if (event.kind == "transfer_start") bob.enqueue(aid, "text", JSONObject().put("text", "Other direction"), true)
        }
        assertTrue("call_offer" in seen)
        assertArrayEquals(bytes, bob.attachment(aid, transferId))
        alice.enqueue(bid, "text", JSONObject().put("text", "After upload"), true); drain()
        assertTrue(b.transaction { it.rows("SELECT data FROM messages WHERE kind='text'").any { row -> JSONObject(row["data"] as String).getString("text") == "After upload" } })
        assertEquals(2L, a.transaction { it.rows("SELECT status FROM messages WHERE id=?", transferId).single()["status"] })
    }
    @Test fun failedHistoryWriteRollsBackSessionAndOutboxTogether() {
        val before = a.transaction { it.rows("SELECT kind,id,bytes FROM signal_records ORDER BY kind,id") }
        a.failExecute = { it.startsWith("INSERT INTO messages") }
        assertThrows(IllegalStateException::class.java) { alice.enqueue(bid, "text", JSONObject().put("text", "Failure"), true) }
        a.failExecute = null
        val after = a.transaction { it.rows("SELECT kind,id,bytes FROM signal_records ORDER BY kind,id") }
        assertEquals(before.size, after.size)
        before.zip(after).forEach { (x, y) -> assertArrayEquals(x["bytes"] as ByteArray, y["bytes"] as ByteArray) }
        assertTrue(alice.pending(bid).isEmpty())
        alice.enqueue(bid, "text", JSONObject().put("text", "Retry works"), true); drain()
        assertEquals(1L, b.transaction { it.count("SELECT COUNT(*) FROM messages") })
    }
    @Test fun duplicateIsIdempotentAndChangedReplayCannotAdvanceRatchet() {
        alice.enqueue(bid, "text", JSONObject().put("text", "Once"), true)
        val frame = alice.pending(bid).single()["frame"] as String
        bob.receive(aid, frame); bob.receive(aid, frame)
        assertEquals(1L, b.transaction { it.count("SELECT COUNT(*) FROM messages") })
        val changed = JSONObject(frame).put("cipher", WireCodec.b64(byteArrayOf(1, 2, 3))).toString()
        assertThrows(IllegalArgumentException::class.java) { bob.receive(aid, changed) }
        assertEquals(1, bob.pending(aid).count { it["needs_ack"] == 0L })
    }
    @Test fun transactionStoreCannotBeUsedAfterScopeEndsOrInAnotherTransaction() {
        val store = a.transaction { TransactionalSignalStore(it) }
        assertThrows(IllegalStateException::class.java) { store.containsSession(SignalProtocolAddress(bid, 2)) }
        a.transaction { assertThrows(IllegalStateException::class.java) { store.containsSession(SignalProtocolAddress(bid, 2)) } }
    }
    @Test fun staleTransferIntentIsCleanedWithoutBlockingNextMessage() {
        alice.enqueue(bid, "text", JSONObject().put("text", "Establish"), true); drain()
        a.transaction { it.execute("INSERT INTO kv(key,value) VALUES(?,?)", "transfer.pending.${WireCodec.id()}",
            WireCodec.canonical(JSONObject().put("peer", bid).put("index", 0))) }
        alice.pumpTransfers(bid)
        assertEquals(0L, a.transaction { it.count("SELECT COUNT(*) FROM kv WHERE key LIKE 'transfer.pending.%'") })
        alice.enqueue(bid, "text", JSONObject().put("text", "After stale file"), true); drain()
        assertTrue(b.transaction { it.rows("SELECT data FROM messages").any { row -> JSONObject(row["data"] as String).getString("text") == "After stale file" } })
    }
    @Test fun forgedDeliveredReceiptRollsBackIncomingRatchetAndCannotMarkOtherContentDelivered() {
        alice.enqueue(bid, "text", JSONObject().put("text", "Establish"), true); drain()
        val outgoing = alice.enqueue(bid, "text", JSONObject().put("text", "Still pending"), true)
        val frameId = alice.pending(bid).single()["id"] as String
        val bogus = bob.enqueue(aid, "ack", JSONObject().put("ackId", frameId).put("deliveredId", WireCodec.id()))
        val invalid = bob.pending(aid).first { it["id"] == bogus }["frame"] as String
        val before = a.transaction { TransactionalSignalStore(it).loadSession(SignalProtocolAddress(bid, 2)).serialize() }
        assertThrows(IllegalArgumentException::class.java) { alice.receive(bid, invalid) }
        val after = a.transaction { TransactionalSignalStore(it).loadSession(SignalProtocolAddress(bid, 2)).serialize() }
        assertArrayEquals(before, after)
        assertEquals(0L, a.transaction { it.rows("SELECT status FROM messages WHERE id=?", outgoing).single()["status"] })
        assertEquals(1, alice.pending(bid).size)
        // A later genuine peer frame remains decryptable through the skipped-key path.
        bob.enqueue(aid, "text", JSONObject().put("text", "Genuine follow-up"), true)
        val genuine = bob.pending(aid).first { it["needs_ack"] == 1L }["frame"] as String
        assertEquals("Genuine follow-up", alice.receive(bid, genuine).data.getString("text"))
    }
}
