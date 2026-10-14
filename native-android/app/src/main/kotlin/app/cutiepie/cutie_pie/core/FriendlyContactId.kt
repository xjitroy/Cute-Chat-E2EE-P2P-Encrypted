package app.cutiepie.cutie_pie.core

import java.math.BigInteger
import java.security.MessageDigest
import java.util.Locale

/** Human-facing label only. The signed CP1 invitation remains the trust authority. */
object FriendlyContactId {
    private val secureCode = Regex("CP1\\.[A-Za-z0-9_-]{20,4096}")

    /** The prefix is a display label, never an identity verification factor. */
    fun number(value: String): String? {
        val clean = value.trim()
        return when {
            clean.matches(Regex("[0-9]{10}")) -> clean
            clean.matches(Regex("[\\p{L}\\p{N}]{4}-[0-9]{10}")) -> clean.substringAfter('-')
            else -> null
        }
    }

    fun create(name: String, signedInvitation: String): String {
        val prefix = name.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }.take(4).padEnd(4, 'X')
        val digest = MessageDigest.getInstance("SHA-256").digest(signedInvitation.toByteArray(Charsets.UTF_8))
        return try {
            val number = BigInteger(1, digest).mod(BigInteger("10000000000")).toString().padStart(10, '0')
            "$prefix-$number"
        } finally { digest.fill(0) }
    }

    fun share(name: String, signedInvitation: String) =
        "Cute Chat ID: ${create(name, signedInvitation)}\nSecure Contact Code:\n$signedInvitation"

    /** Numeric directory ID only: do not leak a display-name prefix or CP1 payload. */
    fun shareNumber(uid: String): String = checkNotNull(number(create("CUTE",uid)))

    fun extract(value: String) = secureCode.find(value)?.value ?: value.trim()
}
