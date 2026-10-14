package app.cutiepie.cutie_pie.core

import java.util.Calendar
import java.util.TimeZone

object GalleryTimeline {
    fun visual(mime: String) = mime.startsWith("image/") || mime.startsWith("video/")
    /** Local calendar days, not fixed UTC/24-hour buckets (DST and travel matter). */
    fun day(at: Long, zone: TimeZone = TimeZone.getDefault()): Long = Calendar.getInstance(zone).run {
        timeInMillis = at; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); timeInMillis
    }
    fun groups(media: List<PrivateLibrary.Media>, zone: TimeZone = TimeZone.getDefault()) =
        media.filter { visual(it.mime) }.sortedByDescending { it.at }.groupBy { day(it.at, zone) }
}
