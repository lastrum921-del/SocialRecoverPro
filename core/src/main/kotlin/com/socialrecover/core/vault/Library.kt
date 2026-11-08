package com.socialrecover.core.vault

import com.socialrecover.core.engine.SrpException
import com.socialrecover.core.model.Category
import com.socialrecover.core.social.SocialItem
import com.socialrecover.core.social.SocialNetwork
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Una instantánea cargada junto con la bóveda (local o nube) en la que está. */
class LoadedSnapshot(val vault: Vault, val ref: Vault.SnapshotRef, val snapshot: Snapshot) {
    val storage: StorageKind get() = ref.storage

    /** Un archivo solo es recuperable si TODOS sus bloques están presentes en el almacenamiento. */
    fun isAvailable(f: SnapshotFile): Boolean = f.blobs.all { vault.hasBlob(it) }
}

/**
 * Catálogo unificado de todas las bóvedas abiertas (local + nube). Base de la Máquina del tiempo,
 * del buscador social y de la Recuperación guiada.
 */
class Library(val vaults: List<Vault>, private val zone: ZoneId = ZoneId.systemDefault()) {
    /** Instantáneas que no se pudieron descifrar (dañadas): se informan, nunca se ofrecen. */
    val damaged = ArrayList<Pair<Vault.SnapshotRef, String>>()

    val snapshots: List<LoadedSnapshot> by lazy {
        vaults.flatMap { v ->
            v.listSnapshots().mapNotNull { ref ->
                try { LoadedSnapshot(v, ref, v.loadSnapshot(ref.name)) } catch (e: SrpException) {
                    damaged += ref to e.userMessage; null
                }
            }
        }.sortedBy { it.ref.createdAt }
    }

    fun date(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    fun allSocial(): List<Pair<LoadedSnapshot, SocialItem>> {
        // La misma publicación puede estar en varias importaciones: se muestra una vez (la más reciente).
        val seen = HashMap<String, Pair<LoadedSnapshot, SocialItem>>()
        for (ls in snapshots) for (it in ls.snapshot.social) {
            seen["${it.network}|${it.type}|${it.timestamp}|${it.text}|${it.url}"] = ls to it
        }
        return seen.values.toList()
    }

    /** Archivo multimedia de un elemento social, si está realmente en la copia. */
    fun mediaFor(ls: LoadedSnapshot, item: SocialItem): SnapshotFile? =
        item.mediaPath?.let { p -> ls.snapshot.find(p) }?.takeIf { ls.isAvailable(it) }
}

/** Filtros de la Máquina del tiempo. */
data class TimeFilter(
    val device: String? = null,
    val platform: SocialNetwork? = null,
    val category: Category? = null,
    val storage: StorageKind? = null,
    val text: String = "",
)

data class TimeItem(val loaded: LoadedSnapshot, val file: SnapshotFile, val available: Boolean)

/** Máquina del tiempo digital: recuperar por calendario. */
class TimeMachine(private val lib: Library) {

    /** Días de un mes que tienen copias → número de copias ese día (para marcar el calendario). */
    fun daysWithBackups(month: YearMonth, filter: TimeFilter = TimeFilter()): Map<Int, Int> =
        lib.snapshots.filter { matchesSnapshot(it, filter) }
            .map { lib.date(it.ref.createdAt) }
            .filter { YearMonth.from(it) == month }
            .groupingBy { it.dayOfMonth }.eachCount()

    fun monthsWithBackups(year: Int): Map<Int, Int> =
        lib.snapshots.map { lib.date(it.ref.createdAt) }.filter { it.year == year }.groupingBy { it.monthValue }.eachCount()

    fun years(): List<Int> = lib.snapshots.map { lib.date(it.ref.createdAt).year }.distinct().sorted()

    fun snapshotsBetween(from: LocalDate, to: LocalDate, filter: TimeFilter = TimeFilter()): List<LoadedSnapshot> =
        lib.snapshots.filter {
            val d = lib.date(it.ref.createdAt)
            !d.isBefore(from) && !d.isAfter(to) && matchesSnapshot(it, filter)
        }

    fun snapshotsOn(day: LocalDate, filter: TimeFilter = TimeFilter()) = snapshotsBetween(day, day, filter)

    /**
     * Archivos guardados en un periodo (día, mes o año). Si un archivo aparece en varias copias del periodo,
     * se muestra la versión más reciente. [TimeItem.available] es false si sus datos ya no están: la interfaz
     * no debe ofrecerlo como recuperable.
     */
    fun filesBetween(from: LocalDate, to: LocalDate, filter: TimeFilter = TimeFilter()): List<TimeItem> {
        val latest = LinkedHashMap<String, TimeItem>()
        for (ls in snapshotsBetween(from, to, filter)) {
            for (f in ls.snapshot.files) {
                if (filter.category != null && f.category != filter.category) continue
                if (filter.platform != null && f.platform != filter.platform) continue
                if (filter.text.isNotBlank() && !norm(f.path).contains(norm(filter.text))) continue
                latest["${ls.snapshot.device}|${ls.storage}|${f.path}"] = TimeItem(ls, f, ls.isAvailable(f))
            }
        }
        return latest.values.sortedWith(compareBy({ it.file.folder }, { it.file.name }))
    }

    fun filesOn(day: LocalDate, filter: TimeFilter = TimeFilter()) = filesBetween(day, day, filter)

    /** Solo lo que realmente se puede recuperar. */
    fun recoverableOn(day: LocalDate, filter: TimeFilter = TimeFilter()) = filesOn(day, filter).filter { it.available }

    fun devices(): List<String> = lib.snapshots.map { it.snapshot.device }.distinct()

    private fun matchesSnapshot(ls: LoadedSnapshot, f: TimeFilter) =
        (f.device == null || ls.snapshot.device == f.device) && (f.storage == null || ls.storage == f.storage)

    companion object {
        fun norm(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    }
}
