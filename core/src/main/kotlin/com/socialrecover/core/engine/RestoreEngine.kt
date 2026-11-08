package com.socialrecover.core.engine

import com.socialrecover.core.crypto.Keys
import com.socialrecover.core.crypto.StreamCipher
import com.socialrecover.core.model.BackupInfo
import com.socialrecover.core.model.Manifest
import com.socialrecover.core.model.ManifestEntry
import com.socialrecover.core.store.BackupLocation
import com.socialrecover.core.store.RestoreTarget
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Arrays

/** Resumen de una copia para la lista "Recuperar mis datos" (no requiere contraseña). */
data class BackupSummary(
    val dirName: String,
    val info: BackupInfo?,
    val status: Status,
    val bytesOnDisk: Long?,
) {
    enum class Status { OK, INCOMPLETE, UNREADABLE }
}

/** Copia abierta con la contraseña correcta. */
class OpenedBackup internal constructor(
    val dirName: String,
    val info: BackupInfo,
    val manifest: Manifest,
    private val dek: ByteArray,
) : AutoCloseable {
    internal fun dataKey() = Keys.subKey(dek, Keys.LABEL_DATA)
    override fun close() = Arrays.fill(dek, 0)
}

enum class EntryStatus {
    /** Restaurado y comprobado: la huella SHA-256 del archivo escrito coincide con la original. */
    VERIFIED,
    /** Restaurado; la huella coincide al escribir pero no se pudo releer el archivo para comprobarlo. */
    WRITTEN_NOT_REREAD,
    /** Solo comprobación (sin escribir): el contenido de la copia es íntegro. */
    INTACT,
    HASH_MISMATCH,
    WRITE_FAILED,
    NOT_FOUND_IN_DATA,
    NOT_PROCESSED,
}

data class EntryResult(val entry: ManifestEntry, val status: EntryStatus, val path: String? = null, val message: String? = null) {
    val ok get() = status == EntryStatus.VERIFIED || status == EntryStatus.WRITTEN_NOT_REREAD || status == EntryStatus.INTACT
}

data class RestoreReport(val results: List<EntryResult>, val fatal: SrpException?) {
    val okCount get() = results.count { it.ok }
    val failedCount get() = results.count { !it.ok }
    val allOk get() = fatal == null && results.all { it.ok }
}

class RestoreEngine {

    fun list(location: BackupLocation): List<BackupSummary> =
        io { location.listDirs() }
            .filter { it.startsWith(Layout.DIR_PREFIX) }
            .map { dir ->
                val size = runCatching { location.dirSize(dir) }.getOrNull()
                try {
                    val info = location.openRead(dir, Layout.INFO)?.use { BackupInfo.readFrom(it) }
                    when {
                        info == null -> BackupSummary(dir, null, BackupSummary.Status.INCOMPLETE, size)
                        !info.complete -> BackupSummary(dir, info, BackupSummary.Status.INCOMPLETE, size)
                        else -> BackupSummary(dir, info, BackupSummary.Status.OK, size)
                    }
                } catch (e: Exception) {
                    BackupSummary(dir, null, BackupSummary.Status.UNREADABLE, size)
                }
            }
            .sortedByDescending { it.info?.createdAt ?: 0L }

    /** Comprueba la contraseña y descifra el índice. */
    fun open(location: BackupLocation, dirName: String, password: CharArray): OpenedBackup {
        val info = io { location.openRead(dirName, Layout.INFO) }?.use { BackupInfo.readFrom(it) }
            ?: throw SrpException.IncompleteBackup()
        if (!info.complete) throw SrpException.IncompleteBackup()
        val dek = Keys.unwrap(info.wrapped, password)
        try {
            val raw = io { location.openRead(dirName, Layout.MANIFEST) } ?: throw SrpException.IncompleteBackup()
            val manifest = StreamCipher.decrypt(BufferedInputStream(raw), Keys.subKey(dek, Keys.LABEL_MANIFEST), Layout.MAGIC_MANIFEST)
                .use { Manifest.readFrom(it) }
            return OpenedBackup(dirName, info, manifest, dek)
        } catch (e: Throwable) {
            Arrays.fill(dek, 0)
            throw e
        }
    }

