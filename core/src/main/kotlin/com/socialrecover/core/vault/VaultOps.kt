package com.socialrecover.core.vault

import com.socialrecover.core.engine.EntryResult
import com.socialrecover.core.engine.EntryStatus
import com.socialrecover.core.engine.Progress
import com.socialrecover.core.engine.RestoreReport
import com.socialrecover.core.engine.SafeNames
import com.socialrecover.core.engine.SrpException
import com.socialrecover.core.store.RestoreTarget
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields

/** Restauración desde una bóveda (local o nube) con verificación SHA-256 y relectura del destino. */
object VaultRestore {
    fun restore(
        vault: Vault,
        snapshot: Snapshot,
        files: List<SnapshotFile>,
        target: RestoreTarget?,
        verifyAfterWrite: Boolean = true,
        onProgress: (Progress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): RestoreReport {
        val total = files.sumOf { it.size }
        var done = 0L
        val results = ArrayList<EntryResult>()
        var fatal: SrpException? = null
        for ((i, f) in files.withIndex()) {
            val entry = f.toManifestEntry(i)
            if (isCancelled()) { fatal = SrpException.Cancelled(); break }
            onProgress(Progress(done, total, i, files.size, f.name))
            var created: RestoreTarget.Created? = null
            try {
                if (target != null) created = target.create(SafeNames.sanitizePath(f.folder), f.name, f.mimeType)
                val out: OutputStream? = created?.output
                val md = MessageDigest.getInstance("SHA-256")
                var size = 0L
                for (id in f.blobs) {
                    if (isCancelled()) throw SrpException.Cancelled()
                    val data = vault.getBlob(id)
                    md.update(data); size += data.size
                    out?.write(data)
                    done += data.size
                    onProgress(Progress(done, total, i, files.size, f.name))
                }
                out?.close()
                val ok = size == f.size && md.digest().contentEquals(f.sha256)
                results += when {
                    !ok -> { created?.discard?.invoke(); EntryResult(entry, EntryStatus.HASH_MISMATCH, null, "El contenido no coincide con la huella original.") }
                    created == null -> EntryResult(entry, EntryStatus.INTACT)
                    !verifyAfterWrite -> EntryResult(entry, EntryStatus.WRITTEN_NOT_REREAD, created.displayPath)
                    else -> reread(entry, f, created)
                }
            } catch (e: Throwable) {
                runCatching { created?.output?.close() }
                created?.discard?.invoke()
                val err = if (e is SrpException) e else SrpException.fromIo(e, "la carpeta de destino")
                val status = when (err) {
                    is Vault.MissingBlob -> EntryStatus.NOT_FOUND_IN_DATA
                    is SrpException.CorruptedBackup -> EntryStatus.HASH_MISMATCH
                    else -> EntryStatus.WRITE_FAILED
                }
                val msg = when (err) {
                    is Vault.MissingBlob -> "Parte del archivo ya no está en el almacenamiento (se borró o no llegó a subirse)."
                    else -> err.userMessage
                }
                results += EntryResult(entry, status, null, msg)
                // Errores que afectan a todo (sin espacio, sin permiso, cancelado, sin conexión) detienen la restauración.
                if (err is SrpException.Cancelled || err is SrpException.NoSpace || err is SrpException.PermissionDenied ||
                    err is SrpException.Offline || err is SrpException.AuthRequired ||
                    err is SrpException.Storage && created == null && target != null
                ) { fatal = err; break }
            }
        }
        val processed = results.map { it.entry.id }.toSet()
        files.withIndex().filter { it.index !in processed }.forEach {
            results += EntryResult(it.value.toManifestEntry(it.index), EntryStatus.NOT_PROCESSED, null, fatal?.userMessage)
        }
        return RestoreReport(results, fatal)
    }

    private fun reread(entry: com.socialrecover.core.model.ManifestEntry, f: SnapshotFile, c: RestoreTarget.Created): EntryResult {
        val input = runCatching { c.reopen() }.getOrNull() ?: return EntryResult(entry, EntryStatus.WRITTEN_NOT_REREAD, c.displayPath)
        val md = MessageDigest.getInstance("SHA-256"); var size = 0L
        val buf = ByteArray(64 * 1024)
        try {
            var n = input.read(buf)
            while (n >= 0) { md.update(buf, 0, n); size += n; n = input.read(buf) }
        } catch (e: Exception) {
            return EntryResult(entry, EntryStatus.WRITTEN_NOT_REREAD, c.displayPath)
        } finally { runCatching { input.close() } }
        return if (size == f.size && md.digest().contentEquals(f.sha256)) EntryResult(entry, EntryStatus.VERIFIED, c.displayPath)
        else EntryResult(entry, EntryStatus.HASH_MISMATCH, c.displayPath, "El archivo guardado en el destino no coincide con el original.")
    }

    /** Lee un archivo completo en memoria (vista previa / comparación de versiones). Límite para no agotar memoria. */
    fun readSmall(vault: Vault, f: SnapshotFile, maxBytes: Long = 8L shl 20): ByteArray? {
        if (f.size > maxBytes) return null
        val out = java.io.ByteArrayOutputStream(f.size.toInt())
        f.blobs.forEach { out.write(vault.getBlob(it)) }
        val data = out.toByteArray()
        return if (MessageDigest.getInstance("SHA-256").digest(data).contentEquals(f.sha256)) data
        else throw SrpException.CorruptedBackup("Vista previa: huella incorrecta")
    }
}

/** Resultado de comprobar la integridad de una bóveda. */
data class CheckReport(
    val checkedAt: Long,
    val snapshotsOk: Int,
    val snapshotsDamaged: List<String>,
    val missingBlobs: Set<String>,
    val corruptBlobs: Set<String>,
    /** Archivos afectados: instantánea → rutas. */
    val affected: Map<String, List<String>>,
    val readData: Boolean,
) {
    val ok get() = snapshotsDamaged.isEmpty() && missingBlobs.isEmpty() && corruptBlobs.isEmpty()
}

object VaultCheck {
    /** @param readData true = descarga y verifica cada bloque (lento); false = solo comprueba que existen. */
    fun run(vault: Vault, readData: Boolean, onProgress: (Progress) -> Unit = {}, isCancelled: () -> Boolean = { false }): CheckReport {
        vault.refreshBlobs()
        val known = vault.knownBlobs()
        val damaged = ArrayList<String>()
        val snaps = ArrayList<Snapshot>()
        for (ref in vault.listSnapshots()) {
            try { snaps += vault.loadSnapshot(ref.name) } catch (e: SrpException) { damaged += ref.name }
        }
        val missing = HashSet<String>(); val corrupt = HashSet<String>(); val verified = HashSet<String>()
        val all = snaps.flatMap { s -> s.files.flatMap { it.blobs } }.toSet()
        var i = 0
        for (id in all) {
            if (isCancelled()) throw SrpException.Cancelled()
            onProgress(Progress(i.toLong(), all.size.toLong(), i, all.size, "")); i++
            if (id !in known) { missing += id; continue }
            if (readData && verified.add(id)) {
                try { vault.getBlob(id) } catch (e: Vault.MissingBlob) { missing += id } catch (e: SrpException.CorruptedBackup) { corrupt += id }
            }
        }
        val bad = missing + corrupt
        val affected = snaps.associate { s -> s.name to s.files.filter { f -> f.blobs.any { it in bad } }.map { it.path } }
            .filterValues { it.isNotEmpty() }
        return CheckReport(System.currentTimeMillis(), snaps.size, damaged, missing, corrupt, affected, readData)
    }
}

/**
 * Política de conservación. NADA se borra si no hay una política aceptada por el usuario:
 * [KEEP_ALL] es el valor por defecto.
 */
data class RetentionPolicy(
    val keepLast: Int = 0,
    val keepDaily: Int = 0,
    val keepWeekly: Int = 0,
    val keepMonthly: Int = 0,
    val keepYearly: Int = 0,
    val keepWithinDays: Int = 0,
    val keepAll: Boolean = false,
) {
    fun describe(): String = if (keepAll) "Conservar todas las versiones" else listOfNotNull(
        keepLast.takeIf { it > 0 }?.let { "las $it últimas copias" },
        keepWithinDays.takeIf { it > 0 }?.let { "todas las de los últimos $it días" },
        keepDaily.takeIf { it > 0 }?.let { "una por día durante $it días" },
        keepWeekly.takeIf { it > 0 }?.let { "una por semana durante $it semanas" },
        keepMonthly.takeIf { it > 0 }?.let { "una por mes durante $it meses" },
        keepYearly.takeIf { it > 0 }?.let { "una por año durante $it años" },
    ).joinToString(", ").ifEmpty { "Conservar todas las versiones" }.replaceFirstChar { it.uppercase() }

    companion object {
        val KEEP_ALL = RetentionPolicy(keepAll = true)
        /** Propuesta recomendada que se ofrece al usuario (debe aceptarla expresamente). */
        val RECOMMENDED = RetentionPolicy(keepLast = 10, keepDaily = 14, keepWeekly = 8, keepMonthly = 24, keepYearly = 10)
    }
}

data class RetentionPlan(val keep: Map<String, List<String>>, val remove: List<String>)

object Retention {
    fun plan(snapshots: List<Vault.SnapshotRef>, policy: RetentionPolicy, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): RetentionPlan {
        val sorted = snapshots.sortedByDescending { it.createdAt }
        if (policy.keepAll || policy == RetentionPolicy() /* política vacía = conservar todo */) {
            return RetentionPlan(sorted.associate { it.name to listOf("conservar todo") }, emptyList())
        }
        val reasons = LinkedHashMap<String, MutableList<String>>()
        fun keep(r: Vault.SnapshotRef, why: String) { reasons.getOrPut(r.name) { mutableListOf() } += why }
        sorted.take(policy.keepLast).forEach { keep(it, "últimas") }
        if (policy.keepWithinDays > 0) sorted.filter { now - it.createdAt <= policy.keepWithinDays * 86_400_000L }.forEach { keep(it, "reciente") }
        fun bucket(n: Int, why: String, key: (LocalDate) -> String) {
            if (n <= 0) return
            val seen = LinkedHashSet<String>()
            for (r in sorted) {
                val k = key(Instant.ofEpochMilli(r.createdAt).atZone(zone).toLocalDate())
                if (k !in seen) { if (seen.size >= n) break; seen += k; keep(r, why) }
            }
        }
        bucket(policy.keepDaily, "diaria") { it.toString() }
        bucket(policy.keepWeekly, "semanal") { "${it.get(IsoFields.WEEK_BASED_YEAR)}-${it.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}" }
        bucket(policy.keepMonthly, "mensual") { "${it.year}-${it.monthValue}" }
        bucket(policy.keepYearly, "anual") { "${it.year}" }
        return RetentionPlan(reasons, sorted.filter { it.name !in reasons }.map { it.name })
    }

