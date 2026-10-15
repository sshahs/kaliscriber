package com.kaliscriber.core.backup

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * KaliScriber backups: the app's files in a zip, encrypted with a key derived from the user's
 * password (PBKDF2-HMAC-SHA256) using AES-256-GCM. The zip is sealed in segments of
 * [SEGMENT] bytes, each authenticated with its position and whether it is the last, so backups
 * with hours of recordings never need to fit in memory and a changed, reordered or cut-off file is
 * detected.
 */
object Backup {
    private val MAGIC = "KSBACKUP".toByteArray()
    private const val FORMAT = 1
    private const val ITERATIONS = 210_000
    private const val SEGMENT = 1 shl 20
    private const val TAG_BITS = 128

    /**
     * Something to back up and its path inside the backup, e.g. "meetings/abc.json". [open] is
     * called when the entry is written; an entry whose file has gone by then is left out. [size]
     * is only used for progress. Already compressed data such as audio is stored as it is.
     */
    class Entry(val path: String, val size: Long, val time: Long = 0, val compress: Boolean = true, val open: () -> InputStream) {
        companion object {
            fun file(path: String, file: File, compress: Boolean = true) =
                Entry(path, file.length(), file.lastModified(), compress) { file.inputStream() }

            fun bytes(path: String, bytes: ByteArray, time: Long = 0) = Entry(path, bytes.size.toLong(), time) { bytes.inputStream() }
        }
    }

    /**
     * Writes [entries] to [out], encrypted with [password]. [onProgress] gets the bytes read so far
     * and the total expected.
     */
    fun write(out: OutputStream, password: CharArray, entries: Sequence<Entry>, total: Long = 0, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val noncePrefix = ByteArray(8).also(random::nextBytes)
        DataOutputStream(out).apply {
            write(MAGIC)
            writeInt(FORMAT)
            writeInt(ITERATIONS)
            write(salt)
            write(noncePrefix)
            flush()
        }
        val sealed = SealingOutputStream(out, key(password, salt, ITERATIONS), noncePrefix)
        ZipOutputStream(sealed).use { zip ->
            var done = 0L
            val buffer = ByteArray(64 * 1024)
            for (entry in entries) {
                val input = try {
                    entry.open()
                } catch (e: FileNotFoundException) {
                    continue
                }
                input.use {
                    zip.setLevel(if (entry.compress) Deflater.DEFAULT_COMPRESSION else Deflater.NO_COMPRESSION)
                    zip.putNextEntry(ZipEntry(entry.path).apply { if (entry.time > 0) time = entry.time })
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        zip.write(buffer, 0, n)
                        done += n
                        onProgress(done, maxOf(total, done))
                    }
                    zip.closeEntry()
                }
            }
        }
    }

    /**
     * Reads a backup from [input], calling [onEntry] with each file's path and contents (read them
     * before returning). Throws [BackupException] for a wrong password or a damaged file.
     */
    suspend fun read(input: InputStream, password: CharArray, onEntry: suspend (path: String, data: InputStream) -> Unit) {
        val header = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        try {
            header.readFully(magic)
        } catch (e: EOFException) {
            throw BackupException("This isn't a KaliScriber backup.", e)
        }
        if (!magic.contentEquals(MAGIC)) throw BackupException("This isn't a KaliScriber backup.")
        val format = header.readInt()
        if (format != FORMAT) throw BackupException("This backup was made by a newer version of KaliScriber.")
        val iterations = header.readInt()
        if (iterations !in 10_000..10_000_000) throw BackupException("This backup is damaged.")
        val salt = ByteArray(16).also(header::readFully)
        val noncePrefix = ByteArray(8).also(header::readFully)
        val opened = OpeningInputStream(input, key(password, salt, iterations), noncePrefix)
        ZipInputStream(opened).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                onEntry(entry.name, NonClosing(zip))
            }
            // Reading to the end checks the final segment, so a cut-off backup is noticed.
            while (opened.read() >= 0) Unit
        }
    }

    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun nonce(prefix: ByteArray, index: Int): ByteArray =
        ByteBuffer.allocate(12).put(prefix).putInt(index).array()

    /** Encrypts what is written in segments; [close] seals the last one. */
    private class SealingOutputStream(private val out: OutputStream, private val key: SecretKeySpec, private val prefix: ByteArray) : OutputStream() {
        private val buffer = ByteArray(SEGMENT)
        private var size = 0
        private var index = 0
        private var closed = false

        override fun write(b: Int) {
            if (size == SEGMENT) seal(last = false)
            buffer[size++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var left = len
            while (left > 0) {
                if (size == SEGMENT) seal(last = false)
                val n = minOf(left, SEGMENT - size)
                System.arraycopy(b, o, buffer, size, n)
                size += n
                o += n
                left -= n
            }
        }

        private fun seal(last: Boolean) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(prefix, index)))
            cipher.updateAAD(byteArrayOf(if (last) 1 else 0))
            val sealed = cipher.doFinal(buffer, 0, size)
            DataOutputStream(out).apply {
                writeInt(sealed.size)
                write(sealed)
            }
            index++
            size = 0
        }

        override fun flush() = out.flush()

        override fun close() {
            if (closed) return
            closed = true
            seal(last = true)
            out.flush()
            out.close()
        }
    }

    /** Decrypts and checks segments as they are read. */
    private class OpeningInputStream(input: InputStream, private val key: SecretKeySpec, private val prefix: ByteArray) : InputStream() {
        private val data = DataInputStream(input)
        private var plain = ByteArray(0)
        private var pos = 0
        private var index = 0
        private var finished = false

        private fun next(): Boolean {
            if (finished) return false
            val length = try {
                data.readInt()
            } catch (e: EOFException) {
                throw BackupException("This backup is incomplete.", e)
            }
            if (length < 16 || length > SEGMENT + 16) throw BackupException("This backup is damaged.")
            val sealed = ByteArray(length).also {
                try {
                    data.readFully(it)
                } catch (e: EOFException) {
                    throw BackupException("This backup is incomplete.", e)
                }
            }
            plain = open(sealed, last = false) ?: open(sealed, last = true)?.also { finished = true }
                ?: throw BackupException(if (index == 0) "Wrong password, or the backup is damaged." else "This backup is damaged.")
            pos = 0
            index++
            return true
        }

        private fun open(sealed: ByteArray, last: Boolean): ByteArray? = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(prefix, index)))
            cipher.updateAAD(byteArrayOf(if (last) 1 else 0))
            cipher.doFinal(sealed)
        } catch (e: AEADBadTagException) {
            null
        }

        override fun read(): Int {
            while (pos >= plain.size) if (!next()) return -1
            return plain[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= plain.size) if (!next()) return -1
            val n = minOf(len, plain.size - pos)
            System.arraycopy(plain, pos, b, off, n)
            pos += n
            return n
        }
    }

    /** Lets a caller read one zip entry without closing the whole zip. */
    private class NonClosing(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }
}
