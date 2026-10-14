package app.cutiepie.cutie_pie.core

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import org.json.JSONObject
import org.whispersystems.libsignal.ecc.Curve
import org.whispersystems.libsignal.util.KeyHelper
import java.io.Closeable

class NativeIdentity private constructor(
    public: JSONObject,
    private val authSecret: ByteArray,
    private val boxSecret: ByteArray,
    private val sodium: LazySodiumAndroid,
) : Closeable {
    private val publicEncoded = WireCodec.canonical(public)
    val public: JSONObject get() = JSONObject(publicEncoded)
    val uid: String get() = public.getString("uid")
    private var closed = false
    @Synchronized fun sign(value: String): String {
        check(!closed)
        val message = value.toByteArray(Charsets.UTF_8)
        val signature = ByteArray(64)
        check(sodium.cryptoSignDetached(signature, message, message.size.toLong(), authSecret))
        return WireCodec.b64(signature)
    }
    fun verify(peer: JSONObject, value: String, signature: String): Boolean {
        val message = value.toByteArray(Charsets.UTF_8)
        return sodium.cryptoSignVerifyDetached(WireCodec.key(signature, 64), message, message.size,
            WireCodec.key(peer.getString("authKey"), 32))
    }
    fun seal(peer: JSONObject, message: ByteArray): String {
        require(message.size <= 24_000)
        val encrypted = ByteArray(message.size + 48)
        check(sodium.cryptoBoxSeal(encrypted, message, message.size.toLong(), WireCodec.key(peer.getString("boxKey"), 32)))
        return WireCodec.b64(encrypted)
    }
    @Synchronized fun openBox(encoded: String): ByteArray {
        check(!closed)
        val cipher = WireCodec.decode(encoded, 24_048)
        require(cipher.size >= 48)
        val plaintext = ByteArray(cipher.size - 48)
        check(sodium.cryptoBoxSealOpen(plaintext, cipher, cipher.size.toLong(),
            WireCodec.key(public.getString("boxKey"), 32), boxSecret)) { "Invalid sealed setup" }
        return plaintext
    }
    override fun close() { synchronized(this) { closed = true; authSecret.fill(0); boxSecret.fill(0) } }

    fun contactCode(displayName: String): String {
        val name = displayName.trim()
        require(name.length in 1..80 && name.none { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() })
        val code = JSONObject().put("invitationVersion", 1).put("bundle", public).put("displayName", name)
        code.put("signature", sign("cutie-pie/invitation/v1\n${WireCodec.canonical(code)}"))
        return "CP1." + WireCodec.b64(WireCodec.canonical(code).toByteArray(Charsets.UTF_8))
    }

    fun parseContact(code: String): Pair<JSONObject, String> {
        require(code.startsWith("CP1.") && code.length <= 4096)
        val decoded = WireCodec.parseObject(WireCodec.decode(code.substring(4), 3072).toString(Charsets.UTF_8), 3072)
        var name = ""
        val bundle = if (decoded.has("invitationVersion")) {
            exact(decoded, setOf("invitationVersion", "bundle", "displayName", "signature"))
            require(integer(decoded, "invitationVersion") == 1L)
            name = decoded.getString("displayName")
            require(name.length in 1..80 && name.none { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() })
            val unsigned = JSONObject(decoded.toString()).apply { remove("signature") }
            require(verify(decoded.getJSONObject("bundle"), "cutie-pie/invitation/v1\n${WireCodec.canonical(unsigned)}", decoded.getString("signature")))
            decoded.getJSONObject("bundle")
        } else decoded
        validateBundle(bundle)
        return bundle to name
    }
    fun validateBundle(bundle: JSONObject) {
        exact(bundle, setOf("version", "uid", "epoch", "authKey", "signalKey", "boxKey", "backupKey", "registrationId", "signedPreKeyId", "signedPreKey", "preKeySignature", "signature"))
        require(integer(bundle, "version") == 1L && integer(bundle, "epoch") > 0)
        val auth = WireCodec.decode(bundle.getString("authKey"), 32)
        require(auth.size == 32 && bundle.getString("uid") == WireCodec.digest(auth))
        require(integer(bundle, "registrationId") in 1L..16383L && integer(bundle, "signedPreKeyId") in 0L..Int.MAX_VALUE.toLong())
        for (key in listOf("boxKey", "backupKey")) require(WireCodec.decode(bundle.getString(key), 32).size == 32)
        val signal = WireCodec.decode(bundle.getString("signalKey"), 33)
        val preKey = WireCodec.decode(bundle.getString("signedPreKey"), 33)
        require(signal.size == 33 && preKey.size == 33)
        val unsigned = JSONObject(bundle.toString()).apply { remove("signature") }
        require(verify(bundle, "cutie-pie/bundle/v1\n${WireCodec.canonical(unsigned)}", bundle.getString("signature")))
        require(Curve.verifySignature(Curve.decodePoint(signal, 0), preKey,
            WireCodec.key(bundle.getString("preKeySignature"), 64)))
    }
    companion object {
        fun createRecords(tx: SqlDatabase) {
            check(tx.rows("SELECT key FROM kv WHERE key LIKE 'identity.%'").isEmpty())
            val sodium = LazySodiumAndroid(SodiumAndroid())
            val authPublic = ByteArray(32); val authPrivate = ByteArray(64)
            val boxPublic = ByteArray(32); val boxPrivate = ByteArray(32)
            val backupPublic = ByteArray(32); val backupPrivate = ByteArray(32)
            try {
                check(sodium.cryptoSignKeypair(authPublic, authPrivate))
                check(sodium.cryptoBoxKeypair(boxPublic, boxPrivate))
                check(sodium.cryptoBoxKeypair(backupPublic, backupPrivate))
                val signal = KeyHelper.generateIdentityKeyPair()
                val registration = KeyHelper.generateRegistrationId(false)
                val signed = KeyHelper.generateSignedPreKey(signal, 1)
                val bundle = JSONObject().put("version", 1).put("uid", WireCodec.digest(authPublic))
                    .put("epoch", System.currentTimeMillis()).put("authKey", WireCodec.b64(authPublic))
                    .put("signalKey", WireCodec.b64(signal.publicKey.serialize())).put("boxKey", WireCodec.b64(boxPublic))
                    .put("backupKey", WireCodec.b64(backupPublic)).put("registrationId", registration)
                    .put("signedPreKeyId", signed.id).put("signedPreKey", WireCodec.b64(signed.keyPair.publicKey.serialize()))
                    .put("preKeySignature", WireCodec.b64(signed.signature))
                val instance = NativeIdentity(bundle, authPrivate, boxPrivate, sodium)
                bundle.put("signature", instance.sign("cutie-pie/bundle/v1\n${WireCodec.canonical(bundle)}"))
                instance.validateBundle(bundle)
                val secret = JSONObject().put("auth", WireCodec.b64(authPrivate)).put("box", WireCodec.b64(boxPrivate))
                    .put("backup", WireCodec.b64(backupPrivate)).put("friendBackupKey", WireCodec.b64(WireCodec.randomBytes(32)))
                tx.execute("INSERT INTO kv(key,value) VALUES('identity.private',?)", WireCodec.canonical(secret))
                tx.execute("INSERT INTO kv(key,value) VALUES('identity.public',?)", "CP1." + WireCodec.b64(WireCodec.canonical(bundle).toByteArray(Charsets.UTF_8)))
                tx.execute("INSERT INTO kv(key,value) VALUES('signal.registration',?)", registration.toString())
                tx.execute("INSERT INTO signal_records(kind,id,bytes) VALUES('localIdentity','1',?)", signal.serialize())
                tx.execute("INSERT INTO signal_records(kind,id,bytes) VALUES('signedPrekey',?,?)", signed.id.toString(), signed.serialize())
            } finally { authPrivate.fill(0); boxPrivate.fill(0); backupPrivate.fill(0) }
        }
        private fun integer(value: JSONObject, key: String): Long {
            val number = value.get(key)
            require(number is Int || number is Long) { "Integer protocol field required" }
            return (number as Number).toLong()
        }
        fun exact(value: JSONObject, keys: Set<String>) { require(value.keys().asSequence().toSet() == keys) { "Unexpected protocol fields" } }
        fun loadExisting(vault: NativeVault): NativeIdentity {
            val sodium = LazySodiumAndroid(SodiumAndroid())
            val secrets = JSONObject(checkNotNull(vault.get("identity.private")))
            val code = checkNotNull(vault.get("identity.public"))
            require(code.startsWith("CP1.") && code.length <= 4096)
            val bundle = JSONObject(WireCodec.decode(code.substring(4), 3072).toString(Charsets.UTF_8))
            val auth = WireCodec.decode(secrets.getString("auth"), 64)
            val box = WireCodec.decode(secrets.getString("box"), 32)
            try {
                require(auth.size == 64 && box.size == 32)
                val instance = NativeIdentity(bundle, auth, box, sodium)
                instance.validateBundle(bundle)
                val derivedAuth = ByteArray(32)
                check(sodium.cryptoSignEd25519SkToPk(derivedAuth, auth))
                check(derivedAuth.contentEquals(WireCodec.decode(bundle.getString("authKey"), 32)))
                // Public/private box mismatch is checked by an authenticated self seal/open.
                val challenge = WireCodec.randomBytes(32)
                check(instance.openBox(instance.seal(bundle, challenge)).contentEquals(challenge))
                return instance
            } catch (failure: Throwable) { auth.fill(0); box.fill(0); throw failure }
        }
    }
}
