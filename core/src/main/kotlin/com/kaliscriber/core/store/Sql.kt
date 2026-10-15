package com.kaliscriber.core.store

/**
 * The small part of SQLite the app uses, so the search index runs on Android's SQLite and, in
 * tests, on the JVM.
 */
interface SqlDb {
    /** Runs a statement that returns no rows; [args] may be String, Long, Int, Double, ByteArray or null. */
    fun execute(sql: String, args: List<Any?> = emptyList())

    /** Runs a query. [args] are bound as text, as Android's rawQuery does. */
    fun <T> query(sql: String, args: List<String> = emptyList(), map: (SqlRow) -> T): List<T>

    /** Runs [block] in a transaction, committed only if it returns normally. Transactions may nest. */
    fun <T> transaction(block: () -> T): T
}

/** One row of a query result. */
interface SqlRow {
    fun string(index: Int): String?
    fun long(index: Int): Long
    fun double(index: Int): Double
    fun blob(index: Int): ByteArray?
}
