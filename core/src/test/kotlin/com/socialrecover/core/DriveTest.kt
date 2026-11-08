package com.socialrecover.core

import com.socialrecover.core.cloud.AccessTokenProvider
import com.socialrecover.core.cloud.DriveRepoBackend
import com.socialrecover.core.crypto.CryptoParams
import com.socialrecover.core.engine.SrpException
import com.socialrecover.core.store.FileRestoreTarget
import com.socialrecover.core.util.Json
import com.socialrecover.core.vault.BytesSource
import com.socialrecover.core.vault.DeviceKind
import com.socialrecover.core.vault.Vault
import com.socialrecover.core.vault.VaultBackupSession
import com.socialrecover.core.vault.VaultCheck
import com.socialrecover.core.vault.VaultRestore
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Bóveda en Google Drive contra un servidor local que imita la API de Drive v3 (el subconjunto que usamos:
 * carpetas, listado paginado, subida multipart, actualización con X-HTTP-Method-Override, descarga, borrado,
 * cuota, tokens caducados y Drive lleno). No usa cuentas reales.
 */
class DriveTest {
    class FakeDrive(val quota: Long = 50L shl 20, val pageSize: Int = 3) : AutoCloseable {
        data class F(val id: String, val name: String, val parent: String?, val folder: Boolean, var data: ByteArray)
        val files = ConcurrentHashMap<String, F>()
        val validToken = "token-2"
        var currentValid = "token-1"
        val calls = AtomicInteger()
        private val n = AtomicInteger()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val api get() = "http://127.0.0.1:${server.address.port}/drive/v3"
        val upload get() = "http://127.0.0.1:${server.address.port}/upload/drive/v3"

