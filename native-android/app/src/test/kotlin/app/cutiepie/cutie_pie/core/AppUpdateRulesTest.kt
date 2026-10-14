package app.cutiepie.cutie_pie.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class AppUpdateRulesTest {
    private val hash="a".repeat(64)
    private fun release()=JSONObject().put("tag_name","cute-chat-0.4.14").put("draft",false).put("prerelease",false)
        .put("assets",JSONArray().put(asset(AppUpdateRules.MANIFEST,500)).put(asset("Cute-Chat-0.4.14-normal.apk",80_000_000)))
    private fun asset(name:String,size:Long)=JSONObject().put("name",name).put("size",size).put("state","uploaded")
        .put("browser_download_url",AppUpdateRules.REPOSITORY+"/releases/download/cute-chat-0.4.14/"+name)
    private fun manifest()=JSONObject().put("schema",1).put("package_name",AppUpdateRules.PACKAGE).put("version_code",35)
        .put("version_name","0.4.14").put("tag","cute-chat-0.4.14").put("min_sdk",24).put("apks",JSONArray().put(
            JSONObject().put("variant","normal").put("name","Cute-Chat-0.4.14-normal.apk").put("bytes",80_000_000).put("sha256",hash)))
    private fun rejected(block:()->Unit) { try { block();fail("Must reject invalid update") } catch(_: IllegalArgumentException) {} }
    @Test fun onlyNewerCompatibleReleasePrompts() {
        assertEquals(35,AppUpdateRules.candidate(release(),manifest(),"normal",34,36)?.code)
        assertNull(AppUpdateRules.candidate(release(),manifest(),"normal",35,36))
        assertNull(AppUpdateRules.candidate(release(),manifest(),"normal",36,36))
        assertNull(AppUpdateRules.candidate(release(),manifest().put("min_sdk",36),"normal",34,29))
    }
    @Test fun noNormalUpdateSilentlyRemovesAssist() { assertNull(AppUpdateRules.candidate(release(),manifest(),"assist",34,36)) }
    @Test fun draftsPrereleasesAndFalseStringsAreRejected() {
        for(key in listOf("draft","prerelease")) for(value in listOf(true,"false")) rejected { AppUpdateRules.manifestUrl(release().put(key,value)) }
    }
    @Test fun differentRepoOrExternalDownloadCannotBeChosen() {
        val r=release();r.getJSONArray("assets").getJSONObject(1).put("browser_download_url","https://github.com/attacker/repo/releases/download/cute-chat-0.4.14/Cute-Chat-0.4.14-normal.apk")
        rejected { AppUpdateRules.candidate(r,manifest(),"normal",34,36) }
    }
    @Test fun duplicateAssetsAreRejectedRatherThanChosenArbitrarily() {
        val r=release();r.getJSONArray("assets").put(asset("Cute-Chat-0.4.14-normal.apk",80_000_000))
        rejected { AppUpdateRules.candidate(r,manifest(),"normal",34,36) }
    }
    @Test fun mismatchedTagPackageSizeOrHashIsRejected() {
        for(m in listOf(manifest().put("tag","other"),manifest().put("package_name","other.app"))) rejected { AppUpdateRules.candidate(release(),m,"normal",34,36) }
        for(field in listOf("bytes","sha256")) {
            val m=manifest();m.getJSONArray("apks").getJSONObject(0).put(field,if(field=="bytes") 1 else "bad")
            rejected { AppUpdateRules.candidate(release(),m,"normal",34,36) }
        }
    }
    @Test fun noFractionalStringOverflowVersionsOrMissingFields() {
        for(code in listOf<Any>(35.5,"35",0,-1,Long.MAX_VALUE)) rejected { AppUpdateRules.candidate(release(),manifest().put("version_code",code),"normal",34,36) }
        val missing=manifest();missing.remove("min_sdk");rejected { AppUpdateRules.candidate(release(),missing,"normal",34,36) }
    }
    @Test fun unknownFieldsAndDuplicateVariantsAreRejected() {
        rejected { AppUpdateRules.candidate(release(),manifest().put("exec","evil"),"normal",34,36) }
        val m=manifest();m.getJSONArray("apks").put(JSONObject(m.getJSONArray("apks").getJSONObject(0).toString()))
        rejected { AppUpdateRules.candidate(release(),m,"normal",34,36) }
    }
    @Test fun traversalTagsAndFilenamesCannotBeUsed() {
        rejected { AppUpdateRules.manifestUrl(release().put("tag_name","../other")) }
        val m=manifest();m.getJSONArray("apks").getJSONObject(0).put("name","../Cute-Chat-0.4.14-normal.apk")
        rejected { AppUpdateRules.candidate(release(),m,"normal",34,36) }
    }
    @Test fun redirectTransportIsHttpsAndExactHostOnly() {
        assertTrue(AppUpdateRules.secureUrl(AppUpdateRules.LATEST))
        assertTrue(AppUpdateRules.secureUrl("https://release-assets.githubusercontent.com/a?token=temporary"))
        for(url in listOf("http://github.com/file","https://github.com.evil.test/file","https://evil.test/github.com","https://user@github.com/file","https://github.com:444/file","file:///tmp/app.apk","https://github.com/a#b")) assertFalse(url,AppUpdateRules.secureUrl(url))
    }
    @Test fun signerMustExactlyMatchCurrentNonemptySignerSet() {
        assertTrue(AppUpdateRules.sameSigners(setOf(hash),setOf(hash)))
        assertFalse(AppUpdateRules.sameSigners(emptySet(),emptySet()))
        assertFalse(AppUpdateRules.sameSigners(setOf(hash),setOf("b".repeat(64))))
        assertFalse(AppUpdateRules.sameSigners(setOf(hash),setOf(hash,"b".repeat(64))))
    }
    private fun bytes()=ByteArray(65536*3+17) { (it%251).toByte() }
    private fun sha(b:ByteArray)=MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    @Test fun downloadStreamsBeforeTheWholeSourceIsConsumedAndPreservesAllBytes() {
        val b=bytes();val output=ByteArrayOutputStream();var first=0L
        AppUpdateDownload.copy(ByteArrayInputStream(b),output,b.size.toLong(),sha(b),{false}) { if(first==0L) first=it }
        assertEquals(65536L,first);assertArrayEquals(b,output.toByteArray())
    }
    @Test fun truncatedOversizedOrTamperedDownloadsCannotFinishVerified() {
        val b=bytes()
        for(source in listOf(b.copyOf(b.size-1),b+byteArrayOf(1),b.clone().apply { this[77]=42 })) rejected {
            AppUpdateDownload.copy(ByteArrayInputStream(source),ByteArrayOutputStream(),b.size.toLong(),sha(b),{false}) {}
        }
    }
    @Test fun cancellationStopsReadingAndNeverFinishesVerified() {
        val b=bytes();val output=ByteArrayOutputStream()
        try { AppUpdateDownload.copy(ByteArrayInputStream(b),output,b.size.toLong(),sha(b),{output.size()>0}) {};fail("Cancelled") }
        catch(_: IllegalStateException) { assertEquals(65536,output.size()) }
    }
    @Test fun invalidDeclaredSizeAndDigestAreRejectedBeforeWriting() {
        val b=bytes();val output=ByteArrayOutputStream()
        for(size in listOf(0L,-1L,AppUpdateRules.MAX_APK+1)) rejected { AppUpdateDownload.copy(ByteArrayInputStream(b),output,size,sha(b),{false}) {} }
        rejected { AppUpdateDownload.copy(ByteArrayInputStream(b),output,b.size.toLong(),"bad",{false}) {} }
        assertEquals(0,output.size())
    }
}
