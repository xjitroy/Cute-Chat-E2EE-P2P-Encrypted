package app.cutiepie.cutie_pie.core

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Bounded streaming verification. Caller owns a private partial file and publishes only on success. */
object AppUpdateDownload {
    fun copy(input: InputStream, output: OutputStream, bytes: Long, hash: String,
        cancelled: () -> Boolean, progress: (Long) -> Unit) {
        require(bytes in 1..AppUpdateRules.MAX_APK)
        require(hash.matches(Regex("[0-9a-f]{64}")))
        val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536);var count=0L
        while(true) {
            check(!cancelled())
            val n=input.read(buffer);if(n<0) break
            count+=n;require(count<=bytes)
            digest.update(buffer,0,n);output.write(buffer,0,n);progress(count)
        }
        check(!cancelled());require(count==bytes && AppUpdateRules.digestMatches(digest.digest(),hash))
    }
}
