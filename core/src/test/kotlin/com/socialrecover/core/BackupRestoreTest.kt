package com.socialrecover.core

import com.socialrecover.core.crypto.CryptoParams
import com.socialrecover.core.engine.BackupEngine
import com.socialrecover.core.engine.BackupSummary
import com.socialrecover.core.engine.EntryStatus
import com.socialrecover.core.engine.RestoreEngine
import com.socialrecover.core.engine.SafeNames
import com.socialrecover.core.engine.SrpException
import com.socialrecover.core.model.Category
import com.socialrecover.core.store.BackupLocation
import com.socialrecover.core.store.BackupSource
import com.socialrecover.core.store.FileBackupLocation
import com.socialrecover.core.store.FileRestoreTarget
import com.socialrecover.core.store.FileSource
import org.junit.Test
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pruebas de extremo a extremo del motor: copia -> restauración -> verificación byte a byte.
 * Todos los datos son sintéticos (no se usan datos personales reales).
 */
class BackupRestoreTest {
    // Iteraciones reducidas para que las pruebas sean rápidas; hay una prueba con el valor real.
    private val fast = CryptoParams(pbkdf2Iterations = 2_000, chunkSize = 64 * 1024)
    private val pw = "Contraseña-de-prueba 123".toCharArray()

    private fun tmp(): File = Files.createTempDirectory("srp-test").toFile()

    private fun writeRandom(f: File, size: Int, seed: Int = size): File {
        f.parentFile.mkdirs()
        f.writeBytes(Random(seed).nextBytes(size)); return f
    }

    private fun sha(f: File): String = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    private fun backupAndRestore(files: List<File>, params: CryptoParams = fast): Pair<File, List<File>> {
        val root = tmp()
        val loc = FileBackupLocation(File(root, "copias"))
        val res = BackupEngine(params).create(loc, "Prueba", pw, files.map { FileSource(it) }, "JVM")
        assertTrue(res.skipped.isEmpty())
        val engine = RestoreEngine()
        val list = engine.list(loc)
        assertEquals(1, list.size)
        assertEquals(BackupSummary.Status.OK, list[0].status)
        val dest = File(root, "restaurado")
        val report = engine.open(loc, list[0].dirName, pw).use { b ->
            engine.restore(loc, b, null, FileRestoreTarget(dest))
        }
        assertTrue(report.allOk, "Informe: $report")
        assertTrue(report.results.all { it.status == EntryStatus.VERIFIED })
        return dest to report.results.map { File(dest, it.path!!) }
    }

    @Test
    fun smallFilesRoundTrip() {
        val src = tmp()
        val files = listOf(
            writeRandom(File(src, "foto.jpg"), 12_345),
            writeRandom(File(src, "nota.txt"), 1),
            File(src, "vacio.pdf").also { it.writeBytes(ByteArray(0)) },
            writeRandom(File(src, "exacto.bin"), 64 * 1024), // exactamente un bloque
            writeRandom(File(src, "exacto2.bin"), 2 * 64 * 1024), // dos bloques justos
        )
        val (_, restored) = backupAndRestore(files)
        files.zip(restored).forEach { (a, b) -> assertEquals(sha(a), sha(b), a.name) }
    }

    @Test
    fun specialFileNames() {
        val src = tmp()
        val names = listOf("Año nuevo 🎉 – señal.jpg", "con espacios  y (paréntesis).mp4", "日本語ファイル.pdf",
            "comillas'simples'.txt", "porcentaje%20y#almohadilla.doc", "MAYÚSCULAS.ÑÑ")
        val files = names.mapIndexed { i, n -> writeRandom(File(src, n), 1000 + i, i) }
        val (_, restored) = backupAndRestore(files)
        restored.forEachIndexed { i, f ->
            assertEquals(java.text.Normalizer.normalize(names[i], java.text.Normalizer.Form.NFC), f.name)
            assertEquals(sha(files[i]), sha(f))
        }
    }

    @Test
    fun hostileNamesStayInsideDestination() {
        assertEquals("archivo", SafeNames.sanitize(".."))
        assertEquals("archivo", SafeNames.sanitize("."))
        assertEquals("_CON.txt", SafeNames.sanitize("CON.txt"))
        assertEquals("a_b_c_.txt", SafeNames.sanitize("a/b\\c*.txt"))
        assertEquals(listOf("etc", "passwd"), SafeNames.sanitizePath("../../etc/passwd"))
        assertTrue(SafeNames.sanitize("x".repeat(500) + ".jpg").let { it.endsWith(".jpg") && it.toByteArray().size <= 200 })

        // Fuente con nombre y carpeta maliciosos: al restaurar debe quedar dentro del destino.
        val root = tmp()
        val loc = FileBackupLocation(File(root, "copias"))
        val evil = object : BackupSource {
            override val displayName = "../../fuera.txt"
            override val folder = "../../../tmp"
            override val mimeType = "text/plain"
            override val sizeHint = 3L
            override val lastModified = 0L
            override val category = Category.DOCUMENTS
            override fun open(): InputStream = "abc".byteInputStream()
        }
        val r = BackupEngine(fast).create(loc, "x", pw, listOf(evil), "JVM")
        val dest = File(root, "dest")
        val engine = RestoreEngine()
        val rep = engine.open(loc, r.dirName, pw).use { engine.restore(loc, it, null, FileRestoreTarget(dest)) }
        assertTrue(rep.allOk)
        val out = File(dest, rep.results[0].path!!)
        assertTrue(out.canonicalPath.startsWith(dest.canonicalPath))
        assertEquals("abc", out.readText())
    }

