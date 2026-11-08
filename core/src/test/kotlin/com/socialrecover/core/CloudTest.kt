package com.socialrecover.core

import com.socialrecover.core.cloud.CloudAccount
import com.socialrecover.core.cloud.HttpRepoBackend
import com.socialrecover.core.crypto.CryptoParams
import com.socialrecover.core.engine.SrpException
import com.socialrecover.core.model.Category
import com.socialrecover.core.store.FileRestoreTarget
import com.socialrecover.core.vault.BytesSource
import com.socialrecover.core.vault.Destination
import com.socialrecover.core.vault.DeviceKind
import com.socialrecover.core.vault.HybridBackup
import com.socialrecover.core.vault.HybridPolicy
import com.socialrecover.core.vault.LocalRepoBackend
import com.socialrecover.core.vault.Vault
import com.socialrecover.core.vault.VaultBackupSession
import com.socialrecover.core.vault.VaultCheck
import com.socialrecover.core.vault.VaultRestore
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pruebas de extremo a extremo contra el servidor REAL (FastAPI) arrancado como proceso.
 * Requiere: SRP_PYTHON=/ruta/python (con requirements-dev instalados) y SRP_BACKEND_DIR=/ruta/backend.
 * Un proxy TCP intermedio simula cortes de conexión reales.
 */
class CloudTest {
    companion object {
        private val python = System.getenv("SRP_PYTHON")
        private val backendDir = System.getenv("SRP_BACKEND_DIR")
        private var server: Process? = null
        private var port = 0
        private val dataDir by lazy { Files.createTempDirectory("srp-server").toFile() }
        const val QUOTA = 30L shl 20

        fun baseUrl(): String? {
            if (python == null || backendDir == null) return null
            if (server == null) {
                port = ServerSocket(0).use { it.localPort }
                val pb = ProcessBuilder(python, "-m", "uvicorn", "--factory", "app.main:create_app", "--port", "$port", "--log-level", "warning")
                    .directory(File(backendDir)).redirectErrorStream(true).redirectOutput(File(dataDir, "server.log"))
                pb.environment().putAll(mapOf(
                    "SRP_DATABASE_URL" to "sqlite:///${dataDir}/srp.db", "SRP_STORAGE_DIR" to "$dataDir/objects",
                    "SRP_DEFAULT_QUOTA_BYTES" to "$QUOTA", "NO_PROXY" to "*", "no_proxy" to "*",
                ))
                server = pb.start()
                Runtime.getRuntime().addShutdownHook(Thread { server?.destroy() })
                val deadline = System.currentTimeMillis() + 20_000
                while (System.currentTimeMillis() < deadline) {
                    if (runCatching { Socket("127.0.0.1", port).close() }.isSuccess) return "http://127.0.0.1:$port"
                    Thread.sleep(200)
                }
                error("El servidor no arrancó: " + File(dataDir, "server.log").readText())
            }
            return "http://127.0.0.1:$port"
        }
    }

    /** Proxy TCP que puede "cortar la conexión" a voluntad. */
    class FlakyProxy(private val upstreamPort: Int) : AutoCloseable {
        val down = AtomicBoolean(false)
        /** Si > 0, corta las conexiones cuando se hayan reenviado tantos bytes hacia el servidor. */
        val cutAfterBytes = AtomicLong(0)
        private val forwarded = AtomicLong(0)
        private val ss = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val url get() = "http://127.0.0.1:${ss.localPort}"
        private val open = java.util.Collections.synchronizedList(ArrayList<Socket>())

        init {
            thread(isDaemon = true) {
                while (!ss.isClosed) {
                    val c = runCatching { ss.accept() }.getOrNull() ?: break
                    if (down.get()) { c.close(); continue }
                    val u = runCatching { Socket("127.0.0.1", upstreamPort) }.getOrNull()
                    if (u == null) { c.close(); continue }
                    open += c; open += u
                    pipe(c, u, true); pipe(u, c, false)
                }
            }
        }

