package com.socialrecover.pro.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.socialrecover.core.engine.BackupEngine
import com.socialrecover.core.engine.BackupResult
import com.socialrecover.core.engine.BackupSummary
import com.socialrecover.core.engine.OpenedBackup
import com.socialrecover.core.engine.Progress
import com.socialrecover.core.engine.RestoreEngine
import com.socialrecover.core.engine.RestoreReport
import com.socialrecover.core.engine.SrpException
import com.socialrecover.core.social.ExportAnalysis
import com.socialrecover.core.social.ExportAnalyzer
import com.socialrecover.core.store.BackupSource
import com.socialrecover.pro.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Punto único de acceso a los motores de copia/restauración desde la interfaz. */
class Repository(private val context: Context) {
    val prefs = Prefs(context)
    private val backupEngine = BackupEngine(appVersion = BuildConfig.VERSION_NAME)
    private val restoreEngine = RestoreEngine()

    val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    fun location(): SafBackupLocation? = prefs.backupTreeUri?.let { SafBackupLocation(context, it) }

    /** ¿Sigue teniendo la app permiso sobre la carpeta de copias? */
    fun hasLocationPermission(): Boolean {
        val uri = prefs.backupTreeUri ?: return false
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
    }

    fun setBackupFolder(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
        prefs.backupTreeUri?.takeIf { it != uri }?.let { old ->
            runCatching { context.contentResolver.releasePersistableUriPermission(old, flags) }
        }
        prefs.backupTreeUri = uri
    }

    fun folderLabel(): String? {
        val uri = prefs.backupTreeUri ?: return null
        // "primary:Documents/Copias" -> "Memoria interna › Documents › Copias"
        val id = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return uri.toString()
        val vol = id.substringBefore(':')
        val path = id.substringAfter(':', "")
        val volLabel = if (vol == "primary") "Memoria interna" else "Almacenamiento externo ($vol)"
        return (listOf(volLabel) + path.split('/').filter { it.isNotBlank() }).joinToString(" › ")
    }

    fun freeDeviceBytes(): Long = runCatching { StatFs(Environment.getDataDirectory().path).availableBytes }.getOrDefault(-1L)

    private fun requireLocation(): SafBackupLocation {
        if (!hasLocationPermission()) throw SrpException.PermissionDenied("la carpeta de copias")
        return location()!!
    }

    fun listBackups(): List<BackupSummary> = restoreEngine.list(requireLocation())

    fun createBackup(
        label: String, password: CharArray, sources: List<BackupSource>,
        onProgress: (Progress) -> Unit, isCancelled: () -> Boolean,
    ): BackupResult {
        val r = backupEngine.create(requireLocation(), label, password, sources, deviceName, onProgress, isCancelled)
        prefs.lastBackupAt = r.manifest.createdAt
        return r
    }

    fun openBackup(dir: String, password: CharArray): OpenedBackup = restoreEngine.open(requireLocation(), dir, password)

    fun restore(
        backup: OpenedBackup, ids: Set<Int>?, destinationTree: Uri?,
        onProgress: (Progress) -> Unit, isCancelled: () -> Boolean,
    ): RestoreReport {
        val target = destinationTree?.let {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.ROOT).format(Date())
            SafRestoreTarget(context, it, "SocialRecover - Restaurado $stamp")
        }
        return restoreEngine.restore(requireLocation(), backup, ids, target, true, onProgress, isCancelled)
    }

    fun deleteBackup(dir: String) = restoreEngine.delete(requireLocation(), dir)

    fun analyzeExport(uri: Uri): ExportAnalysis =
        (context.contentResolver.openInputStream(uri) ?: throw SrpException.SourceUnreadable("el archivo ZIP"))
            .use { ExportAnalyzer.analyze(it.buffered()) }
}
