package com.socialrecover.pro.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import com.socialrecover.core.cloud.CloudAccount
import com.socialrecover.core.cloud.HttpRepoBackend
import com.socialrecover.core.engine.Progress
import com.socialrecover.core.engine.SrpException
import com.socialrecover.core.model.Category
import com.socialrecover.core.recovery.GuidedRecovery
import com.socialrecover.core.recovery.ParsedQuery
import com.socialrecover.core.recovery.RecoveryOutcome
import com.socialrecover.core.social.SocialImport
import com.socialrecover.core.social.SocialNetwork
import com.socialrecover.core.status.ChangeScanner
import com.socialrecover.core.status.ListedFile
import com.socialrecover.core.status.MonitorSettings
import com.socialrecover.core.status.ProtectionAnalyzer
import com.socialrecover.core.status.ProtectionReport
import com.socialrecover.core.status.StorageStatus
import com.socialrecover.core.status.VaultHealth
import com.socialrecover.core.status.WatchedSource
import com.socialrecover.core.store.BackupSource
import com.socialrecover.core.vault.CheckReport
import com.socialrecover.core.vault.DeviceKind
import com.socialrecover.core.vault.Destination
import com.socialrecover.core.vault.HybridBackup
import com.socialrecover.core.vault.HybridPolicy
import com.socialrecover.core.vault.HybridResult
import com.socialrecover.core.vault.Library
import com.socialrecover.core.vault.RetentionPolicy
import com.socialrecover.core.vault.StorageKind
import com.socialrecover.core.vault.Usage
import com.socialrecover.core.vault.Vault
import com.socialrecover.core.vault.VaultCheck
import org.json.JSONArray
import org.json.JSONObject

/** Carpeta autorizada por el usuario para supervisión y copia automática. */
data class WatchedFolder(
    val id: String,
    val treeUri: Uri,
    val label: String,
    val category: Category,
    val lastSuccessAt: Long? = null,
    val lastAttemptAt: Long? = null,
    val lastAttemptOk: Boolean? = null,
    val pendingChanges: Int = 0,
)

/**
 * Gestión de las bóvedas (local y nube), carpetas supervisadas, preferencias de la Nube inteligente
 * y del detector de desprotegidos. Todo lo persistente es configuración: ninguna contraseña se guarda.
 */
class VaultService(private val context: Context) {
    private val sp = context.getSharedPreferences("srp_vault", Context.MODE_PRIVATE)
    val keys = DeviceKeys(context)
    val device: String = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
    val deviceKind: DeviceKind =
        if (context.resources.configuration.smallestScreenWidthDp >= 600) DeviceKind.TABLET else DeviceKind.PHONE

    @Volatile var local: Vault? = null; private set
    @Volatile var cloud: Vault? = null; private set
    @Volatile private var library: Library? = null

    // ------------------------------------------------------------------ configuración

    var localTree: Uri?
        get() = sp.getString("local_tree", null)?.let(Uri::parse)
        set(v) { sp.edit().putString("local_tree", v?.toString()).apply() }

    var cloudUrl: String
        get() = sp.getString("cloud_url", DEFAULT_CLOUD_URL)!!
        set(v) { sp.edit().putString("cloud_url", v.trim()).apply() }

    var cloudEmail: String?
        get() = sp.getString("cloud_email", null)
        set(v) { sp.edit().putString("cloud_email", v).apply() }

    val cloudToken: String? get() = keys.get(DeviceKeys.CLOUD_TOKEN)?.toString(Charsets.UTF_8)

    var policy: HybridPolicy
        get() = sp.getString("hybrid_policy", null)?.let { s ->
            val o = JSONObject(s)
            val by = HashMap<Category, Destination>()
            o.optJSONObject("by")?.let { m -> m.keys().forEach { k -> by[Category.fromId(k)] = Destination.valueOf(m.getString(k)) } }
            HybridPolicy(by, Destination.valueOf(o.optString("default", "LOCAL")), o.optLong("maxCloud", 0))
        } ?: HybridPolicy.SUGGESTED
        set(p) {
            val by = JSONObject(); p.byCategory.forEach { (c, d) -> by.put(c.id, d.name) }
            sp.edit().putString("hybrid_policy", JSONObject().put("by", by).put("default", p.default.name).put("maxCloud", p.maxCloudFileBytes).toString()).apply()
        }

