package com.kaliscriber.app.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.kaliscriber.core.store.SqlDb
import com.kaliscriber.core.store.SqlRow
import java.io.File

/** [SqlDb] on Android's SQLite. The database is opened on first use. */
class AndroidSqlDb(private val file: File) : SqlDb {
    private val db: SQLiteDatabase by lazy {
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).apply { enableWriteAheadLogging() }
    }

    override fun execute(sql: String, args: List<Any?>) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args.map { if (it is Int) it.toLong() else it }.toTypedArray())
    }

    override fun <T> query(sql: String, args: List<String>, map: (SqlRow) -> T): List<T> =
        db.rawQuery(sql, args.toTypedArray()).use { cursor ->
            val row = Row(cursor)
            buildList { while (cursor.moveToNext()) add(map(row)) }
        }

    override fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try {
            return block().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    private class Row(private val cursor: Cursor) : SqlRow {
        override fun string(index: Int): String? = if (cursor.isNull(index)) null else cursor.getString(index)
        override fun long(index: Int): Long = cursor.getLong(index)
        override fun double(index: Int): Double = cursor.getDouble(index)
        override fun blob(index: Int): ByteArray? = if (cursor.isNull(index)) null else cursor.getBlob(index)
    }
}
