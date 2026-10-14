package app.cutiepie.cutie_pie.core

enum class ContactConnectionState { CHECKING, CONNECTING, CONNECTED, OFFLINE, RETRYING, WAITING }

/** Bounded foreground probes. A failed service request is never proof a friend is offline. */
class ContactConnectionPolicy(private val now: () -> Long, private val capacity: Int = 16) {
    private val retryAt = mutableMapOf<String, Long>()
    private val observed = mutableMapOf<String, ContactConnectionState>()
    private var cursor = 0
    fun due(peers: List<String>, connected: Set<String>, allocated: Set<String>, inFlight: Set<String>, preferred: String?): List<String> {
        val free = (capacity - (allocated + inFlight).size).coerceIn(0, 4)
        if (free == 0 || peers.isEmpty()) return emptyList()
        val start = cursor % peers.size
        val ordered = (listOfNotNull(preferred) + peers.drop(start) + peers.take(start)).distinct().filter { it in peers }
        val result = ordered.filter { it !in connected && it !in allocated && it !in inFlight && now() >= (retryAt[it] ?: 0) }.take(free)
        cursor = (start + maxOf(1, result.size)) % peers.size
        return result
    }
    fun began(peer: String) { observed[peer] = ContactConnectionState.CONNECTING; retryAt[peer] = now() + 5000 }
    fun available(peer: String, online: Boolean) {
        observed[peer] = if (online) ContactConnectionState.CONNECTING else ContactConnectionState.OFFLINE
        retryAt[peer] = now() + if (online) 5000 else 15000
    }
    fun failed(peer: String) { observed[peer] = ContactConnectionState.RETRYING; retryAt[peer] = now() + 15000 }
    fun closed(peer: String) { observed[peer] = ContactConnectionState.RETRYING; retryAt[peer] = now() + 5000 }
    fun snapshot(peers: List<String>, connected: Set<String>, allocated: Set<String>, inFlight: Set<String>, serviceReady: Boolean): Map<String, ContactConnectionState> {
        observed.keys.retainAll(peers.toSet()); retryAt.keys.retainAll(peers.toSet())
        return peers.associateWith { peer -> when {
            peer in connected -> ContactConnectionState.CONNECTED
            peer in allocated || peer in inFlight -> ContactConnectionState.CONNECTING
            !serviceReady -> ContactConnectionState.RETRYING
            (allocated + inFlight).size >= capacity -> ContactConnectionState.WAITING
            else -> observed[peer] ?: ContactConnectionState.CHECKING
        } }
    }
    fun reset() { observed.clear(); retryAt.clear(); cursor = 0 }
}
