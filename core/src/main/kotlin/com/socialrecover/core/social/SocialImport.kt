package com.socialrecover.core.social

import com.socialrecover.core.engine.Progress
import com.socialrecover.core.engine.SafeNames
import com.socialrecover.core.engine.SrpException
import com.socialrecover.core.model.Category
import com.socialrecover.core.vault.DeviceKind
import com.socialrecover.core.vault.PlatformSource
import com.socialrecover.core.vault.Snapshot
import com.socialrecover.core.vault.TimeMachine
import com.socialrecover.core.vault.Vault
import com.socialrecover.core.vault.VaultBackupSession
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Importa una exportación oficial (uno o varios ZIP) en la bóveda:
 *  1. Todos los archivos del ZIP se guardan cifrados (fotos y vídeos recuperables uno a uno).
 *  2. El importador de la red analiza los archivos de datos y genera el índice de publicaciones,
 *     comentarios, «Me gusta», guardados, enlaces y fechas, que se guarda (cifrado) en la instantánea.
 * Una sola lectura de cada ZIP, con memoria limitada: solo los archivos de datos se mantienen en memoria.
 */
object SocialImport {
    data class Part(val name: String, val open: () -> InputStream)

    data class Result(
        val network: SocialNetwork,
        val importerFormat: String?,
        val detected: Boolean,
        val items: List<SocialItem>,
        val snapshot: Snapshot,
        val warnings: List<String>,
    ) {
        val countsByType: Map<SocialItemType, Int> get() = items.groupingBy { it.type }.eachCount()
        val mediaAvailable: Int get() = items.count { it.mediaPath != null }
    }

    private const val MAX_DATA_FILE = 64L shl 20
    private const val MAX_DATA_TOTAL = 400L shl 20
    private val dataExt = setOf("json", "js", "csv", "html", "htm", "txt")

