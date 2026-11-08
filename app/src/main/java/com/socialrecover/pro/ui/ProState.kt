package com.socialrecover.pro.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.socialrecover.core.engine.EntryResult
import com.socialrecover.core.engine.Progress
import com.socialrecover.core.engine.RestoreReport
import com.socialrecover.core.engine.SrpException
import com.socialrecover.core.model.Category
import com.socialrecover.core.recovery.LostKind
import com.socialrecover.core.recovery.QueryParser
import com.socialrecover.core.recovery.RecoveryOutcome
import com.socialrecover.core.social.SocialExport
import com.socialrecover.core.social.SocialHit
import com.socialrecover.core.social.SocialImport
import com.socialrecover.core.social.SocialNetwork
import com.socialrecover.core.social.SocialQuery
import com.socialrecover.core.social.SocialSearch
import com.socialrecover.core.status.MonitorSettings
import com.socialrecover.core.status.ProtectionReport
import com.socialrecover.core.vault.FileVersion
import com.socialrecover.core.vault.HybridPolicy
import com.socialrecover.core.vault.Library
import com.socialrecover.core.vault.LoadedSnapshot
import com.socialrecover.core.vault.Retention
import com.socialrecover.core.vault.RetentionPolicy
import com.socialrecover.core.vault.SnapshotFile
import com.socialrecover.core.vault.TimeFilter
import com.socialrecover.core.vault.Usage
import com.socialrecover.core.vault.VersionComparison
import com.socialrecover.core.vault.Versions
import com.socialrecover.core.vault.VaultRestore
import com.socialrecover.pro.data.SafRestoreTarget
import com.socialrecover.pro.data.UriSource
import com.socialrecover.pro.data.VaultService
import com.socialrecover.pro.work.ProtectionWorker
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.YearMonth
import java.util.Arrays
import java.util.Date
import java.util.Locale

/**
 * Estado y acciones del Módulo Premium (bóveda, Máquina del tiempo, redes sociales, detector,
 * Nube inteligente y Recuperación guiada). Separado de AppViewModel para mantener los módulos independientes.
 */
class ProState(private val app: Application, private val ops: Ops) {
    /** Lo que ProState necesita de AppViewModel: ejecutar operaciones largas y mostrar mensajes. */
    interface Ops {
        fun <T> op(title: String, cancellable: Boolean, work: (onProgress: (Progress) -> Unit, isCancelled: () -> Boolean) -> T, done: (T) -> Unit)
        fun msg(title: String, text: String, error: Boolean = false)
    }

    val svc = VaultService(app)

    var unlocked by mutableStateOf(false); private set
    var showUnlock by mutableStateOf(false)
    var library by mutableStateOf<Library?>(null); private set
    var protection by mutableStateOf<ProtectionReport?>(null); private set
    var usage by mutableStateOf<Usage?>(null); private set
    var lastRestore by mutableStateOf<RestoreReport?>(null)
    var localConfigured by mutableStateOf(svc.localTree != null); private set
    var cloudConfigured by mutableStateOf(svc.cloudToken != null); private set

    // ---------------------------------------------------------------- bóveda

    fun setLocalFolder(uri: Uri) {
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        app.contentResolver.takePersistableUriPermission(uri, flags)
        svc.localTree = uri; localConfigured = true
        svc.lock(); unlocked = false
    }

    fun unlock(pw: CharArray, remember: Boolean) {
        ops.op("Abriendo tu bóveda cifrada…", false, { _, _ ->
            try {
                val create = !(svc.localExists() || svc.cloudExists())
                svc.autoBackup = remember
                svc.unlock(pw, createIfMissing = true)
                if (!remember) svc.forgetKeys()
                create
            } finally { Arrays.fill(pw, '\u0000') }
        }) { created ->
            unlocked = svc.unlocked; showUnlock = false
            if (pendingShared.isNotEmpty()) { val u = pendingShared.toList(); pendingShared.clear(); importExports(u, null) }
            if (created) ops.msg("Bóveda creada", "Tu bóveda cifrada está lista. Recuerda la contraseña: nadie puede recuperarla por ti.")
            refreshLibrary()
            if (remember) ProtectionWorker.schedule(app, svc.monitor.checkEveryHours.toLong())
        }
    }

    fun lock() { svc.lock(); unlocked = false; library = null }

    fun refreshLibrary() {
        if (!svc.unlocked) return
        ops.op("Leyendo tus copias…", false, { _, _ -> svc.invalidate(); svc.library().also { it.snapshots } }) { library = it }
    }