    /** null = el usuario aún no ha aceptado ninguna política: se conserva todo. */
    var retention: RetentionPolicy
        get() = sp.getString("retention", null)?.let { s ->
            val o = JSONObject(s)
            RetentionPolicy(o.optInt("last"), o.optInt("daily"), o.optInt("weekly"), o.optInt("monthly"), o.optInt("yearly"), o.optInt("within"), o.optBoolean("all"))
        } ?: RetentionPolicy.KEEP_ALL
        set(r) {
            sp.edit().putString("retention", JSONObject().put("last", r.keepLast).put("daily", r.keepDaily).put("weekly", r.keepWeekly)
                .put("monthly", r.keepMonthly).put("yearly", r.keepYearly).put("within", r.keepWithinDays).put("all", r.keepAll).toString()).apply()
        }

    var monitor: MonitorSettings
        get() = sp.getString("monitor", null)?.let { s ->
            val o = JSONObject(s)
            val cats = o.optJSONArray("cats")?.let { a -> (0 until a.length()).map { Category.fromId(a.getString(it)) }.toSet() } ?: Category.entries.toSet()
            MonitorSettings(staleDays = o.optInt("stale", 7), verifyEveryDays = o.optInt("verify", 30), monitored = cats,
                notificationsEnabled = o.optBoolean("notify", true), checkEveryHours = o.optInt("hours", 24))
        } ?: MonitorSettings()
        set(m) {
            sp.edit().putString("monitor", JSONObject().put("stale", m.staleDays).put("verify", m.verifyEveryDays)
                .put("cats", JSONArray(m.monitored.map { it.id })).put("notify", m.notificationsEnabled).put("hours", m.checkEveryHours).toString()).apply()
        }

    var lastCheckAt: Long
        get() = sp.getLong("last_check", 0); private set(v) { sp.edit().putLong("last_check", v).apply() }
    var lastCheckOk: Boolean
        get() = sp.getBoolean("last_check_ok", true); private set(v) { sp.edit().putBoolean("last_check_ok", v).apply() }
    var lastSyncError: String?
        get() = sp.getString("sync_error", null); private set(v) { sp.edit().putString("sync_error", v).apply() }
    var lastSyncAt: Long
        get() = sp.getLong("sync_at", 0); private set(v) { sp.edit().putLong("sync_at", v).apply() }
    var autoBackup: Boolean
        get() = sp.getBoolean("auto_backup", false); set(v) { sp.edit().putBoolean("auto_backup", v).apply() }

    // ------------------------------------------------------------------ carpetas supervisadas

