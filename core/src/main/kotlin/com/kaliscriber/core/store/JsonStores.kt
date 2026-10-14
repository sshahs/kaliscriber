package com.kaliscriber.core.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

val StoreJson = Json {
    ignoreUnknownKeys = true
    // Default values are left out of files, which keeps long meetings compact on disk.
    encodeDefaults = false
    explicitNulls = false
    coerceInputValues = true
}

/** Writes via a temp file and rename so a crash never leaves a half-written document. */
internal fun File.writeAtomically(text: String) {
    parentFile?.mkdirs()
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(this)) {
        delete()
        if (!tmp.renameTo(this)) {
            writeText(text)
            tmp.delete()
        }
    }
}

/**
 * One JSON file per document in [dir], mirrored in memory as a [StateFlow] so the UI can observe
 * changes. All writes are serialized through a mutex.
 */
class JsonDocumentStore<T>(
    private val dir: File,
    private val serializer: KSerializer<T>,
    /**
     * The part of a document worth saving. Changes that only touch the rest (such as a progress
     * indicator) update memory without rewriting the file.
     */
    private val persisted: (T) -> T = { it },
    private val idOf: (T) -> String,
) {
    private val mutex = Mutex()
    private val _items = MutableStateFlow<Map<String, T>>(emptyMap())
    private val _loaded = MutableStateFlow(false)

    val items: StateFlow<Map<String, T>> = _items.asStateFlow()
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    suspend fun load() = mutex.withLock { loadLocked() }

    private suspend fun loadLocked() {
        if (_loaded.value) return
        val loadedItems = withContext(Dispatchers.IO) {
            dir.mkdirs()
            val files = dir.listFiles { f -> f.isFile && f.extension == "json" }.orEmpty().toList()
            // Decode several documents at once; long transcripts make this the slow part of startup.
            coroutineScope {
                files.map { file ->
                    async(LOAD_DISPATCHER) { runCatching { StoreJson.decodeFromString(serializer, file.readText()) }.getOrNull() }
                }.awaitAll().filterNotNull()
            }
        }
        _items.value = loadedItems.associateBy(idOf)
        _loaded.value = true
    }

    fun get(id: String): T? = _items.value[id]

    suspend fun put(item: T) = mutex.withLock {
        loadLocked()
        write(item)
        _items.update { it + (idOf(item) to item) }
    }

    /** Atomically transforms the document with [id]; returns the new value or null if missing. */
    suspend fun update(id: String, transform: (T) -> T): T? = mutex.withLock {
        loadLocked()
        val current = _items.value[id] ?: return@withLock null
        val next = transform(current)
        if (next != current) {
            if (persisted(next) != persisted(current)) write(next)
            _items.update { it + (id to next) }
        }
        next
    }

    suspend fun delete(id: String) = mutex.withLock {
        loadLocked()
        withContext(Dispatchers.IO) { fileFor(id).delete() }
        _items.update { it - id }
    }

    private suspend fun write(item: T) = withContext(Dispatchers.IO) {
        fileFor(idOf(item)).writeAtomically(StoreJson.encodeToString(serializer, persisted(item)))
    }

    private companion object {
        @OptIn(ExperimentalCoroutinesApi::class)
        val LOAD_DISPATCHER = Dispatchers.IO.limitedParallelism(4)
    }

    private fun fileFor(id: String) = File(dir, "${id.replace(Regex("[^A-Za-z0-9_-]"), "_")}.json")
}

/** A single JSON value persisted to [file], e.g. app settings. */
class JsonValueStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    default: T,
) {
    private val mutex = Mutex()
    private val _value = MutableStateFlow(
        runCatching { if (file.exists()) StoreJson.decodeFromString(serializer, file.readText()) else default }
            .getOrDefault(default),
    )
    val value: StateFlow<T> = _value.asStateFlow()

    suspend fun update(transform: (T) -> T): T = mutex.withLock {
        val next = transform(_value.value)
        withContext(Dispatchers.IO) { file.writeAtomically(StoreJson.encodeToString(serializer, next)) }
        _value.value = next
        next
    }
}
