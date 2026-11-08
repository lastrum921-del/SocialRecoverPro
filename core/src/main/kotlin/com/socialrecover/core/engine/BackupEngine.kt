package com.socialrecover.core.engine

import com.socialrecover.core.crypto.CryptoParams
import com.socialrecover.core.crypto.Keys
import com.socialrecover.core.crypto.StreamCipher
import com.socialrecover.core.model.BackupInfo
import com.socialrecover.core.model.Manifest
import com.socialrecover.core.model.ManifestEntry
import com.socialrecover.core.store.BackupLocation
import com.socialrecover.core.store.BackupSource
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class Progress(
    val bytesDone: Long,
    /** null si algún archivo no informa de su tamaño. */
    val bytesTotal: Long?,
    val filesDone: Int,
    val filesTotal: Int,
    val currentName: String,
)

data class SkippedFile(val name: String, val reason: String)

data class BackupResult(
    val dirName: String,
    val manifest: Manifest,
    val skipped: List<SkippedFile>,
)

internal object Layout {
    const val DATA = "data.srpk"
    const val MANIFEST = "manifest.srpm"
    const val INFO = "info.properties"
    const val PARTIAL_SUFFIX = ".partial"
    const val DIR_PREFIX = "SRP-"
    const val MAGIC_DATA = "SRPD"
    const val MAGIC_MANIFEST = "SRPM"
    const val ARCHIVE_MAGIC = 0x53525041 // "SRPA"
    const val ARCHIVE_VERSION = 1
    const val SEGMENT = 64 * 1024
}

/**
 * Motor de copias: lee los archivos elegidos, calcula su SHA-256 y los guarda cifrados.
 * Memoria constante independientemente del tamaño (vídeos de varios GB incluidos).
 */
class BackupEngine(
    private val params: CryptoParams = CryptoParams.DEFAULT,
    private val appVersion: String = "1.0.0",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun create(
        location: BackupLocation,
        label: String,
        password: CharArray,
        sources: List<BackupSource>,
        deviceName: String,
        onProgress: (Progress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): BackupResult {
        require(sources.isNotEmpty()) { "No hay archivos seleccionados" }
        val now = clock()
        val dirName = newDirName(now)
        val partial = dirName + Layout.PARTIAL_SUFFIX
        val total: Long? = if (sources.all { it.sizeHint != null }) sources.sumOf { it.sizeHint!! } else null

        val dek = Keys.randomBytes(Keys.KEY_BYTES)
        try {
            val wrapped = Keys.wrap(dek, password, params.pbkdf2Iterations)
            if (isCancelled()) throw SrpException.Cancelled()
            io { location.createDir(partial) }
            try {
                val entries = ArrayList<ManifestEntry>()
                val skipped = ArrayList<SkippedFile>()
                var done = 0L

                val raw = io { location.openWrite(partial, Layout.DATA) }
                val enc = StreamCipher.encrypt(BufferedOutputStream(raw, 256 * 1024),
                    Keys.subKey(dek, Keys.LABEL_DATA), Layout.MAGIC_DATA, params.chunkSize)
                val out = DataOutputStream(enc)
                io {
                    out.writeInt(Layout.ARCHIVE_MAGIC)
                    out.writeByte(Layout.ARCHIVE_VERSION)
                }
                val buf = ByteArray(Layout.SEGMENT)
                sources.forEachIndexed { index, src ->
                    if (isCancelled()) throw SrpException.Cancelled()
                    onProgress(Progress(done, total, index, sources.size, src.displayName))
                    val input: InputStream = try {
                        src.open()
                    } catch (e: Exception) {
                        skipped += SkippedFile(src.displayName, "No se pudo abrir: ${e.message ?: e.javaClass.simpleName}")
                        return@forEachIndexed
                    }
                    val id = index
                    io { out.writeByte(1); out.writeInt(id) }
                    val md = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    var err: Exception? = null
                    try {
                        var more = true
                        while (more) {
                            if (isCancelled()) throw SrpException.Cancelled()
                            var n: Int
                            try {
                                n = StreamCipher.readFully(input, buf, 0, buf.size)
                            } catch (e: Exception) {
                                err = e
                                n = -1
                            }
                            if (n > 0) {
                                md.update(buf, 0, n)
                                io { out.writeInt(n); out.write(buf, 0, n) }
                                size += n
                                done += n
                                onProgress(Progress(done, total, index, sources.size, src.displayName))
                            }
                            more = n == buf.size
                        }
                    } finally {
                        runCatching { input.close() }
                    }
                    io { out.writeInt(0) } // fin de este archivo
                    if (err != null) {
                        // El archivo queda en el flujo pero fuera del índice: al restaurar se ignora.
                        skipped += SkippedFile(src.displayName, "Error de lectura: ${err.message ?: err.javaClass.simpleName}")
                    } else {
                        entries += ManifestEntry(
                            id = id,
                            folder = SafeNames.sanitizePath(src.folder).joinToString("/"),
                            name = SafeNames.sanitize(src.displayName),
                            category = src.category,
                            mimeType = src.mimeType,
                            size = size,
                            sha256 = md.digest(),
                            lastModified = src.lastModified,
                        )
                    }
                }
                io { out.writeByte(0); out.close() }

                if (entries.isEmpty()) throw SrpException.SourceUnreadable(
                    sources.first().displayName,
                    IllegalStateException(skipped.joinToString { it.reason }),
                )

                val manifest = Manifest(label.trim().ifEmpty { "Copia" }, now, deviceName, entries)
                io {
                    StreamCipher.encrypt(
                        BufferedOutputStream(location.openWrite(partial, Layout.MANIFEST)),
                        Keys.subKey(dek, Keys.LABEL_MANIFEST), Layout.MAGIC_MANIFEST, params.chunkSize,
                    ).use { manifest.writeTo(it) }
                }
                val info = BackupInfo(
                    BackupInfo.FORMAT_VERSION, manifest.label, now, entries.size,
                    manifest.totalBytes, appVersion, wrapped, complete = true,
                )
                io { location.openWrite(partial, Layout.INFO).use { info.writeTo(it) } }
                // info.properties (complete=true) se escribe el último: es lo que marca la copia como válida.
                // El cambio de nombre es solo estético; algunos proveedores de almacenamiento no permiten renombrar.
                val finalName = if (runCatching { location.renameDir(partial, dirName) }.isSuccess) dirName else partial
                onProgress(Progress(done, total, sources.size, sources.size, ""))
                return BackupResult(finalName, manifest, skipped)
            } catch (e: Throwable) {
                // Limpieza: una copia a medias no debe quedar ocupando espacio.
                runCatching { location.deleteDir(partial) }
                throw e
            }
        } finally {
            Arrays.fill(dek, 0)
        }
    }

    private fun newDirName(now: Long): String {
        val f = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).apply { timeZone = TimeZone.getDefault() }
        val rnd = Keys.randomBytes(2).joinToString("") { "%02x".format(it) }
        return "${Layout.DIR_PREFIX}${f.format(Date(now))}-$rnd"
    }
}

/** Ejecuta una operación de almacenamiento traduciendo sus errores a SrpException. */
internal inline fun <T> io(block: () -> T): T = try {
    block()
} catch (e: SrpException) {
    throw e
} catch (e: Exception) {
    throw SrpException.fromIo(e)
}
