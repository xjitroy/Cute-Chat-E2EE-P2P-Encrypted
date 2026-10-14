package app.cutiepie.cutie_pie.core

/** Memory/latency budgets, not network-speed promises. Wire records remain compatible 32 KiB. */
object FileTransferTuning {
    const val WIFI_QUEUE = 2 * 1024 * 1024
    const val CONTROL_RESERVE = 64 * 1024
    const val SOCKET_BUFFER = 1024 * 1024
    const val STREAM_BUFFER = 128 * 1024
    const val FLUSH_BATCH = 128 * 1024
    const val PROGRESS_DELAY_MS = 50L
    const val ACK_MIN_BYTES = 64 * 1024L
    const val ACK_IDLE_NS = 30_000_000_000L
    fun window(medium: String, inCall: Boolean): Long = when {
        inCall -> 512 * 1024L
        medium == "wifi" -> 4 * 1024 * 1024L
        else -> 1024 * 1024L
    }
    fun canAdvance(next: Long, acknowledged: Long, medium: String, inCall: Boolean): Boolean =
        next >= 0 && acknowledged >= 0 && next >= acknowledged && next - acknowledged <= window(medium, inCall)
}
