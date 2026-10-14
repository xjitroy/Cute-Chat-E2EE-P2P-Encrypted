package app.cutiepie.cutie_pie.core

/** Small, testable boundaries for delayed Android UI callbacks. */
object CallUiPolicy {
    fun mayProject(consentedCall: String, currentCall: String, video: Boolean, status: String, foreground: Boolean) =
        consentedCall.isNotEmpty() && consentedCall == currentCall && video && status == "connected" && foreground

    fun mayEnterPip(currentCall: String, visible: Boolean, unlocked: Boolean) =
        currentCall.isNotEmpty() && visible && unlocked
}
