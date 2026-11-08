package com.socialrecover.core.cloud

import com.socialrecover.core.engine.SrpException
import com.socialrecover.core.util.Json
import com.socialrecover.core.vault.RepoBackend
import com.socialrecover.core.vault.RepoObject
import com.socialrecover.core.vault.StorageKind
import com.socialrecover.core.vault.Usage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** Proporciona el token de acceso OAuth de Google (Android: Google Identity; PC: flujo de escritorio). */
interface AccessTokenProvider {
    fun token(): String
    /** El token ha caducado o fue revocado: obtener uno nuevo en la siguiente llamada. */
    fun invalidate()
}

/**
 * Bóveda en el Google Drive del propio usuario (15 GB gratuitos compartidos con Gmail y Google Fotos).
 *
 * - Ámbito OAuth `drive.file`: la app SOLO ve los archivos que ella misma crea, no el resto del Drive del usuario.
 * - Todo lo que se sube ya está cifrado en el dispositivo: Google solo ve objetos ilegibles.
 * - Una carpeta "SocialRecoverPro-Boveda" con un archivo por objeto ("blobs/ab/…" → "blobs~ab~…").
 */
class DriveRepoBackend(
    private val tokens: AccessTokenProvider,
    override val displayName: String = "Google Drive",
    private val apiBase: String = "https://www.googleapis.com/drive/v3",
    private val uploadBase: String = "https://www.googleapis.com/upload/drive/v3",
    private val folderName: String = FOLDER,
) : RepoBackend {
    override val kind = StorageKind.CLOUD
    private val http = Http("", retries = 3)
    private val ids = ConcurrentHashMap<String, String>()
    @Volatile private var listed = false
    @Volatile private var folderId: String? = null

    private fun name(key: String) = key.replace("/", "~")
    private fun key(name: String) = name.replace("~", "/")
    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Llamada autenticada; renueva el token una vez si Google responde 401. Reintenta los límites de frecuencia. */
    private fun call(method: String, url: String, body: ByteArray? = null, headers: Map<String, String> = emptyMap()): HttpResult {
        var refreshed = false
        var rateRetries = 0
        while (true) {
            http.token = try { tokens.token() } catch (e: SrpException) { throw e } catch (e: Exception) { throw SrpException.AuthRequired() }
            val r = http.call(method, url, body, headers)
            when {
                r.code == 401 && !refreshed -> { tokens.invalidate(); refreshed = true }
                r.code == 401 -> throw SrpException.AuthRequired()
                r.code == 403 && r.text.contains("storageQuotaExceeded") -> throw SrpException.NoSpace(IOException("Google Drive lleno"))
                (r.code == 429 || r.code == 403 && (r.text.contains("rateLimitExceeded") || r.text.contains("userRateLimitExceeded"))) && rateRetries < 5 -> {
                    Thread.sleep(500L shl rateRetries); rateRetries++
                }
                else -> return r
            }
        }
    }

    private fun ok(r: HttpResult, vararg codes: Int): HttpResult {
        if (r.code in codes) return r
        if (r.code == 403 && r.text.contains("insufficient", true)) throw SrpException.PermissionDenied("tu Google Drive")
        throw SrpException.Storage(IOException("Google Drive respondió ${r.code}: ${r.detail()}"))
    }

    private fun json(r: HttpResult) = Json.parse(r.text) as Map<*, *>

    @Synchronized
    private fun folder(): String {
        folderId?.let { return it }
        val query = "name='$folderName' and mimeType='application/vnd.google-apps.folder' and trashed=false and 'root' in parents"
        val found = (json(ok(call("GET", "$apiBase/files?q=${q(query)}&fields=${q("files(id,name)")}&spaces=drive"), 200))["files"] as List<*>)
            .firstOrNull()?.let { (it as Map<*, *>)["id"] as String }
        val id = found ?: (json(ok(call("POST", "$apiBase/files?fields=id",
            """{"name":"$folderName","mimeType":"application/vnd.google-apps.folder"}""".toByteArray(),
            mapOf("Content-Type" to "application/json; charset=UTF-8")), 200))["id"] as String)
        folderId = id
        return id
    }

    @Synchronized
    private fun listAll(): Map<String, Pair<String, Long>> {
        val out = HashMap<String, Pair<String, Long>>()
        var page: String? = null
        val query = "'${folder()}' in parents and trashed=false"
        do {
            val url = "$apiBase/files?q=${q(query)}&fields=${q("nextPageToken,files(id,name,size)")}&pageSize=1000&spaces=drive" +
                (page?.let { "&pageToken=${q(it)}" } ?: "")
            val m = json(ok(call("GET", url), 200))
            (m["files"] as List<*>).forEach { f ->
                f as Map<*, *>
                val n = f["name"] as String
                if (!out.containsKey(n)) out[n] = (f["id"] as String) to ((f["size"] as? String)?.toLongOrNull() ?: (f["size"] as? Number)?.toLong() ?: 0L)
            }
            page = m["nextPageToken"] as? String
        } while (page != null)
        ids.clear(); out.forEach { (n, v) -> ids[n] = v.first }
        listed = true
        return out
    }

    private fun idOf(key: String): String? {
        val n = name(key)
        ids[n]?.let { return it }
        if (!listed) listAll()
        return ids[n]
    }

    override fun list(prefix: String): List<RepoObject> =
        listAll().filterKeys { key(it).startsWith(prefix) }.map { (n, v) -> RepoObject(key(n), v.second) }

    override fun read(key: String): ByteArray? {
        val id = idOf(key) ?: return null
        val r = call("GET", "$apiBase/files/$id?alt=media")
        if (r.code == 404) { ids.remove(name(key)); return null }
        return ok(r, 200).body
    }

    override fun write(key: String, data: ByteArray) {
        val n = name(key)
        val existing = idOf(key)
        if (existing != null) {
            // HttpURLConnection no admite PATCH: las API de Google aceptan POST + X-HTTP-Method-Override.
            val r = call("POST", "$uploadBase/files/$existing?uploadType=media", data,
                mapOf("Content-Type" to "application/octet-stream", "X-HTTP-Method-Override" to "PATCH"))
            if (r.code != 404) { ok(r, 200); return }
            ids.remove(n)
        }
        // Subida "multipart": metadatos + contenido en una sola petición (o se crea completo o no se crea).
        val boundary = "srp" + System.nanoTime()
        val body = ByteArrayOutputStream(data.size + 512).apply {
            write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n".toByteArray())
            write("""{"name":"$n","parents":["${folder()}"]}""".toByteArray())
            write("\r\n--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
            write(data)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        val r = ok(call("POST", "$uploadBase/files?uploadType=multipart&fields=id", body, mapOf("Content-Type" to "multipart/related; boundary=$boundary")), 200)
        ids[n] = json(r)["id"] as String
    }

    override fun delete(key: String) {
        val id = idOf(key) ?: return
        ok(call("DELETE", "$apiBase/files/$id"), 204, 200, 404)
        ids.remove(name(key))
    }

    override fun exists(key: String) = idOf(key) != null

    override fun usage(): Usage? = runCatching {
        val q = json(ok(call("GET", "$apiBase/about?fields=storageQuota"), 200))["storageQuota"] as Map<*, *>
        Usage((q["usage"] as String).toLong(), (q["limit"] as? String)?.toLongOrNull())
    }.getOrNull()

    companion object {
        const val FOLDER = "SocialRecoverPro-Boveda"
        const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    }
}
