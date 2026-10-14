package app.cutiepie.cutie_pie

import android.content.pm.PackageManager
import android.content.pm.PackageInstaller
import android.content.ComponentName
import android.os.Build
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.cutiepie.cutie_pie.core.AppUpdateRules
import app.cutiepie.cutie_pie.core.AppUpdateDownload
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Compile here; execute on a real Android target before public publication. No fixture bypass. */
class AppUpdateAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun updateProviderIsPrivateAndUsesExplicitTemporaryUriGrants() {
        val provider=context.packageManager.resolveContentProvider(context.packageName+".updates",0)!!
        assertFalse(provider.exported);assertTrue(provider.grantUriPermissions)
        val uri=FileProvider.getUriForFile(context,context.packageName+".updates",File(context.filesDir,"app-updates/candidate.apk"))
        assertEquals("content",uri.scheme)
    }
    @Test fun updateProviderCannotExposeVaultOrOtherPrivateFiles() {
        try {
            FileProvider.getUriForFile(context,context.packageName+".updates",File(context.filesDir,"not-updates/private.bin"))
            fail("Non-update file must not be exposed")
        } catch(_: IllegalArgumentException) {}
    }
    @Test fun installerCallbackReceiverIsNotExported() {
        @Suppress("DEPRECATION")
        val receiver=context.packageManager.getReceiverInfo(ComponentName(context,UpdateInstallReceiver::class.java),0)
        assertFalse(receiver.exported)
        assertTrue(receiver.enabled)
    }
    @Test fun androidSessionOwnsAnExactCopyIndependentOfAppFileUri() {
        val apk=File(context.applicationInfo.sourceDir)
        val digest=MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer=ByteArray(65536)
            while(true) { val n=input.read(buffer);if(n<0) break;digest.update(buffer,0,n) }
        }
        val expected=digest.digest().joinToString("") { "%02x".format(it) }
        val installer=context.packageManager.packageInstaller
        val id=installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName);setSize(apk.length())
        })
        try {
            installer.openSession(id).use { session ->
                apk.inputStream().use { input -> session.openWrite("base.apk",0,apk.length()).use { output ->
                    AppUpdateDownload.copy(input,output,apk.length(),expected,{ false }) {}
                    session.fsync(output)
                } }
                val actual=MessageDigest.getInstance("SHA-256");var bytes=0L
                session.openRead("base.apk").use { input ->
                    val buffer=ByteArray(65536)
                    while(true) { val n=input.read(buffer);if(n<0) break;actual.update(buffer,0,n);bytes+=n }
                }
                assertEquals(apk.length(),bytes)
                assertTrue(AppUpdateRules.digestMatches(actual.digest(),expected))
            }
        } finally { installer.abandonSession(id) }
        // Never commit or bypass installation/authentication in this test.
    }
    @Suppress("DEPRECATION")
    @Test fun packageArchiveSignerMetadataMatchesTheActuallyInstalledApk() {
        val flags=if(Build.VERSION.SDK_INT>=28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed=context.packageManager.getPackageInfo(context.packageName,flags)
        val archive=context.packageManager.getPackageArchiveInfo(installed.applicationInfo!!.sourceDir,flags)!!
        fun signers(p:android.content.pm.PackageInfo):Set<String> {
            val s=if(Build.VERSION.SDK_INT>=28) p.signingInfo!!.apkContentsSigners else p.signatures!!
            return s.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
        }
        assertEquals(context.packageName,archive.packageName)
        assertTrue(AppUpdateRules.sameSigners(signers(installed),signers(archive)))
    }
}
