package app.cutiepie.cutie_pie.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.util.concurrent.*

class LocalStreamLinkTest {
    @Test fun bulkPipelinePreservesSixteenMiBAndInterleavedChatWithoutPerRecordOwnerCallbacks() {
        val owner=Executors.newSingleThreadScheduledExecutor();val server=ServerSocket(0,1,InetAddress.getLoopbackAddress())
        val aSocket=Socket(InetAddress.getLoopbackAddress(),server.localPort);val bSocket=server.accept();server.close()
        aSocket.tcpNoDelay=true;bSocket.tcpNoDelay=true
        val invites=CountDownLatch(2);val records=CountDownLatch(512);val chat=CountDownLatch(1)
        val id=WireCodec.id();val cipher=ByteArray(32785) { (it%239).toByte() };val callbacks=java.util.concurrent.atomic.AtomicInteger()
        val sent=java.security.MessageDigest.getInstance("SHA-256");val received=java.security.MessageDigest.getInstance("SHA-256")
        fun link(socket:Socket)=LocalStreamLink("wifi","synthetic-bulk",socket.getInputStream(),socket.getOutputStream(),socket,owner,
            { source,value -> if(value.getString("type")=="invite") invites.countDown() else source.receiveFrame(value.getString("frame")) },{})
        val a=link(aSocket);val b=link(bSocket)
        try {
            owner.submit { a.begin("A");b.begin("B") }.get(5,TimeUnit.SECONDS);assertTrue(invites.await(5,TimeUnit.SECONDS))
            owner.submit {
                for(pipe in listOf(a,b)) { pipe.bind(LocalSharingRules.request(a.nonce,b.nonce),{}, { assertEquals("chat during bulk",it);chat.countDown() },{callbacks.incrementAndGet()},{});pipe.start() }
                b.onBinary={ actual,bytes -> assertEquals(id,actual);received.update(bytes);records.countDown() }
            }.get(5,TimeUnit.SECONDS)
            repeat(512) { i -> sent.update(cipher);assertTrue(a.sendBinary(id,cipher) {true})
                if(i==128) owner.submit { assertTrue(a.send("chat during bulk",true)) }.get(5,TimeUnit.SECONDS) }
            assertTrue(records.await(15,TimeUnit.SECONDS));assertTrue(chat.await(5,TimeUnit.SECONDS))
            assertArrayEquals(sent.digest(),received.digest())
            assertTrue("Bulk callbacks coalesced rather than one per record",callbacks.get()<512)
        } finally { a.close();b.close();owner.shutdownNow();aSocket.close();bSocket.close() }
    }
    @Test fun boundedBinaryRecordsAndUrgentChatCrossRealSocketsWithoutJsonOrBase64Inflation() {
        val owner=Executors.newSingleThreadScheduledExecutor();val server=ServerSocket(0,1,InetAddress.getLoopbackAddress())
        val leftSocket=Socket(InetAddress.getLoopbackAddress(),server.localPort);val rightSocket=server.accept();server.close()
        val invites=CountDownLatch(2);val records=CountDownLatch(96);val chat=CountDownLatch(1);val id=WireCodec.id()
        val cipher=ByteArray(32785) { (it%251).toByte() };val seen=java.util.concurrent.atomic.AtomicLong()
        fun link(socket:Socket)=LocalStreamLink("wifi","synthetic",socket.getInputStream(),socket.getOutputStream(),socket,owner,
            { source,value -> if(value.getString("type")=="invite") invites.countDown() else source.receiveFrame(value.getString("frame")) },{})
        val a=link(leftSocket);val b=link(rightSocket)
        try {
            owner.submit { a.begin("A");b.begin("B") }.get(5,TimeUnit.SECONDS);assertTrue(invites.await(5,TimeUnit.SECONDS))
            owner.submit {
                for(pipe in listOf(a,b)) { pipe.bind(LocalSharingRules.request(a.nonce,b.nonce),{}, { assertEquals("urgent chat",it);chat.countDown() },{},{});pipe.start() }
                b.onBinary={ receivedId,bytes -> assertEquals(id,receivedId);assertArrayEquals(cipher,bytes);seen.addAndGet(bytes.size.toLong());records.countDown() }
            }.get(5,TimeUnit.SECONDS)
            for(i in 0 until 96) { assertTrue(a.sendBinary(id,cipher) { true });if(i==40) owner.submit { assertTrue(a.send("urgent chat",true)) }.get(5,TimeUnit.SECONDS) }
            assertTrue(records.await(5,TimeUnit.SECONDS));assertTrue(chat.await(5,TimeUnit.SECONDS));assertEquals(96L*cipher.size,seen.get())
            assertFalse(a.sendBinary(id,cipher) { false })
        } finally { a.close();b.close();owner.shutdownNow();leftSocket.close();rightSocket.close() }
    }
    @Test fun realLoopbackSocketsPreserveBidirectionalFramesAndReleaseBothEnds() {
        val owner=Executors.newSingleThreadScheduledExecutor();val server=ServerSocket(0,1,InetAddress.getLoopbackAddress())
        val leftSocket=Socket(InetAddress.getLoopbackAddress(),server.localPort);val rightSocket=server.accept();server.close()
        val invites=CountDownLatch(2);val delivered=CountDownLatch(2);val ended=CountDownLatch(2)
        val a=WireCodec.b64(ByteArray(12000) { (it%251).toByte() });val b="reply:"+WireCodec.id()
        val seen=CopyOnWriteArrayList<String>()
        fun link(socket:Socket)=LocalStreamLink("wifi","synthetic-loopback",socket.getInputStream(),socket.getOutputStream(),socket,owner,
            { source,value -> when(value.getString("type")) { "invite"->invites.countDown();"frame"->source.receiveFrame(value.getString("frame"));else->error("Unexpected packet") } },{ ended.countDown() })
        val left=link(leftSocket);val right=link(rightSocket)
        try {
            owner.submit { left.begin("synthetic invitation A");right.begin("synthetic invitation B") }.get(5,TimeUnit.SECONDS)
            assertTrue(invites.await(5,TimeUnit.SECONDS))
            owner.submit {
                val request=LocalSharingRules.request(left.nonce,right.nonce)
                for(pipe in listOf(left,right)) { pipe.bind(request,{}, { seen.add(it);delivered.countDown() },{},{});pipe.start() }
                assertTrue(left.send(a));assertTrue(right.send(b))
            }.get(5,TimeUnit.SECONDS)
            assertTrue(delivered.await(5,TimeUnit.SECONDS));assertEquals(setOf(a,b),seen.toSet())
            assertEquals(WireCodec.digest(WireCodec.decode(a)),WireCodec.digest(WireCodec.decode(seen.first { it==a })))
            left.close();right.close();assertTrue(ended.await(5,TimeUnit.SECONDS))
            assertFalse(left.ready);assertFalse(right.ready);assertFalse(left.offer(JSONObject().put("type","frame").put("frame","late")))
        } finally { left.close();right.close();owner.shutdownNow();leftSocket.close();rightSocket.close() }
    }
    @Test fun bluetoothAudioQueueIsBoundedAndDoesNotRetainPacketsAfterClose() {
        val owner=Executors.newSingleThreadScheduledExecutor()
        val link=LocalStreamLink("bluetooth","synthetic",java.io.ByteArrayInputStream(byteArrayOf()),java.io.ByteArrayOutputStream(),java.io.Closeable {},owner,{ _,_-> },{})
        try {
            link.bind(WireCodec.id(),{}, {},{},{});link.start()
            val packet="ciphertext-fixture"
            assertTrue(link.sendAudio(packet));assertTrue(link.sendAudio(packet));assertFalse(link.sendAudio(packet));assertFalse(link.audioCapacity())
            link.close();assertFalse(link.audioCapacity());assertFalse(link.sendAudio(packet))
        } finally { link.close();owner.shutdownNow() }
    }
}
