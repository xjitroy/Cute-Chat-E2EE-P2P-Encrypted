package app.cutiepie.cutie_pie.core

import org.json.JSONObject
import org.whispersystems.libsignal.IdentityKey
import org.whispersystems.libsignal.IdentityKeyPair
import org.whispersystems.libsignal.InvalidKeyIdException
import org.whispersystems.libsignal.SignalProtocolAddress
import org.whispersystems.libsignal.state.*

/** No in-memory ratchet. Valid only on the current SQL transaction's thread. */
class TransactionalSignalStore(private val db: SqlDatabase) : SignalProtocolStore {
    private val owner = Thread.currentThread()
    init { check(db.inTransaction()) { "Signal state requires an atomic SQL transaction" } }
    private fun guard() { check(Thread.currentThread() === owner && db.inTransaction()) }
    private fun address(a: SignalProtocolAddress): String = "${a.name}:${a.deviceId}"
    private fun read(kind: String, id: String): ByteArray? {
        guard()
        return db.rows("SELECT bytes FROM signal_records WHERE kind=? AND id=?", kind, id).singleOrNull()?.get("bytes") as ByteArray?
    }
    private fun write(kind: String, id: String, bytes: ByteArray) {
        guard()
        db.execute("INSERT OR REPLACE INTO signal_records(kind,id,bytes) VALUES(?,?,?)", kind, id, bytes)
    }
    private fun remove(kind: String, id: String) {
        guard(); db.execute("DELETE FROM signal_records WHERE kind=? AND id=?", kind, id)
    }
    private fun pinned(a: SignalProtocolAddress): IdentityKey? {
        guard()
        val row = db.rows("SELECT bundle FROM contacts WHERE uid=? AND blocked=0", a.name).singleOrNull() ?: return null
        return IdentityKey(WireCodec.key(JSONObject(row["bundle"] as String).getString("signalKey"), 33), 0)
    }
    override fun getIdentityKeyPair(): IdentityKeyPair = IdentityKeyPair(checkNotNull(read("localIdentity", "1")))
    override fun getLocalRegistrationId(): Int {
        guard()
        return (db.rows("SELECT value FROM kv WHERE key='signal.registration'").single()["value"] as String).toInt()
    }
    override fun isTrustedIdentity(a: SignalProtocolAddress, key: IdentityKey?, direction: IdentityKeyStore.Direction): Boolean =
        key != null && pinned(a)?.serialize()?.contentEquals(key.serialize()) == true
    override fun saveIdentity(a: SignalProtocolAddress, key: IdentityKey): Boolean {
        check(isTrustedIdentity(a, key, IdentityKeyStore.Direction.RECEIVING)) { "Unaccepted or changed identity" }
        write("trustedIdentity", address(a), key.serialize())
        return false
    }
    override fun getIdentity(a: SignalProtocolAddress): IdentityKey? = pinned(a)
    override fun loadPreKey(id: Int): PreKeyRecord = PreKeyRecord(read("prekey", "$id") ?: throw InvalidKeyIdException("Unknown prekey"))
    override fun storePreKey(id: Int, record: PreKeyRecord) = write("prekey", "$id", record.serialize())
    override fun containsPreKey(id: Int): Boolean = read("prekey", "$id") != null
    override fun removePreKey(id: Int) = remove("prekey", "$id")
    override fun loadSignedPreKey(id: Int): SignedPreKeyRecord = SignedPreKeyRecord(read("signedPrekey", "$id") ?: throw InvalidKeyIdException("Unknown signed prekey"))
    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> {
        guard()
        return db.rows("SELECT bytes FROM signal_records WHERE kind='signedPrekey'").map { SignedPreKeyRecord(it["bytes"] as ByteArray) }
    }
    override fun storeSignedPreKey(id: Int, record: SignedPreKeyRecord) = write("signedPrekey", "$id", record.serialize())
    override fun containsSignedPreKey(id: Int): Boolean = read("signedPrekey", "$id") != null
    override fun removeSignedPreKey(id: Int) = remove("signedPrekey", "$id")
    override fun loadSession(a: SignalProtocolAddress): SessionRecord = read("session", address(a))?.let(::SessionRecord) ?: SessionRecord()
    override fun storeSession(a: SignalProtocolAddress, record: SessionRecord) = write("session", address(a), record.serialize())
    override fun containsSession(a: SignalProtocolAddress): Boolean = read("session", address(a)) != null
    override fun deleteSession(a: SignalProtocolAddress) = remove("session", address(a))
    override fun getSubDeviceSessions(name: String): List<Int> {
        guard()
        return db.rows("SELECT id FROM signal_records WHERE kind='session' AND substr(id,1,?)=?", "${name.length + 1}", "$name:")
            .map { (it["id"] as String).substringAfterLast(':').toInt() }.filter { it != 1 }
    }
    override fun deleteAllSessions(name: String) {
        guard()
        db.execute("DELETE FROM signal_records WHERE kind='session' AND substr(id,1,?)=?", name.length + 1, "$name:")
    }
}
