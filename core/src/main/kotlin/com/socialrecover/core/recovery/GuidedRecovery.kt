package com.socialrecover.core.recovery

import com.socialrecover.core.model.Category
import com.socialrecover.core.social.SocialItem
import com.socialrecover.core.social.SocialItemType
import com.socialrecover.core.social.SocialNetwork
import com.socialrecover.core.social.SocialSearch
import com.socialrecover.core.vault.Library
import com.socialrecover.core.vault.LoadedSnapshot
import com.socialrecover.core.vault.SnapshotFile
import com.socialrecover.core.vault.StorageKind
import java.time.LocalDate

/** Cómo de recuperable es un resultado. Se muestra siempre al usuario, sin ambigüedad. */
enum class Availability(val label: String) {
    /** El archivo completo está en una copia y se puede restaurar. */
    AVAILABLE("Disponible"),
    /** El texto (publicación, comentario) está guardado y se puede exportar; no hay archivo multimedia. */
    TEXT_AVAILABLE("Texto disponible"),
    /** Solo hay un registro (fecha, enlace): el contenido en sí no estaba en la exportación. */
    RECORD_ONLY("Solo registro de actividad"),
    /** Figuraba en una copia, pero sus datos ya no están (copia dañada o datos borrados). */
    DATA_MISSING("Datos no disponibles"),
}

data class RecoveryResult(
    val title: String,
    val subtitle: String,
    val date: Long,
    val kind: LostKind,
    val availability: Availability,
    val storage: StorageKind,
    val score: Int,
    val loaded: LoadedSnapshot,
    /** Archivo a restaurar (si [availability] == AVAILABLE). */
    val file: SnapshotFile?,
    val social: SocialItem?,
)

enum class SortBy { RELEVANCE, DATE, TYPE }

data class RecoveryOutcome(val results: List<RecoveryResult>, val explanations: List<String>)

/**
 * Pasos 2 y 3 de la recuperación guiada: busca en todas las copias autorizadas (locales y nube, todas las versiones)
 * y en las exportaciones sociales importadas. Nunca presenta como recuperable algo que no lo es.
 */
object GuidedRecovery {

