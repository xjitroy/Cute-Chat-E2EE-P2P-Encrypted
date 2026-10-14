package app.cutiepie.cutie_pie.core

import java.util.Locale

/** Real-time lossy codec policy. Budgets are ceilings, never promised quality or savings. */
object VideoCompressionPolicy {
    enum class Mode(val key: String) { DETAIL("detail"), SMOOTH("smooth"), DATA_SAVER("data_saver") }
    enum class Adaptation { DETAIL, SMOOTH, BALANCED }
    fun mode(key: String?) = Mode.entries.firstOrNull { it.key == key } ?: Mode.DETAIL
    private fun name(codec: String) = codec.substringAfterLast('/').uppercase(Locale.ROOT)
    fun codecRank(codec: String, parameters: Map<String, String>, fittedHardware: Set<String>): Int {
        val c = name(codec)
        val high = parameters["profile-level-id"]?.matches(Regex("(?i)64[0-9a-f]{4}")) == true
        return when {
            c == "H265" && c in fittedHardware -> 0
            c == "VP9" && c in fittedHardware -> 1
            c == "H264" && c in fittedHardware && high -> 2
            c == "H264" -> 3
            c == "VP8" -> 4
            c == "AV1" && c in fittedHardware -> 5
            c == "VP9" -> 6
            c == "AV1" -> 7
            c == "H265" -> 8
            else -> 9 // Preserve the SDK's RTX/RED/FEC capabilities.
        }
    }
    fun budget(p: VideoQualityPolicy.Preset, mode: Mode, codec: String, screen: Boolean): Int {
        if (screen) return if (mode == Mode.DATA_SAVER) 2_000_000 else 6_000_000
        val efficient = name(codec) in setOf("AV1", "H265", "VP9")
        if (mode == Mode.DATA_SAVER) return if (efficient) 1_500_000 else 2_000_000
        val ceiling = p.maxBitrate
        // Separate user budgets; the WebRTC allocator may send far below these ceilings.
        // Detail keeps the full ceiling for ALL codecs: a smaller budget is not
        // proof of equivalent fidelity, and codec efficiency must not imply an
        // arbitrary20% quality cut. Network allocation may still be far lower.
        val percent = if (mode == Mode.SMOOTH) { if (efficient) 60 else 80 } else 100
        return (ceiling.toLong() * percent / 100).toInt()
    }
    fun detailFloor(p: VideoQualityPolicy.Preset, codec: String, screen: Boolean): Double {
        val base = if (screen) 1_000_000.0 else if (p.height >= 1080) 3_000_000.0 else 1_500_000.0
        val efficiency = if (name(codec) in setOf("AV1", "H265", "VP9")) 0.7 else 1.0
        return base * efficiency * if (!screen && p.fps > 30) 1.5 else 1.0
    }
    fun adaptation(mode: Mode, detailHealthy: Boolean, protective: Boolean, screen: Boolean): Adaptation = when {
        protective -> Adaptation.BALANCED
        screen -> if (detailHealthy) Adaptation.DETAIL else Adaptation.BALANCED
        mode == Mode.DATA_SAVER -> Adaptation.BALANCED
        mode == Mode.SMOOTH -> Adaptation.SMOOTH
        detailHealthy -> Adaptation.DETAIL
        else -> Adaptation.BALANCED
    }

    /** Resolution priority only after sustained healthy measurements, with immediate fallback. */
    class DetailGate {
        private var lastAt = -1L
        private var healthySince = -1L
        private var unhealthyAt = -1L
        private var samples = 0
        private var allowed = false
        fun observe(now: Long, bandwidth: Double?, rtt: Double?, loss: Double?, reason: String, floor: Double, protective: Boolean) {
            if (now < 0 || now <= lastAt) return
            if (lastAt >= 0 && now - lastAt > 7_000) { allowed = false; samples = 0; healthySince = -1 }
            lastAt = now
            val healthy = !protective && bandwidth != null && bandwidth.isFinite() && bandwidth >= floor &&
                rtt != null && rtt.isFinite() && rtt in 0.0..0.35 &&
                (loss == null || (loss.isFinite() && loss in 0.0..0.04)) && reason == "none"
            if (!healthy) { allowed = false; samples = 0; healthySince = -1; unhealthyAt = now; return }
            if (healthySince < 0) healthySince = now
            samples++
            if (samples >= 3 && now - healthySince >= 6_000 && (unhealthyAt < 0 || now - unhealthyAt >= 9_000)) allowed = true
        }
        fun permits(now: Long) = allowed && now >= lastAt && now - lastAt <= 7_000
        fun reset() { lastAt = -1; healthySince = -1; unhealthyAt = -1; samples = 0; allowed = false }
    }
}
