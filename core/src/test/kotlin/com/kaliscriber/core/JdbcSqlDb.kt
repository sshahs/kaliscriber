package com.kaliscriber.core

import com.kaliscriber.core.store.SqlDb
import com.kaliscriber.core.store.SqlRow
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Types

/** [SqlDb] on the JVM through sqlite-jdbc, binding query arguments as text like Android does. */
class JdbcSqlDb(file: File) : SqlDb {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
    private var depth = 0

    @Synchronized
    override fun execute(sql: String, args: List<Any?>) {
        connection.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a ->
                when (a) {
                    null -> st.setNull(i + 1, Types.NULL)
                    is String -> st.setString(i + 1, a)
                    is Long -> st.setLong(i + 1, a)
                    is Int -> st.setLong(i + 1, a.toLong())
                    is Double -> st.setDouble(i + 1, a)
                    is Float -> st.setDouble(i + 1, a.toDouble())
                    is ByteArray -> st.setBytes(i + 1, a)
                    else -> error("Unsupported argument ${a.javaClass}")
                }
            }
            st.execute()
        }
    }

    @Synchronized
    override fun <T> query(sql: String, args: List<String>, map: (SqlRow) -> T): List<T> =
        connection.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setString(i + 1, a) }
            st.executeQuery().use { rs ->
                val row = Row(rs)
                buildList { while (rs.next()) add(map(row)) }
            }
        }

    @Synchronized
    override fun <T> transaction(block: () -> T): T {
        if (depth == 0) connection.autoCommit = false
        depth++
        var ok = false
        try {
            return block().also { ok = true }
        } finally {
            depth--
            if (depth == 0) {
                if (ok) connection.commit() else connection.rollback()
                connection.autoCommit = true
            }
        }
    }

    private class Row(private val rs: ResultSet) : SqlRow {
        override fun string(index: Int): String? = rs.getString(index + 1)
        override fun long(index: Int): Long = rs.getLong(index + 1)
        override fun double(index: Int): Double = rs.getDouble(index + 1)
        override fun blob(index: Int): ByteArray? = rs.getBytes(index + 1)
    }
}
