package app.cutiepie.cutie_pie.core

import java.sql.DriverManager
import java.io.Closeable

/** Real SQLite SQL/transactions with synthetic data. Not Android encryption evidence. */
internal class JdbcTestVault : TransactionalVault, Closeable {
    private val connection = DriverManager.getConnection("jdbc:sqlite::memory:").apply {
        createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
    }
    var failExecute: ((String) -> Boolean)? = null
    override fun <T> transaction(block: (SqlDatabase) -> T): T {
        check(connection.autoCommit)
        connection.autoCommit = false
        val scope = Scope()
        try { val result = block(scope); connection.commit(); return result }
        catch (failure: Throwable) { connection.rollback(); throw failure }
        finally { scope.valid = false; connection.autoCommit = true }
    }
    private inner class Scope : SqlDatabase {
        var valid = true
        private val owner = Thread.currentThread()
        override fun inTransaction() = valid && owner === Thread.currentThread() && !connection.autoCommit
        private fun guard() { check(inTransaction()) }
        override var version: Int
            get() = (rows("PRAGMA user_version").single().values.single() as Long).toInt()
            set(value) { execute("PRAGMA user_version=$value") }
        override fun execute(sql: String, vararg args: Any?) {
            guard()
            check(failExecute?.invoke(sql) != true) { "Injected write failure" }
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, arg -> statement.setObject(index + 1, arg) }
                statement.execute()
            }
        }
        override fun rows(sql: String, vararg args: String): List<Map<String, Any?>> {
            guard()
            return connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, arg -> statement.setString(index + 1, arg) }
                statement.executeQuery().use { results ->
                    buildList { while (results.next()) add(buildMap {
                        for (i in 1..results.metaData.columnCount) {
                            val value = results.getObject(i)
                            put(results.metaData.getColumnName(i), if (value is Byte || value is Short || value is Int) (value as Number).toLong() else value)
                        }
                    }) }
                }
            }
        }
    }
    override fun get(key: String): String? = transaction { it.rows("SELECT value FROM kv WHERE key=?", key).singleOrNull()?.get("value") as String? }
    override fun close() = connection.close()
}
