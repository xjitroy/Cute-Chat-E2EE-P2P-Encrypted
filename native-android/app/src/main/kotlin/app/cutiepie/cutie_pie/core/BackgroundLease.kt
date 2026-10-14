package app.cutiepie.cutie_pie.core

/** Pure policy. Uses elapsedRealtime, so clock changes cannot extend a wake. */
class BackgroundLease(private val elapsedMillis: () -> Long) {
    enum class Reason { FOREGROUND, CALL, FILE, RING, SCHEDULE, PING, CHAT_GRACE, NONE }
    data class Decision(val reason: Reason, val untilElapsed: Long?) {
        val active: Boolean get() = reason != Reason.NONE
    }
    private var foreground = false
    private var genuineCall = false
    private var fileTransfer = false
    fun fileTransfer(active:Boolean) { fileTransfer=active }
    private var ringUntil = 0L
    private var scheduledUntil = 0L
    private var graceUntil = 0L
    private var pingUntil = 0L
    private var pingOverride = false
    private var lastPingId: String? = null
    private val seenPings = LinkedHashSet<String>()

    fun enterForeground() { foreground = true; pingOverride = false }
    fun leaveForeground(graceMinutes: Int) {
        require(graceMinutes in 1..30)
        foreground = false
        graceUntil = elapsedMillis() + graceMinutes * 60_000L
    }
    fun acceptedCall(active: Boolean) { genuineCall = active }
    fun incomingRing(untilElapsed: Long) {
        require(untilElapsed == 0L || untilElapsed-elapsedMillis() in 0..60000)
        ringUntil=untilElapsed
    }
    fun schedule(untilElapsed: Long) {
        // Only a user-approved bounded hold, not arbitrary remote metadata.
        require(untilElapsed >= elapsedMillis() && untilElapsed - elapsedMillis() <= 24 * 60 * 60_000L)
        scheduledUntil = untilElapsed
    }
    fun cancelSchedule() { scheduledUntil = 0 }
    fun ping(requestId: String,duration:Long=60000): Boolean {
        require(duration in 1..60000)
        require(requestId.matches(Regex("[A-Za-z0-9_-]{16,128}")))
        if (!seenPings.add(requestId)) return false // Replay does not extend the window.
        while (seenPings.size > 256) seenPings.remove(seenPings.first())
        lastPingId = requestId
        pingUntil = elapsedMillis() + duration
        pingOverride = !foreground
        return true
    }
    fun endPing(requestId: String) { if (lastPingId == requestId) pingUntil = 0 }
    fun actualContentActivity(graceMinutes: Int) {
        require(graceMinutes in 1..30)
        if (!pingOverride) graceUntil = elapsedMillis() + graceMinutes * 60_000L
    }
    fun decision(): Decision {
        val now = elapsedMillis()
        return when {
            foreground -> Decision(Reason.FOREGROUND, null)
            genuineCall -> Decision(Reason.CALL, null)
            fileTransfer -> Decision(Reason.FILE, null)
            ringUntil > now -> Decision(Reason.RING,ringUntil)
            scheduledUntil > now -> Decision(Reason.SCHEDULE, scheduledUntil)
            pingOverride -> if (pingUntil > now) Decision(Reason.PING, pingUntil) else Decision(Reason.NONE, null)
            graceUntil > now -> Decision(Reason.CHAT_GRACE, graceUntil)
            pingUntil > now -> Decision(Reason.PING, pingUntil)
            else -> Decision(Reason.NONE, null)
        }
    }
    fun stop() {
        foreground = false; genuineCall = false; fileTransfer=false; ringUntil=0; scheduledUntil = 0; graceUntil = 0; pingUntil = 0
        // Retain replay history during this process; no stale restart after Stop.
        pingOverride = true
    }
}
