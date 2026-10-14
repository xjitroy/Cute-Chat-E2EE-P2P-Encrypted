package app.cutiepie.cutie_pie.core

/** A denied Android grant is not a granted microphone. Only this current call may retry. */
class CallAudioFocusRetry {
    private var token="";private var began=0L;private var next=0L;private var attempts=0
    fun begin(call:String,now:Long) { token=call;began=now;next=now;attempts=0 }
    fun delay(call:String,now:Long):Long = if(call!=token || now<began || now-began>=2500) -1 else (next-now).coerceAtLeast(0)
    fun denied(call:String,now:Long):Long {
        if(delay(call,now)<0 || attempts>=6) return -1
        val delay=minOf(100L shl attempts,800L);attempts++;next=now+delay
        return if(next-began>=2500) -1 else delay
    }
    fun clear(call:String) { if(call==token) { token="";began=0;next=0;attempts=0 } }
}
