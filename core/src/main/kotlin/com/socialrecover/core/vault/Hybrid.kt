package com.socialrecover.core.vault

import com.socialrecover.core.engine.Progress
import com.socialrecover.core.engine.SrpException
import com.socialrecover.core.model.Category
import com.socialrecover.core.store.BackupSource

enum class Destination(val label: String) { LOCAL("Solo local"), CLOUD("Solo nube"), BOTH("Local y nube") }

/**
 * Preferencias de la Nube inteligente: dónde va cada categoría, y un límite de tamaño para la nube
 * (p. ej. "los vídeos grandes se quedan en local, los documentos importantes se sincronizan").
 */
data class HybridPolicy(
    val byCategory: Map<Category, Destination> = emptyMap(),
    val default: Destination = Destination.LOCAL,
    /** Archivos mayores que esto no se suben a la nube (0 = sin límite). */
    val maxCloudFileBytes: Long = 0,
) {
    fun destinationFor(src: BackupSource): Destination {
        val d = byCategory[src.category] ?: default
        val size = src.sizeHint ?: 0L
        if (maxCloudFileBytes > 0 && size > maxCloudFileBytes) {
            return when (d) { Destination.CLOUD, Destination.BOTH -> Destination.LOCAL; else -> d }
        }
        return d
    }

    companion object {
        /** Propuesta inicial (el usuario la puede cambiar): documentos y redes a ambos sitios; vídeos > 200 MB solo local. */
        val SUGGESTED = HybridPolicy(
            byCategory = mapOf(
                Category.DOCUMENTS to Destination.BOTH,
                Category.SOCIAL_EXPORTS to Destination.BOTH,
                Category.PHOTOS to Destination.BOTH,
                Category.VIDEOS to Destination.LOCAL,
                Category.PASSWORD_EXPORTS to Destination.LOCAL,
            ),
            default = Destination.LOCAL,
            maxCloudFileBytes = 200L shl 20,
        )
    }
}

data class HybridResult(
    val local: VaultBackupResult?,
    val cloud: VaultBackupResult?,
    val localError: SrpException?,
    /** Error de sincronización con la nube: la copia local sigue siendo válida. */
    val cloudError: SrpException?,
    val routedLocal: Int,
    val routedCloud: Int,
)

/**
 * Copia en modo local, nube o híbrido. Cada destino es una bóveda independiente: si la nube falla
 * (sin conexión, cuota llena), la copia local se completa igualmente y el error se informa al usuario.
 */
object HybridBackup {
    fun run(
        local: Vault?, cloud: Vault?, sources: List<BackupSource>, policy: HybridPolicy,
        label: String, device: String, deviceKind: DeviceKind,
        onProgress: (Progress) -> Unit = {}, isCancelled: () -> Boolean = { false },
        createdAt: Long = System.currentTimeMillis(),
    ): HybridResult {
        val toLocal = ArrayList<BackupSource>(); val toCloud = ArrayList<BackupSource>()
        for (s in sources) {
            when (policy.destinationFor(s)) {
                Destination.LOCAL -> (if (local != null) toLocal else toCloud).add(s)
                Destination.CLOUD -> (if (cloud != null) toCloud else toLocal).add(s)
                Destination.BOTH -> { if (local != null) toLocal += s; if (cloud != null) toCloud += s }
            }
        }
        var lr: VaultBackupResult? = null; var le: SrpException? = null
        var cr: VaultBackupResult? = null; var ce: SrpException? = null
        if (local != null && toLocal.isNotEmpty()) {
            try { lr = VaultBackupSession.run(local, toLocal, label, device, deviceKind, onProgress, isCancelled, createdAt) }
            catch (e: SrpException) { if (e is SrpException.Cancelled) throw e; le = e }
        }
        if (cloud != null && toCloud.isNotEmpty()) {
            try { cr = VaultBackupSession.run(cloud, toCloud, label, device, deviceKind, onProgress, isCancelled, createdAt) }
            catch (e: SrpException) { if (e is SrpException.Cancelled) throw e; ce = e }
        }
        return HybridResult(lr, cr, le, ce, toLocal.size, toCloud.size)
    }
}
