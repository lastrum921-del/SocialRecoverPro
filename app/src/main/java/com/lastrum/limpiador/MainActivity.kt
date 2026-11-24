package com.lastrum.limpiador

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.nio.file.Files
import java.util.Locale

/**
 * Limpiador Móvil
 * - Borra la caché de TODAS las apps (con el diálogo oficial de Android, Android 11+)
 * - Borra basura del almacenamiento: miniaturas, temporales, logs, instaladores APK,
 *   carpetas vacías y estados vistos de WhatsApp.
 * - NO toca fotos, vídeos, documentos, descargas normales, chats ni contraseñas.
 */
class MainActivity : Activity() {

    private val bgColor = Color.rgb(15, 17, 23)
    private val cardColor = Color.rgb(27, 30, 39)
    private val fgColor = Color.rgb(236, 239, 245)
    private val mutedColor = Color.rgb(148, 155, 170)
    private val greenColor = Color.rgb(34, 197, 94)
    private val amberColor = Color.rgb(245, 158, 11)

    private data class Cat(val id: String, val title: String, val desc: String, val default: Boolean)

    private val cats = listOf(
        Cat("appcache", "Caché de todas las apps", "Instagram, TikTok, Chrome, WhatsApp, juegos... Android te pedirá confirmar.", true),
        Cat("thumbs", "Miniaturas de la galería", "Carpetas .thumbnails. Se regeneran solas.", true),
        Cat("temp", "Archivos temporales y registros", "Archivos .tmp, .temp y .log que dejan las apps.", true),
        Cat("apk", "Instaladores APK", "Archivos .apk ya descargados. Las apps instaladas no se tocan.", true),
        Cat("empty", "Carpetas vacías", "Carpetas que no tienen nada dentro.", true),
        Cat("wa_status", "Estados de WhatsApp ya vistos", "Copias de los estados que has visto.", true),
        Cat("wa_sent", "Copias de lo enviado por WhatsApp", "Carpetas \"Sent\": fotos y vídeos que TÚ enviaste. Seguirán en los chats si el otro los tiene.", false),
        Cat("telegram", "Caché de Telegram", "Fotos y vídeos descargados de Telegram. Se vuelven a bajar al abrirlos.", false),
        Cat("trash", "Papelera de la galería", "Fotos que ya borraste y están en la papelera. Se pierden para siempre.", false)
    )

    private val checks = HashMap<String, CheckBox>()
    private lateinit var storageText: TextView
    private lateinit var storageBar: ProgressBar
    private lateinit var permBox: LinearLayout
    private lateinit var cleanButton: Button
    private lateinit var statusText: TextView
    private lateinit var logText: TextView

