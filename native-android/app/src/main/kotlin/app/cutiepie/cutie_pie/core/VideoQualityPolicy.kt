package app.cutiepie.cutie_pie.core

/** Capture targets, not promises about remotely delivered resolution. */
object VideoQualityPolicy {
    data class Preset(val width: Int, val height: Int, val fps: Int) {
        val label get() = "${height}p · ${fps}fps"
        val maxBitrate get() = when {
            height >= 1080 && fps > 30 -> 20_000_000
            height >= 1080 -> 12_000_000
            fps > 30 -> 10_000_000
            else -> 6_000_000
        }
    }
    data class Format(val width: Int, val height: Int, val maxFps: Int)
    val defaults = Preset(1920,1080,30)
    val presets = listOf(defaults,Preset(1920,1080,60),Preset(1280,720,30),Preset(1280,720,60))
    fun supports(p: Preset, formats: List<Format>) = formats.any {
        maxOf(it.width,it.height)==p.width && minOf(it.width,it.height)==p.height && it.maxFps>=p.fps
    }
    fun choose(requested: Preset, formats: List<Format>, protective: Boolean): Preset {
        val target=if(protective) Preset(1280,720,30) else requested
        if(supports(target,formats)) return target
        return formats.filter { maxOf(it.width,it.height)<=target.width && minOf(it.width,it.height)<=target.height && it.maxFps>=15 }
            .maxByOrNull { it.width.toLong()*it.height }?.let {
                Preset(maxOf(it.width,it.height),minOf(it.width,it.height),minOf(target.fps,it.maxFps))
            } ?: Preset(640,480,15)
    }
    fun protect(batteryPercent: Int, thermalStatus: Int, temperatureTenths: Int) =
        batteryPercent in 0..49 || thermalStatus>=3 || temperatureTenths>=430
    fun recovery(batteryPercent: Int, thermalStatus: Int, temperatureTenths: Int) =
        batteryPercent>=55 && thermalStatus<=1 && temperatureTenths in 1..400
    fun androidMime(name: String): String? = when(name.uppercase(java.util.Locale.ROOT)) {
        "H264" -> "video/avc"
        "H265" -> "video/hevc"
        "AV1" -> "video/av01"
        "VP8" -> "video/x-vnd.on2.vp8"
        "VP9" -> "video/x-vnd.on2.vp9"
        else -> null
    }
}
