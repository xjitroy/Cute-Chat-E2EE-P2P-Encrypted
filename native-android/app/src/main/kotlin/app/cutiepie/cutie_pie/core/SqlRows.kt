package app.cutiepie.cutie_pie.core

import android.database.Cursor
import net.zetetic.database.sqlcipher.SQLiteDatabase

/** Protocol code shares this SQL contract; Android production adapter is SQLCipher. */
interface SqlDatabase {
    var version: Int
    fun inTransaction(): Boolean
    fun rows(sql: String, vararg args: String): List<Map<String, Any?>>
    fun execute(sql: String, vararg args: Any?)
    fun count(sql: String, vararg args: String): Long = rows(sql, *args).first().values.first().let { (it as Number).toLong() }
}
interface TransactionalVault {
    fun <T> transaction(block: (SqlDatabase) -> T): T
    fun get(key: String): String?
}
internal class AndroidSqlDatabase(private val db: SQLiteDatabase) : SqlDatabase {
    private var valid = true
    private val owner = Thread.currentThread()
    private fun guard() { check(inTransaction()) { "SQL scope is expired or owned by another thread" } }
    fun invalidate() { valid = false }
    override fun inTransaction(): Boolean = valid && owner === Thread.currentThread() && db.inTransaction()
    override var version: Int
        get() { guard(); return db.version }
        set(value) { guard(); db.version = value }
    override fun execute(sql: String, vararg args: Any?) { guard(); db.execSQL(sql, args) }
    override fun rows(sql: String, vararg args: String): List<Map<String, Any?>> {
        guard()
        return db.rawQuery(sql, args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(buildMap {
                    for (i in 0 until cursor.columnCount) put(cursor.getColumnName(i), when (cursor.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(i)
                        else -> cursor.getString(i)
                    })
                })
            }
        }
    }
}
