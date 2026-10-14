package app.cutiepie.cutie_pie.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/** Synthetic installed-format fixture in the instrumentation test package only. */
@RunWith(AndroidJUnit4::class)
class LegacyVaultMigrationTest {
    private val context: Context = NativeTestContext("legacy")
    private val file get() = File(context.filesDir, "cutie.db")
    private val prefix = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIHNlY3VyZSBzdG9yYWdlCg_cutie.db-key.v1"
    private val wrappedName = "AESVGhpcyBpcyB0aGUga2V5IGZvciBhIHNlY3VyZSBzdG9yYWdlIEFFUyBLZXkK"
    private val alias get() = context.packageName + ".FlutterSecureStoragePluginKeyOAEP"
    @Before fun createSyntheticLegacyVault() {
        check(context.packageName.endsWith(".test")) { "Must never run against real user data" }
        cleanup()
        System.loadLibrary("sqlcipher")
        val generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore")
        generator.initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_ENCRYPT)
            .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP).build())
        val keyPair = generator.generateKeyPair()
        val aes = SecretKeySpec(WireCodec.randomBytes(16), "AES")
        val wrap = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        wrap.init(Cipher.WRAP_MODE, keyPair.public, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT))
        context.getSharedPreferences("FlutterSecureKeyStorage", 0).edit().putString(wrappedName,
            Base64.encodeToString(wrap.wrap(aes), Base64.DEFAULT)).commit()
        val password = WireCodec.b64(WireCodec.randomBytes(32))
        val encrypt = Cipher.getInstance("AES/GCM/NoPadding")
        encrypt.init(Cipher.ENCRYPT_MODE, aes)
        val protectedPassword = encrypt.iv + encrypt.doFinal(password.toByteArray(Charsets.UTF_8))
        context.getSharedPreferences("FlutterSecureStorage", 0).edit().putString(prefix,
            Base64.encodeToString(protectedPassword, Base64.DEFAULT)).commit()
        val preserve = DatabaseErrorHandler { _, _ -> error("Synthetic DB corruption") }
        SQLiteDatabase.openOrCreateDatabase(file, password, null, preserve).use { db ->
            db.execSQL("CREATE TABLE kv(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
            db.execSQL("CREATE TABLE signal_records(kind TEXT,id TEXT,bytes BLOB,PRIMARY KEY(kind,id))")
            for (key in listOf("identity.private", "identity.public", "signal.registration")) {
                db.execSQL("INSERT INTO kv VALUES(?,?)", arrayOf(key, "synthetic-fixture"))
            }
            db.execSQL("INSERT INTO signal_records VALUES('session','synthetic:2',?)", arrayOf(byteArrayOf(1, 2, 3)))
            db.version = 3
        }
    }
    @After fun cleanup() {
        check(context.packageName.endsWith(".test"))
        for (suffix in listOf("", "-wal", "-shm", "-journal")) File(file.path + suffix).delete()
        for (name in listOf("FlutterSecureStorage", "FlutterSecureKeyStorage")) context.getSharedPreferences(name, 0).edit().clear().commit()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); if (containsAlias(alias)) deleteEntry(alias) }
    }
    @Test fun nativeReadsLegacyKeyWithoutRewrappingAndPreservesSession() {
        val before = context.getSharedPreferences("FlutterSecureStorage", 0).all
        NativeVault.openExisting(context).use { vault ->
            assertEquals("synthetic-fixture", vault.get("identity.public"))
            vault.db.rawQuery("SELECT bytes FROM signal_records WHERE kind='session'", emptyArray<String>()).use {
                assertTrue(it.moveToFirst()); assertArrayEquals(byteArrayOf(1, 2, 3), it.getBlob(0))
            }
        }
        assertEquals(before, context.getSharedPreferences("FlutterSecureStorage", 0).all)
    }
    @Test fun missingKeyFailsWithoutDeletingOrReplacingEncryptedDatabase() {
        val before = file.readBytes()
        context.getSharedPreferences("FlutterSecureStorage", 0).edit().remove(prefix).commit()
        assertThrows(IllegalStateException::class.java) { NativeVault.openExisting(context) }
        assertArrayEquals(before, file.readBytes())
        assertFalse(context.getSharedPreferences("FlutterSecureStorage", 0).contains(prefix))
    }
}