    @Test
    fun largeVideoRoundTrip() {
        // Tamaño configurable: -Dsrp.bigTestMb=1024 para probar con 1 GiB.
        val mb = System.getProperty("srp.bigTestMb")?.toInt() ?: 64
        val src = tmp()
        val video = File(src, "video_grande.mp4")
        RandomAccessFile(video, "rw").use { raf ->
            val block = Random(7).nextBytes(1 shl 20)
            repeat(mb) { i -> block[0] = i.toByte(); raf.write(block) }
            raf.write(byteArrayOf(1, 2, 3)) // tamaño no múltiplo del bloque
        }
        val (_, restored) = backupAndRestore(listOf(video), CryptoParams(2_000, CryptoParams.DEFAULT_CHUNK_SIZE))
        assertEquals(video.length(), restored[0].length())
        assertEquals(sha(video), sha(restored[0]))
        println("    (vídeo de ${video.length() / (1 shl 20)} MiB verificado)")
    }

    @Test
    fun wrongPasswordIsRejected() {
        val src = tmp()
        val loc = FileBackupLocation(File(src, "copias"))
        val r = BackupEngine(fast).create(loc, "x", pw, listOf(FileSource(writeRandom(File(src, "a.bin"), 100))), "JVM")
        assertFailsWith<SrpException.WrongPassword> { RestoreEngine().open(loc, r.dirName, "otra".toCharArray()) }
        assertFailsWith<SrpException.WrongPassword> { RestoreEngine().open(loc, r.dirName, CharArray(0)) }
        // Contraseña casi igual (diferencia de mayúsculas)
        assertFailsWith<SrpException.WrongPassword> { RestoreEngine().open(loc, r.dirName, "contraseña-de-prueba 123".toCharArray()) }
    }

    @Test
    fun corruptedDataIsDetected() {
        val src = tmp()
        val root = File(src, "copias")
        val loc = FileBackupLocation(root)
        val r = BackupEngine(fast).create(loc, "x", pw, listOf(FileSource(writeRandom(File(src, "a.bin"), 300_000))), "JVM")
        val data = File(File(root, r.dirName), "data.srpk")
        RandomAccessFile(data, "rw").use { it.seek(100_000); val b = it.read(); it.seek(100_000); it.write(b xor 0x01) }
        val engine = RestoreEngine()
        val dest = File(src, "dest")
        val rep = engine.open(loc, r.dirName, pw).use { engine.restore(loc, it, null, FileRestoreTarget(dest)) }
        assertFalse(rep.allOk)
        assertTrue(rep.fatal is SrpException.CorruptedBackup)
        assertTrue(rep.results.none { it.ok })
    }

    @Test
    fun corruptedManifestOrInfoIsDetected() {
        val src = tmp()
        val root = File(src, "copias")
        val loc = FileBackupLocation(root)
        val r = BackupEngine(fast).create(loc, "x", pw, listOf(FileSource(writeRandom(File(src, "a.bin"), 1000))), "JVM")
        val man = File(File(root, r.dirName), "manifest.srpm")
        val bytes = man.readBytes(); bytes[bytes.size - 5] = (bytes[bytes.size - 5].toInt() xor 0x40).toByte(); man.writeBytes(bytes)
        assertFailsWith<SrpException.CorruptedBackup> { RestoreEngine().open(loc, r.dirName, pw) }

        // Manipular las iteraciones en info.properties hace fallar el desenvolvimiento de la clave.
        val r2 = BackupEngine(fast).create(loc, "y", pw, listOf(FileSource(writeRandom(File(src, "b.bin"), 1000))), "JVM")
        val info = File(File(root, r2.dirName), "info.properties")
        info.writeText(info.readText().replace("iterations=2000", "iterations=2001"))
        assertFailsWith<SrpException.WrongPassword> { RestoreEngine().open(loc, r2.dirName, pw) }
    }

