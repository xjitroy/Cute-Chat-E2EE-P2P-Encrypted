package app.cutiepie.cutie_pie.core

/** Local aggregate WebRTC measurements. Never records frames, content, IPs or peer IDs. */
class VideoDiagnostics {
    data class Stat(val id: String, val type: String, val timeUs: Double, val values: Map<String, Any?>)
    data class Stream(val width: Int, val height: Int, val fps: Double?, val codec: String, val bitrate: Long?)
    data class Snapshot(val sent: Stream?, val received: Stream?, val bandwidth: Double?, val rtt: Double?,
        val loss: Double?, val limitation: String)
    private data class Counter(val id: String, val time: Double, val bytes: Long, val frames: Long?)
    private val previous = mutableMapOf<Boolean, Counter>()
    private var reportTime = Double.NEGATIVE_INFINITY
    private fun number(v: Any?): Double? = (v as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 }
    fun read(timeUs: Double, stats: List<Stat>): Snapshot? {
        if (!timeUs.isFinite() || timeUs < 0 || timeUs <= reportTime) return null
        reportTime = timeUs
        val byId = stats.associateBy { it.id }
        fun primary(type: String) = stats.filter { it.type == type &&
            (it.values["kind"] == "video" || it.values["mediaType"] == "video") &&
            (number(it.values["frameWidth"]) ?: 0.0) > 0 && (number(it.values["frameHeight"]) ?: 0.0) > 0 }
            .maxByOrNull { number(it.values[if (type == "outbound-rtp") "bytesSent" else "bytesReceived"]) ?: 0.0 }
        val sent = primary("outbound-rtp"); val received = primary("inbound-rtp")
        val transport = (sent ?: received)?.values?.get("transportId")?.toString()?.let(byId::get)
            ?: stats.firstOrNull { it.type == "transport" && it.values["selectedCandidatePairId"] != null }
        val pair = transport?.values?.get("selectedCandidatePairId")?.toString()?.let(byId::get)
            ?.takeIf { it.type == "candidate-pair" && it.values["state"] == "succeeded" }
        val remote = sent?.values?.get("remoteId")?.toString()?.let(byId::get)?.takeIf { it.type == "remote-inbound-rtp" }
        fun stream(s: Stat?, outbound: Boolean): Stream? {
            if (s == null) { previous.remove(outbound); return null }
            val bytes = number(s.values[if (outbound) "bytesSent" else "bytesReceived"])?.toLong()
            val frames = number(s.values[if (outbound) "framesEncoded" else "framesDecoded"])?.toLong()
            val old = previous[outbound]
            val valid = old != null && old.id == s.id && s.timeUs.isFinite() && s.timeUs > old.time &&
                s.timeUs - old.time <= 10_000_000 && bytes != null && bytes >= old.bytes
            val dt = if (valid) s.timeUs - old!!.time else 0.0
            val rate = if (valid) ((bytes!! - old!!.bytes).toDouble() * 8_000_000 / dt).takeIf { it.isFinite() && it <= Long.MAX_VALUE }?.toLong() else null
            val oldFrames = old?.frames
            val fps = number(s.values["framesPerSecond"]) ?: if (valid && frames != null && oldFrames != null && frames >= oldFrames)
                (frames - oldFrames) * 1_000_000 / dt else null
            if (bytes != null && s.timeUs.isFinite() && s.timeUs >= 0 && (old == null || s.timeUs > old.time))
                previous[outbound] = Counter(s.id, s.timeUs, bytes, frames)
            val codec = s.values["codecId"]?.toString()?.let(byId::get)?.values?.get("mimeType")?.toString()?.substringAfter('/') ?: ""
            return Stream(number(s.values["frameWidth"])!!.toInt(), number(s.values["frameHeight"])!!.toInt(), fps, codec, rate)
        }
        return Snapshot(stream(sent,true),stream(received,false),number(pair?.values?.get("availableOutgoingBitrate")),
            number(pair?.values?.get("currentRoundTripTime")),number(remote?.values?.get("fractionLost"))?.takeIf { it <= 1 },
            sent?.values?.get("qualityLimitationReason")?.toString() ?: "unknown")
    }
    fun reset() { previous.clear(); reportTime = Double.NEGATIVE_INFINITY }
}
