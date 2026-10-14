package app.cutiepie.cutie_pie.core

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/** Read-only default 0.2.x secure-storage compatibility. NEVER repairs or deletes keys. */
class LegacyVaultKey(private val context: Context) {
    fun read(): String? {
        val data = context.getSharedPreferences("FlutterSecureStorage", Context.MODE_PRIVATE)
        val raw = data.getString("${PREFIX}_cutie.db-key.v1", null) ?: return null
        val config = context.getSharedPreferences("FlutterSecureStorageConfiguration:FlutterSecureStorage", Context.MODE_PRIVATE)
        val global = context.getSharedPreferences("FlutterSecureStorageConfiguration", Context.MODE_PRIVATE)
        fun algorithm(name: String, fallback: String): String = config.getString(name, null)
            ?: global.getString(name, null) ?: fallback
        check(algorithm("FlutterSecureSAlgorithmKey", KEY_ALGORITHM) == KEY_ALGORITHM) { "Unsupported legacy key protection; vault preserved" }
        check(algorithm("FlutterSecureSAlgorithmStorage", "AES_GCM_NoPadding") == "AES_GCM_NoPadding") { "Unsupported legacy storage; vault preserved" }
        val keyPreferences = context.getSharedPreferences("FlutterSecureKeyStorage", Context.MODE_PRIVATE)
        val wrapped = keyPreferences.getString(WRAPPED_KEY, null)
            ?: error("Legacy wrapped key unavailable; vault preserved")
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val privateKey = keystore.getKey(context.packageName + ".FlutterSecureStoragePluginKeyOAEP", null) as? PrivateKey
            ?: error("Legacy Keystore key unavailable; vault preserved")
        val unwrap = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        unwrap.init(Cipher.UNWRAP_MODE, privateKey,
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT))
        val aes = unwrap.unwrap(Base64.decode(wrapped, Base64.DEFAULT), "AES", Cipher.SECRET_KEY)
        val encrypted = Base64.decode(raw, Base64.DEFAULT)
        check(encrypted.size >= 28) { "Invalid protected vault key; vault preserved" }
        val decrypt = Cipher.getInstance("AES/GCM/NoPadding")
        decrypt.init(Cipher.DECRYPT_MODE, aes, GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
        val plain = decrypt.doFinal(encrypted, 12, encrypted.size - 12)
        try {
            return String(plain, Charsets.UTF_8).also { check(WireCodec.decode(it, 32).size == 32) }
        } finally { plain.fill(0) }
    }
    companion object {
        private const val PREFIX = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIHNlY3VyZSBzdG9yYWdlCg"
        private const val WRAPPED_KEY = "AESVGhpcyBpcyB0aGUga2V5IGZvciBhIHNlY3VyZSBzdG9yYWdlIEFFUyBLZXkK"
        private const val KEY_ALGORITHM = "RSA_ECB_OAEPwithSHA_256andMGF1Padding"
    }
}