        private fun send(x: HttpExchange, code: Int, body: ByteArray = ByteArray(0), type: String = "application/json") {
            x.responseHeaders.add("Content-Type", type)
            x.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) x.responseBody.use { it.write(body) } else x.close()
        }
        private fun js(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        private fun params(x: HttpExchange) = (x.requestURI.rawQuery ?: "").split("&").filter { it.contains("=") }
            .associate { URLDecoder.decode(it.substringBefore("="), "UTF-8") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }
        private fun used() = files.values.sumOf { it.data.size.toLong() }

        init {
            server.createContext("/") { x ->
                calls.incrementAndGet()
                try {
                    if (x.requestHeaders.getFirst("Authorization") != "Bearer $currentValid") { send(x, 401, """{"error":{"code":401}}""".toByteArray()); return@createContext }
                    val path = x.requestURI.path
                    val p = params(x)
                    val method = x.requestHeaders.getFirst("X-HTTP-Method-Override") ?: x.requestMethod
                    val body = x.requestBody.readBytes()
                    when {
                        path.endsWith("/about") -> send(x, 200, """{"storageQuota":{"limit":"$quota","usage":"${used()}"}}""".toByteArray())
                        method == "GET" && path.endsWith("/files") -> {
                            val q = p["q"] ?: ""
                            var list = files.values.filter { true }
                            Regex("name='([^']+)'").find(q)?.let { m -> list = list.filter { it.name == m.groupValues[1] } }
                            Regex("'([^']+)' in parents").find(q)?.let { m -> list = list.filter { it.parent == m.groupValues[1] } }
                            list = list.sortedBy { it.id }
                            val start = p["pageToken"]?.toInt() ?: 0
                            val page = list.drop(start).take(pageSize)
                            val next = if (start + pageSize < list.size) ",\"nextPageToken\":\"${start + pageSize}\"" else ""
                            send(x, 200, ("""{"files":[""" + page.joinToString(",") { """{"id":"${it.id}","name":${js(it.name)},"size":"${it.data.size}"}""" } + "]$next}").toByteArray())
                        }
                        method == "POST" && path == "/drive/v3/files" -> {
                            val m = Json.parse(String(body)) as Map<*, *>
                            val id = "f" + n.incrementAndGet()
                            files[id] = F(id, m["name"] as String, "root", true, ByteArray(0))
                            send(x, 200, """{"id":"$id"}""".toByteArray())
                        }
                        method == "POST" && path.endsWith("/upload/drive/v3/files") -> {
                            val boundary = x.requestHeaders.getFirst("Content-Type").substringAfter("boundary=")
                            val text = String(body, Charsets.ISO_8859_1)
                            val parts = text.split("--$boundary").filter { it.contains("\r\n\r\n") }
                            val meta = Json.parse(parts[0].substringAfter("\r\n\r\n").trimEnd()) as Map<*, *>
                            val content = parts[1].substringAfter("\r\n\r\n").removeSuffix("\r\n").toByteArray(Charsets.ISO_8859_1)
                            if (used() + content.size > quota) { send(x, 403, """{"error":{"errors":[{"reason":"storageQuotaExceeded"}]}}""".toByteArray()); return@createContext }
                            val id = "f" + n.incrementAndGet()
                            files[id] = F(id, meta["name"] as String, (meta["parents"] as List<*>)[0] as String, false, content)
                            send(x, 200, """{"id":"$id"}""".toByteArray())
                        }
                        method == "PATCH" -> {
                            val id = path.substringAfterLast('/')
                            val f = files[id] ?: run { send(x, 404); return@createContext }
                            f.data = body; send(x, 200, """{"id":"$id"}""".toByteArray())
                        }
                        method == "GET" -> {
                            val f = files[path.substringAfterLast('/')] ?: run { send(x, 404, """{"error":404}""".toByteArray()); return@createContext }
                            send(x, 200, f.data, "application/octet-stream")
                        }
                        method == "DELETE" -> { files.remove(path.substringAfterLast('/')); send(x, 204) }
                        else -> send(x, 400)
                    }
                } catch (e: Exception) { e.printStackTrace(); send(x, 500) }
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }

    class Tokens(private val drive: FakeDrive) : AccessTokenProvider {
        var current = "token-1"; var refreshes = 0
        override fun token() = current
        override fun invalidate() { refreshes++; current = drive.validToken }
    }

    private val pw = "contraseña de la bóveda".toCharArray()
    private val fast = CryptoParams(2_000)

    @Test
    fun backupAndRestoreThroughGoogleDriveWithExpiredToken() {
        FakeDrive().use { d ->
            val tokens = Tokens(d)
            val be = DriveRepoBackend(tokens, apiBase = d.api, uploadBase = d.upload)
            val v = Vault.create(be, pw, fast)
            // Caduca el token a mitad: debe renovarse solo y continuar.
            d.currentValid = d.validToken
            val secret = "DIARIO-SECRETO ".repeat(5000).toByteArray()
            val files = (1..5).map { BytesSource("foto$it.jpg", Random(it).nextBytes(700_000)) } + BytesSource("diario.txt", secret, mimeType = "text/plain")
            VaultBackupSession.run(v, files, "x", "Pixel", DeviceKind.PHONE)
            assertEquals(1, tokens.refreshes)

            // Otro dispositivo (el PC) abre la misma bóveda desde Drive.
            val v2 = Vault.open(DriveRepoBackend(Tokens(d).apply { current = d.validToken }, apiBase = d.api, uploadBase = d.upload), pw)
            val snap = v2.loadSnapshot(v2.listSnapshots().single().name)
            val dest = Files.createTempDirectory("srp-drive").toFile()
            assertTrue(VaultRestore.restore(v2, snap, snap.files, FileRestoreTarget(dest)).allOk)
            assertTrue(File(dest, "diario.txt").readBytes().contentEquals(secret))
            assertTrue(VaultCheck.run(v2, readData = true).ok)
            assertEquals(1, d.files.values.count { it.folder }, "Una sola carpeta en Drive")
            assertTrue(d.files.values.none { String(it.data, Charsets.ISO_8859_1).contains("DIARIO-SECRETO") }, "Google solo ve datos cifrados")
            assertTrue(d.files.values.none { it.name.contains("diario") || it.name.contains("foto") }, "Ni siquiera los nombres")

            // Sobrescritura (cambio de contraseña) usa la actualización de Drive, sin duplicar archivos.
            val before = d.files.size
            v2.changePassword("nueva".toCharArray(), fast)
            assertEquals(before, d.files.size)
            assertFailsWith<SrpException.WrongPassword> { Vault.open(DriveRepoBackend(Tokens(d).apply { current = d.validToken }, apiBase = d.api, uploadBase = d.upload), pw) }
            assertTrue(be.usage()!!.usedBytes > 3_000_000)
        }
    }

    @Test
    fun driveFullAndRevokedAccess() {
        FakeDrive(quota = 2L shl 20).use { d ->
            d.currentValid = d.validToken
            val v = Vault.create(DriveRepoBackend(Tokens(d).apply { current = d.validToken }, apiBase = d.api, uploadBase = d.upload), pw, fast)
            assertFailsWith<SrpException.NoSpace> {
                VaultBackupSession.run(v, (1..4).map { BytesSource("v$it.mp4", Random(it).nextBytes(900_000), mimeType = "video/mp4") }, "x", "Pixel", DeviceKind.PHONE)
            }
            assertTrue(v.listSnapshots().isEmpty())
            // Acceso revocado por el usuario: el token renovado tampoco vale.
            val revoked = object : AccessTokenProvider { override fun token() = "revocado"; override fun invalidate() {} }
            assertFailsWith<SrpException.AuthRequired> { DriveRepoBackend(revoked, apiBase = d.api, uploadBase = d.upload).list("") }
            assertFalse(d.files.isEmpty())
        }
    }
}