    @Test
    fun truncatedDataIsDetected() {
        val src = tmp()
        val root = File(src, "copias")
        val loc = FileBackupLocation(root)
        // 3 bloques completos + resto: cortamos justo en un límite de bloque (el caso difícil).
        val r = BackupEngine(fast).create(loc, "x", pw, listOf(FileSource(writeRandom(File(src, "a.bin"), 200_000))), "JVM")
        val data = File(File(root, r.dirName), "data.srpk")
        val cut = 16 + 2L * (64 * 1024 + 16)
        RandomAccessFile(data, "rw").use { it.setLength(cut) }
        val engine = RestoreEngine()
        val rep = engine.open(loc, r.dirName, pw).use { engine.restore(loc, it, null, null) }
        assertTrue(rep.fatal is SrpException.CorruptedBackup, "fatal=${rep.fatal}")
    }

    @Test
    fun interruptedBackupLeavesNoValidLookingCopy() {
        val src = tmp()
        val loc = FileBackupLocation(File(src, "copias"))
        val files = (1..5).map { writeRandom(File(src, "f$it.bin"), 200_000, it) }
        var calls = 0
        assertFailsWith<SrpException.Cancelled> {
            BackupEngine(fast).create(loc, "x", pw, files.map { FileSource(it) }, "JVM", isCancelled = { ++calls > 8 })
        }
        assertTrue(RestoreEngine().list(loc).isEmpty(), "La carpeta parcial debe eliminarse")

        // Simulación de cierre forzoso de la app: queda una carpeta .partial en disco.
        File(File(src, "copias"), "SRP-20260101-000000-abcd.partial").mkdirs()
        val l = RestoreEngine().list(loc)
        assertEquals(BackupSummary.Status.INCOMPLETE, l.single().status)
        assertFailsWith<SrpException.IncompleteBackup> { RestoreEngine().open(loc, l.single().dirName, pw) }
    }