    /**
     * Restaura (o solo comprueba, si target == null) los archivos seleccionados.
     * Cada archivo se verifica con SHA-256 al escribirlo y, si es posible, se relee del destino
     * para confirmar que lo guardado coincide exactamente con el original.
     */
    fun restore(
        location: BackupLocation,
        backup: OpenedBackup,
        selectedIds: Set<Int>? = null,
        target: RestoreTarget?,
        verifyAfterWrite: Boolean = true,
        onProgress: (Progress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): RestoreReport {
        val byId = backup.manifest.entries.associateBy { it.id }
        val wanted = backup.manifest.entries.filter { selectedIds == null || it.id in selectedIds }
        val wantedIds = wanted.map { it.id }.toMutableSet()
        val total = wanted.sumOf { it.size }
        val results = LinkedHashMap<Int, EntryResult>()
        var done = 0L
        var fatal: SrpException? = null

        try {
            val raw = io { location.openRead(backup.dirName, Layout.DATA) } ?: throw SrpException.IncompleteBackup()
            val din = DataInputStream(StreamCipher.decrypt(BufferedInputStream(raw, 256 * 1024), backup.dataKey(), Layout.MAGIC_DATA))
            try {
                try {
                    if (din.readInt() != Layout.ARCHIVE_MAGIC) throw SrpException.CorruptedBackup("Contenedor no reconocido")
                    val v = din.readUnsignedByte()
                    if (v != Layout.ARCHIVE_VERSION) throw SrpException.UnsupportedVersion(v)
                    val buf = ByteArray(Layout.SEGMENT)
                    while (wantedIds.isNotEmpty()) {
                        val tag = din.readUnsignedByte()
                        if (tag == 0) break
                        if (tag != 1) throw SrpException.CorruptedBackup("Marca de archivo no válida")
                        val id = din.readInt()
                        val meta = byId[id]
                        val isWanted = meta != null && id in wantedIds
                        if (isWanted) onProgress(Progress(done, total, results.size, wanted.size, meta!!.name))

                        var created: RestoreTarget.Created? = null
                        var writeError: String? = null
                        if (isWanted && target != null) {
                            try {
                                created = target.create(SafeNames.sanitizePath(meta!!.folder), meta.name, meta.mimeType)
                            } catch (e: Exception) {
                                writeError = SrpException.fromIo(e, "la carpeta de destino").userMessage
                            }
                        }
                        val out: OutputStream? = created?.output
                        val md = MessageDigest.getInstance("SHA-256")
                        var size = 0L
                        try {
                            while (true) {
                                if (isCancelled()) throw SrpException.Cancelled()
                                val n = din.readInt()
                                if (n == 0) break
                                if (n < 0 || n > Layout.SEGMENT) throw SrpException.CorruptedBackup("Segmento no válido")
                                din.readFully(buf, 0, n)
                                if (isWanted) {
                                    md.update(buf, 0, n)
                                    size += n
                                    done += n
                                    if (out != null && writeError == null) {
                                        try {
                                            out.write(buf, 0, n)
                                        } catch (e: Exception) {
                                            writeError = SrpException.fromIo(e, "la carpeta de destino").userMessage
                                        }
                                    }
                                    onProgress(Progress(done, total, results.size, wanted.size, meta!!.name))
                                }
                            }
                            if (out != null) {
                                try { out.close() } catch (e: Exception) {
                                    if (writeError == null) writeError = SrpException.fromIo(e, "la carpeta de destino").userMessage
                                }
                            }
                        } catch (e: Throwable) {
                            runCatching { out?.close() }
                            if (isWanted) {
                                results[id] = EntryResult(meta!!, EntryStatus.WRITE_FAILED, created?.displayPath,
                                    "Restauración interrumpida: el archivo puede estar incompleto.")
                                wantedIds.remove(id)
                            }
                            throw e
                        }
                        if (!isWanted) continue
                        meta!!
                        wantedIds.remove(id)
                        val digest = md.digest()
                        results[id] = when {
                            writeError != null -> EntryResult(meta, EntryStatus.WRITE_FAILED, created?.displayPath, writeError)
                            size != meta.size || !digest.contentEquals(meta.sha256) ->
                                EntryResult(meta, EntryStatus.HASH_MISMATCH, created?.displayPath,
                                    "El contenido no coincide con la huella original.")
                            created == null -> EntryResult(meta, EntryStatus.INTACT)
                            !verifyAfterWrite -> EntryResult(meta, EntryStatus.WRITTEN_NOT_REREAD, created.displayPath)
                            else -> reread(meta, created)
                        }
                    }
                } catch (e: EOFException) {
                    throw SrpException.CorruptedBackup("Los datos terminan antes de lo esperado")
                }
            } finally {
                runCatching { din.close() }
            }
        } catch (e: SrpException) {
            fatal = e
        } catch (e: Exception) {
            fatal = SrpException.fromIo(e)
        }

        val finalResults = wanted.map { e ->
            results[e.id] ?: EntryResult(e,
                if (fatal == null) EntryStatus.NOT_FOUND_IN_DATA else EntryStatus.NOT_PROCESSED,
                message = fatal?.userMessage ?: "El archivo figura en el índice pero no en los datos.")
        }
        onProgress(Progress(done, total, wanted.size, wanted.size, ""))
        return RestoreReport(finalResults, fatal)
    }

    private fun reread(meta: ManifestEntry, created: RestoreTarget.Created): EntryResult {
        val input = try { created.reopen() } catch (e: Exception) { null }
            ?: return EntryResult(meta, EntryStatus.WRITTEN_NOT_REREAD, created.displayPath)
        val md = MessageDigest.getInstance("SHA-256")
        var size = 0L
        val buf = ByteArray(Layout.SEGMENT)
        try {
            var n = input.read(buf)
            while (n >= 0) {
                md.update(buf, 0, n); size += n
                n = input.read(buf)
            }
        } catch (e: Exception) {
            return EntryResult(meta, EntryStatus.WRITTEN_NOT_REREAD, created.displayPath)
        } finally {
            runCatching { input.close() }
        }
        return if (size == meta.size && md.digest().contentEquals(meta.sha256)) {
            EntryResult(meta, EntryStatus.VERIFIED, created.displayPath)
        } else {
            EntryResult(meta, EntryStatus.HASH_MISMATCH, created.displayPath,
                "El archivo guardado en el destino no coincide con el original.")
        }
    }

    /**
     * Recorre los archivos de la copia entregando su contenido como flujo (para migrarlos a la bóveda).
     * Cada flujo solo es válido durante la llamada a [consumer]. Si el contenido no coincide con la huella
     * del índice, se lanza CorruptedBackup.
     */
    fun forEachEntry(location: BackupLocation, backup: OpenedBackup, consumer: (ManifestEntry, java.io.InputStream) -> Unit) {
        val byId = backup.manifest.entries.associateBy { it.id }
        val raw = io { location.openRead(backup.dirName, Layout.DATA) } ?: throw SrpException.IncompleteBackup()
        val din = DataInputStream(StreamCipher.decrypt(BufferedInputStream(raw, 256 * 1024), backup.dataKey(), Layout.MAGIC_DATA))
        try {
            if (din.readInt() != Layout.ARCHIVE_MAGIC) throw SrpException.CorruptedBackup("Contenedor no reconocido")
            if (din.readUnsignedByte() != Layout.ARCHIVE_VERSION) throw SrpException.UnsupportedVersion(0)
            while (true) {
                val tag = din.readUnsignedByte()
                if (tag == 0) break
                if (tag != 1) throw SrpException.CorruptedBackup("Marca de archivo no válida")
                val meta = byId[din.readInt()]
                val seg = SegmentStream(din)
                if (meta != null) {
                    val md = MessageDigest.getInstance("SHA-256")
                    val hashing = java.security.DigestInputStream(seg, md)
                    consumer(meta, hashing)
                    seg.drain()
                    if (!md.digest().contentEquals(meta.sha256)) throw SrpException.CorruptedBackup("Huella incorrecta: ${meta.path}")
                } else seg.drain()
            }
        } catch (e: EOFException) {
            throw SrpException.CorruptedBackup("Los datos terminan antes de lo esperado")
        } finally {
            runCatching { din.close() }
        }
    }

    /** Flujo sobre los segmentos de un archivo dentro del contenedor (termina en el segmento de longitud 0). */
    private class SegmentStream(private val din: DataInputStream) : java.io.InputStream() {
        private var remaining = 0
        private var ended = false
        private fun fill(): Boolean {
            while (remaining == 0 && !ended) {
                val n = din.readInt()
                if (n == 0) ended = true
                else if (n < 0 || n > Layout.SEGMENT) throw SrpException.CorruptedBackup("Segmento no válido")
                else remaining = n
            }
            return !ended || remaining > 0
        }
        override fun read(): Int = if (!fill()) -1 else { remaining--; din.readUnsignedByte() }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val k = minOf(len, remaining)
            din.readFully(b, off, k); remaining -= k
            return k
        }
        override fun close() {}
        fun drain() { val buf = ByteArray(64 * 1024); while (read(buf, 0, buf.size) >= 0) {} }
    }

    fun delete(location: BackupLocation, dirName: String) {
        require(dirName.startsWith(Layout.DIR_PREFIX)) { "No es una carpeta de copia" }
        io { location.deleteDir(dirName) }
    }
}
