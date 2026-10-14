package app.cutiepie.cutie_pie

import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.cutiepie.cutie_pie.core.AppUpdateRules
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
