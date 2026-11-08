package com.socialrecover.core.vault

import com.socialrecover.core.engine.RestoreEngine
import com.socialrecover.core.engine.SrpException
import com.socialrecover.core.model.Category
import com.socialrecover.core.store.BackupLocation
import com.socialrecover.core.store.BackupSource
import java.io.InputStream

/**
 * Migración de copias de la versión 1 (una carpeta por copia, cada una con su contraseña) a la bóveda.
 * La copia original NO se borra: el usuario decide después si eliminarla.
 * La instantánea conserva la fecha original, de modo que aparece en el día correcto de la Máquina del tiempo.
 */
object LegacyMigrator {
    data class Result(val snapshot: Snapshot, val files: Int)

    fun migrate(location: BackupLocation, dirName: String, legacyPassword: CharArray, vault: Vault, deviceKind: DeviceKind = DeviceKind.UNKNOWN): Result {
        val engine = RestoreEngine()
        engine.open(location, dirName, legacyPassword).use { old ->
            val m = old.manifest
            if (vault.listSnapshots().any { runCatching { vault.loadSnapshot(it.name).origin }.getOrNull() == "v1:$dirName" }) {
                throw IllegalStateException("Esta copia ya se migró a la bóveda")
            }
            val session = VaultBackupSession(vault, m.label, m.deviceName, deviceKind, parent = null,
                createdAt = m.createdAt, origin = "v1:$dirName", totalHint = m.totalBytes)
            try {
                engine.forEachEntry(location, old) { entry, stream ->
                    session.add(object : BackupSource {
                        override val displayName = entry.name
                        override val folder = entry.folder
                        override val mimeType = entry.mimeType
                        override val sizeHint: Long = entry.size
                        override val lastModified = entry.lastModified
                        override val category: Category = entry.category
                        override fun open(): InputStream = stream
                    })
                }
                val r = session.finish()
                // Verificación cruzada: cada archivo migrado debe tener la misma huella que en la copia original.
                val want = m.entries.map { it.sha256.joinToString("") { b -> "%02x".format(b) } }.sorted()
                val got = r.snapshot.files.map { it.sha256Hex }.sorted()
                if (want != got) throw SrpException.CorruptedBackup("Las huellas migradas no coinciden con la copia original")
                return Result(r.snapshot, r.snapshot.files.size)
            } catch (e: Throwable) {
                session.abort(); throw e
            }
        }
    }
}