    data class PruneResult(val snapshotsRemoved: Int, val blobsRemoved: Int, val bytesFreed: Long)

    /**
     * Aplica un plan: borra instantáneas y después los bloques que ya no usa ninguna.
     * Se niega si hay una copia en curso (sus bloques aún no figuran en ninguna instantánea).
     */
    fun prune(vault: Vault, plan: RetentionPlan, confirmedByUser: Boolean): PruneResult {
        require(confirmedByUser) { "La limpieza requiere la confirmación expresa del usuario" }
        if (vault.activeLocks(maxAgeMs = 6 * 3_600_000L).isNotEmpty()) {
            throw IllegalStateException("Hay una copia en curso. Inténtalo cuando termine.")
        }
        plan.remove.forEach { vault.deleteSnapshot(it) }
        val referenced = HashSet<String>()
        for (r in vault.listSnapshots()) vault.loadSnapshot(r.name).files.forEach { referenced += it.blobs }
        val sizes = vault.backend.list("blobs/").associate { it.key.substringAfterLast('/') to it.size }
        var n = 0; var bytes = 0L
        for ((id, size) in sizes) if (id !in referenced) { vault.deleteBlob(id); n++; bytes += size }
        return PruneResult(plan.remove.size, n, bytes)
    }
}

data class FileVersion(val snapshot: Vault.SnapshotRef, val file: SnapshotFile, val changed: Boolean)

sealed class DiffLine(val text: String) {
    class Same(t: String) : DiffLine(t)
    class Added(t: String) : DiffLine(t)
    class Removed(t: String) : DiffLine(t)
}

data class VersionComparison(
    val a: FileVersion,
    val b: FileVersion,
    val identical: Boolean,
    val sizeDelta: Long,
    /** Diferencias línea a línea, solo para archivos de texto pequeños; null en otro caso. */
    val textDiff: List<DiffLine>?,
)

/** Historial y comparación de versiones de un archivo a lo largo de las copias. */
object Versions {
    fun history(snapshots: List<Pair<Vault.SnapshotRef, Snapshot>>, path: String): List<FileVersion> {
        var prev: ByteArray? = null
        return snapshots.sortedBy { it.first.createdAt }.mapNotNull { (ref, s) ->
            s.find(path)?.let { f ->
                val changed = prev == null || !prev!!.contentEquals(f.sha256)
                prev = f.sha256
                FileVersion(ref, f, changed)
            }
        }
    }