        private fun pipe(a: Socket, b: Socket, upstream: Boolean) = thread(isDaemon = true) {
            val buf = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = a.getInputStream().read(buf)
                    if (n < 0) break
                    if (down.get()) break
                    b.getOutputStream().write(buf, 0, n)
                    if (upstream && cutAfterBytes.get() > 0 && forwarded.addAndGet(n.toLong()) > cutAfterBytes.get()) { down.set(true); break }
                }
            } catch (_: Exception) {
            } finally { runCatching { a.close() }; runCatching { b.close() } }
        }

        override fun close() { ss.close(); open.forEach { runCatching { it.close() } } }
    }

    private val fast = CryptoParams(pbkdf2Iterations = 2_000)
    private val pw = "contraseña de la bóveda".toCharArray()
    private var n = 0
    private var lastEmail = ""
    private fun newAccount(url: String): CloudAccount.Session {
        lastEmail = "prueba${System.nanoTime()}${n++}@example.com"
        return CloudAccount(url).register(lastEmail, "cuenta-larga-123", "JVM")
    }
    private fun files(count: Int, size: Int, seed: Int = 0) = (1..count).map { BytesSource("doc$it.bin", Random(seed + it).nextBytes(size), "Documentos", Category.DOCUMENTS) }

    private fun skip(): Boolean { if (baseUrl() == null) { println("    (omitida: define SRP_PYTHON y SRP_BACKEND_DIR)"); return true }; return false }

    @Test
    fun cloudBackupRestoreFromAnotherDeviceAndZeroKnowledge() {
        if (skip()) return
        val url = baseUrl()!!
        val s = newAccount(url)
        val v = Vault.create(HttpRepoBackend(url, s.token), pw, fast)
        val secret = "TEXTO-SECRETO-DEL-USUARIO ".repeat(2000).toByteArray()
        val r = VaultBackupSession.run(v, files(3, 400_000) + BytesSource("diario_privado.txt", secret, mimeType = "text/plain"), "nube", "Pixel", DeviceKind.PHONE)

        // Otro dispositivo: inicia sesión y abre la bóveda con la contraseña.
        assertFailsWith<SrpException.WrongPassword> { CloudAccount(url).login(lastEmail, "contraseña-mala-1", "Portátil") }
        val s2 = CloudAccount(url).login(lastEmail, "cuenta-larga-123", "Portátil")
        val v2 = Vault.open(HttpRepoBackend(url, s2.token), pw)
        val snap = v2.loadSnapshot(v2.listSnapshots().single().name)
        val dest = Files.createTempDirectory("srp-cloud-out").toFile()
        val rep = VaultRestore.restore(v2, snap, snap.files, FileRestoreTarget(dest))
        assertTrue(rep.allOk, rep.toString())
        assertTrue(File(dest, "diario_privado.txt").readBytes().contentEquals(secret))
        assertTrue(VaultCheck.run(v2, readData = true).ok)
        assertEquals(r.snapshot.files.size, snap.files.size)

        // Conocimiento cero: nada de lo almacenado en el servidor contiene el texto ni los nombres.
        dataDir.walkTopDown().filter { it.isFile && it.parentFile.path.contains("objects") }.forEach {
            val txt = String(it.readBytes(), Charsets.ISO_8859_1)
            assertFalse(txt.contains("TEXTO-SECRETO") || txt.contains("diario_privado"), it.path)
        }
        assertFailsWith<SrpException.WrongPassword> { Vault.open(HttpRepoBackend(url, s.token), "mala".toCharArray()) }
        assertFailsWith<SrpException.AuthRequired> { HttpRepoBackend(url, "token-falso").list("") }
    }

    @Test
    fun connectionLossDuringUploadThenResume() {
        if (skip()) return
        val url = baseUrl()!!
        val s = newAccount(url)
        FlakyProxy(port).use { proxy ->
            val v = Vault.create(HttpRepoBackend(proxy.url, s.token), pw, fast)
            val fs = files(8, 1_100_000, seed = 50)
            proxy.cutAfterBytes.set(3_500_000) // se corta la conexión en mitad de la subida
            val e = assertFailsWith<SrpException.Offline> { VaultBackupSession.run(v, fs, "x", "Pixel", DeviceKind.PHONE) }
            assertTrue(e.userMessage.contains("reanudará"))
            proxy.cutAfterBytes.set(0); proxy.down.set(false)
            val v2 = Vault.open(HttpRepoBackend(proxy.url, s.token), pw)
            assertTrue(v2.listSnapshots().isEmpty(), "Sin instantánea tras el corte")
            val before = v2.knownBlobs().size
            assertTrue(before > 0, "Parte de los bloques llegó antes del corte")
            val r = VaultBackupSession.run(v2, fs, "x", "Pixel", DeviceKind.PHONE)
            assertTrue(r.snapshot.stats.bytesDeduplicated > 0, "Reanudación: no se vuelve a subir lo que ya estaba")
            // Restaurar con un corte a mitad: se informa, no se deja ningún archivo a medias.
            proxy.cutAfterBytes.set(0)
            val dest = Files.createTempDirectory("srp-cut").toFile()
            val snap = v2.loadSnapshot(r.snapshot.name)
            val fresh = Vault.open(HttpRepoBackend(proxy.url, s.token), pw)
            var calls = 0
            proxy.down.set(false)
            val rep = VaultRestore.restore(fresh, snap, snap.files, FileRestoreTarget(dest), onProgress = { if (++calls == 6) proxy.down.set(true) })
            assertTrue(rep.fatal is SrpException.Offline, "fatal=${rep.fatal}")
            proxy.down.set(false)
            val okFiles = rep.results.filter { it.ok }.map { it.path }.toSet()
            dest.walkTopDown().filter { it.isFile }.forEach { assertTrue(it.relativeTo(dest).path in okFiles, "Archivo a medias: $it") }
            val rep2 = VaultRestore.restore(fresh, snap, snap.files, FileRestoreTarget(Files.createTempDirectory("srp-cut2").toFile()))
            assertTrue(rep2.allOk)
        }
    }

    @Test
    fun quotaAndHybridModeKeepLocalCopyWhenCloudFails() {
        if (skip()) return
        val url = baseUrl()!!
        val s = newAccount(url)
        val cloud = Vault.create(HttpRepoBackend(url, s.token), pw, fast)
        val e = assertFailsWith<SrpException.NoSpace> {
            VaultBackupSession.run(cloud, listOf(BytesSource("enorme.mp4", Random(7).nextBytes((QUOTA + (2 shl 20)).toInt()), mimeType = "video/mp4")), "x", "Pixel", DeviceKind.PHONE)
        }
        assertTrue(e.userMessage.contains("espacio"))
        val usage = cloud.backend.usage()!!
        assertTrue(usage.usedBytes <= QUOTA && usage.quotaBytes == QUOTA)

        // Híbrido: vídeos grandes en local, documentos en ambos. La nube se cae → la copia local sigue.
        val local = Vault.create(LocalRepoBackend(Files.createTempDirectory("srp-hyb").toFile()), pw, fast)
        FlakyProxy(port).use { proxy ->
            val cloud2 = Vault.open(HttpRepoBackend(proxy.url, s.token), pw)
            val policy = HybridPolicy(mapOf(Category.DOCUMENTS to Destination.BOTH, Category.VIDEOS to Destination.CLOUD), maxCloudFileBytes = 1_000_000)
            val srcs = listOf(
                BytesSource("contrato.pdf", Random(1).nextBytes(50_000), category = Category.DOCUMENTS),
                BytesSource("boda.mp4", Random(2).nextBytes(2_000_000), category = Category.VIDEOS),
            )
            val ok = HybridBackup.run(local, cloud2, srcs, policy, "h", "Pixel", DeviceKind.PHONE)
            assertEquals(2, ok.local!!.snapshot.files.size, "El vídeo grande se queda en local")
            assertEquals(listOf("contrato.pdf"), ok.cloud!!.snapshot.files.map { it.name })
            proxy.down.set(true)
            val off = HybridBackup.run(local, cloud2, srcs.map { BytesSource(it.displayName, Random(9).nextBytes(60_000), category = it.category) }, policy, "h2", "Pixel", DeviceKind.PHONE)
            assertNotNull(off.local); assertTrue(off.cloudError is SrpException.Offline)
        }
        CloudAccount(url).deleteAccount(s.token)
        assertFailsWith<SrpException.AuthRequired> { HttpRepoBackend(url, s.token).list("") }
    }
}
