package app.cutiepie.cutie_pie.core

import app.cutiepie.cutie_pie.NativeQuickShare
import android.content.Context
import android.os.StatFs
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import org.json.JSONObject
import java.io.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicBoolean

/** Accepted authenticated routes only. Quick files stream live; private large files keep an encrypted spool. */
class NativeLargeFiles(context:Context,private val vault:TransactionalVault,private val owner:ScheduledExecutorService,
    private val link:(String)->FileStreamLink?,private val control:(String,JSONObject)->Unit,
    private val changed:()->Unit,private val activity:(String)->Unit,private val available:(String,String,String,String)->Unit,
    private val canExport:()->Boolean,private val inCall:()->Boolean,private val quickEpoch:()->Long,private val quickDestination:(String,String,Long)->NativeQuickShare.Destination?,private val offerAlert:(String,String)->Unit) {
    data class View(val id:String,val peer:String,val name:String,val size:Long,val bytes:Long,val state:String,val outgoing:Boolean,val quick:Boolean=false,val paused:Boolean=false,val locallyPaused:Boolean=false,val speed:Long=0,val remaining:Long?=null,val medium:String="wifi")
    private class Job(val id:String,val peer:String,val meta:JSONObject,val outgoing:Boolean,val channel:FileStreamLink) {
        val stopped=AtomicBoolean(false);@Volatile var state="preparing";@Volatile var bytes=0L
        val flow=FileTransferFlow();val rate=FileTransferRate();@Volatile var sentBytes=0L;@Volatile var finalSent=false
        var liveReader:LiveSecretFileStream.Reader?=null;var liveWriter:LiveSecretFileStream.Writer?=null;var open:(()->InputStream)?=null
        val live get()=meta.optString("type")=="stream_offer"
        val progressPending=AtomicBoolean(false);@Volatile var reported=0L
        val progressLock=java.lang.Object()
        @Volatile var updated=System.nanoTime();var notified=0L
        var quick:NativeQuickShare.Destination?=null;var done:(Boolean)->Unit={};val completedCallback=AtomicBoolean(false);var output:FileOutputStream?=null;var writer:DataOutputStream?=null;var reader:SecretFileStream.Reader?=null
    }
    private val folder=File(context.filesDir,"local-file-v1").apply { check(mkdirs() || isDirectory) }
    private val sodium=LazySodiumAndroid(SodiumAndroid())
    private val crypto=SecretFileStream(sodium)
    private val liveCrypto=LiveSecretFileStream(sodium)
    private val jobs=ConcurrentHashMap<String,Job>()
    private val discard=LocalFileDiscard()
    private val quickConsent=QuickReceiveConsent()
    private val io=Executors.newSingleThreadExecutor()
    @Volatile var views:List<View> = emptyList();private set
    init {
        vault.transaction { it.execute("CREATE TABLE IF NOT EXISTS large_files(id TEXT PRIMARY KEY,peer TEXT NOT NULL,meta TEXT NOT NULL,outgoing INTEGER NOT NULL,at INTEGER NOT NULL)") }
        // Interrupted partial streams never appear as delivered. Completed streams and ratchets survive.
        val keep=vault.transaction { tx ->
            tx.rows("SELECT id,peer,meta FROM large_files").filter { JSONObject(it["meta"] as String).optBoolean("quick") }.forEach { row ->
                file(row["id"] as String).delete();tx.execute("DELETE FROM large_files WHERE id=?",row["id"])
                tx.execute("UPDATE messages SET kind='text',data=? WHERE id=? AND peer=? AND kind='attachment'",JSONObject().put("text","Quick Share · Interrupted; select the file again to retry").toString(),row["id"],row["peer"])
            }
            tx.rows("SELECT id FROM large_files").map { row -> row["id"] as String }.toSet()
        }
        folder.listFiles()?.filter { it.name.endsWith(".part") || it.name.removeSuffix(".stream") !in keep }?.forEach { it.delete() }
        owner.scheduleWithFixedDelay({
            jobs.values.toList().forEach { job ->
                val seconds=(System.nanoTime()-job.updated)/1_000_000_000
                if(job.flow.expired(android.os.SystemClock.elapsedRealtime()) || job.state=="offered" && seconds>120 || !job.flow.paused() && job.state in setOf("sending","receiving","confirming") && seconds>60) fail(job,true)
            };prune()
        },5,5,java.util.concurrent.TimeUnit.SECONDS)
    }
    private fun file(id:String,part:Boolean=false)=File(folder,id+if(part) ".part" else ".stream")
    private fun snapshot() {
        views=jobs.values.map { View(it.id,it.peer,it.meta.optString("name"),it.meta.optLong("size"),it.bytes,it.state,it.outgoing,it.meta.optBoolean("quick"),it.flow.paused(),it.flow.locallyPaused(),if(it.flow.paused()) 0 else it.rate.speed(System.nanoTime()),if(it.flow.paused()) null else it.rate.remaining(it.meta.optLong("size"),System.nanoTime()),it.channel.medium) }.sortedBy { it.id }
        changed()
    }
    private fun reserve(size:Long) {
        val pending=jobs.values.filter { it.state in setOf("preparing","receiving") && (it.outgoing || !it.meta.optBoolean("quick")) }.sumOf { LargeFileRules.diskBytes(it.meta.getLong("size"))-it.bytes }
        check(StatFs(folder.path).availableBytes >= LargeFileRules.diskBytes(size)+pending) { "Not enough private storage" }
    }
    private fun note(job:Job,bytes:Long) {
        job.bytes=bytes;job.updated=System.nanoTime();job.rate.note(bytes,job.updated)
        if(job.live && !job.outgoing && (bytes-job.reported>=FileTransferTuning.ACK_MIN_BYTES || bytes==job.meta.getLong("size"))) reportProgress(job)
        if(job.updated-job.notified>1_000_000_000) {
            job.notified=job.updated;owner.execute { if(!job.stopped.get()) { activity(job.peer);if(!job.outgoing && job.meta.optBoolean("quick")) quickConsent.progress(job.peer,job.channel.requestId,quickEpoch(),android.os.SystemClock.elapsedRealtime());snapshot() } }
        }
    }
    private fun reportProgress(job:Job) {
        if(!job.progressPending.compareAndSet(false,true)) return
        owner.schedule({
            try {
                if(!job.stopped.get() && job.state in setOf("receiving","confirming","done") && link(job.peer)===job.channel) {
                    val bytes=job.bytes
                    control(job.peer,JSONObject().put("type","stream_progress").put("id",job.id).put("bytes",bytes))
                    job.reported=bytes
                }
            } catch(_:IllegalStateException) { /* Retry a coalesced checkpoint if the bounded control outbox is busy. */ }
            finally {
                job.progressPending.set(false)
                if(!job.stopped.get() && job.state in setOf("receiving","confirming") && job.bytes>job.reported) reportProgress(job)
            }
        },FileTransferTuning.PROGRESS_DELAY_MS,java.util.concurrent.TimeUnit.MILLISECONDS)
    }
    private fun visible(job:Job)=JSONObject().put("id",job.id).put("name",job.meta.getString("name")).put("mime",job.meta.getString("mime"))
        .put("size",job.meta.getLong("size")).put("large",true)
    private fun completed(job:Job) {
        check(!job.stopped.get())
        val destination=file(job.id);check(file(job.id,true).renameTo(destination))
        val now=System.currentTimeMillis()
        vault.transaction { tx ->
            check(tx.rows("SELECT uid FROM contacts WHERE uid=? AND blocked=0",job.peer).isNotEmpty())
            check(tx.rows("SELECT value FROM kv WHERE key=?","deleted.${job.peer}.${job.id}").isEmpty())
            tx.execute("INSERT INTO large_files(id,peer,meta,outgoing,at) VALUES(?,?,?,?,?)",job.id,job.peer,job.meta.toString(),if(job.outgoing) 1 else 0,now)
            tx.execute("INSERT INTO messages(id,peer,outgoing,kind,data,created_at,status) VALUES(?,?,?,'attachment',?,?,?)",job.id,job.peer,if(job.outgoing) 1 else 0,visible(job).toString(),now,if(job.outgoing) 0 else 2)
        }
    }
    fun sendLive(peer:String,name:String,mime:String,size:Long,open:()->InputStream,done:(Boolean)->Unit) {
        check(jobs.values.none { it.outgoing && !it.stopped.get() && it.state !in setOf("done","failed") }) { "Finish or cancel the current transfer first" }
        jobs.entries.removeIf { it.value.state in setOf("done","failed") };check(jobs.size<32)
        val channel=link(peer) ?: error("An authenticated direct connection is required")
        val id=WireCodec.id();val writer=liveCrypto.Writer(id,size)
        val meta=JSONObject().put("type","stream_offer").put("id",id).put("name",name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\p{Cntrl}]")," ").take(120).ifBlank { "file" })
            .put("mime",mime).put("size",size).put("quick",true).put("key",WireCodec.b64(writer.key)).put("header",WireCodec.b64(writer.header))
        try { LiveFileRules.validate(meta);val job=Job(id,peer,meta,true,channel);job.liveWriter=writer;job.open=open;job.done=done;job.state="offered";jobs[id]=job
            control(peer,meta);activity(peer);snapshot()
        } catch(failure:Exception) { writer.close();jobs[id]?.let { fail(it,false) };throw failure }
    }
    private fun type(job:Job,suffix:String)=(if(job.live) "stream_" else "large_")+suffix
    fun pause(id:String,paused:Boolean) {
        val job=jobs[id] ?: return
        check(job.live && job.state in setOf("sending","receiving") && !job.stopped.get())
        val seq=job.flow.local(paused,android.os.SystemClock.elapsedRealtime())
        job.updated=System.nanoTime()
        synchronized(job.progressLock) { job.progressLock.notifyAll() }
        control(job.peer,JSONObject().put("type","stream_pause").put("id",id).put("paused",paused).put("seq",seq));snapshot()
    }
    private fun awaitFlow(job:Job):Boolean {
        while(job.flow.paused() && !job.stopped.get() && job.channel.ready && !job.flow.expired(android.os.SystemClock.elapsedRealtime())) Thread.sleep(50)
        return !job.stopped.get() && job.channel.ready && !job.flow.expired(android.os.SystemClock.elapsedRealtime())
    }
    private fun transmitLive(job:Job) {
        val writer=synchronized(job) { if(job.stopped.get()) null else job.liveWriter.also { job.liveWriter=null } } ?: return
        try {
            writer.use { output -> checkNotNull(job.open).invoke().buffered(LargeFileRules.CHUNK).use { input -> output.stream(input,{ cipher,count,final ->
                check(awaitFlow(job))
                var acknowledged=job.bytes;var idleAt=System.nanoTime()
                while(!FileTransferTuning.canAdvance(count,job.bytes,job.channel.medium,inCall())) {
                    val paused=job.flow.paused();check(awaitFlow(job))
                    if(paused || job.bytes>acknowledged) { acknowledged=job.bytes;idleAt=System.nanoTime() }
                    check(System.nanoTime()-idleAt<FileTransferTuning.ACK_IDLE_NS) { "Recipient progress stalled" }
                    synchronized(job.progressLock) { if(!FileTransferTuning.canAdvance(count,job.bytes,job.channel.medium,inCall()) && !job.stopped.get()) job.progressLock.wait(100) }
                }
                check(!job.stopped.get());job.sentBytes=count;if(final) job.finalSent=true
                check(job.channel.sendBinary(job.id,cipher) { !job.stopped.get() })
                job.updated=System.nanoTime()
                // A smaller in-call window preserves headroom; no35ms sleep per32KiB record.
            },{ awaitFlow(job) }) } }
            owner.execute { if(job.state=="sending" && !job.stopped.get()) { job.state="confirming";job.updated=System.nanoTime();snapshot() } }
        } catch(_:Exception) { owner.execute { fail(job,true) } } finally { job.open=null }
    }
    fun send(peer:String,name:String,mime:String,size:Long,quick:Boolean,open:()->InputStream,done:(Boolean)->Unit) {
        check(jobs.values.none { it.outgoing && !it.stopped.get() && it.state !in setOf("done","failed") }) { "Finish or cancel the current large file first" }
        jobs.entries.removeIf { it.value.state in setOf("done","failed") };check(jobs.size<32);val channel=link(peer) ?: error("Use an authenticated Wi-Fi connection")
        reserve(size);val id=WireCodec.id()
        val meta=JSONObject().put("type","large_offer").put("id",id).put("name",name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\p{Cntrl}]")," ").take(120).ifBlank { "file" }).put("mime",mime).put("size",size).put("quick",quick)
        val job=Job(id,peer,meta,true,channel);job.done=done;jobs[id]=job;snapshot()
        io.execute {
            var prepared:SecretFileStream.Prepared?=null
            try {
                open().use { input -> FileOutputStream(file(id,true)).use { output ->
                    prepared=crypto.prepare(input,output,id,size,{ note(job,it) },{ !job.stopped.get() && channel.ready });output.fd.sync()
                } }
                val result=prepared!!;meta.put("key",WireCodec.b64(result.key)).put("header",WireCodec.b64(result.header)).put("hash",result.hash)
                LargeFileRules.validate(meta)
                owner.execute {
                    try {
                        check(!job.stopped.get() && link(peer)===channel);completed(job)
                        job.state="offered";job.bytes=0;job.updated=System.nanoTime();control(peer,meta);snapshot()
                    } catch(_:Exception) { fail(job,true) }
                }
            } catch(_:Exception) { owner.execute { fail(job,true) } }
            finally { prepared?.key?.fill(0);prepared?.header?.fill(0) }
        }
    }
    fun control(peer:String,value:JSONObject,channel:FileStreamLink) {
        if(value.getString("type").startsWith("stream_")) LiveFileRules.validate(value) else LargeFileRules.validate(value);val id=value.getString("id")
        jobs[id]?.let { job -> if(job.peer==peer && job.channel===channel && value.getString("type") !in setOf("large_offer","stream_offer")) check(value.getString("type").startsWith("stream_")==job.live) }
        when(value.getString("type")) {
            "large_offer", "stream_offer" -> {
                jobs.entries.removeIf { it.value.state in setOf("done","failed") }
                if(jobs.containsKey(id) || vault.transaction { it.rows("SELECT id FROM messages WHERE id=?",id).isNotEmpty() || it.rows("SELECT value FROM kv WHERE key=?","deleted.$peer.$id").isNotEmpty() }) return
                if(jobs.values.count { !it.outgoing && it.state in setOf("offered","receiving","confirming") }>=2 || jobs.size>=32) { control(peer,JSONObject().put("type",if(value.getString("type")=="stream_offer") "stream_abort" else "large_abort").put("id",id));return }
                val job=Job(id,peer,JSONObject(value.toString()),false,channel);job.state="offered";jobs[id]=job;if(value.getBoolean("quick") && quickConsent.allow(peer,channel.requestId,quickEpoch(),android.os.SystemClock.elapsedRealtime())) runCatching { accept(id,true) }.onFailure { fail(job,true) } else { snapshot();offerAlert(peer,id) }
            }
            "large_accept", "stream_accept" -> jobs[id]?.takeIf { it.peer==peer && it.channel===channel && it.outgoing && it.state=="offered" && !it.stopped.get() }?.let { job ->
                job.state="sending";job.updated=System.nanoTime();snapshot();activity(peer);io.execute { if(job.live) transmitLive(job) else transmit(job) }
            }
            "large_done", "stream_done" -> jobs[id]?.takeIf { it.peer==peer && it.channel===channel && it.outgoing && it.state in setOf("sending","confirming") && (!it.live || it.finalSent) && !it.stopped.get() }?.let { job ->
                job.state="done";job.meta.remove("key");job.meta.remove("header");job.bytes=job.meta.getLong("size")
                vault.transaction { it.execute("UPDATE messages SET status=MAX(status,2) WHERE id=? AND peer=? AND outgoing=1 AND kind='attachment'",id,peer) };if(job.meta.getBoolean("quick")) {
                    vault.transaction { tx ->
                        if(job.live) tx.execute("INSERT INTO messages(id,peer,outgoing,kind,data,created_at,status) VALUES(?,?,1,'text',?,?,2)",id,peer,JSONObject().put("text","Quick Share · "+job.meta.getString("name")+" · Saved by recipient").toString(),System.currentTimeMillis())
                        else { tx.execute("DELETE FROM large_files WHERE id=? AND peer=?",id,peer);tx.execute("UPDATE messages SET kind='text',data=? WHERE id=? AND peer=? AND kind='attachment'",JSONObject().put("text","Quick Share · "+job.meta.getString("name")+" · Saved by recipient").toString(),id,peer) }
                    };file(id).delete()
                };if(job.completedCallback.compareAndSet(false,true)) job.done(true);snapshot()
            }
            "stream_progress" -> jobs[id]?.takeIf { it.live && it.peer==peer && it.channel===channel && it.outgoing && it.state in setOf("sending","confirming") && !it.stopped.get() }?.let { job ->
                val bytes=value.getLong("bytes");check(bytes<=job.sentBytes);if(bytes>=job.bytes) {
                    note(job,bytes);synchronized(job.progressLock) { job.progressLock.notifyAll() }
                }
            }
            "stream_pause" -> jobs[id]?.takeIf { it.live && it.peer==peer && it.channel===channel && it.state in setOf("sending","receiving","confirming") && !it.stopped.get() }?.let { job ->
                if(job.flow.received(value.getBoolean("paused"),value.getLong("seq"),android.os.SystemClock.elapsedRealtime())) { job.updated=System.nanoTime();snapshot() }
            }
            "large_abort", "stream_abort" -> jobs[id]?.takeIf { it.peer==peer && it.channel===channel }?.let { if(it.state!="done") fail(it,false) }
        }
    }
    fun accept(id:String,accept:Boolean,userApproved:Boolean=false) {
        val job=jobs[id] ?: return
        check(!job.outgoing && job.state=="offered")
        if(!accept) { fail(job,true);return }
        check(link(job.peer)===job.channel)
        if(userApproved) check(canExport())
        else if(job.meta.optBoolean("quick")) check(quickConsent.allow(job.peer,job.channel.requestId,quickEpoch(),android.os.SystemClock.elapsedRealtime()))
        if(job.meta.getBoolean("quick")) job.quick=quickDestination(job.meta.getString("name"),job.meta.getString("mime"),job.meta.getLong("size")) ?: error("Enable Quick Share and choose a folder first")
        else reserve(job.meta.getLong("size"))
        val key=WireCodec.key(job.meta.getString("key"),32);val header=WireCodec.key(job.meta.getString("header"),24)
        try {
            synchronized(job) {
                if(job.live) job.liveReader=liveCrypto.Reader(id,job.meta.getLong("size"),key,header) else job.reader=crypto.Reader(id,job.meta.getLong("size"),key,header,job.meta.getString("hash"))
                if(job.quick==null) { job.output=FileOutputStream(file(id,true));job.writer=DataOutputStream(BufferedOutputStream(job.output!!,FileTransferTuning.STREAM_BUFFER)) };job.state="receiving";job.updated=System.nanoTime()
            }
            if(userApproved && job.meta.getBoolean("quick")) quickConsent.approve(job.peer,job.channel.requestId,quickEpoch(),android.os.SystemClock.elapsedRealtime())
            control(job.peer,JSONObject().put("type",type(job,"accept")).put("id",id));activity(job.peer);snapshot()
        } catch(failure:Exception) { fail(job,true);throw failure } finally { key.fill(0);header.fill(0) }
    }
    /** Called synchronously by the pipe reader. At most one chunk is in memory per link. */
    fun binary(peer:String,channel:FileStreamLink,id:String,cipher:ByteArray) {
        val job=jobs[id]
        if(job==null) { check(discard.allows(peer,channel.requestId,id)) { "File was not accepted" };return }
        check(job.peer==peer && job.channel===channel && !job.outgoing)
        if(job.state in setOf("failed","failing")) return
        try {
            synchronized(job) {
                check(!job.stopped.get() && job.state=="receiving")
                val live=job.liveReader;val regular=job.reader
                if(live!=null) live.consume(cipher,checkNotNull(job.quick)) else checkNotNull(regular).consume(cipher,job.quick)
                if(job.writer!=null) LargeFileRules.write(job.writer!!,cipher);note(job,live?.bytes ?: checkNotNull(regular).bytes)
                if(live?.complete ?: checkNotNull(regular).complete) {
                    check(!job.stopped.get());job.writer?.flush();job.output?.fd?.sync();job.writer?.close();job.output=null;job.writer=null;job.quick?.finish(true);job.quick=null;live?.close();regular?.close();job.liveReader=null;job.reader=null;job.state="confirming"
                    owner.execute {
                        try {
                            check(!job.stopped.get() && link(peer)===channel)
                            if(job.meta.getBoolean("quick")) {
                                vault.transaction { tx ->
                                    tx.execute("INSERT INTO messages(id,peer,outgoing,kind,data,created_at,status) VALUES(?,?,0,'text',?,?,2)",job.id,peer,JSONObject().put("text","Quick Share · "+job.meta.getString("name")+" · Saved to your selected folder").toString(),System.currentTimeMillis())
                                }
                            } else {
                                completed(job)
                            }
                            job.state="done"
                            job.meta.remove("key")
                            job.meta.remove("header")
                            snapshot()
                            control(peer,JSONObject().put("type",type(job,"done")).put("id",id))
                            available(peer,id,job.meta.getString("mime"),job.meta.getString("name"))
                        } catch(_:Exception) { fail(job,true) }
                    }
                }
            }
        } catch(failure:Exception) {
            if(failure is IOException || failure is SecurityException) { job.state="failing";owner.execute { fail(job,true) };return }
            owner.execute { fail(job,true) };throw failure
        }
    }
    private fun transmit(job:Job) {
        try {
            DataInputStream(BufferedInputStream(FileInputStream(file(job.id)),FileTransferTuning.STREAM_BUFFER)).use { input ->
                var bytes=0L
                while(!job.stopped.get()) {
                    val cipher=LargeFileRules.read(input)
                    try { check(job.channel.sendBinary(job.id,cipher) { !job.stopped.get() });bytes+=cipher.size-LargeFileRules.OVERHEAD;note(job,bytes) } finally { cipher.fill(0) }
                    if(bytes==job.meta.getLong("size") && input.available()==0) break
                    if(inCall()) Thread.sleep(2) // Preserve modest media headroom without a sub1MiB/s ceiling.
                }
                check(!job.stopped.get() && bytes==job.meta.getLong("size"));check(input.read()==-1)
            }
            owner.execute { if(job.state=="sending" && !job.stopped.get()) { job.state="confirming";snapshot() } }
        } catch(_:Exception) { owner.execute { fail(job,true) } }
    }
    fun cancel(id:String) { jobs[id]?.let { fail(it,true) } }
    fun disconnected(peer:String) { quickConsent.disconnected(peer);jobs.values.filter { it.peer==peer && it.state!="done" }.forEach { fail(it,false) } }
    private fun fail(job:Job,notify:Boolean) {
        if(job.state=="done" || !job.stopped.compareAndSet(false,true)) return
        synchronized(job.progressLock) { job.progressLock.notifyAll() }
        if(!job.outgoing) discard.add(job.peer,job.channel.requestId,job.id)
        synchronized(job) { runCatching { job.quick?.finish(false) };job.quick=null;runCatching { job.writer?.close() };job.writer=null;job.reader?.close();job.reader=null;job.liveReader?.close();job.liveReader=null;job.liveWriter?.close();job.liveWriter=null;job.open=null }
        file(job.id,true).delete();job.state="failed"
        // A prepared sender copy can remain available locally; recipient partial files never do.
        if(!job.outgoing || job.meta.optBoolean("quick") || !contains(job.peer,job.id)) file(job.id).delete()
        if(job.meta.optBoolean("quick")) vault.transaction { it.execute("DELETE FROM large_files WHERE id=? AND peer=?",job.id,job.peer);it.execute("UPDATE messages SET kind='text',data=? WHERE id=? AND peer=? AND kind='attachment'",JSONObject().put("text","Quick Share · "+job.meta.optString("name")+" · Transfer stopped").toString(),job.id,job.peer) }
        if(notify && link(job.peer)===job.channel) runCatching { control(job.peer,JSONObject().put("type",type(job,"abort")).put("id",job.id)) }
        job.meta.remove("key");job.meta.remove("header");if(job.completedCallback.compareAndSet(false,true)) job.done(false);snapshot()
    }
    fun prune() {
        val removed=vault.transaction { tx ->
            val rows=tx.rows("SELECT l.id FROM large_files l LEFT JOIN messages m ON m.id=l.id AND m.peer=l.peer WHERE m.id IS NULL OR m.kind='deleted'")
            rows.forEach { tx.execute("DELETE FROM large_files WHERE id=?",it["id"]) };rows.map { it["id"] as String }
        }
        removed.forEach { id -> jobs.remove(id)?.let { fail(it,true) };file(id).delete();file(id,true).delete() }
    }
    fun contains(peer:String,id:String)=vault.transaction { it.rows("SELECT id FROM large_files WHERE id=? AND peer=?",id,peer).isNotEmpty() }
    fun media():List<PrivateLibrary.Media> = vault.transaction { tx ->
        tx.rows("SELECT l.* FROM large_files l JOIN messages m ON m.id=l.id AND m.peer=l.peer WHERE m.kind='attachment' ORDER BY l.at DESC LIMIT 500").map { row ->
            val meta=JSONObject(row["meta"] as String)
            PrivateLibrary.Media(row["id"] as String,row["peer"] as String,row["outgoing"]==1L,meta.getString("name"),meta.getString("mime"),meta.getLong("size"),row["at"] as Long)
        }
    }
    fun export(peer:String,id:String,open:()->OutputStream,done:(Boolean)->Unit,authorized:()->Boolean=canExport) {
        check(authorized())
        val row=vault.transaction { it.rows("SELECT l.meta FROM large_files l JOIN messages m ON m.id=l.id AND m.peer=l.peer JOIN contacts c ON c.uid=l.peer WHERE l.id=? AND l.peer=? AND m.kind='attachment' AND c.blocked=0",id,peer).single() }
        val meta=JSONObject(row["meta"] as String)
        io.execute {
            val key=WireCodec.key(meta.getString("key"),32);val header=WireCodec.key(meta.getString("header"),24)
            var ok=false
            try { FileInputStream(file(id)).use { input -> open().use { output -> crypto.export(input,output,id,meta.getLong("size"),key,header,meta.getString("hash")) { authorized() && file(id).exists() } } };ok=true }
            catch(_:Exception) { /* partial destination is removed by the caller */ }
            finally { key.fill(0);header.fill(0);done(ok) }
        }
    }
}
