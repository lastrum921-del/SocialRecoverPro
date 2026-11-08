package com.socialrecover.core.vault

import com.socialrecover.core.crypto.Keys
import com.socialrecover.core.crypto.StreamCipher
import com.socialrecover.core.engine.Progress
import com.socialrecover.core.engine.SafeNames
import com.socialrecover.core.engine.SkippedFile
import com.socialrecover.core.engine.SrpException
import com.socialrecover.core.model.Category
import com.socialrecover.core.social.SocialItem
import com.socialrecover.core.social.SocialNetwork
import com.socialrecover.core.store.BackupSource
import com.socialrecover.core.util.Bin
import java.io.InputStream
import java.security.MessageDigest

data class DuplicateFile(val path: String, val sameAs: String, val size: Long)

data class VaultBackupResult(
    val snapshot: Snapshot,
    val skipped: List<SkippedFile>,
    val duplicates: List<DuplicateFile>,
)

/** Fuente con red social de origen (para exportaciones importadas). */
interface PlatformSource : BackupSource {
    val platform: SocialNetwork?
}

/**
 * Copia incremental en una bóveda.
 *
 * Incremental en dos niveles:
 *  1. Archivo sin cambios (misma ruta, tamaño y fecha que en la instantánea anterior del dispositivo) → no se vuelve a leer.
 *  2. Bloque ya presente en la bóveda (mismo contenido en otro archivo, otra copia u otro dispositivo) → no se vuelve a guardar.
 *
 * Si se interrumpe (cancelación, sin conexión, sin espacio) no se escribe la instantánea; los bloques ya guardados
 * se reutilizan en el siguiente intento, de modo que la copia se reanuda donde se quedó.
 */
