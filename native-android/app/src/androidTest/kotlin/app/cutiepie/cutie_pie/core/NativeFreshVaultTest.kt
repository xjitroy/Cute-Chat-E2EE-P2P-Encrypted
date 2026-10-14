package app.cutiepie.cutie_pie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/** Disposable test-package identity only. Never clears the target/user application. */
@RunWith(AndroidJUnit4::class)
class NativeFreshVaultTest {
    private val context = NativeTestContext("fresh")
    private val file get() = File(context.filesDir, "cutie.db")
    @Before @After fun cleanup() {
        check(context.packageName.endsWith(".test"))
        for (suffix in listOf("", "-wal", "-shm", "-journal")) File(file.path + suffix).delete()
        context.getSharedPreferences("native_vault_protection", 0).edit().clear().commit()
        val alias = context.packageName + ".native.vault.aes.v1"
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); if (containsAlias(alias)) deleteEntry(alias) }
    }
    @Test fun freshIdentitySurvivesCloseAndNameSignatureVerifies() {
        var uid = ""
        NativeVault.createFresh(context).use { vault ->
            NativeIdentity.loadExisting(vault).use { identity ->
                uid = identity.uid
                val named = identity.contactCode("বন্ধু ♥")
                val decoded = identity.parseContact(named)
                assertEquals(uid, decoded.first.getString("uid")); assertEquals("বন্ধু ♥", decoded.second)
                val raw = WireCodec.parseObject(WireCodec.decode(named.substring(4), 3072).toString(Charsets.UTF_8), 3072)
                raw.put("displayName", "changed")
                assertThrows(IllegalArgumentException::class.java) { identity.parseContact("CP1." + WireCodec.b64(WireCodec.canonical(raw).toByteArray(Charsets.UTF_8))) }
            }
        }
        NativeVault.openExisting(context).use { vault ->
            NativeIdentity.loadExisting(vault).use { assertEquals(uid, it.uid) }
            vault.transaction { tx -> assertEquals(1, tx.rows("SELECT bytes FROM signal_records WHERE kind='localIdentity'").size) }
        }
        assertFalse(file.readBytes().take(16).toByteArray().contentEquals("SQLite format 3\u0000".toByteArray()))
        assertThrows(IllegalStateException::class.java) { NativeVault.createFresh(context) }
        NativeVault.openExisting(context).use { vault -> NativeIdentity.loadExisting(vault).use { assertEquals(uid, it.uid) } }
    }
    @Test fun missingKeystoreKeyDoesNotReplaceDatabase() {
        NativeVault.createFresh(context).close()
        val before = file.readBytes()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(context.packageName + ".native.vault.aes.v1") }
        assertThrows(IllegalStateException::class.java) { NativeVault.openExisting(context) }
        assertArrayEquals(before, file.readBytes())
    }
}
