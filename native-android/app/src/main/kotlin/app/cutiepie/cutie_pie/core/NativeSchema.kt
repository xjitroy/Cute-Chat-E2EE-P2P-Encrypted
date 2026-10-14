package app.cutiepie.cutie_pie.core

/** Exact legacy schema 3, also used by explicit fresh native vault creation. */
object NativeSchema {
    fun create(db: SqlDatabase) {
        db.execute("CREATE TABLE kv (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execute("CREATE TABLE signal_records (kind TEXT NOT NULL, id TEXT NOT NULL, bytes BLOB NOT NULL, PRIMARY KEY(kind,id))")
        db.execute("CREATE TABLE contacts (uid TEXT PRIMARY KEY, bundle TEXT NOT NULL, alias TEXT NOT NULL, verified INTEGER NOT NULL DEFAULT 0, blocked INTEGER NOT NULL DEFAULT 0, last_seen INTEGER, photo BLOB, photo_hash TEXT)")
        db.execute("CREATE TABLE messages (id TEXT PRIMARY KEY, peer TEXT NOT NULL, outgoing INTEGER NOT NULL, kind TEXT NOT NULL, data TEXT NOT NULL, created_at INTEGER NOT NULL, status INTEGER NOT NULL DEFAULT 0, historical INTEGER NOT NULL DEFAULT 0)")
        db.execute("CREATE INDEX message_peer_time ON messages(peer,created_at)")
        db.execute("CREATE TABLE outbox (id TEXT PRIMARY KEY, peer TEXT NOT NULL, frame TEXT NOT NULL, message_id TEXT, needs_ack INTEGER NOT NULL, created_at INTEGER NOT NULL, protocol INTEGER NOT NULL DEFAULT 1, priority INTEGER NOT NULL DEFAULT 1)")
        db.execute("CREATE TABLE inbox (id TEXT PRIMARY KEY, peer TEXT NOT NULL, hash TEXT NOT NULL, delivered_id TEXT)")
        db.execute("CREATE TABLE transfers (id TEXT PRIMARY KEY, peer TEXT NOT NULL, name TEXT NOT NULL, mime TEXT NOT NULL, size INTEGER NOT NULL, hash TEXT NOT NULL, purpose TEXT NOT NULL, completed INTEGER NOT NULL DEFAULT 0)")
        db.execute("CREATE TABLE chunks (transfer_id TEXT NOT NULL, chunk_index INTEGER NOT NULL, bytes BLOB NOT NULL, PRIMARY KEY(transfer_id,chunk_index), FOREIGN KEY(transfer_id) REFERENCES transfers(id) ON DELETE CASCADE)")
        db.execute("CREATE TABLE consent_requests (id TEXT PRIMARY KEY, peer TEXT NOT NULL, operation TEXT NOT NULL, request TEXT NOT NULL, state TEXT NOT NULL, expires_at INTEGER NOT NULL)")
        db.execute("CREATE TABLE used_approvals (id TEXT PRIMARY KEY, used_at INTEGER NOT NULL)")
        db.version = 3
    }
}
