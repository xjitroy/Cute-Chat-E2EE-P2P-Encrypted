package app.cutiepie.cutie_pie.core

/** Authenticated connection is distinct from fresh encrypted foreground presence. */
class PeerPresence(private val now: () -> Long) {
    private data class Entry(val active: Boolean,val at: Long)
    private val states = mutableMapOf<String,Entry>()
    fun received(peer: String,active: Boolean) { states[peer] = Entry(active,now()) }
    fun remove(peer: String) { states.remove(peer) }
    fun clear() = states.clear()
    fun online(connected: Set<String>) = connected.filter { peer ->
        states[peer]?.let { it.active && now()-it.at in 0..90_000 } == true
    }.toSet()
}
