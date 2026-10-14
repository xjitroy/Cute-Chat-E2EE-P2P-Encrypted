package app.cutiepie.cutie_pie.core

import org.json.JSONObject
import org.whispersystems.libsignal.*
import org.whispersystems.libsignal.ecc.Curve
import org.whispersystems.libsignal.protocol.PreKeySignalMessage
import org.whispersystems.libsignal.protocol.SignalMessage
import org.whispersystems.libsignal.state.PreKeyBundle
import java.io.ByteArrayOutputStream

/** Called only by one native owner. Ratchet, history and ciphertext commit together. */
class NativeChatEngine(private val vault: TransactionalVault, publicBundle: JSONObject) {
    private val local = JSONObject(WireCodec.canonical(publicBundle))
    private val self = local.getString("uid")
    data class Received(val peer: String, val kind: String, val data: JSONObject, val id: String, val deliveredId: String?, val progress:Boolean=false) {
        val content: Boolean get() = kind in setOf("text","link") || kind == "transfer_finish" && deliveredId != null
    }
    private fun contact(tx: SqlDatabase, peer: String): JSONObject {
        val row = tx.rows("SELECT bundle FROM contacts WHERE uid=? AND blocked=0", peer).singleOrNull()
            ?: error("Accept this contact first")
        return JSONObject(row["bundle"] as String)
    }
    fun enqueue(peer: String, kind: String, data: JSONObject, visibleMessage: Boolean = false): String {
        ProtocolRules.validate(kind, data, self, peer)
        require(!visibleMessage || kind in setOf("text", "link"))
        val id = WireCodec.id()
        vault.transaction { tx ->
            encrypt(tx, peer, id, kind, data, if (visibleMessage) id else null)
            if (visibleMessage) tx.execute("INSERT INTO messages(id,peer,outgoing,kind,data,created_at,status) VALUES(?,?,1,?,?,?,0)",
                id, peer, kind, WireCodec.canonical(data), System.currentTimeMillis())
        }
        return id
    }
    private fun encrypt(tx: SqlDatabase, peer: String, id: String, kind: String, data: JSONObject, messageId: String?) {
        ProtocolRules.validate(kind, data, self, peer)
        val bundle = contact(tx, peer)
        val limit = when (kind) { "ack" -> 384; "hello" -> 320; else -> 256 }
        check(tx.count("SELECT COUNT(*) FROM outbox WHERE peer=? AND protocol=2", peer) < limit) { "Pending queue is full" }
        val store = TransactionalSignalStore(tx)
        val address = SignalProtocolAddress(peer, 2)
        if (!store.containsSession(address)) {
            if (self > peer) {
                check(tx.count("SELECT COUNT(*) FROM kv WHERE key LIKE ?", "deferred.$peer.%") < limit)
                val intent = JSONObject().put("id", id).put("peer", peer).put("kind", kind).put("data", data)
                    .put("messageId", messageId ?: JSONObject.NULL)
                val encoded = WireCodec.canonical(intent)
                require(encoded.toByteArray(Charsets.UTF_8).size <= 24000)
                tx.execute("INSERT INTO kv(key,value) VALUES(?,?)", "deferred.$peer.$id", encoded)
                return
            }
            val preKey = PreKeyBundle(bundle.getInt("registrationId"), 2, 0, null,
                bundle.getInt("signedPreKeyId"), Curve.decodePoint(WireCodec.key(bundle.getString("signedPreKey"), 33), 0),
                WireCodec.key(bundle.getString("preKeySignature"), 64), IdentityKey(WireCodec.key(bundle.getString("signalKey"), 33), 0))
            SessionBuilder(store, address).process(preKey)
        }
        val plain = JSONObject().put("v", 2).put("id", id).put("sender", self).put("recipient", peer)
            .put("epoch", local.getLong("epoch")).put("peerEpoch", bundle.getLong("epoch"))
            .put("kind", kind).put("data", data).put("createdAt", System.currentTimeMillis())
        val encoded = WireCodec.canonical(plain).toByteArray(Charsets.UTF_8)
        require(encoded.size <= 24000)
        val ciphertext = SessionCipher(store, address).encrypt(encoded)
        val frame = JSONObject().put("v", 2).put("id", id).put("peer", peer).put("type", ciphertext.type)
            .put("cipher", WireCodec.b64(ciphertext.serialize()))
        tx.execute("INSERT INTO outbox(id,peer,frame,message_id,needs_ack,created_at,protocol,priority) VALUES(?,?,?,?,?,?,2,?)",
            id, peer, WireCodec.canonical(frame), messageId, if (kind == "ack") 0 else 1,
            System.currentTimeMillis(), if (kind == "hello") -1 else if (kind == "ack") 0 else if (kind.startsWith("transfer_")) 2 else 1)
    }
    fun receive(peer: String, raw: String, live:Boolean=false, medium:String="internet"): Received {
        require(raw.toByteArray(Charsets.UTF_8).size <= 40000)
        val frame = WireCodec.parseObject(raw, 40000)
        NativeIdentity.exact(frame, setOf("v", "id", "peer", "type", "cipher"))
        require(ProtocolRules.integer(frame.get("v")) == 2L && frame.getString("peer") == self)
        val id = ProtocolRules.id(frame.get("id"))
        val frameHash = WireCodec.digest(WireCodec.canonical(frame).toByteArray(Charsets.UTF_8))
        val event = vault.transaction { tx ->
            val bundle = contact(tx, peer)
            val previous = tx.rows("SELECT peer,hash,delivered_id FROM inbox WHERE id=?", id).singleOrNull()
            if (!live && previous != null) {
                require(previous["peer"] == peer && previous["hash"] == frameHash) { "Replay changed content" }
                return@transaction Received(peer, "duplicate", JSONObject().put("ack", previous["delivered_id"] != "no-ack"), id, previous["delivered_id"] as String?)
            }
            val store = TransactionalSignalStore(tx)
            val cipher = SessionCipher(store, SignalProtocolAddress(peer, 2))
            val encrypted = WireCodec.decode(frame.getString("cipher"), 30000)
            val plain = when (ProtocolRules.integer(frame.get("type"))) {
                3L -> {
                    val message = PreKeySignalMessage(encrypted)
                    require(store.containsSignedPreKey(message.signedPreKeyId))
                    if (message.preKeyId.isPresent) require(store.containsPreKey(message.preKeyId.get()))
                    cipher.decrypt(message)
                }
                2L -> cipher.decrypt(SignalMessage(encrypted))
                else -> error("Unsupported encrypted frame")
            }
            val message = try { WireCodec.parseObject(plain.toString(Charsets.UTF_8), 24000) } finally { plain.fill(0) }
            NativeIdentity.exact(message, setOf("v", "id", "sender", "recipient", "epoch", "peerEpoch", "kind", "data", "createdAt"))
            require(ProtocolRules.integer(message.get("v")) == 2L && message.getString("id") == id && message.getString("sender") == peer && message.getString("recipient") == self)
            require(ProtocolRules.integer(message.get("epoch")) == bundle.getLong("epoch") && ProtocolRules.integer(message.get("peerEpoch")) == local.getLong("epoch"))
            val created = ProtocolRules.integer(message.get("createdAt"))
            require(created in 0..(System.currentTimeMillis() + 86400000))
            val kind = message.getString("kind"); val data = message.getJSONObject("data")
            ProtocolRules.validate(kind, data, self, peer)
            if(live) {
                require(medium=="bluetooth" && kind=="local_audio")
                return@transaction Received(peer,kind,data,id,null)
            }
            require(kind!="local_audio")
            if(kind=="transfer_start" && medium=="bluetooth") {
                LocalSharingRules.attachment(medium,data.getString("mime"),data.getInt("size"))
                if(data.getString("purpose")!="photo") check(tx.count("SELECT COUNT(*) FROM transfers WHERE peer=? AND completed=0 AND purpose!='photo'",peer)==0L)
            }
            val target = if(kind in setOf("text","link")) id else if(kind.startsWith("transfer_")) data.getString("id") else null
            val discarded = target != null && jsonKv(tx,"deleted.$peer.$target") != null
            val progress=!discarded && kind=="transfer_chunk" && tx.rows("SELECT purpose FROM transfers WHERE id=? AND peer=?",data.getString("id"),peer).singleOrNull()?.get("purpose")!="photo"
            val deliveredId = if(discarded) null else applyReceived(tx, peer, id, kind, data, created)
            tx.execute("INSERT INTO inbox(id,peer,hash,delivered_id) VALUES(?,?,?,?)", id, peer, frameHash, if (kind == "ack") "no-ack" else deliveredId)
            Received(peer, if(discarded) "discarded" else kind, data, id, deliveredId,progress)
        }
        if(live) return event
        activatePending(peer)
        // Receiver commit above happens BEFORE constructing the encrypted receipt.
        if (event.kind != "ack" && !(event.kind == "duplicate" && !event.data.getBoolean("ack"))) {
            vault.transaction { tx ->
                val ackKey = "ack.pending.$peer.$id"
                if (tx.rows("SELECT value FROM kv WHERE key=?", ackKey).isEmpty()) {
                    val ackId = WireCodec.id()
                    encrypt(tx, peer, ackId, "ack", JSONObject().put("ackId", id).put("deliveredId", event.deliveredId ?: JSONObject.NULL), null)
                    tx.execute("INSERT INTO kv(key,value) VALUES(?,?)", ackKey, ackId)
                }
            }
        }
        return event
    }
    /** Live speech uses the SAME standard Signal session and transaction, without history/outbox/ACK accumulation. */
    fun encryptLive(peer:String,data:JSONObject):String {
        ProtocolRules.validate("local_audio",data,self,peer)
        return vault.transaction { tx ->
            val bundle=contact(tx,peer);val store=TransactionalSignalStore(tx);val address=SignalProtocolAddress(peer,2)
            check(store.containsSession(address)) { "Authenticate this peer before live audio" }
            val id=WireCodec.id()
            val plain=JSONObject().put("v",2).put("id",id).put("sender",self).put("recipient",peer)
                .put("epoch",local.getLong("epoch")).put("peerEpoch",bundle.getLong("epoch"))
                .put("kind","local_audio").put("data",data).put("createdAt",System.currentTimeMillis())
            val bytes=WireCodec.canonical(plain).toByteArray(Charsets.UTF_8)
            try { val encrypted=SessionCipher(store,address).encrypt(bytes)
                WireCodec.canonical(JSONObject().put("v",2).put("id",id).put("peer",peer).put("type",encrypted.type).put("cipher",WireCodec.b64(encrypted.serialize())))
            } finally { bytes.fill(0) }
        }
    }
    fun pendingPhoto(peer:String)=vault.transaction { tx -> tx.count("SELECT COUNT(*) FROM messages WHERE peer=? AND outgoing=1 AND kind='attachment' AND status<2 AND data NOT LIKE '%\"large\":true%'",peer)>0 }
    fun nonPhotoPending(peer:String)=vault.transaction { tx -> tx.count("SELECT COUNT(*) FROM kv k JOIN transfers t ON k.key='transfer.pending.' || t.id WHERE t.peer=? AND (t.mime NOT LIKE 'image/%' OR t.size>?)",peer,LocalSharingRules.BLUETOOTH_PHOTO_LIMIT.toString())>0 }
    private fun applyReceived(tx: SqlDatabase, peer: String, id: String, kind: String, data: JSONObject, created: Long): String? {
        when (kind) {
            "consent_request" -> {
                NativeExtensions.parse(data)?.let { extension ->
                    when(extension.getString("type")) {
                        "features" -> setKv(tx,"features.$peer",extension)
                        "profile" -> {
                            setKv(tx,"profile.$peer",extension)
                            if(extension.isNull("photoHash")) tx.execute("UPDATE contacts SET photo=NULL,photo_hash=NULL WHERE uid=?",peer)
                        }
                        "delete" -> {
                            val target = extension.getString("id")
                            val row = tx.rows("SELECT outgoing FROM messages WHERE id=? AND peer=?",target,peer).singleOrNull()
                            val transfer = tx.rows("SELECT purpose FROM transfers WHERE id=? AND peer=?",target,peer).singleOrNull()
                            if(row?.get("outgoing") != 1L && transfer?.get("purpose") != "photo") erase(tx,peer,target,false)
                        }
                    }
                }
            }
            "media_state" -> {
                val row = tx.rows("SELECT data FROM messages WHERE id=? AND peer=? AND outgoing=1 AND kind='attachment'", data.getString("id"), peer).singleOrNull()
                if (row != null) {
                    val meta = JSONObject(row["data"] as String)
                    check(meta.optInt("viewLimit") in 1..2 && data.getInt("used") <= meta.getInt("viewLimit"))
                    meta.put("peerViewsUsed", maxOf(meta.optInt("peerViewsUsed"), data.getInt("used")))
                    meta.put("peerExpired", meta.optBoolean("peerExpired") || data.getBoolean("expired"))
                    tx.execute("UPDATE messages SET data=? WHERE id=? AND peer=?", WireCodec.canonical(meta), data.getString("id"), peer)
                }
            }
            "text", "link" -> {
                tx.execute("INSERT INTO messages(id,peer,outgoing,kind,data,created_at,status) VALUES(?,?,0,?,?,?,2)", id, peer, kind, WireCodec.canonical(data), created)
                return id
            }
            "ack" -> {
                val ackId = data.getString("ackId")
                val row = tx.rows("SELECT message_id FROM outbox WHERE id=? AND peer=?", ackId, peer).singleOrNull()
                if (row != null) {
                    if (!data.isNull("deliveredId")) {
                        val delivered = data.getString("deliveredId"); require(row["message_id"] == delivered)
                        tx.execute("UPDATE messages SET status=MAX(status,2) WHERE id=? AND peer=? AND outgoing=1", delivered, peer)
                    }
                    tx.execute("DELETE FROM outbox WHERE id=? AND peer=?", ackId, peer)
                }
            }
            "read" -> {
                val ids = data.getJSONArray("ids")
                for (i in 0 until ids.length()) tx.execute("UPDATE messages SET status=MAX(status,3) WHERE id=? AND peer=? AND outgoing=1", ids.getString(i), peer)
            }
            "reaction" -> {
                if (tx.rows("SELECT id FROM messages WHERE id=? AND peer=? AND historical=0 AND kind!='deleted'", data.getString("messageId"), peer).isNotEmpty()) {
                    val key = "reaction.$peer.${data.getString("messageId")}"
                    val values = jsonKv(tx, key) ?: JSONObject()
                    val previous = values.optJSONObject(peer)
                    if (previous == null || data.getLong("revision") > previous.getLong("revision")) {
                        values.put(peer, JSONObject().put("emoji", data.getString("emoji")).put("revision", data.getLong("revision")))
                        setKv(tx, key, values)
                    }
                }
            }
            "presence" -> {
                val seen = if (data.getBoolean("sharing") && !data.isNull("lastSeen")) data.getLong("lastSeen") else null
                tx.execute("UPDATE contacts SET last_seen=? WHERE uid=?", seen, peer)
            }
            "ping_label" -> {
                val key = "ping.label.$peer"; val old = jsonKv(tx, key)
                if (old == null || data.getLong("revision") > old.getLong("revision") ||
                    (data.getLong("revision") == old.getLong("revision") && data.getString("author") > old.getString("author"))) setKv(tx, key, data)
            }
            "transfer_start" -> {
                require(tx.rows("SELECT id FROM transfers WHERE id=?", data.getString("id")).isEmpty())
                require(tx.rows("SELECT id FROM messages WHERE id=?", data.getString("id")).isEmpty()) { "Attachment IDs cannot be reused after expiry" }
                check(tx.count("SELECT COUNT(*) FROM transfers WHERE peer=? AND completed=0", peer) < 8) { "Too many pending transfers" }
                check(tx.count("SELECT COALESCE(SUM(size),0) FROM transfers WHERE completed=0") + data.getLong("size") <= 256L * 1024 * 1024) { "Pending transfer limit reached" }
                tx.execute("INSERT INTO transfers(id,peer,name,mime,size,hash,purpose) VALUES(?,?,?,?,?,?,?)", data.getString("id"), peer,
                    data.getString("name"), data.getString("mime"), data.getLong("size"), data.getString("hash"), data.getString("purpose"))
            }
            "transfer_chunk" -> {
                val transfer = transfer(tx, peer, data.getString("id"))
                require(transfer["completed"] == 0L)
                val size = transfer["size"] as Long; val index = data.getLong("index")
                require(index < (size + ProtocolRules.CHUNK_SIZE - 1) / ProtocolRules.CHUNK_SIZE)
                val chunk = WireCodec.decode(data.getString("bytes"), ProtocolRules.CHUNK_SIZE)
                require(chunk.size.toLong() == minOf(ProtocolRules.CHUNK_SIZE.toLong(), size - index * ProtocolRules.CHUNK_SIZE))
                val old = tx.rows("SELECT bytes FROM chunks WHERE transfer_id=? AND chunk_index=?", data.getString("id"), index.toString()).singleOrNull()
                require(old == null || (old["bytes"] as ByteArray).contentEquals(chunk)) { "Chunk changed" }
                tx.execute("INSERT OR IGNORE INTO chunks(transfer_id,chunk_index,bytes) VALUES(?,?,?)", data.getString("id"), index, chunk)
            }
            "transfer_finish" -> {
                val transferId = data.getString("id"); val transfer = transfer(tx, peer, transferId)
                val content = collect(tx, transferId, transfer["size"] as Long, transfer["hash"] as String)
                tx.execute("UPDATE transfers SET completed=1 WHERE id=? AND peer=?", transferId, peer)
                if (transfer["purpose"] == "photo") {
                    val expected = jsonKv(tx,"profile.$peer")
                    if(expected == null || expected.optString("photoHash") == transfer["hash"]) tx.execute("UPDATE contacts SET photo=?,photo_hash=? WHERE uid=?", content, transfer["hash"], peer)
                    tx.execute("DELETE FROM chunks WHERE transfer_id=?",transferId)
                    tx.execute("DELETE FROM transfers WHERE id=? AND peer=?",transferId,peer)
                    content.fill(0)
                }
                else {
                    val meta = JSONObject().put("id", transferId).put("name", transfer["name"]).put("mime", transfer["mime"]).put("size", transfer["size"])
                    val limit = ProtocolRules.viewLimit(transfer["purpose"] as String)
                    if (limit > 0) meta.put("viewLimit", limit).put("viewsUsed", 0)
                    tx.execute("INSERT OR IGNORE INTO messages(id,peer,outgoing,kind,data,created_at,status) VALUES(?,?,0,'attachment',?,?,2)", transferId, peer, WireCodec.canonical(meta), System.currentTimeMillis())
                    return transferId
                }
            }
        }
        return null
    }
    fun react(peer: String, messageId: String, emoji: String) {
        ProtocolRules.id(messageId); require(emoji.isEmpty() || emoji in ProtocolRules.emojis)
        vault.transaction { tx ->
            val target = tx.rows("SELECT outgoing,status FROM messages WHERE id=? AND peer=? AND historical=0 AND kind!='deleted'", messageId, peer).singleOrNull()
            check(target != null && (target["outgoing"] == 0L || (target["status"] as Long) >= 2)) { "Wait for delivery" }
            val key = "reaction.$peer.$messageId"; val values = jsonKv(tx, key) ?: JSONObject()
            val revision = (values.optJSONObject(self)?.getLong("revision") ?: 0) + 1
            val data = JSONObject().put("messageId", messageId).put("emoji", emoji).put("revision", revision)
            encrypt(tx, peer, WireCodec.id(), "reaction", data, null)
            values.put(self, JSONObject().put("emoji", emoji).put("revision", revision)); setKv(tx, key, values)
        }
    }
    fun supports(peer: String, feature: String): Boolean = vault.transaction { tx ->
        val features = jsonKv(tx,"features.$peer")?.optJSONArray("features") ?: return@transaction false
        (0 until features.length()).any { features.optString(it) == feature }
    }
    fun deleteForEveryone(peer: String, id: String) = vault.transaction { tx ->
        ProtocolRules.id(id)
        check(tx.rows("SELECT id FROM messages WHERE id=? AND peer=? AND outgoing=1 AND historical=0 AND kind!='deleted'",id,peer).isNotEmpty()) { "Only your own message can be deleted for everyone" }
        val features = jsonKv(tx,"features.$peer")?.optJSONArray("features")
        check(features != null && (0 until features.length()).any { features.optString(it) == "delete" }) { "Connect to an updated peer first" }
        encrypt(tx,peer,WireCodec.id(),"consent_request",NativeExtensions.envelope(JSONObject().put("type","delete").put("id",id)),null)
        erase(tx,peer,id,true)
    }
    private fun erase(tx: SqlDatabase, peer: String, id: String, outgoing: Boolean) {
        val row = tx.rows("SELECT peer,outgoing FROM messages WHERE id=?",id).singleOrNull()
        val transfer = tx.rows("SELECT peer FROM transfers WHERE id=?",id).singleOrNull()
        if(row != null && (row["peer"] != peer || row["outgoing"] != (if(outgoing) 1L else 0L)) || transfer != null && transfer["peer"] != peer) return
        setKv(tx,"deleted.$peer.$id",JSONObject().put("outgoing",outgoing))
        if(row == null && transfer == null) return
        tx.execute("DELETE FROM chunks WHERE transfer_id=? AND EXISTS(SELECT 1 FROM transfers WHERE id=? AND peer=?)",id,id,peer)
        tx.execute("DELETE FROM transfers WHERE id=? AND peer=?",id,peer)
        tx.execute("DELETE FROM kv WHERE key IN (?,?,?,?)", "transfer.pending.$id","media.expiry.$id","media.state.$id","reaction.$peer.$id")
        tx.execute("UPDATE messages SET kind='deleted',data='{}' WHERE id=? AND peer=? AND outgoing=?",id,peer,if(outgoing) 1 else 0)
    }
    fun stageAttachment(peer: String, name: String, mime: String, content: ByteArray, purpose: String = "file"): String {
        val id = WireCodec.id(); val hash = WireCodec.digest(content)
        val metadata = JSONObject().put("id", id).put("name", name).put("mime", mime).put("size", content.size).put("hash", hash).put("purpose", purpose)
        ProtocolRules.validate("transfer_start", metadata, self, peer)
        vault.transaction { tx ->
            contact(tx, peer)
            check(tx.count("SELECT COUNT(*) FROM kv WHERE key LIKE 'transfer.pending.%'") < 16) { "Too many queued attachments" }
            tx.execute("INSERT INTO transfers(id,peer,name,mime,size,hash,purpose,completed) VALUES(?,?,?,?,?,?,?,1)", id, peer, name, mime, content.size, hash, purpose)
            for (index in 0 until (content.size + ProtocolRules.CHUNK_SIZE - 1) / ProtocolRules.CHUNK_SIZE) {
                tx.execute("INSERT INTO chunks(transfer_id,chunk_index,bytes) VALUES(?,?,?)", id, index,
                    content.copyOfRange(index * ProtocolRules.CHUNK_SIZE, minOf(content.size, (index + 1) * ProtocolRules.CHUNK_SIZE)))
            }
            setKv(tx, "transfer.pending.$id", JSONObject().put("peer", peer).put("index", -1))
            if (purpose != "photo") {
                val visible = JSONObject().put("id", id).put("name", name).put("mime", mime).put("size", content.size)
                val limit = ProtocolRules.viewLimit(purpose)
                if (limit > 0) visible.put("viewLimit", limit).put("viewsUsed", 0)
                tx.execute("INSERT INTO messages(id,peer,outgoing,kind,data,created_at,status) VALUES(?,?,1,'attachment',?,?,0)", id, peer, WireCodec.canonical(visible), System.currentTimeMillis())
            }
        }
        return id
    }
    fun pumpTransfers(peer: String) {
        repeat(8) {
            val advanced = vault.transaction { tx ->
                val count = tx.count("SELECT COUNT(*) FROM outbox WHERE peer=? AND needs_ack=1", peer) + tx.count("SELECT COUNT(*) FROM kv WHERE key LIKE ?", "deferred.$peer.%")
                if (count >= 12) return@transaction false
                val queue = tx.rows("SELECT key,value FROM kv WHERE key LIKE 'transfer.pending.%' ORDER BY rowid")
                    .firstOrNull { JSONObject(it["value"] as String).getString("peer") == peer } ?: return@transaction false
                val key = queue["key"] as String; val state = JSONObject(queue["value"] as String); val id = key.removePrefix("transfer.pending.")
                val transfer = tx.rows("SELECT * FROM transfers WHERE id=? AND peer=?", id, peer).singleOrNull()
                if (transfer == null) { tx.execute("DELETE FROM kv WHERE key=?", key); return@transaction true }
                val index = state.getInt("index")
                val total = ((transfer["size"] as Long) + ProtocolRules.CHUNK_SIZE - 1) / ProtocolRules.CHUNK_SIZE
                val data = JSONObject().put("id", id)
                val kind = when {
                    index < 0 -> {
                        for (field in listOf("name", "mime", "size", "hash", "purpose")) data.put(field, transfer[field])
                        "transfer_start"
                    }
                    index < total -> {
                        val chunk = tx.rows("SELECT bytes FROM chunks WHERE transfer_id=? AND chunk_index=?", id, index.toString()).single()
                        data.put("index", index).put("bytes", WireCodec.b64(chunk["bytes"] as ByteArray)); "transfer_chunk"
                    }
                    else -> "transfer_finish"
                }
                encrypt(tx, peer, WireCodec.id(), kind, data, if (kind == "transfer_finish" && transfer["purpose"] != "photo") id else null)
                if (kind == "transfer_finish") {
                    tx.execute("DELETE FROM kv WHERE key=?", key)
                    if(transfer["purpose"] == "photo") { tx.execute("DELETE FROM chunks WHERE transfer_id=?",id); tx.execute("DELETE FROM transfers WHERE id=? AND peer=?",id,peer) }
                }
                else setKv(tx, key, JSONObject().put("peer", peer).put("index", index + 1))
                true
            }
            if (!advanced) return
        }
    }
    fun activatePending(peer: String) = vault.transaction { tx ->
        val store = TransactionalSignalStore(tx)
        if (!store.containsSession(SignalProtocolAddress(peer, 2))) return@transaction
        for (row in tx.rows("SELECT key,value FROM kv WHERE key LIKE ? ORDER BY rowid LIMIT 16", "deferred.$peer.%")) {
            if (tx.count("SELECT COUNT(*) FROM outbox WHERE peer=? AND protocol=2", peer) >= 192) break
            val intent = JSONObject(row["value"] as String)
            encrypt(tx, peer, intent.getString("id"), intent.getString("kind"), intent.getJSONObject("data"), if (intent.isNull("messageId")) null else intent.getString("messageId"))
            tx.execute("DELETE FROM kv WHERE key=?", row["key"])
        }
    }
    fun pending(peer: String): List<Map<String, Any?>> = vault.transaction { it.rows("SELECT * FROM outbox WHERE peer=? AND protocol=2 ORDER BY priority,rowid LIMIT 32", peer) }
    fun markHandedOff(row: Map<String, Any?>) = vault.transaction { tx ->
        if (row["needs_ack"] == 0L) {
            tx.execute("DELETE FROM outbox WHERE id=?", row["id"])
            tx.execute("DELETE FROM kv WHERE key LIKE 'ack.pending.%' AND value=?", row["id"])
        }
        row["message_id"]?.let { tx.execute("UPDATE messages SET status=MAX(status,1) WHERE id=? AND outgoing=1", it) }
    }
    fun attachment(peer: String, id: String): ByteArray = vault.transaction { tx ->
        val transfer = transfer(tx, peer, id); check(transfer["completed"] == 1L)
        check(ProtocolRules.viewLimit(transfer["purpose"] as String) == 0) { "Limited-view media cannot be saved or exported" }
        collect(tx, id, transfer["size"] as Long, transfer["hash"] as String)
    }
    /** Reserve a view transactionally BEFORE releasing plaintext. A crash uses
     * that view rather than restoring the budget. No ratchet/inbox rollback. */
    fun openMedia(peer: String, id: String, now: Long = System.currentTimeMillis()): ByteArray {
        expireLimitedMedia(now)
        var plaintext: ByteArray? = null
        return try { vault.transaction { tx ->
        val row = tx.rows("SELECT outgoing,data FROM messages WHERE id=? AND peer=? AND kind='attachment'", id, peer).single()
        val meta = JSONObject(row["data"] as String)
        check(!meta.optBoolean("expired", false)) { "Media has expired" }
        val transfer = transfer(tx, peer, id); check(transfer["completed"] == 1L)
        val limit = ProtocolRules.viewLimit(transfer["purpose"] as String)
        val used = meta.optInt("viewsUsed", 0)
        if (limit > 0 && row["outgoing"] == 0L) check(used < limit)
        val bytes = collect(tx, id, transfer["size"] as Long, transfer["hash"] as String); plaintext = bytes
        if (limit > 0 && row["outgoing"] == 0L) {
            meta.put("viewsUsed", used + 1)
            if ((transfer["mime"] as String).startsWith("image/") && !meta.has("viewDeadline")) {
                meta.put("viewDeadline", now + 30000)
                setKv(tx, "media.expiry.$id", JSONObject().put("peer", peer).put("id", id).put("deadline", now + 30000))
            }
            if (used + 1 == limit) {
                meta.put("expired", true)
                tx.execute("DELETE FROM chunks WHERE transfer_id=?", id)
                tx.execute("DELETE FROM transfers WHERE id=? AND peer=?", id, peer)
            }
            tx.execute("UPDATE messages SET data=? WHERE id=? AND peer=?", WireCodec.canonical(meta), id, peer)
            mediaState(tx, peer, id, meta)
        }
        bytes
        } } catch (failure: Throwable) { plaintext?.fill(0); throw failure }
    }
    private fun mediaState(tx: SqlDatabase, peer: String, id: String, meta: JSONObject) {
        setKv(tx, "media.state.$id", JSONObject().put("peer", peer).put("data", JSONObject().put("id", id).put("used", meta.optInt("viewsUsed")).put("expired", meta.optBoolean("expired"))))
    }
    /** Queue pressure must never roll back deletion. Receipt encryption is separate. */
    fun pumpMediaStates(peer: String) = vault.transaction { tx ->
        tx.rows("SELECT key,value FROM kv WHERE key LIKE 'media.state.%' LIMIT 128").forEach { row ->
            val value = JSONObject(row["value"] as String)
            if (value.getString("peer") == peer && tx.count("SELECT COUNT(*) FROM outbox WHERE peer=?", peer) < 240) {
                encrypt(tx, peer, WireCodec.id(), "media_state", value.getJSONObject("data"), null)
                tx.execute("DELETE FROM kv WHERE key=?", row["key"])
            }
        }
    }
    fun expireMedia(peer: String, id: String) = vault.transaction { tx -> expireMedia(tx, peer, id) }
    private fun expireMedia(tx: SqlDatabase, peer: String, id: String) {
        val row = tx.rows("SELECT data FROM messages WHERE id=? AND peer=? AND outgoing=0 AND kind='attachment'", id, peer).singleOrNull() ?: return
        val meta = JSONObject(row["data"] as String)
        if (meta.optInt("viewLimit") !in 1..2) return
        val fresh = !meta.optBoolean("expired")
        meta.put("expired", true)
        tx.execute("DELETE FROM chunks WHERE transfer_id=?", id)
        tx.execute("DELETE FROM transfers WHERE id=? AND peer=?", id, peer)
        tx.execute("DELETE FROM kv WHERE key=?", "media.expiry.$id")
        tx.execute("UPDATE messages SET data=? WHERE id=? AND peer=?", WireCodec.canonical(meta), id, peer)
        if (fresh) mediaState(tx, peer, id, meta)
    }
    /** Persisted deadlines survive process death; no plaintext/ratchet restoration. */
    fun expireLimitedMedia(now: Long = System.currentTimeMillis()): Boolean = vault.transaction { tx ->
        var changed = false
        tx.rows("SELECT value FROM kv WHERE key LIKE 'media.expiry.%'").forEach {
            val value = JSONObject(it["value"] as String)
            if (value.getLong("deadline") <= now) { expireMedia(tx, value.getString("peer"), value.getString("id")); changed = true }
        }
        changed
    }
    private fun transfer(tx: SqlDatabase, peer: String, id: String) = tx.rows("SELECT * FROM transfers WHERE id=? AND peer=?", id, peer).singleOrNull() ?: error("Unknown attachment")
    private fun collect(tx: SqlDatabase, id: String, size: Long, hash: String): ByteArray {
        require(size in 1L..ProtocolRules.MAX_ATTACHMENT.toLong())
        val chunks = tx.rows("SELECT chunk_index,bytes FROM chunks WHERE transfer_id=? ORDER BY chunk_index", id)
        require(chunks.size.toLong() == (size + ProtocolRules.CHUNK_SIZE - 1) / ProtocolRules.CHUNK_SIZE)
        val output = ByteArrayOutputStream(size.toInt())
        for ((i, chunk) in chunks.withIndex()) {
            require(chunk["chunk_index"] == i.toLong())
            val bytes = chunk["bytes"] as ByteArray
            require(bytes.size.toLong() == minOf(ProtocolRules.CHUNK_SIZE.toLong(), size - i.toLong() * ProtocolRules.CHUNK_SIZE))
            output.write(bytes)
        }
        return output.toByteArray().also { require(it.size.toLong() == size && WireCodec.digest(it) == hash) }
    }
    private fun jsonKv(tx: SqlDatabase, key: String): JSONObject? = tx.rows("SELECT value FROM kv WHERE key=?", key).singleOrNull()?.let { JSONObject(it["value"] as String) }
    private fun setKv(tx: SqlDatabase, key: String, value: JSONObject) = tx.execute("INSERT OR REPLACE INTO kv(key,value) VALUES(?,?)", key, WireCodec.canonical(value))
}
