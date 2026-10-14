package app.cutiepie.cutie_pie.core

/** Owns a bounded decrypted playback buffer; never persists it to a file. */
class EphemeralMediaBytes(private var bytes: ByteArray?) : AutoCloseable {
    @Synchronized fun size() = bytes?.size?.toLong() ?: 0L
    @Synchronized fun read(position: Long,target: ByteArray,offset: Int,size: Int): Int {
        require(offset >= 0 && size >= 0 && offset <= target.size-size)
        val source = bytes ?: return -1
        if(position < 0 || position >= source.size) return -1
        if(size == 0) return 0
        val count = minOf(size,source.size-position.toInt())
        source.copyInto(target,offset,position.toInt(),position.toInt()+count)
        return count
    }
    @Synchronized override fun close() { bytes?.fill(0); bytes = null }
}
