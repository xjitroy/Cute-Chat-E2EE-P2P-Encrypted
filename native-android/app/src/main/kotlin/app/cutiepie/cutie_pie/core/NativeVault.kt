package app.cutiepie.cutie_pie.core

import android.content.Context
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.Closeable
import java.io.File

/** A single process owner calls this vault; transactions include all Signal mutations. */
class NativeVault private constructor(val db: SQLiteDatabase) : Closeable, TransactionalVault {
    @Synchronized override fun <T> transaction(block: (SqlDatabase) -> T): T {
        db.beginTransaction()
        val scope = AndroidSqlDatabase(db)
        try {
            val result = block(scope)
            db.setTransactionSuccessful()
            return result
        } finally { scope.invalidate(); db.endTransaction() }
    }
    @Synchronized override fun get(key: String): String? = db.rawQuery("SELECT value FROM kv WHERE key=?", arrayOf(key)).use {
        if (it.moveToFirst()) it.getString(0) else null
    }
    @Synchronized override fun close() = db.close()

    companion object {
        /** Existing vault ONLY. Fresh identity creation is an explicit separate operation. */
        fun openExisting(context: Context): NativeVault {
            System.loadLibrary("sqlcipher")
            val file = File(context.filesDir, "cutie.db")
            check(file.isFile) { "No existing vault" }
            val native = NativeVaultKey(context)
            val password = if (native.present()) native.read() else LegacyVaultKey(context).read()
                ?: error("Protected database key missing; existing vault preserved")
            // SQLCipher's default corruption handler may delete a DB. Replace it.
            val preserve = DatabaseErrorHandler { _, _ -> error("Encrypted vault validation failed; file preserved") }
            val db = SQLiteDatabase.openDatabase(file.path, password, null, SQLiteDatabase.OPEN_READWRITE, preserve, null)
            try {
                check(db.version in 1..3) { "Unsupported vault schema; file preserved" }
                db.rawQuery("PRAGMA cipher_integrity_check", emptyArray<String>()).use { cursor ->
                    check(!cursor.moveToFirst()) { "Encrypted vault integrity check failed" }
                }
                db.execSQL("PRAGMA foreign_keys=ON")
                val vault = NativeVault(db)
                vault.transaction { tx ->
                    val version = tx.version
                    if (version < 2) tx.execute("ALTER TABLE outbox ADD COLUMN protocol INTEGER NOT NULL DEFAULT 1")
                    if (version < 3) tx.execute("ALTER TABLE outbox ADD COLUMN priority INTEGER NOT NULL DEFAULT 1")
                    tx.version = 3
                    for (key in listOf("identity.private", "identity.public", "signal.registration")) {
                        check(vault.get(key) != null) { "Existing identity is incomplete; no replacement generated" }
                    }
                }
                return vault
            } catch (failure: Throwable) {
                db.close()
                throw failure
            }
        }
        /** User-confirmed first setup; never replaces any existing DB or protected key. */
        fun createFresh(context: Context): NativeVault {
            System.loadLibrary("sqlcipher")
            val file = File(context.filesDir, "cutie.db")
            check(!file.exists()) { "Existing vault preserved" }
            check(LegacyVaultKey(context).read() == null) { "Existing legacy identity protection preserved" }
            val password = NativeVaultKey(context).create()
            val preserve = DatabaseErrorHandler { _, _ -> error("Vault validation failed; file preserved") }
            val db = SQLiteDatabase.openOrCreateDatabase(file, password, null, preserve)
            try {
                db.execSQL("PRAGMA foreign_keys=ON")
                val vault = NativeVault(db)
                vault.transaction { tx -> NativeSchema.create(tx); NativeIdentity.createRecords(tx) }
                return vault
            } catch (failure: Throwable) { db.close(); throw failure }
        }
    }
}
