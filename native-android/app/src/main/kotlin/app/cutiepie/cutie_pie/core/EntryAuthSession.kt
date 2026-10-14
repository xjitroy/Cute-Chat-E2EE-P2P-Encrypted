package app.cutiepie.cutie_pie.core

/** A delayed callback from an old entry must never unlock a newer entry. */
class EntryAuthSession {
    private var sequence=0L
    private var current:Long?=null
    fun begin():Long = (++sequence).also { current=it }
    fun invalidate() { current=null }
    fun isCurrent(token:Long)=current == token
    fun finish(token:Long):Boolean {
        if(!isCurrent(token)) return false
        current=null; return true
    }
}