    // ---------------------------------------------------------------- copia

    fun backup(sources: List<UriSource>, label: String, after: () -> Unit) {
        ops.op("Creando copia cifrada…", true, { p, c -> svc.backup(sources, label, p, c) }) { r ->
            val parts = ArrayList<String>()
            r.local?.let { parts += "Local: ${it.snapshot.stats.filesTotal} archivos (${it.snapshot.stats.filesUnchanged} sin cambios, ${Format.size(app, it.snapshot.stats.bytesStored)} nuevos)" }
            r.cloud?.let { parts += "Nube: ${it.snapshot.files.size} archivos (${Format.size(app, it.snapshot.stats.bytesStored)} subidos)" }
            val dups = (r.local ?: r.cloud)?.duplicates.orEmpty()
            if (dups.isNotEmpty()) parts += "Duplicados detectados: ${dups.size} (se guardan una sola vez)."
            r.localError?.let { parts += "Error local: ${it.userMessage}" }
            r.cloudError?.let { parts += "Nube: ${it.userMessage} La copia local es válida." }
            ops.msg(if (r.localError == null) "Copia creada" else "Copia con problemas", parts.joinToString("\n"), r.localError != null)
            refreshLibrary(); after()
        }
    }

    // ---------------------------------------------------------------- Proteger todo con un botón

    /** Archivos de exportación recibidos con «Abrir con» / «Compartir» mientras la bóveda estaba cerrada. */
    val pendingShared = mutableStateListOf<Uri>()

    /** Cambia cada vez que se pide o importa una exportación (para refrescar la lista de redes). */
    var netTick by mutableStateOf(0); private set

    fun requestExport(n: SocialNetwork) { svc.markRequested(n); netTick++ }

    fun receiveShared(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (svc.unlocked) importExports(uris, null) else { pendingShared.clear(); pendingShared += uris; showUnlock = true }
    }

    /** Redes cuya última exportación importada tiene más de [days] días (o nunca se importó). */
    fun staleNetworks(days: Int = 60): List<SocialNetwork> {
        val limit = System.currentTimeMillis() - days * 86_400_000L
        return SocialNetwork.entries.filter { it != SocialNetwork.OTHER }.filter { svc.networkRecord(it).second in 1 until limit }
    }

    /**
     * «Proteger todo ahora»: copia incremental de TODAS las carpetas autorizadas (local y/o nube según la
     * Nube inteligente) y recordatorio de las redes cuya exportación está desactualizada.
     */
    fun protectAll(onNoFolders: () -> Unit) {
        if (svc.watched.isEmpty()) { onNoFolders(); return }
        if (!svc.unlocked) { showUnlock = true; return }
        ops.op("Protegiendo todo…", true, { p, c -> svc.backupWatched(p, c) }) { res ->
            val bad = res.filter { it.second != null }
            val stale = staleNetworks()
            val text = buildString {
                append(if (bad.isEmpty()) "Se han copiado tus ${res.size} carpeta(s). Solo se ha guardado lo nuevo o modificado." else "Copiadas ${res.size - bad.size} de ${res.size} carpetas.")
                bad.forEach { append("\n• ${it.first.label}: ${(it.second as? SrpException)?.userMessage ?: it.second?.message}") }
                if (stale.isNotEmpty()) append("\n\nTus copias de ${stale.joinToString { it.label }} tienen más de 2 meses. Pide una nueva en «Mis redes sociales».")
                if (svc.autoBackup) append("\n\nLas copias automáticas diarias están activadas.")
            }
            ops.msg(if (bad.isEmpty()) "Todo protegido" else "Protección incompleta", text, bad.isNotEmpty())
            refreshLibrary()
        }
    }

    // ---------------------------------------------------------------- Máquina del tiempo

    var month by mutableStateOf(YearMonth.now())
    var day by mutableStateOf<LocalDate?>(null)
    var filter by mutableStateOf(TimeFilter())

