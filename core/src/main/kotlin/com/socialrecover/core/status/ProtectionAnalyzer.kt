package com.socialrecover.core.status

import com.socialrecover.core.model.Category
import com.socialrecover.core.vault.Snapshot
import com.socialrecover.core.vault.StorageKind

/** Una carpeta o categoría que el usuario ha pedido supervisar (solo ubicaciones que él ha autorizado). */
data class WatchedSource(
    val id: String,
    val label: String,
    val category: Category,
    val lastSuccessAt: Long?,
    val lastAttemptAt: Long?,
    val lastAttemptOk: Boolean?,
    /** Archivos nuevos o modificados desde la última copia correcta (según el último escaneo). */
    val pendingChanges: Int,
    /** false si la app ha perdido el permiso o la unidad no está conectada. */
    val accessible: Boolean,
)

data class StorageStatus(
    val kind: StorageKind,
    val name: String,
    val freeBytes: Long?,
    val totalBytes: Long?,
    val usedBytes: Long?,
    val quotaBytes: Long?,
    val lastSyncAt: Long?,
    val lastSyncError: String?,
)

data class VaultHealth(
    val lastCheckAt: Long?,
    val lastCheckOk: Boolean?,
    /** Copias que empezaron y no terminaron (marcas de bloqueo antiguas). */
    val interruptedRuns: Int,
    val damagedSnapshots: Int,
)

data class MonitorSettings(
    val staleDays: Int = 7,
    val verifyEveryDays: Int = 30,
    val lowSpacePercent: Int = 10,
    val lowSpaceBytes: Long = 1L shl 30,
    val quotaWarnPercent: Int = 90,
    val monitored: Set<Category> = Category.entries.toSet(),
    val notificationsEnabled: Boolean = true,
    /** Cada cuántas horas se comprueba en segundo plano (Android: mínimo 15 min por WorkManager; usamos horas). */
    val checkEveryHours: Int = 24,
)

enum class Severity { INFO, WARNING, CRITICAL }

enum class AlertAction(val label: String) {
    BACKUP_NOW("Hacer copia ahora"), REAUTHORIZE("Volver a elegir la carpeta"), FREE_SPACE("Gestionar espacio"),
    VERIFY("Comprobar copias"), RETRY_SYNC("Reintentar sincronización"), REVIEW("Revisar"), NONE(""),
}

data class ProtectionAlert(
    val code: String,
    val severity: Severity,
    val title: String,
    val message: String,
    val action: AlertAction,
    val sourceId: String? = null,
)

enum class ProtectionLevel(val label: String) { PROTECTED("Protegido"), ATTENTION("Requiere atención"), AT_RISK("En riesgo") }

data class ProtectionReport(val level: ProtectionLevel, val alerts: List<ProtectionAlert>)

/**
 * Detector inteligente de archivos desprotegidos. Función pura: recibe el estado conocido
 * (que la app obtiene SOLO de las ubicaciones autorizadas) y devuelve avisos comprensibles.
 */
object ProtectionAnalyzer {
    private const val DAY = 86_400_000L

    private fun yours(c: Category) = when (c) {
        Category.PHOTOS -> "Tus fotografías seleccionadas"
        Category.VIDEOS -> "Tus vídeos seleccionados"
        Category.DOCUMENTS -> "Tus documentos"
        Category.DOWNLOADS -> "Tus descargas"
        Category.SOCIAL_EXPORTS -> "Tus exportaciones de redes sociales"
        Category.MUSIC -> "Tu música"
        else -> "Tus archivos de «${c.label}»"
    }

    private fun yoursLower(c: Category) = yours(c).replaceFirstChar { it.lowercase() }.replace("Tus ", "tus ").replace("Tu ", "tu ")