    @Test
    fun noSpaceIsReportedClearly() {
        val src = tmp()
        val real = FileBackupLocation(File(src, "copias"))
        val full = object : BackupLocation by real {
            override fun openWrite(dir: String, file: String): OutputStream {
                val o = real.openWrite(dir, file)
                return object : FilterOutputStream(o) {
                    var n = 0L
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        n += len
                        if (n > 150_000) throw IOException("write failed: ENOSPC (No space left on device)")
                        out.write(b, off, len)
                    }
                }
            }
        }
        val e = assertFailsWith<SrpException.NoSpace> {
            BackupEngine(fast).create(full, "x", pw, listOf(FileSource(writeRandom(File(src, "a.bin"), 1_000_000))), "JVM")
        }
        assertTrue(e.userMessage.contains("espacio"))
        assertTrue(real.listDirs().isEmpty())
    }

    @Test
    fun permissionDeniedOnDestination() {
        val src = tmp()
        val real = FileBackupLocation(File(src, "copias"))
        val denied = object : BackupLocation by real {
            override fun createDir(name: String) = throw SecurityException("Permission Denial: no grant")
        }
        assertFailsWith<SrpException.PermissionDenied> {
            BackupEngine(fast).create(denied, "x", pw, listOf(FileSource(writeRandom(File(src, "a.bin"), 10))), "JVM")
        }
    }

    @Test
    fun providerWithoutRenameStillProducesValidBackup() {
        val src = tmp()
        val real = FileBackupLocation(File(src, "copias"))
        val noRename = object : BackupLocation by real {
            override fun renameDir(from: String, to: String) = throw UnsupportedOperationException("rename")
        }
        val a = writeRandom(File(src, "a.bin"), 5000)
        val r = BackupEngine(fast).create(noRename, "x", pw, listOf(FileSource(a)), "JVM")
        assertTrue(r.dirName.endsWith(".partial"))
        val engine = RestoreEngine()
        assertEquals(BackupSummary.Status.OK, engine.list(real).single().status)
        val rep = engine.open(real, r.dirName, pw).use { engine.restore(real, it, null, FileRestoreTarget(File(src, "d"))) }
        assertTrue(rep.allOk)
    }

    @Test
    fun unreadableSourceIsSkippedAndReported() {
        val src = tmp()
        val loc = FileBackupLocation(File(src, "copias"))
        val good = FileSource(writeRandom(File(src, "bueno.jpg"), 5000))
        val gone = FileSource(File(src, "no_existe.jpg"))
        val failsMidway = object : BackupSource {
            override val displayName = "se_desconecta.mp4"
            override val folder = ""
            override val mimeType = "video/mp4"
            override val sizeHint: Long? = null
            override val lastModified = 0L
            override val category = Category.VIDEOS
            override fun open(): InputStream = object : InputStream() {
                var n = 0
                override fun read(): Int = if (n++ < 100_000) 7 else throw IOException("USB desconectado")
            }
        }
        val r = BackupEngine(fast).create(loc, "x", pw, listOf(good, gone, failsMidway), "JVM")
        assertEquals(2, r.skipped.size)
        assertEquals(listOf("bueno.jpg"), r.manifest.entries.map { it.name })
        val engine = RestoreEngine()
        val rep = engine.open(loc, r.dirName, pw).use { engine.restore(loc, it, null, FileRestoreTarget(File(src, "d"))) }
        assertTrue(rep.allOk); assertEquals(1, rep.okCount)
    }

    @Test
    fun selectiveRestoreAndFolders() {
        val src = tmp()
        val loc = FileBackupLocation(File(src, "copias"))
        val a = writeRandom(File(src, "a.jpg"), 1000, 1)
        val b = writeRandom(File(src, "b.jpg"), 2000, 2)
        val c = writeRandom(File(src, "c.jpg"), 3000, 3)
        val r = BackupEngine(fast).create(loc, "x", pw,
            listOf(FileSource(a, "Viaje/Día 1"), FileSource(b, "Viaje/Día 2"), FileSource(c)), "JVM")
        val engine = RestoreEngine()
        val dest = File(src, "dest")
        val rep = engine.open(loc, r.dirName, pw).use { bk ->
            val id = bk.manifest.entries.first { it.name == "b.jpg" }.id
            engine.restore(loc, bk, setOf(id), FileRestoreTarget(dest))
        }
        assertEquals(1, rep.results.size)
        val out = File(dest, "Viaje/Día 2/b.jpg")
        assertTrue(out.exists()); assertEquals(sha(b), sha(out))
        assertFalse(File(dest, "c.jpg").exists())
    }

    @Test
    fun restoreNeverOverwritesExistingFiles() {
        val src = tmp()
        val loc = FileBackupLocation(File(src, "copias"))
        val a = writeRandom(File(src, "a.jpg"), 1000)
        val r = BackupEngine(fast).create(loc, "x", pw, listOf(FileSource(a)), "JVM")
        val dest = File(src, "dest").also { it.mkdirs() }
        File(dest, "a.jpg").writeText("existente")
        val engine = RestoreEngine()
        val rep = engine.open(loc, r.dirName, pw).use { engine.restore(loc, it, null, FileRestoreTarget(dest)) }
        assertTrue(rep.allOk)
        assertEquals("existente", File(dest, "a.jpg").readText())
        assertEquals("a (1).jpg", File(rep.results[0].path!!).name)
    }

    @Test
    fun verifyOnlyMode() {
        val src = tmp()
        val loc = FileBackupLocation(File(src, "copias"))
        val r = BackupEngine(fast).create(loc, "x", pw, listOf(FileSource(writeRandom(File(src, "a.bin"), 99_999))), "JVM")
        val engine = RestoreEngine()
        val rep = engine.open(loc, r.dirName, pw).use { engine.restore(loc, it, null, null) }
        assertTrue(rep.allOk)
        assertEquals(EntryStatus.INTACT, rep.results.single().status)
    }

    @Test
    fun ciphertextDoesNotContainPlaintextOrNames() {
        val src = tmp()
        val root = File(src, "copias")
        val loc = FileBackupLocation(root)
        val secret = File(src, "nombre_secreto_diario.txt").also { it.writeText("TEXTO-MUY-SECRETO ".repeat(1000)) }
        val r = BackupEngine(fast).create(loc, "Mi copia", pw, listOf(FileSource(secret)), "JVM")
        val dir = File(root, r.dirName)
        dir.listFiles()!!.forEach { f ->
            val s = String(f.readBytes(), Charsets.ISO_8859_1)
            assertFalse(s.contains("TEXTO-MUY-SECRETO"), f.name)
            assertFalse(s.contains("nombre_secreto"), f.name)
        }
    }

    @Test
    fun productionKdfParametersWork() {
        val src = tmp()
        val loc = FileBackupLocation(File(src, "copias"))
        val t0 = System.nanoTime()
        val r = BackupEngine(CryptoParams.DEFAULT).create(loc, "x", pw, listOf(FileSource(writeRandom(File(src, "a.bin"), 10))), "JVM")
        val engine = RestoreEngine()
        val b = engine.open(loc, r.dirName, pw)
        assertEquals(600_000, b.info.wrapped.iterations)
        b.close()
        println("    (PBKDF2 600 000 iteraciones: copia+apertura en ${(System.nanoTime() - t0) / 1_000_000} ms en este equipo)")
    }

    @Test
    fun deleteBackup() {
        val src = tmp()
        val loc = FileBackupLocation(File(src, "copias"))
        val r = BackupEngine(fast).create(loc, "x", pw, listOf(FileSource(writeRandom(File(src, "a.bin"), 10))), "JVM")
        val engine = RestoreEngine()
        assertNotNull(engine.list(loc).singleOrNull())
        engine.delete(loc, r.dirName)
        assertTrue(engine.list(loc).isEmpty())
    }
}
