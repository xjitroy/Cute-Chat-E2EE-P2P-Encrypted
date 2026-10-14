package app.cutiepie.cutie_pie.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.whispersystems.libsignal.*
import org.whispersystems.libsignal.protocol.PreKeySignalMessage
import org.whispersystems.libsignal.state.*
import org.whispersystems.libsignal.state.impl.InMemorySignalProtocolStore
import java.io.File

/** Memory store is ONLY for synthetic interoperability tests, never app state. */
class LegacySignalCompatibilityTest {
    @Test fun nativeDecryptsDartAndDartCanResumePersistedRatchet() {
        val input = checkNotNull(javaClass.getResourceAsStream("/migration/dart-v2.json"))
        val fixture = input.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        assertTrue(fixture.getBoolean("synthetic"))
        val records = fixture.getJSONArray("bobRecords")
        fun record(kind: String): ByteArray = (0 until records.length()).map(records::getJSONObject)
            .first { it.getString("kind") == kind }.getString("bytes").let(WireCodec::decode)
        val store = InMemorySignalProtocolStore(IdentityKeyPair(record("localIdentity")), fixture.getString("bobRegistration").toInt())
        val address = SignalProtocolAddress(fixture.getString("aliceUid"), 2)
        val pinned = IdentityKey(WireCodec.decode(fixture.getJSONObject("aliceBundle").getString("signalKey")), 0)
        store.saveIdentity(address, pinned)
        val signed = SignedPreKeyRecord(record("signedPrekey"))
        store.storeSignedPreKey(signed.id, signed)
        val frame = fixture.getJSONObject("frame")
        val cipher = SessionCipher(store, address)
        val plain = cipher.decrypt(PreKeySignalMessage(WireCodec.decode(frame.getString("cipher"))))
        assertEquals("Migration বাংলা ❤️", JSONObject(plain.toString(Charsets.UTF_8)).getJSONObject("data").getString("text"))

        // Deserialize both ports' protobuf records instead of resetting the session.
        val restored = SessionRecord(store.loadSession(address).serialize())
        store.storeSession(address, restored)
        val reply = cipher.encrypt("Native Kotlin reply বাংলা ❤️".toByteArray(Charsets.UTF_8))
        assertEquals(2, reply.type)
        val response = JSONObject().put("aliceUid", fixture.getString("aliceUid"))
            .put("cipher", WireCodec.b64(reply.serialize()))
        File("build/migration/native-response.json").apply { checkNotNull(parentFile).mkdirs(); writeText(response.toString()) }
    }
}
