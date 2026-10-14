package app.cutiepie.cutie_pie.core

/** Drain only already-cancelled file records from the exact authenticated pipe.
 * Bounded tombstones preserve chat/calls after cancel/disk failure; never accept new content. */
class LocalFileDiscard(private val now:()->Long=System::nanoTime) {
    private data class Entry(val peer:String,val session:String,val expires:Long)
    private val entries=linkedMapOf<String,Entry>()
    @Synchronized fun add(peer:String,session:String,id:String) {
        entries.entries.removeAll { it.value.expires<=now() }
        entries[id]=Entry(peer,session,now()+60_000_000_000)
        while(entries.size>64) entries.remove(entries.keys.first())
    }
    @Synchronized fun allows(peer:String,session:String,id:String):Boolean {
        val entry=entries[id] ?: return false
        if(entry.expires<=now()) { entries.remove(id);return false }
        return entry.peer==peer && entry.session==session
    }
}
