package app.cutiepie.cutie_pie.core

/** Independent local/remote pause controls. Resume cannot override the other person's pause. */
class FileTransferFlow {
    private var own=false;private var remote=false;private var remoteSeq=0L;private var ownSeq=0L;private var pausedAt:Long?=null
    @Synchronized fun local(value:Boolean,now:Long):Long { own=value;update(now);return ++ownSeq }
    @Synchronized fun received(value:Boolean,seq:Long,now:Long):Boolean { if(seq<=remoteSeq) return false;remoteSeq=seq;remote=value;update(now);return true }
    private fun update(now:Long) { if(own || remote) { if(pausedAt==null) pausedAt=now } else pausedAt=null }
    @Synchronized fun paused()=own || remote
    @Synchronized fun locallyPaused()=own
    @Synchronized fun expired(now:Long)=pausedAt?.let { now<it || now-it>=600_000 } ?: false
}

/** Rate is based on verified recipient progress, never selected-file size or preparation. */
class FileTransferRate {
    private var initialized=false
    private var bytes=0L;private var at=0L;private var rate=0L
    private var sampleBytes=0L;private var sampleAt=0L
    @Synchronized fun note(next:Long,now:Long) {
        if(next<0 || next<bytes || initialized && now<at) return
        if(!initialized) { initialized=true;bytes=next;sampleBytes=next;at=now;sampleAt=now;return }
        if(next==bytes) return
        bytes=next;at=now
        // A250ms verified-byte sample avoids bursts of tiny chunks producing fake speed/ETA.
        if(now-sampleAt>=250_000_000L) {
            val sample=((bytes-sampleBytes).toDouble()*1_000_000_000.0/(now-sampleAt)).toLong()
            rate=if(rate==0L) sample else (rate*3+sample)/4
            sampleBytes=bytes;sampleAt=now
        }
    }
    @Synchronized fun speed(now:Long)=if(!initialized || now<at || now-at>5_000_000_000L) 0L else rate
    @Synchronized fun remaining(size:Long,now:Long):Long? { val speed=speed(now);return if(speed<=0 || size<bytes) null else (size-bytes+speed-1)/speed }
}
