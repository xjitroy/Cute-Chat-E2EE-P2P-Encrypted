package app.cutiepie.cutie_pie
/** No service, timer, consent, input handler or peer control exists in Normal. */
class NativeRemoteAssist(@Suppress("UNUSED_PARAMETER") app:CutieApplication) {
    val hostGranted:Boolean get() = false
    val remoteEpoch:String get() = ""
    fun stopLocal() = Unit
    fun grant(@Suppress("UNUSED_PARAMETER") ticket:String) = Unit
}
