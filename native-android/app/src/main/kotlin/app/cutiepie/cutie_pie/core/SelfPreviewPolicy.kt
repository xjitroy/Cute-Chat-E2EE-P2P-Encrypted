package app.cutiepie.cutie_pie.core

/** UI-only, monotonic and call-bound. Expiry never switches a newer call. */
class SelfPreviewPolicy {
    private var call = ""
    private var until = 0L
    fun expanded(currentCall: String, eligible: Boolean, now: Long): Boolean {
        if (!eligible || currentCall.isEmpty() || currentCall != call || now >= until) { reset(); return false }
        return true
    }
    fun tap(currentCall: String, eligible: Boolean, now: Long): Boolean {
        val wasExpanded = expanded(currentCall, eligible, now)
        if (!eligible || currentCall.isEmpty() || wasExpanded) { reset(); return false }
        call = currentCall; until = now + 10_000L; return true
    }
    fun reset() { call = ""; until = 0L }
}
