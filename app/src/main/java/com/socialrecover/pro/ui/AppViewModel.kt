package com.socialrecover.pro.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.socialrecover.core.engine.BackupResult
import com.socialrecover.core.engine.BackupSummary
import com.socialrecover.core.engine.OpenedBackup
import com.socialrecover.core.engine.Progress
import com.socialrecover.core.engine.RestoreReport
import com.socialrecover.core.engine.SrpException
import com.socialrecover.core.model.Category
import com.socialrecover.core.social.ExportAnalysis
import com.socialrecover.core.social.SocialNetwork
import com.socialrecover.pro.data.Repository
import com.socialrecover.pro.data.UriSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Arrays
import java.util.concurrent.atomic.AtomicBoolean

enum class Screen { HOME, CREATE, BACKUPS, BACKUP_DETAIL, SOCIAL, SOCIAL_WIZARD, DEVICES, STORAGE, SETTINGS,
    TIME_MACHINE, SOCIAL_HUB, STATUS, SMART_CLOUD, GUIDED, VERSIONS }

data class UiMessage(val title: String, val text: String, val isError: Boolean = false)

data class Busy(val title: String, val progress: Progress? = null, val cancellable: Boolean = false)

data class ExportItem(val uri: Uri, val name: String, val analysis: ExportAnalysis?, val error: String?)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val repo = Repository(app)

    // ---------- Navegación ----------
    var onboardingDone by mutableStateOf(repo.prefs.onboardingDone)
        private set
    private val stack = mutableStateListOf(Screen.HOME)
    val screen: Screen get() = stack.last()

    fun go(s: Screen) {
        stack.add(s)
        if (s == Screen.BACKUPS || s == Screen.HOME) refresh()
    }

    /** @return false si ya estamos en la pantalla principal. */
    fun back(): Boolean {
        if (busy != null) return true
        if (stack.size <= 1) return false
        val leaving = stack.removeAt(stack.lastIndex)
        if (leaving == Screen.BACKUP_DETAIL) closeOpened()
        if (screen == Screen.HOME || screen == Screen.BACKUPS) refresh()
        return true
    }

    private fun home() {
        closeOpened()
        stack.clear(); stack.add(Screen.HOME)
        refresh()
    }

    // ---------- Estado general ----------
    var message by mutableStateOf<UiMessage?>(null)
    var busy by mutableStateOf<Busy?>(null)
        private set
    private val cancelFlag = AtomicBoolean(false)
    var folderOk by mutableStateOf(repo.hasLocationPermission())
        private set
    var folderLabel by mutableStateOf(repo.folderLabel())
        private set
    var backups by mutableStateOf<List<BackupSummary>>(emptyList())
        private set
    var lastBackupAt by mutableStateOf(repo.prefs.lastBackupAt)
        private set
    var enabledCategories by mutableStateOf(repo.prefs.enabledCategories)
        private set
    var showCategoriesDialog by mutableStateOf(false)

    init {
        refresh()
    }

    fun finishOnboarding() {
        repo.prefs.onboardingDone = true
        onboardingDone = true
        refresh()
    }

    fun setBackupFolder(uri: Uri) {
        try {
            repo.setBackupFolder(uri)
        } catch (e: Exception) {
            message = UiMessage("No se pudo usar esa carpeta", "Elige otra carpeta (por ejemplo, Documentos o una tarjeta SD).", true)
        }
        folderOk = repo.hasLocationPermission()
        folderLabel = repo.folderLabel()
        refresh()
    }

    fun saveCategories(set: Set<Category>) {
        repo.prefs.enabledCategories = set
        enabledCategories = set
    }

    fun refresh() {
        folderOk = repo.hasLocationPermission()
        lastBackupAt = repo.prefs.lastBackupAt
        if (!folderOk) { backups = emptyList(); return }
        viewModelScope.launch {
            backups = try {
                withContext(Dispatchers.IO) { repo.listBackups() }
            } catch (e: Exception) {
                folderOk = false
                emptyList()
            }
        }
    }

    fun cancel() = cancelFlag.set(true)

    /** Ejecuta una operación larga en segundo plano con indicador de progreso y errores comprensibles. */
    private fun <T> launchOp(title: String, cancellable: Boolean, work: (onProgress: (Progress) -> Unit, isCancelled: () -> Boolean) -> T, done: (T) -> Unit) {
        if (busy != null) return
        cancelFlag.set(false)
        busy = Busy(title, null, cancellable)
        var lastEmit = 0L
        val onProgress: (Progress) -> Unit = { p ->
            val now = System.currentTimeMillis()
            if (now - lastEmit > 120 || p.currentName.isEmpty()) {
                lastEmit = now
                viewModelScope.launch { busy = busy?.copy(progress = p) }
            }
        }
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { work(onProgress, { cancelFlag.get() }) }
                busy = null
                done(r)
            } catch (e: SrpException) {
                busy = null
                if (e is SrpException.PermissionDenied) folderOk = repo.hasLocationPermission()
                message = UiMessage(if (e is SrpException.Cancelled) "Cancelado" else "No se pudo completar", e.userMessage, e !is SrpException.Cancelled)
            } catch (e: Exception) {
                busy = null
                message = UiMessage("Error inesperado", "Ha ocurrido un error inesperado. Detalle técnico: ${e.message ?: e.javaClass.simpleName}", true)
            }
        }
    }

    // ---------- Crear copia ----------
    val selected = mutableStateListOf<UriSource>()
    var backupLabel by mutableStateOf("")
    var lastResult by mutableStateOf<BackupResult?>(null)

    fun addFiles(uris: List<Uri>, category: Category?) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                uris.mapNotNull { runCatching { UriSource.from(getApplication<Application>(), it, category) }.getOrNull() }
            }
            list.filter { n -> selected.none { it.uri == n.uri } }.forEach { selected.add(it) }
            if (list.size < uris.size) message = UiMessage("Algunos archivos no se añadieron", "No se pudo leer la información de ${uris.size - list.size} archivo(s).", true)
        }
    }

    fun addFolder(tree: Uri) {
        launchOp("Leyendo la carpeta…", false, { _, _ -> UriSource.fromTree(getApplication<Application>(), tree, Category.CUSTOM_FOLDERS) }) { list ->
            list.filter { n -> selected.none { it.uri == n.uri } }.forEach { selected.add(it) }
            if (list.isEmpty()) message = UiMessage("Carpeta vacía", "La carpeta elegida no contiene archivos.")
        }
    }

    fun removeSelected(s: UriSource) { selected.remove(s) }

    fun startBackup(password: CharArray) {
        val sources = selected.toList()
        val label = backupLabel
        launchOp("Creando copia cifrada…", true, { p, c ->
            try { repo.createBackup(label, password, sources, p, c) } finally { Arrays.fill(password, '\u0000') }
        }) { r ->
            lastResult = r
            selected.clear(); backupLabel = ""
            lastBackupAt = repo.prefs.lastBackupAt
            val skipped = if (r.skipped.isEmpty()) "" else
                "\n\nNo se pudieron incluir ${r.skipped.size} archivo(s):\n" + r.skipped.take(5).joinToString("\n") { "• ${it.name}" }
            message = UiMessage("Copia creada",
                "Se han protegido ${r.manifest.entries.size} archivo(s) (${Format.size(getApplication<Application>(), r.manifest.totalBytes)}).$skipped\n\n" +
                    "Recuerda tu contraseña: sin ella nadie puede abrir esta copia, tampoco SocialRecover Pro.")
            home()
        }
    }

    // ---------- Recuperar ----------
    var opened by mutableStateOf<OpenedBackup?>(null)
        private set
    var restoreReport by mutableStateOf<RestoreReport?>(null)

    fun openBackup(dir: String, password: CharArray) {
        launchOp("Comprobando la contraseña…", false, { _, _ ->
            try { repo.openBackup(dir, password) } finally { Arrays.fill(password, '\u0000') }
        }) { b ->
            closeOpened()
            opened = b
            restoreReport = null
            go(Screen.BACKUP_DETAIL)
        }
    }

    private fun closeOpened() {
        opened?.close()
        opened = null
        restoreReport = null
    }

    fun restore(ids: Set<Int>, destination: Uri) {
        val b = opened ?: return
        launchOp("Restaurando y verificando…", true, { p, c -> repo.restore(b, ids, destination, p, c) }) { r ->
            restoreReport = r
        }
    }

    fun verify() {
        val b = opened ?: return
        launchOp("Comprobando la integridad de la copia…", true, { p, c -> repo.restore(b, null, null, p, c) }) { r ->
            restoreReport = r
        }
    }

    fun deleteBackup(dir: String) {
        launchOp("Eliminando copia…", false, { _, _ -> repo.deleteBackup(dir) }) {
            refresh()
        }
    }

    // ---------- Redes sociales ----------
    var wizardNetwork by mutableStateOf<SocialNetwork?>(null)
        private set
    val exportItems = mutableStateListOf<ExportItem>()

    fun startWizard(n: SocialNetwork) {
        wizardNetwork = n
        exportItems.clear()
        go(Screen.SOCIAL_WIZARD)
    }

    fun analyzeExports(uris: List<Uri>) {
        if (uris.isEmpty()) return
        launchOp("Analizando la exportación…", false, { _, _ ->
            uris.map { u ->
                val name = runCatching { UriSource.from(getApplication<Application>(), u).displayName }.getOrDefault("exportación.zip")
                try {
                    ExportItem(u, name, repo.analyzeExport(u), null)
                } catch (e: Exception) {
                    ExportItem(u, name, null, e.message ?: "No se pudo analizar el archivo")
                }
            }
        }) { items -> exportItems.addAll(items) }
    }

    /** Lleva los ZIP analizados a la pantalla de copia, en la categoría de exportaciones sociales. */
    fun exportsToBackup() {
        val ok = exportItems.filter { it.error == null }.map { it.uri }
        if (ok.isEmpty()) return
        backupLabel = "Exportación de ${wizardNetwork?.label ?: "red social"}"
        addFiles(ok, Category.SOCIAL_EXPORTS)
        stack.removeAt(stack.lastIndex)
        go(Screen.CREATE)
    }

    // ---------- Módulo Premium (bóveda incremental, Máquina del tiempo, redes, detector, nube, recuperación guiada)
    val pro: ProState by lazy {
        ProState(getApplication<Application>(), object : ProState.Ops {
            override fun <T> op(title: String, cancellable: Boolean, work: (onProgress: (Progress) -> Unit, isCancelled: () -> Boolean) -> T, done: (T) -> Unit) =
                launchOp(title, cancellable, work, done)
            override fun msg(title: String, text: String, error: Boolean) { message = UiMessage(title, text, error) }
        })
    }

    /** Navega a una pantalla del módulo premium pidiendo antes abrir la bóveda si hace falta. */
    fun goPro(s: Screen) {
        go(s)
        if (!pro.unlocked && (pro.localConfigured || pro.cloudConfigured)) pro.showUnlock = true
        else if (pro.unlocked && pro.library == null) pro.refreshLibrary()
    }

    fun backupToVault() {
        val list = selected.toList()
        pro.backup(list, backupLabel) { selected.clear(); backupLabel = ""; lastBackupAt = System.currentTimeMillis(); home() }
    }

    /** Migra una copia de la versión 1 a la bóveda (la original no se borra). */
    fun migrateLegacy(dir: String, password: CharArray) {
        val loc = repo.location() ?: return
        val v = pro.svc.local ?: pro.svc.cloud ?: run { pro.showUnlock = true; return }
        launchOp("Migrando a la bóveda…", false, { _, _ ->
            try { com.socialrecover.core.vault.LegacyMigrator.migrate(loc, dir, password, v, pro.svc.deviceKind) } finally { Arrays.fill(password, '\u0000') }
        }) { r ->
            message = UiMessage("Copia migrada", "${r.files} archivo(s) añadidos a la Máquina del tiempo con su fecha original. La copia antigua no se ha borrado.")
            pro.refreshLibrary()
        }
    }

    override fun onCleared() {
        closeOpened()
    }
}
