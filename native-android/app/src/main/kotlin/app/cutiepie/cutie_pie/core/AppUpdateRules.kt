package app.cutiepie.cutie_pie.core

import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

/** Public release metadata is a hint, never permission to trust another package/signer. */
object AppUpdateRules {
    const val REPOSITORY = "https://github.com/xjitroy/Cute-Chat-E2EE-P2P-Encrypted"
    const val LATEST = "https://api.github.com/repos/xjitroy/Cute-Chat-E2EE-P2P-Encrypted/releases/latest"
    const val MANIFEST = "cute-chat-update.json"
    const val PACKAGE = "app.cutiepie.cutie_pie"
    const val MAX_APK = 128L * 1024 * 1024
    private val hosts = setOf("github.com", "api.github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")
    data class Candidate(val code: Int, val version: String, val variant: String, val tag: String,
        val filename: String, val bytes: Long, val sha256: String, val url: String, val minSdk: Int)

    fun secureUrl(value: String): Boolean = runCatching {
        val u = URI(value)
        u.scheme == "https" && u.host in hosts && u.userInfo == null && u.fragment == null && u.port in setOf(-1, 443)
    }.getOrDefault(false)

    private fun releaseTag(release: JSONObject): String {
        require(release.get("draft") == false && release.get("prerelease") == false)
        return release.getString("tag_name").also { require(it.matches(Regex("[A-Za-z0-9._-]{1,80}"))) }
    }
    private fun asset(release: JSONObject, name: String, tag: String): JSONObject {
        val assets = release.getJSONArray("assets"); require(assets.length() <= 32)
        val matches = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("name") == name }
        require(matches.size == 1)
        return matches.single().also {
            require(it.getString("browser_download_url") == "$REPOSITORY/releases/download/$tag/$name")
            require(it.getString("state") == "uploaded")
        }
    }
    fun manifestUrl(release: JSONObject): String {
        val a = asset(release, MANIFEST, releaseTag(release))
        require(integer(a, "size") in 1..32768)
        return a.getString("browser_download_url")
    }
    private fun integer(j: JSONObject, key: String): Long {
        val n = j.get(key); require(n is Int || n is Long)
        return (n as Number).toLong()
    }
    fun candidate(release: JSONObject, manifest: JSONObject, variant: String, currentCode: Long, sdk: Int): Candidate? {
        require(variant in setOf("normal", "assist"))
        require(manifest.keys().asSequence().toSet() == setOf("schema", "package_name", "version_code", "version_name", "tag", "min_sdk", "apks"))
        require(integer(manifest,"schema") == 1L && manifest.getString("package_name") == PACKAGE)
        val tag = releaseTag(release); require(manifest.getString("tag") == tag)
        val code = integer(manifest,"version_code"); require(code in 1..Int.MAX_VALUE.toLong())
        val version = manifest.getString("version_name"); require(version.matches(Regex("[0-9][0-9A-Za-z.+_-]{0,47}")))
        val minimum = integer(manifest,"min_sdk"); require(minimum in 24..100)
        val apks = manifest.getJSONArray("apks"); require(apks.length() in 1..2)
        val entries = (0 until apks.length()).map { apks.getJSONObject(it) }
        require(entries.map { it.getString("variant") }.distinct().size == entries.size)
        entries.forEach { require(it.getString("variant") in setOf("normal", "assist")) }
        val selected = entries.singleOrNull { it.getString("variant") == variant } ?: return null
        require(selected.keys().asSequence().toSet() == setOf("variant", "name", "bytes", "sha256"))
        val name = selected.getString("name"); require(name.matches(Regex("Cute-Chat-[0-9A-Za-z._-]{1,64}-$variant\\.apk")))
        val bytes = integer(selected,"bytes"); require(bytes in 1..MAX_APK)
        val hash = selected.getString("sha256"); require(hash.matches(Regex("[0-9a-f]{64}")))
        val a = asset(release, name, tag); require(integer(a,"size") == bytes)
        if(code <= currentCode || sdk < minimum) return null
        return Candidate(code.toInt(),version,variant,tag,name,bytes,hash,a.getString("browser_download_url"),minimum.toInt())
    }
    fun sameSigners(installed: Set<String>, downloaded: Set<String>): Boolean =
        installed.isNotEmpty() && installed == downloaded && installed.all { it.matches(Regex("[0-9a-f]{64}")) }
    fun digestMatches(actual: ByteArray, expected: String): Boolean =
        expected.matches(Regex("[0-9a-f]{64}")) && MessageDigest.isEqual(actual, ByteArray(32) { expected.substring(it*2,it*2+2).toInt(16).toByte() })
}
