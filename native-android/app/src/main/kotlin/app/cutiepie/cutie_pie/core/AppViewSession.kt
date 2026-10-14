package app.cutiepie.cutie_pie.core

/** Process-memory view authorization only. Never store an unlocked flag or authentication timestamp on disk. */
class AppViewSession {
    private var verified=false
    private var outsideAt:Long?=null
    @Synchronized fun authenticated() { verified=true;outsideAt=null }
    @Synchronized fun leave(now:Long) { if(verified && outsideAt==null) outsideAt=now }
    @Synchronized fun invalidate() { verified=false;outsideAt=null }
    @Synchronized fun usable(now:Long,minutes:Int,phoneLocked:Boolean=false):Boolean {
        if(phoneLocked) invalidate()
        if(!verified) return false
        val left=outsideAt
        if(left!=null && (now<left || now-left>=minutes.coerceIn(0,60)*60_000L)) { invalidate();return false }
        return true
    }
    @Synchronized fun resume(now:Long,minutes:Int,phoneLocked:Boolean=false):Boolean {
        if(!usable(now,minutes,phoneLocked)) return false
        outsideAt=null;return true
    }
}
