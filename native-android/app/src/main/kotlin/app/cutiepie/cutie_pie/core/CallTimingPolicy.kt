package app.cutiepie.cutie_pie.core

/** One deadline owner for ringing; answering replaces, never retains, that deadline. */
object CallTimingPolicy {
    const val RING_MS = 60_000L
    const val CONNECT_MS = 30_000L
    fun ringBudget(expires: Long, now: Long) = (expires - now).coerceIn(0, RING_MS)
    fun timeout(incoming: Boolean, stage: String) = when {
        stage == "incoming" && incoming -> "missed"
        stage == "calling" -> "unanswered"
        else -> "failed"
    }
    fun ringback(stage: String, confirmed: Boolean) = stage == "calling" && confirmed
}