    fun restore(items: List<Pair<LoadedSnapshot, SnapshotFile>>, dest: Uri) {
        if (items.isEmpty()) return
        ops.op("Restaurando y verificando…", true, { p, c ->
            val stamp = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.ROOT).format(Date())
            val target = SafRestoreTarget(app, dest, "SocialRecover - Restaurado $stamp")
            val all = ArrayList<EntryResult>(); var fatal: SrpException? = null
            for ((ls, files) in items.groupBy({ it.first }, { it.second })) {
                val r = VaultRestore.restore(ls.vault, ls.snapshot, files, target, true, p, c)
                all += r.results; if (r.fatal != null) { fatal = r.fatal; break }
            }
            RestoreReport(all, fatal)
        }) { r ->
            lastRestore = r
            ops.msg(if (r.allOk) "Recuperación completada" else "Recuperación con problemas",
                if (r.allOk) "${r.okCount} archivo(s) recuperados. Cada uno se ha comprobado byte a byte con su huella SHA-256."
                else "${r.okCount} recuperados correctamente, ${r.failedCount} con problemas.\n" +
                    r.results.filter { !it.ok }.take(5).joinToString("\n") { "• ${it.entry.name}: ${it.message ?: ""}" } + (r.fatal?.let { "\n${it.userMessage}" } ?: ""),
                !r.allOk)
        }
    }

    // ---------------------------------------------------------------- versiones

    var versionPath by mutableStateOf<String?>(null)
    var versions by mutableStateOf<List<FileVersion>>(emptyList()); private set
    var comparison by mutableStateOf<VersionComparison?>(null); private set

    fun openVersions(path: String) {
        val lib = library ?: return
        versionPath = path
        versions = Versions.history(lib.snapshots.map { it.ref to it.snapshot }, path)
        comparison = null
    }

    fun compare(a: FileVersion, b: FileVersion) {
        val lib = library ?: return
        val vault = lib.snapshots.first { it.ref == b.snapshot }.vault
        ops.op("Comparando versiones…", false, { _, _ -> Versions.compare(vault, a, b) }) { comparison = it }
    }

    // ---------------------------------------------------------------- redes sociales

    var socialQuery by mutableStateOf(SocialQuery())
    val socialResults = mutableStateListOf<SocialHit>()

    fun importExports(uris: List<Uri>, expected: SocialNetwork?) {
        if (uris.isEmpty()) return
        val parts = uris.map { u -> (runCatching { UriSource.from(app, u).displayName }.getOrDefault("exportación.zip")) to u }
        ops.op("Importando y cifrando la exportación…", true, { p, c -> svc.importSocial(parts, expected, p, c) }) { results ->
            netTick++
            val r = results.firstOrNull() ?: return@op
            val counts = r.countsByType.entries.sortedByDescending { it.value }.joinToString("\n") { "• ${it.key.label}: ${it.value}" }
            ops.msg("Importación de ${r.network.label} completada",
                "Se han guardado ${r.snapshot.files.size} archivos cifrados y se han encontrado ${r.items.size} elementos:\n$counts" +
                    (if (r.warnings.isNotEmpty()) "\n\nAvisos:\n" + r.warnings.take(4).joinToString("\n") { "• $it" } else "") +
                    (if (results.size > 1) "\n\nGuardada también en la nube." else ""))
            refreshLibrary()
        }
    }

    fun searchSocial() {
        val lib = library ?: return
        socialResults.clear()
        socialResults += SocialSearch.search(lib.allSocial().map { it.second }, socialQuery)
    }

    fun exportResults(dest: Uri, html: Boolean) {
        val items = socialResults.map { it.item }
        ops.op("Exportando resultados…", false, { _, _ ->
            val text = if (html) SocialExport.toHtml(items, "Mis recuerdos de redes sociales") else SocialExport.toCsv(items)
            app.contentResolver.openOutputStream(dest, "w")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ?: throw SrpException.Storage(null)
        }) { ops.msg("Resultados exportados", "Se han exportado ${items.size} elementos.") }
    }

    fun mediaFor(hit: SocialHit): Pair<LoadedSnapshot, SnapshotFile>? {
        val lib = library ?: return null
        return lib.allSocial().firstOrNull { it.second == hit.item }?.let { (ls, item) -> lib.mediaFor(ls, item)?.let { f -> ls to f } }
    }

    // ---------------------------------------------------------------- Recuperación guiada

    var guidedStep by mutableStateOf(1)
    var guidedKind by mutableStateOf<LostKind?>(null)
    var guidedText by mutableStateOf("")
    var guidedOutcome by mutableStateOf<RecoveryOutcome?>(null); private set
    var guidedUnderstood by mutableStateOf<List<String>>(emptyList()); private set

    fun guidedSearch() {
        val q = QueryParser.parse(guidedText, selectedKind = guidedKind)
        guidedUnderstood = q.understood
        if (!svc.unlocked) { showUnlock = true; return }
        ops.op("Buscando en tus copias…", false, { _, _ -> svc.search(q) }) { guidedOutcome = it; guidedStep = 2 }
    }

    fun resetGuided() { guidedStep = 1; guidedKind = null; guidedText = ""; guidedOutcome = null }

    // ---------------------------------------------------------------- Estado de protección

    fun refreshProtection(scan: Boolean) {
        ops.op(if (scan) "Revisando tus carpetas…" else "Calculando…", false, { _, _ -> svc.protection(scan) }) { protection = it }
    }

    fun addWatched(uri: Uri, cat: Category) {
        runCatching { svc.addWatched(uri, cat) }
        if (svc.autoBackup) ProtectionWorker.schedule(app, svc.monitor.checkEveryHours.toLong())
        refreshProtection(false)
    }
    fun removeWatched(id: String) { svc.removeWatched(id); refreshProtection(false) }
    fun saveMonitor(m: MonitorSettings) { svc.monitor = m; ProtectionWorker.schedule(app, m.checkEveryHours.toLong()) }

    fun backupWatchedNow() {
        ops.op("Copiando carpetas supervisadas…", true, { p, c -> svc.backupWatched(p, c) }) { res ->
            val bad = res.filter { it.second != null }
            ops.msg(if (bad.isEmpty()) "Carpetas protegidas" else "Algunas carpetas no se copiaron",
                if (bad.isEmpty()) "Se han copiado ${res.size} carpeta(s)."
                else bad.joinToString("\n") { "• ${it.first.label}: ${(it.second as? SrpException)?.userMessage ?: it.second?.message}" }, bad.isNotEmpty())
            refreshLibrary(); refreshProtection(false)
        }
    }

    fun checkNow(full: Boolean) {
        ops.op("Comprobando la integridad…", true, { p, c -> svc.check(full, p, c) }) { reps ->
            val ok = reps.all { it.ok }
            ops.msg(if (ok) "Copias verificadas" else "Se han encontrado problemas",
                if (ok) "Todas tus copias se pueden restaurar correctamente."
                else reps.flatMap { r -> r.affected.values.flatten() }.distinct().take(10).joinToString("\n") { "• $it" } +
                    "\n\nEstos archivos no se pueden recuperar de esas copias. Haz una copia nueva cuanto antes.", !ok)
            refreshProtection(false)
        }
    }

    // ---------------------------------------------------------------- Nube inteligente

    var policy by mutableStateOf(svc.policy); private set
    var retention by mutableStateOf(svc.retention); private set

    fun savePolicy(p: HybridPolicy) { svc.policy = p; policy = p }

    fun cloudSignIn(email: String, password: String, totp: String?, register: Boolean) {
        ops.op(if (register) "Creando tu cuenta…" else "Iniciando sesión…", false, { _, _ ->
            if (register) svc.cloudRegister(email, password) else svc.cloudLogin(email, password, totp)
        }) {
            cloudConfigured = true; svc.lock(); unlocked = false; showUnlock = true
            ops.msg("Nube conectada", "Ahora abre tu bóveda con su contraseña para empezar a sincronizar. La contraseña de la bóveda nunca se envía a la nube.")
        }
    }

    fun cloudSignOut() { svc.cloudLogout(); cloudConfigured = false; usage = null; refreshLibrary() }

    fun deleteCloudAccount() {
        ops.op("Eliminando tu cuenta y tus datos de la nube…", false, { _, _ -> svc.cloudDeleteAccount() }) {
            cloudConfigured = false; usage = null
            ops.msg("Cuenta eliminada", "Tu cuenta y todas tus copias en la nube se han eliminado. Tus copias locales siguen en tu dispositivo.")
        }
    }

    fun refreshUsage() { ops.op("Consultando espacio…", false, { _, _ -> svc.cloudUsage() }) { usage = it } }

    /** Guarda la política y, si el usuario lo confirma, la aplica (borra versiones fuera de la política). */
    fun applyRetention(r: RetentionPolicy, confirmed: Boolean) {
        svc.retention = r; retention = r
        if (!confirmed || r.keepAll) return
        ops.op("Aplicando la política de conservación…", false, { _, _ ->
            listOfNotNull(svc.local, svc.cloud).map { v -> Retention.prune(v, Retention.plan(v.listSnapshots(), r), confirmedByUser = true) }
        }) { res ->
            ops.msg("Espacio liberado", "Se han eliminado ${res.sumOf { it.snapshotsRemoved }} versiones antiguas y liberado ${Format.size(app, res.sumOf { it.bytesFreed })}.")
            refreshLibrary()
        }
    }

    fun previewRetention(r: RetentionPolicy): Int =
        listOfNotNull(svc.local, svc.cloud).sumOf { Retention.plan(it.listSnapshots(), r).remove.size }
}
