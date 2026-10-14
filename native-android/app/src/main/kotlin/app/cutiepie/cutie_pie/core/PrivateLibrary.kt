package app.cutiepie.cutie_pie.core

/** All records stay inside the existing SQLCipher vault; no preferences/cloud copy. */
class PrivateLibrary(private val vault: TransactionalVault) {
    data class Call(val id: String, val peer: String, val incoming: Boolean, val video: Boolean,
        val started: Long, val connected: Long?, val ended: Long?, val duration: Long?, val outcome: String)
    data class Media(val id: String, val peer: String, val outgoing: Boolean, val name: String, val mime: String, val size: Long, val at: Long)
    init {
        // Additive auxiliary table: older vault readers may ignore it safely.
        // Never restore/replace the vault or modify any Signal ratchet record.
        vault.transaction { it.execute("CREATE TABLE IF NOT EXISTS private_calls(id TEXT PRIMARY KEY, peer TEXT NOT NULL, incoming INTEGER NOT NULL, video INTEGER NOT NULL, started INTEGER NOT NULL, connected INTEGER, ended INTEGER, duration INTEGER, outcome TEXT NOT NULL)") }
    }
    fun recoverInterrupted(now: Long) = vault.transaction { tx ->
        tx.rows("SELECT id FROM private_calls WHERE ended IS NULL AND incoming=1 AND connected IS NULL").forEach { row ->
            val id=row["id"] as String
            val call=tx.rows("SELECT * FROM private_calls WHERE id=?",id).single()
            tx.execute("UPDATE private_calls SET ended=?,duration=0,outcome='missed' WHERE id=?",now,id)
            val data=org.json.JSONObject().put("callId",id).put("video",call["video"] == 1L).put("outcome","missed").put("started",call["started"]).put("duration",0)
            tx.execute("INSERT OR IGNORE INTO messages(id,peer,outgoing,kind,data,created_at,status) VALUES(?,?,0,'call_event',?,?,3)","call.$id",call["peer"],data.toString(),now)
        }
        tx.execute("UPDATE private_calls SET ended=?,duration=NULL,outcome='interrupted' WHERE ended IS NULL", now)
    }
    fun begin(id: String, peer: String, incoming: Boolean, video: Boolean, now: Long) = vault.transaction {
        require(id.isNotBlank() && peer.isNotBlank() && now > 0)
        it.execute("INSERT OR IGNORE INTO private_calls(id,peer,incoming,video,started,outcome) VALUES(?,?,?,?,?,?)", id, peer, if(incoming) 1 else 0, if(video) 1 else 0, now, if(incoming) "incoming" else "calling")
    }
    fun connected(id: String, now: Long) = vault.transaction {
        it.execute("UPDATE private_calls SET connected=COALESCE(connected,?),outcome='connected' WHERE id=? AND ended IS NULL", now, id)
    }
    fun finish(id: String, now: Long, duration: Long, outcome: String): Call? = vault.transaction {
        require(duration >= 0 && outcome in setOf("ended","missed","declined","cancelled","failed","unanswered"))
        val row = it.rows("SELECT * FROM private_calls WHERE id=? AND ended IS NULL", id).singleOrNull() ?: return@transaction null
        it.execute("UPDATE private_calls SET ended=?,duration=?,outcome=? WHERE id=? AND ended IS NULL", now, duration, outcome, id)
        val data = org.json.JSONObject().put("callId",id).put("video",row["video"] == 1L)
            .put("outcome",outcome).put("started",row["started"]).put("duration",duration)
        it.execute("INSERT OR IGNORE INTO messages(id,peer,outgoing,kind,data,created_at,status) VALUES(?,?,?,'call_event',?,?,3)",
            "call.$id", row["peer"], if(row["incoming"] == 1L) 0 else 1, data.toString(), now)
        Call(id,row["peer"] as String,row["incoming"] == 1L,row["video"] == 1L,
            row["started"] as Long,row["connected"] as Long?,now,duration,outcome)
    }
    fun calls(): List<Call> = vault.transaction { tx ->
        tx.rows("SELECT * FROM private_calls ORDER BY started DESC LIMIT 500").map {
            Call(it["id"] as String, it["peer"] as String, it["incoming"] == 1L, it["video"] == 1L,
                it["started"] as Long, it["connected"] as Long?, it["ended"] as Long?, it["duration"] as Long?, it["outcome"] as String)
        }
    }
    fun media(): List<Media> = vault.transaction { tx ->
        // Message IDs are the transfer ID for outgoing; incoming metadata keeps
        // it in data. Do not rely on SQLite JSON extensions or active chat state.
        val messages = tx.rows("SELECT peer,outgoing,data,created_at FROM messages WHERE kind='attachment' ORDER BY created_at DESC LIMIT 500")
            .mapNotNull { row -> runCatching { org.json.JSONObject(row["data"] as String).optString("id") to row }.getOrNull() }.toMap()
        tx.rows("SELECT id,peer,name,mime,size FROM transfers WHERE completed=1 AND purpose='file' ORDER BY rowid DESC LIMIT 1000")
            .mapNotNull { transfer ->
                val row = messages[transfer["id"]] ?: return@mapNotNull null
                if (row["peer"] != transfer["peer"]) return@mapNotNull null
                if (org.json.JSONObject(row["data"] as String).optInt("viewLimit") > 0) return@mapNotNull null
                Media(transfer["id"] as String, transfer["peer"] as String, row["outgoing"] == 1L,
                    transfer["name"] as String, transfer["mime"] as String, transfer["size"] as Long, row["created_at"] as Long)
            }.sortedByDescending { it.at }
    }
}
