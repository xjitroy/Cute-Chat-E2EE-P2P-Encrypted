package app.cutiepie.cutie_pie.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Fresh installs only. Existing protection is never overwritten or regenerated. */
class NativeVaultKey(private val context: Context) {
    private val preferences = context.getSharedPreferences("native_vault_protection", 0)
    private val alias = context.packageName + ".native.vault.aes.v1"
    private val aad = (context.packageName + "/vault/v1").toByteArray(Charsets.UTF_8)
    fun present() = preferences.contains("protected")
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    fun read(): String {
        val encoded = preferences.getString("protected", null) ?: error("Protected vault key missing")
        val key = store().getKey(alias, null) as? SecretKey ?: error("Keystore key missing; vault preserved")
        val bytes = WireCodec.decode(encoded, 256)
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(aad)
        val plain = cipher.doFinal(bytes, 12, bytes.size - 12)
        return try { plain.toString(Charsets.UTF_8).also { WireCodec.key(it, 32) } } finally { plain.fill(0) }
    }
    fun create(): String {
        check(!present() && !store().containsAlias(alias)) { "Existing protection preserved; explicit recovery required" }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        val key = generator.generateKey()
        val password = WireCodec.b64(WireCodec.randomBytes(32))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key); cipher.updateAAD(aad)
        check(preferences.edit().putString("protected", WireCodec.b64(cipher.iv + cipher.doFinal(password.toByteArray(Charsets.UTF_8)))).commit())
        return password
    }
}