class VaultBackupSession(
    private val vault: Vault,
    private val label: String,
    private val device: String,
    private val deviceKind: DeviceKind,
    private val parent: Snapshot? = vault.latestFor(device),
    private val reuseUnchanged: Boolean = true,
    private val createdAt: Long = System.currentTimeMillis(),
    private val origin: String? = null,
    private val totalHint: Long? = null,
    private val onProgress: (Progress) -> Unit = {},
    private val isCancelled: () -> Boolean = { false },
) {
    private val lockKey = vault.lock("backup")
    private val files = ArrayList<SnapshotFile>()
    private val paths = HashSet<String>()
    private val social = ArrayList<SocialItem>()
    private val skipped = ArrayList<SkippedFile>()
    private val duplicates = ArrayList<DuplicateFile>()
    private val byHash = HashMap<String, String>()
    private val parentIndex = parent?.files?.associateBy { it.path } ?: emptyMap()
    private var unchanged = 0
    private var bytesTotal = 0L
    private var stored = 0L
    private var dedup = 0L
    private var compressionSaved = 0L
    private var done = 0L
    private val buf = ByteArray(vault.chunkSize)
    private var finished = false

    /** Ruta única dentro de la instantánea (dos archivos con el mismo nombre en la misma carpeta → "foto (1).jpg"). */
    private fun uniquePath(folder: String, name: String): Pair<String, String> {
        var n = name; var k = 1
        while ((if (folder.isEmpty()) n else "$folder/$n") in paths) n = SafeNames.withCounter(name, k++)
        return folder to n
    }

    private fun check() { if (isCancelled()) throw SrpException.Cancelled() }

    fun add(src: BackupSource) {
        check(); require(!finished)
        val folder0 = SafeNames.sanitizePath(src.folder).joinToString("/")
        val (folder, name) = uniquePath(folder0, SafeNames.sanitize(src.displayName))
        val path = if (folder.isEmpty()) name else "$folder/$name"
        val platform = (src as? PlatformSource)?.platform
        onProgress(Progress(done, totalHint, files.size, -1, src.displayName))

        // Nivel 1: archivo sin cambios desde la última copia de este dispositivo.
        val prev = parentIndex[folder0.let { if (it.isEmpty()) name else "$it/$name" }]
        if (reuseUnchanged && prev != null && src.sizeHint == prev.size && src.lastModified > 0 &&
            src.lastModified == prev.lastModified && prev.blobs.all { vault.hasBlob(it) }
        ) {
            files += prev.copy(folder = folder, name = name, category = src.category, platform = platform ?: prev.platform)
            paths += path; unchanged++; bytesTotal += prev.size; dedup += prev.size; done += prev.size
            noteDuplicate(prev.sha256Hex, path, prev.size)
            return
        }

        val input: InputStream = try { src.open() } catch (e: Exception) {
            skipped += SkippedFile(src.displayName, "No se pudo abrir: ${e.message ?: e.javaClass.simpleName}"); return
        }
        val md = MessageDigest.getInstance("SHA-256")
        val blobs = ArrayList<String>()
        var size = 0L
        val compress = worthCompressing(src.mimeType, name)
        try {
            var more = true
            while (more) {
                check()
                val n = try { StreamCipher.readFully(input, buf, 0, buf.size) } catch (e: Exception) {
                    skipped += SkippedFile(src.displayName, "Error de lectura: ${e.message ?: e.javaClass.simpleName}")
                    return
                }
                if (n > 0 || size == 0L && blobs.isEmpty()) {
                    md.update(buf, 0, n)
                    val id = vault.blobId(buf, 0, n)
                    val (w, saved) = try { vault.putBlob(id, buf, 0, n, compress) } catch (e: SrpException) { throw e } catch (e: Exception) { throw SrpException.fromIo(e) }
                    if (w == 0L) dedup += n else { stored += w; compressionSaved += saved }
                    blobs += id; size += n; done += n
                    onProgress(Progress(done, totalHint, files.size, -1, src.displayName))
                }
                more = n == buf.size
            }
        } finally {
            runCatching { input.close() }
        }
        val sha = md.digest()
        files += SnapshotFile(folder, name, src.category, platform, src.mimeType, size, src.lastModified, sha, blobs)
        paths += path; bytesTotal += size
        noteDuplicate(Bin.hex(sha), path, size)
    }

    private fun noteDuplicate(hex: String, path: String, size: Long) {
        val first = byHash.putIfAbsent(hex, path)
        if (first != null && size > 0) duplicates += DuplicateFile(path, first, size)
    }

    fun addSocial(items: Collection<SocialItem>) { social += items }

    fun pathCount() = files.size

    fun lastAddedPath(): String? = files.lastOrNull()?.path

    /** Marca con su red social los archivos guardados bajo una carpeta (se conoce tras analizar la exportación). */
    fun setPlatform(folderPrefix: String, network: SocialNetwork) {
        for (i in files.indices) {
            val f = files[i]
            if (f.folder == folderPrefix || f.folder.startsWith("$folderPrefix/")) files[i] = f.copy(platform = network)
        }
    }

    fun finish(): VaultBackupResult {
        require(!finished); finished = true
        try {
            if (files.isEmpty() && social.isEmpty()) {
                throw SrpException.SourceUnreadable(skipped.firstOrNull()?.name ?: "los archivos", null)
            }
            val snap = Snapshot(
                name = Snapshot.newName(createdAt, Bin.hex(Keys.randomBytes(3))),
                createdAt = createdAt, device = device, deviceKind = deviceKind,
                label = label.trim().ifEmpty { "Copia" }, parent = parent?.name, origin = origin,
                files = files.toList(), social = social.toList(),
                stats = SnapshotStats(files.size, unchanged, bytesTotal, stored, dedup, compressionSaved, duplicates.size),
            )
            try { vault.saveSnapshot(snap) } catch (e: SrpException) { throw e } catch (e: Exception) { throw SrpException.fromIo(e) }
            return VaultBackupResult(snap, skipped.toList(), duplicates.toList())
        } finally {
            vault.unlock(lockKey)
        }
    }

    /** Abandona la sesión sin escribir instantánea (los bloques quedan para reanudar). */
    fun abort() { if (!finished) { finished = true; vault.unlock(lockKey) } }

    companion object {
        private val incompressible = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "mp4", "mov", "mkv", "webm", "m4v",
            "3gp", "mp3", "m4a", "aac", "ogg", "opus", "flac", "zip", "rar", "7z", "gz", "xz", "docx", "xlsx", "pptx", "pdf", "srpk")

        fun worthCompressing(mime: String, name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext in incompressible) return false
            val m = mime.lowercase()
            return !(m.startsWith("image/") && !m.contains("svg") || m.startsWith("video/") || m.startsWith("audio/") || m.contains("zip"))
        }

        /** Atajo: copia completa de una lista de fuentes. */
        fun run(
            vault: Vault, sources: List<BackupSource>, label: String, device: String, deviceKind: DeviceKind,
            onProgress: (Progress) -> Unit = {}, isCancelled: () -> Boolean = { false }, createdAt: Long = System.currentTimeMillis(),
        ): VaultBackupResult {
            val total = if (sources.all { it.sizeHint != null }) sources.sumOf { it.sizeHint!! } else null
            val s = VaultBackupSession(vault, label, device, deviceKind, createdAt = createdAt, totalHint = total,
                onProgress = onProgress, isCancelled = isCancelled)
            try {
                sources.forEach { s.add(it) }
                return s.finish()
            } catch (e: Throwable) {
                s.abort(); throw e
            }
        }
    }
}

/** Fuente en memoria (útil para pruebas y para entradas de exportaciones). */
class BytesSource(
    override val displayName: String,
    private val data: ByteArray,
    override val folder: String = "",
    override val category: Category = Category.guess(null, displayName),
    override val mimeType: String = "application/octet-stream",
    override val lastModified: Long = 0L,
    override val platform: SocialNetwork? = null,
) : PlatformSource {
    override val sizeHint: Long get() = data.size.toLong()
    override fun open(): InputStream = data.inputStream()
}