    fun import(
        parts: List<Part>,
        vault: Vault,
        device: String,
        deviceKind: DeviceKind,
        expected: SocialNetwork? = null,
        createdAt: Long = System.currentTimeMillis(),
        onProgress: (Progress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Result {
        require(parts.isNotEmpty())
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(createdAt))
        val baseName = SafeNames.sanitize(parts[0].name.substringBeforeLast('.').replace(Regex("[-_ ]?(part|parte)?[-_ ]?\\d+$", RegexOption.IGNORE_CASE), "").ifBlank { "exportacion" })
        val rootFolder = "Redes sociales/$baseName ($stamp)"
        val data = HashMap<String, ByteArray>(); val stored = LinkedHashMap<String, String>(); val times = HashMap<String, Long>()
        val warnings = ArrayList<String>()
        var dataTotal = 0L

        val session = VaultBackupSession(vault, "Exportación de ${expected?.label ?: "red social"}", device, deviceKind,
            parent = null, reuseUnchanged = false, createdAt = createdAt, onProgress = onProgress, isCancelled = isCancelled)
        try {
            for (part in parts) {
                val zin = try { ZipInputStream(part.open().buffered(256 * 1024)) } catch (e: Exception) { throw SrpException.SourceUnreadable(part.name, e) }
                try {
                    var e = try { zin.nextEntry } catch (ex: ZipException) { throw IllegalArgumentException("«${part.name}» no es un ZIP válido o está dañado.", ex) }
                    if (e == null) throw IllegalArgumentException("«${part.name}» está vacío o no es un archivo ZIP.")
                    while (e != null) {
                        if (!e.isDirectory) {
                            val zpath = e.name.replace('\\', '/').trimStart('/')
                            val segs = SafeNames.sanitizePath(zpath)
                            if (segs.isEmpty()) { e = zin.nextEntry; continue }
                            val folder = (listOf(rootFolder) + segs.dropLast(1)).joinToString("/")
                            val name = segs.last()
                            val ext = name.substringAfterLast('.', "").lowercase()
                            val time = if (e.time > 0) e.time else 0L
                            times[zpath] = time
                            val cat = Category.SOCIAL_EXPORTS
                            val before = session.pathCount()
                            if (ext in dataExt && (e.size in 0..MAX_DATA_FILE || e.size < 0) && dataTotal < MAX_DATA_TOTAL) {
                                val bytes = readLimited(zin, MAX_DATA_FILE)
                                if (bytes != null) {
                                    data[zpath] = bytes; dataTotal += bytes.size
                                    session.add(ZipEntrySource(name, folder, cat, mimeFor(ext), time, bytes.inputStream(), bytes.size.toLong()))
                                } else warnings += "«$zpath» es demasiado grande para analizarlo; se guarda sin indexar."
                            } else {
                                if (ext in dataExt) warnings += "«$zpath» no se ha analizado (límite de tamaño); se guarda igualmente."
                                session.add(ZipEntrySource(name, folder, cat, mimeFor(ext), time, NonClosing(zin), e.size.takeIf { it >= 0 }))
                            }
                            session.lastAddedPath()?.takeIf { session.pathCount() > before }?.let { stored[zpath] = it }
                        }
                        e = zin.nextEntry
                    }
                } finally {
                    runCatching { zin.close() }
                }
            }

            val files = ExportFiles(data, stored, times)
            val importer = Importers.detect(files.paths) ?: expected?.let { Importers.forNetwork(it) }
            val detected = Importers.detect(files.paths) != null
            val network = importer?.network ?: expected ?: SocialNetwork.OTHER
            if (expected != null && detected && importer!!.network != expected) {
                warnings += "El archivo parece una exportación de ${importer.network.label}, no de ${expected.label}. Se ha importado como ${importer.network.label}."
            }
            if (!detected) warnings += "No se ha reconocido el formato. Se guardan todos los archivos y se indexan las fotos y vídeos."
            val items = ArrayList<SocialItem>()
            if (importer != null) {
                try { items += importer.parse(files) } catch (ex: Exception) {
                    warnings += "El formato de esta exportación ha cambiado y no se ha podido analizar del todo. Los archivos están guardados igualmente."
                }
            }
            if (importer == null) items += PinterestImporter.htmlLinks(files, network)
            // Fotos y vídeos que ningún registro menciona: se indexan igualmente para poder buscarlos.
            val linked = items.mapNotNull { it.mediaPath }.toSet()
            for ((zpath, spath) in stored) {
                val mt = mediaType(zpath) ?: continue
                if (spath !in linked) items += SocialItem(network, mt, times[zpath] ?: 0, zpath.substringAfterLast('/'), null, spath, zpath)
            }
            session.setPlatform(rootFolder, network)
            session.addSocial(items)
            val r = session.finish()
            return Result(network, importer?.formatId, detected, items, r.snapshot, warnings + r.skipped.map { "No se pudo guardar «${it.name}»: ${it.reason}" })
        } catch (e: Throwable) {
            session.abort(); throw e
        }
    }

    private fun readLimited(input: InputStream, max: Long): ByteArray? {
        val out = ByteArrayOutputStream(); val buf = ByteArray(64 * 1024)
        var n = input.read(buf)
        while (n >= 0) {
            out.write(buf, 0, n)
            if (out.size() > max) { while (input.read(buf) >= 0) {}; return null }
            n = input.read(buf)
        }
        return out.toByteArray()
    }

    private fun mimeFor(ext: String) = when (ext) {
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"; "gif" -> "image/gif"; "heic" -> "image/heic"
        "mp4", "m4v" -> "video/mp4"; "mov" -> "video/quicktime"; "webm" -> "video/webm"
        "json" -> "application/json"; "js" -> "text/javascript"; "csv" -> "text/csv"; "html", "htm" -> "text/html"; "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    private class NonClosing(i: InputStream) : FilterInputStream(i) { override fun close() {} }

    private class ZipEntrySource(
        override val displayName: String, override val folder: String, override val category: Category,
        override val mimeType: String, override val lastModified: Long, private val stream: InputStream, override val sizeHint: Long?,
    ) : PlatformSource {
        override val platform: SocialNetwork? = null
        override fun open(): InputStream = stream
    }
}

/** Criterios del buscador de recuerdos sociales. */
data class SocialQuery(
    val text: String = "",
    val from: Long? = null,
    val to: Long? = null,
    val networks: Set<SocialNetwork> = emptySet(),
    val types: Set<SocialItemType> = emptySet(),
)

data class SocialHit(val item: SocialItem, val score: Int)

object SocialSearch {
    private val splitter = Regex("[^\\p{L}\\p{N}@#]+")

    fun tokens(s: String): List<String> = TimeMachine.norm(s).split(splitter).filter { it.isNotEmpty() }

    /**
     * @param matchAll true: deben aparecer todas las palabras (buscador); false: basta una, ordenado por coincidencias.
     * Una palabra coincide si alguna palabra del elemento empieza por ella ("playa" encuentra "playas").
     */
    fun search(items: Collection<SocialItem>, q: SocialQuery, limit: Int = 500, matchAll: Boolean = true): List<SocialHit> {
        val words = tokens(q.text).filter { it.length >= 2 }
        return items.asSequence()
            .filter { q.networks.isEmpty() || it.network in q.networks }
            .filter { q.types.isEmpty() || it.type in q.types }
            .filter { (q.from == null || it.timestamp >= q.from) && (q.to == null || it.timestamp in 1..q.to) }
            .mapNotNull { item ->
                if (words.isEmpty()) return@mapNotNull SocialHit(item, 1)
                val hay = tokens(item.text + " " + (item.url ?: "") + " " + (item.mediaPath?.substringAfterLast('/') ?: ""))
                val hits = words.count { w -> hay.any { it.startsWith(w) } }
                if (hits == 0 || matchAll && hits < words.size) null
                else SocialHit(item, hits * 10 + (if (TimeMachine.norm(item.text).contains(words.joinToString(" "))) 5 else 0))
            }
            .sortedWith(compareByDescending<SocialHit> { it.score }.thenByDescending { it.item.timestamp })
            .take(limit).toList()
    }
}

/** Exportar resultados de búsqueda a CSV (hoja de cálculo) o HTML (página que se abre en cualquier navegador). */
object SocialExport {
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    private fun date(ms: Long) = if (ms <= 0) "" else fmt.format(Instant.ofEpochMilli(ms))
    private fun csv(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
    private fun html(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun toCsv(items: List<SocialItem>): String = buildString {
        append("﻿").append("Fecha,Red,Tipo,Texto,Enlace,Archivo en la copia\r\n")
        items.forEach { append(listOf(date(it.timestamp), it.network.label, it.type.label, it.text, it.url ?: "", it.mediaPath ?: "").joinToString(",") { v -> csv(v) }).append("\r\n") }
    }

    fun toHtml(items: List<SocialItem>, title: String): String = buildString {
        append("<!doctype html><html lang=\"es\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        append("<title>").append(html(title)).append("</title><style>body{font-family:system-ui,sans-serif;max-width:900px;margin:24px auto;padding:0 16px;color:#0B1F3A}")
        append("h1{font-size:22px}.i{border-bottom:1px solid #ddd;padding:10px 0}.m{color:#14807a;font-size:13px}a{color:#0e7490}</style></head><body>")
        append("<h1>").append(html(title)).append("</h1><p>").append(items.size).append(" elementos · exportado con SocialRecover Pro</p>")
        items.forEach {
            append("<div class=\"i\"><div class=\"m\">").append(html(listOf(date(it.timestamp), it.network.label, it.type.label).filter { s -> s.isNotEmpty() }.joinToString(" · ")))
            append("</div><div>").append(html(it.text)).append("</div>")
            it.url?.let { u -> if (u.startsWith("http")) append("<a href=\"").append(html(u)).append("\">").append(html(u)).append("</a>") }
            it.mediaPath?.let { m -> append("<div class=\"m\">Archivo guardado: ").append(html(m)).append("</div>") }
            append("</div>")
        }
        append("</body></html>")
    }
}