    /** Solo las versiones distintas (el contenido cambió). */
    fun distinct(history: List<FileVersion>) = history.filter { it.changed }

    fun isText(f: SnapshotFile) = f.mimeType.startsWith("text/") ||
        f.name.substringAfterLast('.', "").lowercase() in setOf("txt", "md", "csv", "json", "html", "htm", "xml", "log", "js")

    fun compare(vault: Vault, a: FileVersion, b: FileVersion): VersionComparison {
        val identical = a.file.sha256.contentEquals(b.file.sha256)
        var diff: List<DiffLine>? = null
        if (!identical && isText(a.file) && isText(b.file) && a.file.size < 1_000_000 && b.file.size < 1_000_000) {
            val ta = VaultRestore.readSmall(vault, a.file)?.toString(Charsets.UTF_8)
            val tb = VaultRestore.readSmall(vault, b.file)?.toString(Charsets.UTF_8)
            if (ta != null && tb != null) diff = lineDiff(ta.lines(), tb.lines())
        }
        return VersionComparison(a, b, identical, b.file.size - a.file.size, diff)
    }

    /** Diferencia por LCS (suficiente para documentos de hasta unos miles de líneas). */
    fun lineDiff(a: List<String>, b: List<String>, maxLines: Int = 3000): List<DiffLine> {
        if (a.size > maxLines || b.size > maxLines) return listOf(DiffLine.Same("(Documento demasiado largo para comparar línea a línea)"))
        val n = a.size; val m = b.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        val out = ArrayList<DiffLine>()
        var i = 0; var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { out += DiffLine.Same(a[i]); i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> { out += DiffLine.Removed(a[i]); i++ }
                else -> { out += DiffLine.Added(b[j]); j++ }
            }
        }
        while (i < n) out += DiffLine.Removed(a[i++])
        while (j < m) out += DiffLine.Added(b[j++])
        return out
    }
}
