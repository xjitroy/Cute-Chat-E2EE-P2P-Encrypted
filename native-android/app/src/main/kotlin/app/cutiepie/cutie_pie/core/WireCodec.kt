package app.cutiepie.cutie_pie.core

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Exact v1/v2 canonical wire encoding; no signing with incidental map order. */
object WireCodec {
    private val random = SecureRandom()
    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
    fun id(): String = b64(randomBytes(24))
    fun b64(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    fun decode(value: String, maxBytes: Int = 64 * 1024 * 1024): ByteArray {
        require(maxBytes >= 0 && value.isNotEmpty() && value.length.toLong() <= (maxBytes.toLong() * 4 + 2) / 3)
        require(value.matches(Regex("[A-Za-z0-9_-]+")))
        val bytes = Base64.getUrlDecoder().decode(value)
        require(bytes.size <= maxBytes && b64(bytes) == value) { "Non-canonical base64url" }
        return bytes
    }
    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun key(value: String, size: Int): ByteArray = decode(value, size).also { require(it.size == size) { "Invalid key length" } }
    fun parseObject(raw: String, maxBytes: Int, maxDepth: Int = 16): JSONObject {
        require(raw.toByteArray(Charsets.UTF_8).size <= maxBytes)
        var depth = 0; var quoted = false; var escaped = false
        for (ch in raw) {
            if (quoted) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') quoted = false
            } else when (ch) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= maxDepth) { "JSON nesting exceeds limit" } }
                '}', ']' -> { depth--; require(depth >= 0) }
            }
        }
        require(!quoted && depth == 0)
        return JSONObject(raw)
    }

    fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is String -> quote(value)
        is Boolean -> value.toString()
        is Byte, is Short, is Int, is Long -> value.toString()
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
            quote(it) + ":" + canonical(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is Map<*, *> -> {
            require(value.keys.all { it is String })
            value.keys.map { it as String }.sorted().joinToString(",", "{", "}") { quote(it) + ":" + canonical(value[it]) }
        }
        is List<*> -> value.joinToString(",", "[", "]", transform = ::canonical)
        else -> error("Unsupported canonical value")
    }

    // Dart jsonEncode keeps Unicode and '/', and escapes these controls exactly.
    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 32) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }
}