    @Volatile private var freedBytes = 0L
    @Volatile private var deletedCount = 0
    private var freeBeforeCache = 0L
    private val deletedPaths = ArrayList<String>()
    private val reqCache = 4201
    private val reqLegacy = 4202
    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }

    // ---------------------------------------------------------------- UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bgColor
        window.navigationBarColor = bgColor

        val scroll = ScrollView(this).apply { setBackgroundColor(bgColor); isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        scroll.addView(root)

        root.addView(text("Limpiador", 30f, fgColor, bold = true))
        root.addView(text("Tus fotos, vídeos, documentos y chats no se tocan.", 14f, mutedColor).apply {
            setPadding(0, dp(4), 0, dp(18))
        })

        // Almacenamiento
        val storageCard = card()
        storageText = text("", 15f, fgColor)
        storageBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(greenColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10)).apply { topMargin = dp(10) }
        }
        storageCard.addView(text("Almacenamiento", 13f, mutedColor))
        storageCard.addView(storageText)
        storageCard.addView(storageBar)
        root.addView(storageCard)

        // Permisos
        permBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(permBox)

        // Botón grande
        cleanButton = Button(this).apply {
            text = "LIMPIAR"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = GradientDrawable().apply { setColor(greenColor); cornerRadius = dp(22).toFloat() }
            stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(84)).apply {
                topMargin = dp(18); bottomMargin = dp(10)
            }
            setOnClickListener { startClean() }
        }
        root.addView(cleanButton)

        statusText = text("Pulsa el botón para limpiar.", 14f, mutedColor).apply { gravity = Gravity.CENTER }
        root.addView(statusText)

        // Opciones
        root.addView(text("Qué limpiar", 13f, mutedColor).apply { setPadding(dp(4), dp(22), 0, dp(8)) })
        val optionsCard = card()
        val saved = prefs.getStringSet("sel", null)
        for (c in cats) {
            val cb = CheckBox(this).apply {
                text = c.title
                textSize = 16f
                setTextColor(fgColor)
                buttonTintList = android.content.res.ColorStateList.valueOf(greenColor)
                isChecked = saved?.contains(c.id) ?: c.default
            }
            if (c.id == "appcache" && Build.VERSION.SDK_INT < 30) {
                cb.isChecked = false
                cb.isEnabled = false
                cb.text = c.title + " (necesita Android 11 o superior)"
            }
            checks[c.id] = cb
            optionsCard.addView(cb)
            optionsCard.addView(text(c.desc, 12.5f, mutedColor).apply { setPadding(dp(32), 0, 0, dp(10)) })
        }
        root.addView(optionsCard)

        // Registro
        root.addView(text("Resultado", 13f, mutedColor).apply { setPadding(dp(4), dp(22), 0, dp(8)) })
        val logCard = card()
        logText = text("—", 13f, fgColor).apply { typeface = Typeface.MONOSPACE }
        logCard.addView(logText)
        root.addView(logCard)

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refreshStorage()
        refreshPermissions()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply { setColor(cardColor); cornerRadius = dp(16).toFloat() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        }
    }

    private fun refreshStorage() {
        val st = StatFs(Environment.getDataDirectory().path)
        val total = st.totalBytes
        val free = st.availableBytes
        val used = total - free
        storageText.text = "${fmt(used)} usados de ${fmt(total)}  ·  ${fmt(free)} libres"
        storageBar.progress = if (total > 0) ((used * 1000) / total).toInt() else 0
    }

    private fun refreshPermissions() {
        permBox.removeAllViews()
        if (hasAllFiles()) return
        val c = card()
        c.addView(text("Falta un permiso", 16f, amberColor, bold = true))
        c.addView(text("Para poder limpiar, Android necesita que actives \"Acceso a todos los archivos\" para Limpiador. Solo se usa para borrar basura.", 13.5f, fgColor).apply {
            setPadding(0, dp(4), 0, dp(10))
        })
        c.addView(Button(this).apply {
            text = "Dar permiso"
            isAllCaps = false
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply { setColor(amberColor); cornerRadius = dp(12).toFloat() }
            setOnClickListener { requestAllFiles() }
        })
        permBox.addView(c)
    }

    private fun hasAllFiles(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestAllFiles() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), reqLegacy)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPermissions()
    }

    // ---------------------------------------------------------------- Limpieza

    private fun startClean() {
        val sel = cats.map { it.id }.filter { checks[it]?.isChecked == true && checks[it]?.isEnabled == true }.toSet()
        prefs.edit().putStringSet("sel", sel).apply()
        if (sel.isEmpty()) {
            Toast.makeText(this, "Marca al menos una opción", Toast.LENGTH_SHORT).show(); return
        }
        if (!hasAllFiles()) {
            AlertDialog.Builder(this)
                .setTitle("Falta un permiso")
                .setMessage("Activa \"Acceso a todos los archivos\" para Limpiador y vuelve a pulsar LIMPIAR.")
                .setPositiveButton("Dar permiso") { _, _ -> requestAllFiles() }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        if ("trash" in sel) {
            AlertDialog.Builder(this)
                .setTitle("¿Vaciar la papelera de la galería?")
                .setMessage("Las fotos y vídeos de la papelera se borrarán para siempre.")
                .setPositiveButton("Sí, limpiar") { _, _ -> runClean(sel) }
                .setNegativeButton("Cancelar", null)
                .show()
        } else runClean(sel)
    }

    private fun runClean(sel: Set<String>) {
        cleanButton.isEnabled = false
        cleanButton.text = "LIMPIANDO..."
        cleanButton.alpha = 0.7f
        logText.text = ""
        freedBytes = 0L
        deletedCount = 0
        deletedPaths.clear()

        Thread {
            val root = Environment.getExternalStorageDirectory()
            val waBases = listOf(
                File(root, "Android/media/com.whatsapp/WhatsApp"),
                File(root, "Android/media/com.whatsapp.w4b/WhatsApp Business"),
                File(root, "WhatsApp"),
                File(root, "WhatsApp Business")
            )

            if ("wa_status" in sel) step("Estados de WhatsApp") {
                for (b in waBases) deleteContents(File(b, "Media/.Statuses"))
            }
            if ("wa_sent" in sel) step("Enviados de WhatsApp") {
                for (b in waBases) {
                    File(b, "Media").listFiles()?.forEach { mediaDir ->
                        if (mediaDir.isDirectory && !isLink(mediaDir)) deleteContents(File(mediaDir, "Sent"))
                    }
                }
            }
            if ("telegram" in sel) step("Caché de Telegram") {
                for (pkg in listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram")) {
                    val tg = File(root, "Android/media/$pkg/Telegram")
                    for (sub in listOf("Telegram Images", "Telegram Video", "Telegram Audio", "Telegram Documents", "Telegram Stories")) {
                        deleteContents(File(tg, sub))
                    }
                }
            }
            if (sel.any { it in setOf("thumbs", "temp", "apk", "empty", "trash") }) step("Basura del almacenamiento") {
                walk(root, root, sel)
            }

            runOnUiThread {
                if (deletedPaths.isNotEmpty()) {
                    // Avisa a la galería de que esos archivos ya no existen
                    try { MediaScannerConnection.scanFile(this, deletedPaths.take(5000).toTypedArray(), null, null) } catch (e: Exception) {}
                }
                if ("appcache" in sel && Build.VERSION.SDK_INT >= 30) launchClearCache() else finishClean(0L)
            }
        }.start()
    }

    private inline fun step(name: String, block: () -> Unit) {
        val before = freedBytes
        runOnUiThread { statusText.text = "$name..." }
        try { block() } catch (e: Exception) {}
        val got = freedBytes - before
        runOnUiThread { addLog("✓ $name: ${fmt(got)}") }
    }

    private val skipNames = setOf("data", "obb")

    /** Recorre el almacenamiento una sola vez buscando basura. */
    private fun walk(dir: File, root: File, sel: Set<String>) {
        val list = dir.listFiles() ?: return
        for (f in list) {
            if (isLink(f)) continue
            if (f.isDirectory) {
                // Android/data y Android/obb son de las apps: no se tocan
                if (dir.name == "Android" && dir.parentFile == root && f.name in skipNames) continue
                if (f.name.equals(".thumbnails", true)) {
                    if ("thumbs" in sel) deleteContents(f)
                    continue
                }
                walk(f, root, sel)
                if ("empty" in sel && !isProtectedDir(f, root)) {
                    val rest = f.list()
                    if (rest != null && rest.isEmpty()) { if (f.delete()) deletedCount++ }
                }
            } else {
                val n = f.name.lowercase(Locale.ROOT)
                val junk = ("temp" in sel && (n.endsWith(".tmp") || n.endsWith(".temp") || n.endsWith(".log"))) ||
                        ("apk" in sel && (n.endsWith(".apk") || n.endsWith(".apks") || n.endsWith(".xapk") || n.endsWith(".apkm"))) ||
                        ("trash" in sel && n.startsWith(".trashed-"))
                if (junk) deleteFile(f)
            }
        }
    }

    /** No borra carpetas principales (DCIM, Download...) ni nada dentro de Android/. */
    private fun isProtectedDir(f: File, root: File): Boolean {
        if (f.parentFile == root) return true
        val androidDir = File(root, "Android").absolutePath + File.separator
        return f.absolutePath.startsWith(androidDir)
    }

    private fun isLink(f: File): Boolean = try { Files.isSymbolicLink(f.toPath()) } catch (e: Exception) { true }

    /** Vacía una carpeta (sin borrarla). Respeta los archivos .nomedia. */
    private fun deleteContents(dir: File) {
        if (!dir.isDirectory || isLink(dir)) return
        val list = dir.listFiles() ?: return
        for (f in list) {
            if (isLink(f)) continue
            if (f.isDirectory) {
                deleteContents(f)
                val rest = f.list()
                if (rest != null && rest.isEmpty()) f.delete()
            } else if (f.name != ".nomedia") {
                deleteFile(f)
            }
        }
    }

    private fun deleteFile(f: File) {
        val len = f.length()
        if (f.delete()) {
            freedBytes += len
            deletedCount++
            synchronized(deletedPaths) { deletedPaths.add(f.absolutePath) }
            if (deletedCount % 200 == 0) {
                val now = freedBytes
                runOnUiThread { statusText.text = "Liberado: ${fmt(now)}..." }
            }
        }
    }

    // Caché de todas las apps: diálogo oficial de Android (Android 11+)
    private fun launchClearCache() {
        statusText.text = "Confirma para borrar la caché de las apps..."
        freeBeforeCache = freeBytes()
        try {
            startActivityForResult(Intent("android.os.storage.action.CLEAR_APP_CACHE"), reqCache)
        } catch (e: Exception) {
            addLog("✗ Este móvil no permite borrar la caché de todas las apps de golpe.")
            addLog("  Ve a Ajustes > Almacenamiento para hacerlo a mano.")
            finishClean(0L)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqCache) return
        statusText.text = "Terminando..."
        Handler(Looper.getMainLooper()).postDelayed({
            val gained = (freeBytes() - freeBeforeCache).coerceAtLeast(0L)
            if (resultCode == RESULT_OK) addLog("✓ Caché de todas las apps: ${fmt(gained)}")
            else addLog("– Caché de apps: cancelado")
            finishClean(if (resultCode == RESULT_OK) gained else 0L)
        }, 2000)
    }

    private fun finishClean(extra: Long) {
        val total = freedBytes + extra
        addLog("")
        addLog("TOTAL LIBERADO: ${fmt(total)}")
        statusText.text = "¡Listo! Has liberado ${fmt(total)}"
        statusText.setTextColor(greenColor)
        cleanButton.text = "LIMPIAR"
        cleanButton.alpha = 1f
        cleanButton.isEnabled = true
        refreshStorage()
        Handler(Looper.getMainLooper()).postDelayed({ statusText.setTextColor(mutedColor) }, 6000)
    }

    private fun addLog(s: String) {
        logText.text = if (logText.text.isNullOrEmpty() || logText.text == "—") s else "${logText.text}\n$s"
    }

    private fun freeBytes(): Long = StatFs(Environment.getDataDirectory().path).availableBytes

    private fun fmt(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale.getDefault(), "%.2f GB", b / 1073741824.0)
        b >= 1L shl 20 -> String.format(Locale.getDefault(), "%.1f MB", b / 1048576.0)
        b >= 1L shl 10 -> String.format(Locale.getDefault(), "%.0f KB", b / 1024.0)
        else -> "$b B"
    }
}
