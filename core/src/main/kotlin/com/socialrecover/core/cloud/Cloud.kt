package com.socialrecover.core.cloud

import com.socialrecover.core.engine.SrpException
import com.socialrecover.core.util.Bin
import com.socialrecover.core.util.Json
import com.socialrecover.core.vault.RepoBackend
import com.socialrecover.core.vault.RepoObject
import com.socialrecover.core.vault.StorageKind
import com.socialrecover.core.vault.Usage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** Respuesta HTTP mínima. */
internal class HttpResult(val code: Int, val body: ByteArray, val headers: Map<String, String>) {
    val text get() = body.toString(Charsets.UTF_8)
}

/**
 * Cliente HTTP sin dependencias (java.net, disponible en Android y en escritorio).
 * Reintenta con espera creciente ante cortes de red; si persisten, lanza [SrpException.Offline].
 */
internal class Http(private val baseUrl: String, var token: String? = null, private val retries: Int = 3, private val backoffMs: Long = 300) {
    fun call(method: String, path: String, body: ByteArray? = null, headers: Map<String, String> = emptyMap()): HttpResult {
        var last: IOException? = null
        for (attempt in 0..retries) {
            if (attempt > 0) Thread.sleep(backoffMs * (1L shl (attempt - 1)))
            val c = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = 10_000; c.readTimeout = 60_000
                c.setRequestProperty("User-Agent", "SocialRecoverPro/1.0")
                token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                if (body != null) {
                    c.doOutput = true
                    c.setFixedLengthStreamingMode(body.size)
                    c.outputStream.use { it.write(body) }
                }
                val code = c.responseCode
                val stream = if (code >= 400) c.errorStream else if (method == "HEAD") null else c.inputStream
                val bytes = stream?.use { s -> ByteArrayOutputStream().also { s.copyTo(it) }.toByteArray() } ?: ByteArray(0)
                if (code in 500..599 && code != 507 && attempt < retries) { last = IOException("HTTP $code"); continue }
                return HttpResult(code, bytes, c.headerFields.filterKeys { it != null }.mapValues { it.value.firstOrNull() ?: "" })
            } catch (e: IOException) {
                last = e
            } finally {
                c.disconnect()
            }
        }
        throw SrpException.Offline(last)
    }
}

internal fun HttpResult.detail(): String =
    runCatching { (Json.parse(text) as? Map<*, *>)?.get("detail")?.toString() }.getOrNull() ?: "HTTP $code"

/** Cuenta de usuario en la nube de SocialRecover Pro (distinta de la contraseña de las copias). */
class CloudAccount(private val baseUrl: String) {
    data class Session(val token: String)

    private fun post(path: String, json: String, token: String? = null): HttpResult =
        Http(baseUrl, token, retries = 1).call("POST", path, json.toByteArray(), mapOf("Content-Type" to "application/json"))

    private fun js(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    fun register(email: String, password: String, device: String): Session {
        val r = post("/v1/auth/register", """{"email":${js(email)},"password":${js(password)},"device":${js(device)}}""")
        if (r.code != 201) throw IllegalArgumentException(r.detail())
        return Session((Json.parse(r.text) as Map<*, *>)["token"] as String)
    }

    fun login(email: String, password: String, device: String, totp: String? = null): Session {
        val t = totp?.let { ""","totp":${js(it)}""" } ?: ""
        val r = post("/v1/auth/login", """{"email":${js(email)},"password":${js(password)},"device":${js(device)}$t}""")
        if (r.code == 401) throw SrpException.WrongPassword()
        if (r.code != 200) throw IllegalArgumentException(r.detail())
        return Session((Json.parse(r.text) as Map<*, *>)["token"] as String)
    }

    fun deleteAccount(token: String) {
        val r = Http(baseUrl, token, retries = 1).call("DELETE", "/v1/account")
        if (r.code != 204) throw IllegalStateException(r.detail())
    }
}

/**
 * Bóveda en la nube. Solo recibe objetos ya cifrados en el dispositivo.
 * Cada subida lleva su SHA-256 para que el servidor rechace objetos dañados en tránsito.
 */
class HttpRepoBackend(baseUrl: String, token: String, override val displayName: String = "Nube SocialRecover Pro") : RepoBackend {
    override val kind = StorageKind.CLOUD
    private val http = Http(baseUrl, token)

    private fun enc(k: String) = k.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun check(r: HttpResult, okCodes: Set<Int>): HttpResult {
        if (r.code in okCodes) return r
        throw when (r.code) {
            401, 403 -> SrpException.AuthRequired()
            507 -> SrpException.NoSpace(IOException("Cuota de la nube superada"))
            413 -> SrpException.Storage(IOException("Objeto demasiado grande"))
            else -> SrpException.Storage(IOException("Error del servidor: ${r.detail()}"))
        }
    }

    override fun list(prefix: String): List<RepoObject> {
        val out = ArrayList<RepoObject>()
        var after = ""
        while (true) {
            val r = check(http.call("GET", "/v1/repo?prefix=${URLEncoder.encode(prefix, "UTF-8")}&after=${URLEncoder.encode(after, "UTF-8")}&limit=5000"), setOf(200))
            val m = Json.parse(r.text) as Map<*, *>
            (m["items"] as List<*>).forEach { i -> val o = i as Map<*, *>; out += RepoObject(o["key"] as String, (o["size"] as Number).toLong()) }
            after = m["next"] as? String ?: break
        }
        return out
    }

    override fun read(key: String): ByteArray? {
        val r = http.call("GET", "/v1/repo/${enc(key)}")
        if (r.code == 404) return null
        check(r, setOf(200))
        val sha = r.headers.entries.firstOrNull { it.key.equals("X-Content-SHA256", true) }?.value
        if (sha != null && sha != Bin.hex(MessageDigest.getInstance("SHA-256").digest(r.body))) throw SrpException.Offline(IOException("Descarga incompleta"))
        return r.body
    }

    override fun write(key: String, data: ByteArray) {
        val sha = Bin.hex(MessageDigest.getInstance("SHA-256").digest(data))
        check(http.call("PUT", "/v1/repo/${enc(key)}", data, mapOf("X-Content-SHA256" to sha, "Content-Type" to "application/octet-stream")), setOf(200, 201))
    }

    override fun delete(key: String) {
        check(http.call("DELETE", "/v1/repo/${enc(key)}"), setOf(204, 404))
    }

    override fun exists(key: String): Boolean {
        val r = http.call("HEAD", "/v1/repo/${enc(key)}")
        if (r.code == 404) return false
        check(r, setOf(200)); return true
    }

    override fun usage(): Usage {
        val m = Json.parse(check(http.call("GET", "/v1/usage"), setOf(200)).text) as Map<*, *>
        return Usage((m["used_bytes"] as Number).toLong(), (m["quota_bytes"] as Number).toLong())
    }
}
