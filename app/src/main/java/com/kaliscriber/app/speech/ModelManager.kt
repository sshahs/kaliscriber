package com.kaliscriber.app.speech

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

sealed interface ModelState {
    data object NotDownloaded : ModelState
    data class Downloading(val progress: Float) : ModelState
    data class Ready(val bytes: Long) : ModelState
    data class Failed(val message: String) : ModelState
}

/**
 * Downloads, unpacks and tracks on-device speech models under `files/models/<id>/`. Archives are
 * streamed straight through bzip2 and tar so the compressed file never touches the disk, and only
 * the int8 model weights and token tables are kept.
 */
class ModelManager(context: Context, http: OkHttpClient, private val scope: CoroutineScope) {

    private val root = File(context.filesDir, "models")
    private val http = http.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .build()
    private val jobs = mutableMapOf<String, Job>()

    private val _states = MutableStateFlow(ModelCatalog.all.associate { it.id to stateOnDisk(it) })
    val states: StateFlow<Map<String, ModelState>> = _states.asStateFlow()

    fun dir(model: LocalModel): File = File(root, model.id)

    fun isReady(model: LocalModel): Boolean = File(dir(model), READY_MARKER).exists()

    fun readyAsrModels(): List<LocalModel> = ModelCatalog.asrModels.filter(::isReady)

    val diarizationReady: Boolean get() = ModelCatalog.diarization.all(::isReady)

    /** Finds a file in the model folder by name suffix, e.g. "encoder.int8.onnx" or "tokens.txt". */
    fun file(model: LocalModel, suffix: String): File =
        dir(model).listFiles().orEmpty().firstOrNull { it.name.endsWith(suffix) }
            ?: throw IOException("${model.name} is missing $suffix. Delete and download it again.")

    /** Downloads [model] (and the voice detector every recognizer needs). */
    fun download(model: LocalModel) {
        if (model.role == ModelRole.ASR && !isReady(ModelCatalog.vad)) download(ModelCatalog.vad)
        synchronized(jobs) {
            if (jobs[model.id]?.isActive == true || isReady(model)) return
            setState(model, ModelState.Downloading(0f))
            jobs[model.id] = scope.launch(Dispatchers.IO) {
                try {
                    fetch(model)
                    setState(model, stateOnDisk(model))
                } catch (e: CancellationException) {
                    dir(model).deleteRecursively()
                    setState(model, ModelState.NotDownloaded)
                    throw e
                } catch (e: Exception) {
                    dir(model).deleteRecursively()
                    setState(model, ModelState.Failed(e.message ?: "Download failed"))
                }
            }
        }
    }

    fun downloadDiarization() = ModelCatalog.diarization.forEach(::download)

    fun cancel(model: LocalModel) {
        synchronized(jobs) { jobs.remove(model.id)?.cancel() }
    }

    fun delete(model: LocalModel) {
        cancel(model)
        scope.launch(Dispatchers.IO) {
            dir(model).deleteRecursively()
            setState(model, ModelState.NotDownloaded)
        }
    }

    private suspend fun fetch(model: LocalModel) {
        val target = dir(model)
        target.deleteRecursively()
        target.mkdirs()
        val response = http.newCall(Request.Builder().url(model.url).build()).execute()
        response.use {
            if (!it.isSuccessful) throw IOException("Download failed (${it.code}) for ${model.name}")
            val body = it.body ?: throw IOException("Empty download for ${model.name}")
            val total = body.contentLength().takeIf { len -> len > 0 } ?: (model.downloadMb * 1_000_000L)
            val context = coroutineContext
            var lastReported = 0f
            val counting = CountingInputStream(body.byteStream()) { read ->
                context.ensureActive()
                val progress = (read.toFloat() / total).coerceIn(0f, 1f)
                if (progress - lastReported >= 0.01f) {
                    lastReported = progress
                    setState(model, ModelState.Downloading(progress))
                }
            }
            if (model.isArchive) {
                extract(counting, target)
            } else {
                File(target, model.url.substringAfterLast('/')).outputStream().use { out -> counting.copyTo(out, BUFFER) }
            }
        }
        if (target.listFiles().orEmpty().none { it.length() > 0 }) throw IOException("${model.name} archive was empty")
        File(target, READY_MARKER).writeText(model.url)
    }

    private fun extract(input: InputStream, target: File) {
        TarArchiveInputStream(BZip2CompressorInputStream(input.buffered(BUFFER))).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.substringAfterLast('/')
                if (!keep(name)) continue
                File(target, name).outputStream().use { out -> tar.copyTo(out, BUFFER) }
            }
        }
    }

    /** Only the quantized weights and token tables are needed; skip fp32 copies, samples and scripts. */
    private fun keep(name: String): Boolean = name.endsWith(".int8.onnx") || name.endsWith("tokens.txt")

    private fun stateOnDisk(model: LocalModel): ModelState =
        if (isReady(model)) ModelState.Ready(dir(model).walkBottomUp().filter { it.isFile }.sumOf { it.length() })
        else ModelState.NotDownloaded

    private fun setState(model: LocalModel, state: ModelState) = _states.update { it + (model.id to state) }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _states.update { current ->
            ModelCatalog.all.associate { m ->
                m.id to (current[m.id]?.takeIf { it is ModelState.Downloading } ?: stateOnDisk(m))
            }
        }
    }

    private class CountingInputStream(input: InputStream, private val onRead: (Long) -> Unit) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) report(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) report(it.toLong()) }

        private fun report(n: Long) {
            count += n
            onRead(count)
        }
    }

    private companion object {
        const val READY_MARKER = ".ready"
        const val BUFFER = 1 shl 16
    }
}
