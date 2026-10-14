package app.cutiepie.cutie_pie.core

/** Serialized by the native owner; grants and frame capabilities are RAM-only. */
class AssistGuard {
    data class Frame(val token:String,val key:String,val issued:Long)
    private var peer=""; private var call=""; private var epoch=""
    private var accepted=false; private var expires=0L; private var activity=0L
    private var idle=120_000L; private var sequence=0L; private var lastInput=-1000L
    private val frames=ArrayDeque<Frame>()
    fun grant(peer:String,call:String,epoch:String,now:Long,duration:Long,idle:Long) {
        require(peer.isNotEmpty() && call.isNotEmpty() && epoch.isNotEmpty())
        clear(); this.peer=peer;this.call=call;this.epoch=epoch
        expires=now+duration.coerceIn(60_000,1_800_000);activity=now;this.idle=idle.coerceIn(30_000,300_000)
    }
    fun accept(peer:String,call:String,epoch:String,now:Long):Boolean {
        if(!matches(peer,call,epoch) || !live(now)) return false
        accepted=true;activity=now;return true
    }
    fun matches(peer:String,call:String,epoch:String)=this.peer==peer && this.call==call && this.epoch==epoch && epoch.isNotEmpty()
    fun live(now:Long)=epoch.isNotEmpty() && now>=activity && now<expires && now-activity<idle
    fun remaining(now:Long)=minOf(expires-now,idle-(now-activity)).coerceAtLeast(0)
    fun publish(token:String,key:String,now:Long):Frame {
        if(frames.lastOrNull()?.key!=key) frames.clear()
        val frame=Frame(token,key,now);frames.addLast(frame)
        while(frames.size>3) frames.removeFirst()
        return frame
    }
    fun pause() { frames.clear() }
    fun authorize(peer:String,call:String,epoch:String,token:String,seq:Long,now:Long):Frame? {
        if(!accepted || !matches(peer,call,epoch) || !live(now) || seq<=sequence || seq>1_000_000_000 || now-lastInput<120) return null
        val frame=frames.find { it.token==token && now-it.issued in 0..1500 } ?: return null
        sequence=seq;lastInput=now;return frame
    }
    fun performed(epoch:String,now:Long) { if(this.epoch==epoch && live(now)) activity=now }
    fun clear() { peer="";call="";epoch="";accepted=false;expires=0;activity=0;sequence=0;lastInput=-1000;frames.clear() }
}