    fun analyze(
        now: Long,
        sources: List<WatchedSource>,
        storages: List<StorageStatus>,
        health: VaultHealth,
        settings: MonitorSettings = MonitorSettings(),
    ): ProtectionReport {
        val a = ArrayList<ProtectionAlert>()
        for (s in sources.filter { it.category in settings.monitored }) {
            if (!s.accessible) {
                a += ProtectionAlert("NO_ACCESS", Severity.CRITICAL, "Sin acceso a «${s.label}»",
                    "SocialRecover Pro ya no puede leer «${s.label}» (permiso retirado o unidad desconectada). Mientras tanto no se está protegiendo.",
                    AlertAction.REAUTHORIZE, s.id)
                continue
            }
            if (s.lastAttemptOk == false && (s.lastSuccessAt == null || (s.lastAttemptAt ?: 0) > s.lastSuccessAt)) {
                a += ProtectionAlert("LAST_FAILED", Severity.WARNING, "Copia no completada",
                    "La última copia de ${yoursLower(s.category)} no se completó.", AlertAction.BACKUP_NOW, s.id)
            }
            val since = s.lastSuccessAt
            if (since == null) {
                a += ProtectionAlert("NEVER", Severity.CRITICAL, "Sin copia todavía",
                    "${yours(s.category)} (${s.label}) todavía no tienen ninguna copia de seguridad.", AlertAction.BACKUP_NOW, s.id)
            } else {
                val days = ((now - since) / DAY).toInt()
                if (days >= settings.staleDays) {
                    a += ProtectionAlert("STALE", if (days >= settings.staleDays * 4) Severity.CRITICAL else Severity.WARNING, "Copia antigua",
                        "${yours(s.category)} llevan $days días sin una copia de seguridad correcta.", AlertAction.BACKUP_NOW, s.id)
                }
            }
            if (s.pendingChanges > 0) {
                a += ProtectionAlert("PENDING", Severity.INFO, "Cambios sin proteger",
                    "Hay ${s.pendingChanges} archivo(s) nuevos o modificados en «${s.label}» desde la última copia.", AlertAction.BACKUP_NOW, s.id)
            }
        }
        for (st in storages) {
            if (st.lastSyncError != null) {
                a += ProtectionAlert("SYNC_ERROR", Severity.WARNING, "Error de sincronización",
                    "No se pudo sincronizar con ${st.name}: ${st.lastSyncError}", AlertAction.RETRY_SYNC)
            }
            val free = st.freeBytes; val total = st.totalBytes
            if (free != null && (free < settings.lowSpaceBytes || total != null && total > 0 && free * 100 / total < settings.lowSpacePercent)) {
                a += ProtectionAlert("LOW_SPACE", if (free < settings.lowSpaceBytes / 4) Severity.CRITICAL else Severity.WARNING,
                    "Poco espacio", "Tu almacenamiento está casi lleno (${st.name}). Las próximas copias pueden fallar.", AlertAction.FREE_SPACE)
            }
            val used = st.usedBytes; val quota = st.quotaBytes
            if (used != null && quota != null && quota > 0 && used * 100 / quota >= settings.quotaWarnPercent) {
                a += ProtectionAlert("QUOTA", if (used >= quota) Severity.CRITICAL else Severity.WARNING, "Nube casi llena",
                    "Has usado el ${used * 100 / quota} % de tu espacio en la nube. Puedes liberar versiones antiguas o ampliar el plan.", AlertAction.FREE_SPACE)
            }
        }
        if (health.interruptedRuns > 0) {
            a += ProtectionAlert("INCOMPLETE", Severity.WARNING, "Copias incompletas",
                "${health.interruptedRuns} copia(s) se interrumpieron sin terminar. La próxima copia aprovechará lo ya guardado.", AlertAction.BACKUP_NOW)
        }
        if (health.damagedSnapshots > 0 || health.lastCheckOk == false) {
            a += ProtectionAlert("INTEGRITY", Severity.CRITICAL, "Problema de integridad",
                "La última comprobación encontró datos dañados en tus copias. Haz una copia nueva y revisa el informe.", AlertAction.VERIFY)
        }
        val lc = health.lastCheckAt
        if (lc == null || now - lc > settings.verifyEveryDays * DAY) {
            a += ProtectionAlert("UNVERIFIED", Severity.INFO, "Copias sin verificar",
                if (lc == null) "Tus copias aún no se han verificado nunca. Comprobarlas confirma que se pueden restaurar."
                else "Hace ${(now - lc) / DAY} días que no se verifican tus copias.", AlertAction.VERIFY)
        }
        val sorted = a.sortedBy { -it.severity.ordinal }
        val level = when {
            sorted.any { it.severity == Severity.CRITICAL } -> ProtectionLevel.AT_RISK
            sorted.any { it.severity == Severity.WARNING } -> ProtectionLevel.ATTENTION
            else -> ProtectionLevel.PROTECTED
        }
        return ProtectionReport(level, sorted)
    }
}

/** Archivo tal como está ahora en una carpeta autorizada (escaneo sin leer contenido). */
data class ListedFile(val path: String, val size: Long, val lastModified: Long)

data class ChangeSummary(val added: Int, val modified: Int, val removed: Int) {
    val pending get() = added + modified
}

/** Compara el contenido actual de una carpeta con la última copia (solo nombres, tamaños y fechas). */
object ChangeScanner {
    fun compare(current: List<ListedFile>, last: Snapshot?, folderPrefix: String = ""): ChangeSummary {
        if (last == null) return ChangeSummary(current.size, 0, 0)
        val prev = last.files.filter { folderPrefix.isEmpty() || it.path.startsWith(folderPrefix) }.associateBy { it.path }
        var added = 0; var modified = 0
        for (f in current) {
            val p = prev[f.path]
            if (p == null) added++ else if (p.size != f.size || p.lastModified != f.lastModified) modified++
        }
        val cur = current.map { it.path }.toSet()
        return ChangeSummary(added, modified, prev.keys.count { it !in cur })
    }
}
