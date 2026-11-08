package com.socialrecover.desktop

import com.socialrecover.core.cloud.AccessTokenProvider
import com.socialrecover.core.cloud.DriveRepoBackend
import com.socialrecover.core.engine.SrpException
import com.socialrecover.core.util.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties

/**
 * Inicio de sesión de Google en el ordenador (OAuth para aplicaciones instaladas, con PKCE y redirección local).
 * Usa el MISMO proyecto de Google Cloud que la app Android: con el permiso `drive.file`, ambas ven la misma
 * carpeta de bóveda en Drive y solo esa carpeta.
 */
object GoogleDesktopAuth {
    private val file = File(System.getProperty("user.home"), ".socialrecover/google.properties")
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun post(url: String, form: Map<String, String>): Map<*, *> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(form.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }.toByteArray()) }
        val code = c.responseCode
        val body = (if (code >= 400) c.errorStream else c.inputStream)?.readBytes()?.toString(Charsets.UTF_8) ?: ""
        if (code != 200) {
            if (body.contains("invalid_grant")) throw SrpException.AuthRequired()
            throw IllegalStateException("Google respondió $code: $body")
        }
        return Json.parse(body) as Map<*, *>
    }

    fun login(clientId: String, clientSecret: String) {
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { ss ->
            val redirect = "http://127.0.0.1:${ss.localPort}"
            val url = "https://accounts.google.com/o/oauth2/v2/auth?client_id=${enc(clientId)}&redirect_uri=${enc(redirect)}" +
                "&response_type=code&scope=${enc(DriveRepoBackend.SCOPE)}&code_challenge=$challenge&code_challenge_method=S256" +
                "&access_type=offline&prompt=consent"
            println("Abre esta dirección en el navegador e inicia sesión con tu cuenta de Google:\n\n$url\n")
            runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
            ss.accept().use { s ->
                val line = s.getInputStream().bufferedReader().readLine() ?: ""
                val q = line.substringAfter("?", "").substringBefore(" ")
                val params = q.split("&").filter { it.contains("=") }.associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }
                val ok = params["code"] != null
                val html = if (ok) "SocialRecover Pro: acceso concedido. Ya puedes cerrar esta ventana." else "No se concedió el acceso."
                s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\n\r\n$html".toByteArray())
                val code = params["code"] ?: throw IllegalStateException("Google no concedió el acceso: ${params["error"]}")
                val t = post(TOKEN_URL, mapOf("code" to code, "client_id" to clientId, "client_secret" to clientSecret,
                    "redirect_uri" to redirect, "grant_type" to "authorization_code", "code_verifier" to verifier))
                val refresh = t["refresh_token"] as? String ?: throw IllegalStateException("Google no devolvió un token de renovación")
                file.parentFile.mkdirs()
                val p = Properties().apply { put("client_id", clientId); put("client_secret", clientSecret); put("refresh_token", refresh) }
                file.outputStream().use { p.store(it, "SocialRecover Pro - acceso a Google Drive (no compartir)") }
                runCatching { file.setReadable(false, false); file.setReadable(true, true); file.setWritable(false, false); file.setWritable(true, true) }
            }
        }
        println("Google Drive conectado. Usa «drive» como bóveda: srp backup drive C:\\Users\\tu\\Pictures")
    }

    fun logout() { file.delete() }

    /** Proveedor de tokens a partir del token de renovación guardado. */
    fun tokens(): AccessTokenProvider {
        if (!file.isFile) throw IllegalStateException("Primero conecta Google Drive: srp drive-login --client-id=… --client-secret=…")
        val p = Properties().apply { file.inputStream().use { load(it) } }
        return object : AccessTokenProvider {
            @Volatile var cached: String? = null
            override fun token(): String = cached ?: (post(TOKEN_URL, mapOf("client_id" to p.getProperty("client_id"),
                "client_secret" to p.getProperty("client_secret"), "refresh_token" to p.getProperty("refresh_token"),
                "grant_type" to "refresh_token"))["access_token"] as String).also { cached = it }
            override fun invalidate() { cached = null }
        }
    }
}
