package app.cutiepie.cutie_pie.core

import org.json.JSONObject
import java.io.*
import java.util.ArrayDeque
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded reliable TCP/RFCOMM pipe. Content is always a Signal frame, never plaintext. */
class LocalStreamLink(
    override val medium:String, val endpoint:String,
    private val input:InputStream, private val output:OutputStream, private val resource:Closeable,
    private val owner:ScheduledExecutorService, private val packet:(LocalStreamLink,JSONObject)->Unit,
    private val finished:(LocalStreamLink)->Unit
) : FileStreamLink {
    var unmanagedNetwork=false;internal set
    val nonce=WireCodec.id()
    override var requestId=""; private set
    @Volatile override var ready=false; private set
    private val closed=AtomicBoolean(false)
    private val lock=java.lang.Object()
    private val control=ArrayDeque<ByteArray>();private val data=ArrayDeque<ByteArray>();private val audio=ArrayDeque<ByteArray>()
    private var queued=0
    private var onOpen:()->Unit={};private var onFrame:(String)->Unit={};private var onWritable:()->Unit={};private var onClosed:()->Unit={}
    @Volatile override var onBinary:((String,ByteArray)->Unit)?=null
    private val writablePending=AtomicBoolean(false)
    private val readPending=java.util.concurrent.Semaphore(8)
    @Volatile private var writingAt=0L
    private var watchdog:java.util.concurrent.ScheduledFuture<*>?=null
    private var deadline:java.util.concurrent.ScheduledFuture<*>?=null
    fun bind(id:String,open:()->Unit,frame:(String)->Unit,writable:()->Unit,close:()->Unit) {
        check(!closed.get() && !ready);requestId=ProtocolRules.id(id)
        onOpen=open;onFrame=frame;onWritable=writable;onClosed=close
    }
    override fun start() { check(requestId.isNotEmpty() && !ready && !closed.get());ready=true;deadline?.cancel(false);onOpen();onWritable() }
    fun receiveFrame(frame:String) { check(ready);onFrame(frame) }
    fun begin(invitation:String) {
        watchdog=owner.scheduleWithFixedDelay({ if(writingAt>0 && System.nanoTime()-writingAt>TimeUnit.SECONDS.toNanos(15)) close() },3,3,TimeUnit.SECONDS)
        deadline=owner.schedule({ if(!ready) close() },120,TimeUnit.SECONDS)
        check(offer(JSONObject().put("type","invite").put("code",invitation).put("nonce",nonce),true))
        Thread({
            try { val stream=DataInputStream(if(medium=="wifi") BufferedInputStream(input,FileTransferTuning.STREAM_BUFFER) else input)
                while(!closed.get()) {
                    readPending.acquire()
                    val bytes=try { LocalSharingRules.read(stream) } catch(t:Throwable) { readPending.release();throw t }
                    if(bytes[0]==0.toByte()) {
                        try {
                            check(ready && medium=="wifi" && bytes.size in (25+LargeFileRules.OVERHEAD)..(25+LargeFileRules.CHUNK+LargeFileRules.OVERHEAD))
                            val id=WireCodec.b64(bytes.copyOfRange(1,25));val cipher=bytes.copyOfRange(25,bytes.size)
                            try { (onBinary ?: error("Unaccepted file stream"))(id,cipher) } finally { cipher.fill(0) }
                        } finally { bytes.fill(0);readPending.release() }
                        continue
                    }
                    owner.execute { try { if(!closed.get()) packet(this,WireCodec.parseObject(bytes.toString(Charsets.UTF_8),LocalSharingRules.MAX_PACKET)) }
                        catch(_:Exception) { close() } finally { bytes.fill(0);readPending.release() } }
                }
            } catch(_:Exception) { close() }
        },"CuteLocalRead").apply { isDaemon=true;start() }
        Thread({
            try { val stream=DataOutputStream(if(medium=="wifi") BufferedOutputStream(output,FileTransferTuning.STREAM_BUFFER) else output)
                var batch=0;var writableAt=0L
                while(!closed.get()) {
                    val bytes=synchronized(lock) {
                        while(!closed.get() && control.isEmpty() && audio.isEmpty() && data.isEmpty()) lock.wait()
                        if(closed.get()) null else (if(control.isNotEmpty()) control else if(audio.isNotEmpty()) audio else data).removeFirst().also { queued-=it.size;lock.notifyAll() }
                    } ?: break
                    writingAt=System.nanoTime()
                    val binary=bytes[0]==0.toByte()
                    val drained=synchronized(lock) { control.isEmpty() && audio.isEmpty() && data.isEmpty() }
                    try {
                        // Combine length/payload writes, retain bounded batches and immediate controls.
                        batch+=bytes.size+4
                        val flush=medium!="wifi" || !binary || drained || batch>=FileTransferTuning.FLUSH_BATCH
                        LocalSharingRules.write(stream,bytes,flush)
                        if(flush) batch=0
                    } finally { writingAt=0;bytes.fill(0) }
                    val now=System.nanoTime()
                    if((!binary || drained || now-writableAt>=16_000_000L) && writablePending.compareAndSet(false,true)) {
                        writableAt=now
                        owner.execute { try { if(ready && !closed.get()) onWritable() } finally { writablePending.set(false) } }
                    }
                }
            } catch(_:Exception) { close() }
        },"CuteLocalWrite").apply { isDaemon=true;start() }
    }
    fun offer(value:JSONObject,metadata:Boolean=false,live:Boolean=false):Boolean {
        if(closed.get()) return false
        val bytes=WireCodec.canonical(value).toByteArray(Charsets.UTF_8);require(bytes.size<=LocalSharingRules.MAX_PACKET)
        return synchronized(lock) {
            if(closed.get() || queued+bytes.size>(if(medium=="bluetooth") 64*1024 else FileTransferTuning.WIFI_QUEUE+FileTransferTuning.CONTROL_RESERVE) || (metadata && control.size>=8) || (live && audio.size>=2)) { bytes.fill(0);false }
            else { (if(metadata) control else if(live) audio else data).add(bytes);queued+=bytes.size;lock.notifyAll();true }
        }
    }
    override fun send(frame:String,urgent:Boolean):Boolean { check(ready);require(frame.toByteArray().size<=40000);return offer(JSONObject().put("type","frame").put("frame",frame),metadata=urgent) }
    /** Only encrypted secretstream records. Blocks the dedicated file worker, never the owner/UI. */
    override fun sendBinary(id:String,cipher:ByteArray,alive:()->Boolean):Boolean {
        require(cipher.size in LargeFileRules.OVERHEAD..LargeFileRules.CHUNK+LargeFileRules.OVERHEAD)
        val bytes=ByteArray(25+cipher.size);WireCodec.key(id,24).copyInto(bytes,1);cipher.copyInto(bytes,25)
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
        return synchronized(lock) {
            while(ready && !closed.get() && alive() && queued+bytes.size>FileTransferTuning.WIFI_QUEUE && System.nanoTime()<deadline) lock.wait(100)
            if(!ready || closed.get() || medium!="wifi" || !alive() || queued+bytes.size>FileTransferTuning.WIFI_QUEUE) { bytes.fill(0);false }
            else { data.add(bytes);queued+=bytes.size;lock.notifyAll();true }
        }
    }
    fun audioCapacity()=synchronized(lock) { ready && !closed.get() && audio.size<2 && queued<(if(medium=="bluetooth") 60 else 252)*1024 }
    fun sendAudio(frame:String)=ready && offer(JSONObject().put("type","audio").put("frame",frame),live=true)
    override fun close() {
        if(!closed.compareAndSet(false,true)) return
        ready=false;deadline?.cancel(false);watchdog?.cancel(false)
        synchronized(lock) { for(q in listOf(control,data,audio)) { q.forEach { it.fill(0) };q.clear() };queued=0;lock.notifyAll() }
        try { resource.close() } catch(_:Exception) { }
        try { owner.execute { try { onClosed() } finally { finished(this) } } } catch(_:java.util.concurrent.RejectedExecutionException) { /* resources already closed */ }
    }
}
