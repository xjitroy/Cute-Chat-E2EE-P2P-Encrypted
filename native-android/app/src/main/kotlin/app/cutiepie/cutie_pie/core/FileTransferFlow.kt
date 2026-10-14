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
    private var bytes=0L;private var at=0L;private var rate=0L
    @Synchronized fun note(next:Long,now:Long) {
        if(next<bytes) return
        if(at!=0L && now>at && next>bytes) { val sample=((next-bytes).toDouble()*1_000_000_000.0/(now-at)).toLong();rate=if(rate==0L) sample else (rate*3+sample)/4 }
        bytes=next;at=now
    }
    @Synchronized fun speed(now:Long)=if(at==0L || now<at || now-at>5_000_000_000L) 0L else rate
    @Synchronized fun remaining(size:Long,now:Long):Long? { val speed=speed(now);return if(speed<=0 || size<bytes) null else (size-bytes+speed-1)/speed }
}