    private fun kindOf(f: SnapshotFile): LostKind = when {
        f.category == Category.PHOTOS || f.mimeType.startsWith("image/") -> LostKind.PHOTO
        f.category == Category.VIDEOS || f.mimeType.startsWith("video/") -> LostKind.VIDEO
        f.category == Category.DOCUMENTS || f.mimeType.contains("pdf") || f.mimeType.startsWith("text/") ||
            f.name.substringAfterLast('.', "").lowercase() in setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "txt", "pdf") -> LostKind.DOCUMENT
        else -> when (f.name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg", "png", "heic", "webp", "gif" -> LostKind.PHOTO
            "mp4", "mov", "webm", "mkv", "3gp" -> LostKind.VIDEO
            else -> LostKind.OTHER
        }
    }

    private fun kindOf(t: SocialItemType): LostKind = when (t) {
        SocialItemType.PHOTO -> LostKind.PHOTO
        SocialItemType.VIDEO, SocialItemType.REEL, SocialItemType.STORY -> LostKind.VIDEO
        SocialItemType.POST, SocialItemType.MESSAGE -> LostKind.POST
        SocialItemType.COMMENT, SocialItemType.REPLY -> LostKind.COMMENT
        SocialItemType.LIKE, SocialItemType.SAVED -> LostKind.LIKE
        else -> LostKind.OTHER
    }

    fun search(lib: Library, q: ParsedQuery, sort: SortBy = SortBy.RELEVANCE, limit: Int = 300): RecoveryOutcome {
        val strict = searchOnce(lib, q, sort, limit)
        if (strict.results.isNotEmpty() || q.keywords.isEmpty() || (q.kinds.isEmpty() && q.networks.isEmpty() && q.from == null && q.deviceKind == null)) return strict
        // Ninguna coincidencia con las palabras: se muestran los resultados del tipo/red/fecha pedidos, avisando.
        val relaxed = searchOnce(lib, q.copy(keywords = emptyList()), sort, limit)
        if (relaxed.results.isEmpty()) return strict
        return RecoveryOutcome(relaxed.results,
            listOf("No hay coincidencias exactas con «${q.keywords.joinToString(" ")}». Se muestran resultados del mismo tipo y fecha.") + relaxed.explanations)
    }

    private fun searchOnce(lib: Library, q: ParsedQuery, sort: SortBy, limit: Int): RecoveryOutcome {
        val fromMs = q.from?.atStartOfDay(java.time.ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
        val toMs = q.to?.plusDays(1)?.atStartOfDay(java.time.ZoneId.systemDefault())?.toInstant()?.toEpochMilli()?.minus(1)
        fun inRange(ms: Long) = ms > 0 && (fromMs == null || ms >= fromMs) && (toMs == null || ms <= toMs)
        val dated = fromMs != null || toMs != null
        val socialOnly = q.kinds.isNotEmpty() && q.kinds.all { it in setOf(LostKind.POST, LostKind.COMMENT, LostKind.LIKE) }
        val results = ArrayList<RecoveryResult>()

        // --- archivos de las copias (todas las versiones; se muestra la más reciente de cada archivo)
        if (!socialOnly) {
            val latest = LinkedHashMap<String, Pair<LoadedSnapshot, SnapshotFile>>()
            for (ls in lib.snapshots) {
                if (q.deviceKind != null && ls.snapshot.deviceKind != q.deviceKind) continue
                for (f in ls.snapshot.files) latest["${ls.snapshot.device}|${ls.storage}|${f.path}|${f.sha256Hex}"] = ls to f
            }
            for ((ls, f) in latest.values) {
                val k = kindOf(f)
                val folderMatch = LostKind.FOLDER in q.kinds && q.keywords.any { w -> SocialSearch.tokens(f.folder).any { it.startsWith(w) } }
                if (q.kinds.isNotEmpty() && k !in q.kinds && !folderMatch) continue
                if (q.networks.isNotEmpty() && f.platform !in q.networks) continue
                if (dated && !inRange(f.lastModified) && !inRange(ls.ref.createdAt)) continue
                val toks = SocialSearch.tokens(f.path)
                val hits = q.keywords.count { w -> toks.any { it.startsWith(w) } }
                if (q.keywords.isNotEmpty() && hits == 0) continue
                val avail = if (ls.isAvailable(f)) Availability.AVAILABLE else Availability.DATA_MISSING
                results += RecoveryResult(
                    f.name, listOfNotNull(f.folder.takeIf { it.isNotEmpty() }, ls.snapshot.device, ls.storage.label).joinToString(" · "),
                    if (f.lastModified > 0) f.lastModified else ls.ref.createdAt, if (folderMatch) LostKind.FOLDER else k, avail, ls.storage,
                    hits * 10 + (if (k in q.kinds) 5 else 0) + (if (avail == Availability.AVAILABLE) 3 else 0), ls, f, null,
                )
            }
        }

        // --- elementos de redes sociales importados
        if (q.deviceKind == null || q.networks.isNotEmpty()) {
            for ((ls, it) in lib.allSocial()) {
                val k = kindOf(it.type)
                if (q.kinds.isNotEmpty() && k !in q.kinds) continue
                if (q.networks.isNotEmpty() && it.network !in q.networks) continue
                if (dated && !inRange(it.timestamp)) continue
                val toks = SocialSearch.tokens(it.text + " " + (it.url ?: ""))
                val hits = q.keywords.count { w -> toks.any { t -> t.startsWith(w) } }
                if (q.keywords.isNotEmpty() && hits == 0) continue
                val media = lib.mediaFor(ls, it)
                val avail = when {
                    media != null -> Availability.AVAILABLE
                    it.mediaPath != null -> Availability.DATA_MISSING
                    it.type in setOf(SocialItemType.POST, SocialItemType.COMMENT, SocialItemType.REPLY) && it.text.isNotBlank() -> Availability.TEXT_AVAILABLE
                    else -> Availability.RECORD_ONLY
                }
                if (media != null) results.removeAll { r -> r.file === media }
                results += RecoveryResult(
                    it.text.ifBlank { it.type.label }.take(140), "${it.network.label} · ${it.type.label}", it.timestamp, k, avail, ls.storage,
                    hits * 10 + (if (k in q.kinds) 5 else 0) + (if (it.network in q.networks) 5 else 0) + (if (avail == Availability.AVAILABLE) 3 else 0),
                    ls, media, it,
                )
            }
        }

        val sorted = when (sort) {
            SortBy.RELEVANCE -> results.sortedWith(compareByDescending<RecoveryResult> { it.score }.thenByDescending { it.date })
            SortBy.DATE -> results.sortedByDescending { it.date }
            SortBy.TYPE -> results.sortedWith(compareBy<RecoveryResult> { it.kind.ordinal }.thenByDescending { it.date })
        }.take(limit)
        return RecoveryOutcome(sorted, explain(lib, q, sorted))
    }

    /** Explicaciones honestas y alternativas reales cuando no hay resultados (o solo registros). */
    private fun explain(lib: Library, q: ParsedQuery, results: List<RecoveryResult>): List<String> {
        val out = ArrayList<String>()
        if (results.any { it.availability == Availability.AVAILABLE || it.availability == Availability.TEXT_AVAILABLE }) {
            if (results.any { it.availability == Availability.RECORD_ONLY }) {
                out += "Algunos resultados son solo registros (fecha y enlace): la red social no incluyó el archivo en su exportación."
            }
            return out
        }
        if (lib.snapshots.isEmpty()) {
            out += "Todavía no tienes ninguna copia. SocialRecover Pro solo puede recuperar lo que se haya guardado antes en una copia."
            return out
        }
        for (n in q.networks) {
            val imported = lib.snapshots.any { ls -> ls.snapshot.social.any { it.network == n } || ls.snapshot.files.any { it.platform == n } }
            if (!imported) out += "No has importado ninguna exportación de ${n.label}. Puedes pedir tu copia oficial desde la app de ${n.label} y traerla aquí desde «Mis redes sociales»."
        }
        if (SocialNetwork.TIKTOK in q.networks && LostKind.VIDEO in q.kinds) {
            out += "La exportación oficial de TikTok incluye los enlaces de tus vídeos, pero no los archivos. Si el vídeo sigue publicado, puedes descargarlo desde la propia app."
        }
        val first = lib.snapshots.minOf { it.ref.createdAt }
        val q0 = q.to
        if (q0 != null && q0.isBefore(lib.date(first))) {
            out += "Tu primera copia es del ${lib.date(first).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))}. Lo que se perdió antes de esa fecha no llegó a protegerse."
        }
        if (q.deviceKind == com.socialrecover.core.vault.DeviceKind.COMPUTER && lib.snapshots.none { it.snapshot.deviceKind == com.socialrecover.core.vault.DeviceKind.COMPUTER }) {
            out += "No hay copias de ningún ordenador. Instala SocialRecover Pro en el ordenador para protegerlo a partir de ahora."
        }
        if (results.any { it.availability == Availability.DATA_MISSING }) {
            out += "Hemos encontrado el archivo en el índice de una copia, pero sus datos ya no están (copia dañada o datos borrados). Prueba con otra fecha en la Máquina del tiempo."
        }
        if (results.isNotEmpty() && results.all { it.availability == Availability.RECORD_ONLY }) {
            out += "Solo existen registros de actividad (fecha y enlace). El contenido no estaba en la exportación, así que no se puede restaurar desde aquí."
        }
        out += "Revisa la papelera o la sección «Eliminado recientemente» del dispositivo o de la aplicación: muchas la conservan durante un tiempo limitado."
        out += "Prueba con menos palabras o sin fecha, o busca en la Máquina del tiempo por día."
        return out
    }

    /** Fecha → rango del día completo (útil para la interfaz). */
    fun day(d: LocalDate) = ParsedQuery(from = d, to = d)
}