    var watched: List<WatchedFolder>
        get() = sp.getString("watched", null)?.let { s ->
            val a = JSONArray(s)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                WatchedFolder(o.getString("id"), Uri.parse(o.getString("uri")), o.getString("label"), Category.fromId(o.getString("cat")),
                    o.optLong("ok", 0).takeIf { it > 0 }, o.optLong("try", 0).takeIf { it > 0 },
                    if (o.has("tryOk")) o.getBoolean("tryOk") else null, o.optInt("pending", 0))
            }
        } ?: emptyList()
        set(list) {
            val a = JSONArray()
            list.forEach { w ->
                a.put(JSONObject().put("id", w.id).put("uri", w.treeUri.toString()).put("label", w.label).put("cat", w.category.id)
                    .put("ok", w.lastSuccessAt ?: 0).put("try", w.lastAttemptAt ?: 0).put("pending", w.pendingChanges)
                    .apply { w.lastAttemptOk?.let { put("tryOk", it) } })
            }
            sp.edit().putString("watched", a.toString()).apply()
        }

    fun addWatched(treeUri: Uri, category: Category) {
        context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val label = runCatching { UriSource.fromTree(context, treeUri, category, maxFiles = 1); treeName(treeUri) }.getOrDefault("Carpeta")
        watched = watched.filter { it.treeUri != treeUri } + WatchedFolder("w" + System.currentTimeMillis(), treeUri, label, category)
    }

    fun removeWatched(id: String) {
        val w = watched.firstOrNull { it.id == id } ?: return
        runCatching { context.contentResolver.releasePersistableUriPermission(w.treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        watched = watched.filter { it.id != id }
    }

    private fun treeName(uri: Uri): String {
        val t = SafTree(context.contentResolver, uri)
        return context.contentResolver.query(t.docUri(t.rootDocId), arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "Carpeta"
    }

    fun canRead(uri: Uri) = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    // ------------------------------------------------------------------ bóvedas

    fun localBackend(): SafRepoBackend? = localTree?.let { SafRepoBackend(context, it, "Bóveda local") }
    fun cloudBackend(): HttpRepoBackend? = cloudToken?.let { HttpRepoBackend(cloudUrl, it, "Nube SocialRecover Pro") }

    fun localExists() = runCatching { localBackend()?.let { Vault.exists(it) } }.getOrNull() == true
    fun cloudExists() = runCatching { cloudBackend()?.let { Vault.exists(it) } }.getOrNull() == true

    /** Crea las bóvedas que falten y abre todas las configuradas con la misma contraseña. */
    fun unlock(password: CharArray, createIfMissing: Boolean): Pair<Vault?, Vault?> {
        val lb = localBackend()
        val l = lb?.let { if (Vault.exists(it)) Vault.open(it, password) else if (createIfMissing) Vault.create(it, password) else null }
        val c = try {
            cloudBackend()?.let { if (Vault.exists(it)) Vault.open(it, password) else if (createIfMissing) Vault.create(it, password) else null }
        } catch (e: SrpException.Offline) { lastSyncError = e.userMessage; null }
        local = l; cloud = c; library = null
        if (autoBackup) rememberKeys()
        return l to c
    }

    /** Apertura desatendida para copias automáticas (solo si el usuario lo activó). */
    fun unlockFromDeviceKeys(): Boolean {
        val lk = keys.get(DeviceKeys.VAULT_LOCAL); val ck = keys.get(DeviceKeys.VAULT_CLOUD)
        if (lk == null && ck == null) return false
        local = lk?.let { k -> localBackend()?.let { runCatching { Vault.openWithKey(it, k) }.getOrNull() } }
        cloud = ck?.let { k -> cloudBackend()?.let { runCatching { Vault.openWithKey(it, k) }.getOrNull() } }
        library = null
        return local != null || cloud != null
    }

    fun rememberKeys() {
        local?.let { keys.put(DeviceKeys.VAULT_LOCAL, it.exportMasterKey()) }
        cloud?.let { keys.put(DeviceKeys.VAULT_CLOUD, it.exportMasterKey()) }
    }

    fun forgetKeys() { keys.remove(DeviceKeys.VAULT_LOCAL); keys.remove(DeviceKeys.VAULT_CLOUD) }

    fun lock() { local?.close(); cloud?.close(); local = null; cloud = null; library = null }

    val unlocked get() = local != null || cloud != null

    fun library(): Library = library ?: Library(listOfNotNull(local, cloud)).also { library = it }
    fun invalidate() { library = null; local?.refreshBlobs(); cloud?.refreshBlobs() }

    // ------------------------------------------------------------------ cuenta en la nube

    fun cloudRegister(email: String, password: String) {
        val s = CloudAccount(cloudUrl).register(email, password, device)
        keys.put(DeviceKeys.CLOUD_TOKEN, s.token.toByteArray()); cloudEmail = email
    }

    fun cloudLogin(email: String, password: String, totp: String?) {
        val s = CloudAccount(cloudUrl).login(email, password, device, totp?.takeIf { it.isNotBlank() })
        keys.put(DeviceKeys.CLOUD_TOKEN, s.token.toByteArray()); cloudEmail = email
    }

    fun cloudLogout() { keys.remove(DeviceKeys.CLOUD_TOKEN); keys.remove(DeviceKeys.VAULT_CLOUD); cloud?.close(); cloud = null; library = null }

    fun cloudDeleteAccount() { cloudToken?.let { CloudAccount(cloudUrl).deleteAccount(it) }; cloudLogout(); cloudEmail = null }

    fun cloudUsage(): Usage? = runCatching { cloudBackend()?.usage() }.getOrNull()

    // ------------------------------------------------------------------ operaciones

    fun backup(sources: List<BackupSource>, label: String, onProgress: (Progress) -> Unit, isCancelled: () -> Boolean): HybridResult {
        val r = HybridBackup.run(local, cloud, sources, policy, label, device, deviceKind, onProgress, isCancelled)
        if (r.cloud != null) { lastSyncAt = System.currentTimeMillis(); lastSyncError = null }
        r.cloudError?.let { lastSyncError = it.userMessage }
        library = null
        return r
    }

    /** Copia de las carpetas supervisadas (manual o automática). Registra éxitos y fallos para el detector. */
    fun backupWatched(onProgress: (Progress) -> Unit = {}, isCancelled: () -> Boolean = { false }): List<Pair<WatchedFolder, Throwable?>> {
        val res = ArrayList<Pair<WatchedFolder, Throwable?>>()
        val updated = watched.map { w ->
            val now = System.currentTimeMillis()
            if (!canRead(w.treeUri)) { res += w to SrpException.PermissionDenied(w.label); return@map w.copy(lastAttemptAt = now, lastAttemptOk = false) }
            try {
                val src = UriSource.fromTree(context, w.treeUri, w.category)
                val r = backup(src, "Automática: ${w.label}", onProgress, isCancelled)
                val ok = (r.local != null || local == null) && r.localError == null
                res += w to (r.localError ?: r.cloudError)
                w.copy(lastAttemptAt = now, lastAttemptOk = ok, lastSuccessAt = if (ok) now else w.lastSuccessAt, pendingChanges = if (ok) 0 else w.pendingChanges)
            } catch (e: Exception) {
                res += w to e
                w.copy(lastAttemptAt = now, lastAttemptOk = false)
            }
        }
        watched = updated
        return res
    }

    fun check(readData: Boolean, onProgress: (Progress) -> Unit, isCancelled: () -> Boolean): List<CheckReport> {
        val reports = listOfNotNull(local, cloud).map { VaultCheck.run(it, readData, onProgress, isCancelled) }
        lastCheckAt = System.currentTimeMillis(); lastCheckOk = reports.all { it.ok }
        return reports
    }

    fun importSocial(parts: List<Pair<String, Uri>>, expected: SocialNetwork?, onProgress: (Progress) -> Unit, isCancelled: () -> Boolean): List<SocialImport.Result> {
        val ps = parts.map { (n, u) -> SocialImport.Part(n) { context.contentResolver.openInputStream(u) ?: throw SrpException.SourceUnreadable(n) } }
        val out = ArrayList<SocialImport.Result>()
        val dest = policy.byCategory[Category.SOCIAL_EXPORTS] ?: policy.default
        val targets = listOfNotNull(
            local.takeIf { dest != Destination.CLOUD || cloud == null },
            cloud.takeIf { dest != Destination.LOCAL || local == null },
        )
        for (v in targets) {
            try { out += SocialImport.import(ps, v, device, deviceKind, expected, onProgress = onProgress, isCancelled = isCancelled) }
            catch (e: SrpException.Offline) { lastSyncError = e.userMessage; if (v === local) throw e }
        }
        library = null
        return out
    }

    fun search(q: ParsedQuery): RecoveryOutcome = GuidedRecovery.search(library(), q)

    /** Escanea (sin leer contenido) las carpetas supervisadas y calcula los avisos. */
    fun protection(scan: Boolean): ProtectionReport {
        val lib = runCatching { if (unlocked) library() else null }.getOrNull()
        if (scan) {
            watched = watched.map { w ->
                if (!canRead(w.treeUri)) return@map w
                val last = lib?.snapshots?.lastOrNull { it.snapshot.device == device && it.snapshot.files.any { f -> f.folder.startsWith(w.label) } }?.snapshot
                val listed = runCatching { UriSource.fromTree(context, w.treeUri, w.category).map { ListedFile(it.folder + "/" + it.displayName, it.sizeHint ?: 0, it.lastModified) } }.getOrNull()
                if (listed == null || last == null) w else w.copy(pendingChanges = ChangeScanner.compare(listed, last, w.label).pending)
            }
        }
        val sources = watched.map { WatchedSource(it.id, it.label, it.category, it.lastSuccessAt, it.lastAttemptAt, it.lastAttemptOk, it.pendingChanges, canRead(it.treeUri)) }
        val stat = StatFs(Environment.getDataDirectory().path)
        val storages = ArrayList<StorageStatus>()
        storages += StorageStatus(StorageKind.LOCAL, "este teléfono", stat.availableBytes, stat.totalBytes, null, null, null, null)
        if (cloudToken != null) {
            val u = cloudUsage()
            storages += StorageStatus(StorageKind.CLOUD, "la nube", null, null, u?.usedBytes, u?.quotaBytes, lastSyncAt.takeIf { it > 0 }, lastSyncError)
        }
        val interrupted = listOfNotNull(local, cloud).sumOf { runCatching { it.staleLocks().size }.getOrDefault(0) }
        val health = VaultHealth(lastCheckAt.takeIf { it > 0 }, if (lastCheckAt > 0) lastCheckOk else null, interrupted, lib?.damaged?.size ?: 0)
        return ProtectionAnalyzer.analyze(System.currentTimeMillis(), sources, storages, health, monitor)
    }

    companion object {
        /** Sustituir por el dominio del servidor de producción (HTTPS obligatorio). */
        const val DEFAULT_CLOUD_URL = "https://api.socialrecover.example"
    }
}
