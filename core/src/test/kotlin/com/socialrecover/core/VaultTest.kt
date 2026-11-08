package com.socialrecover.core

import com.socialrecover.core.crypto.CryptoParams
import com.socialrecover.core.engine.BackupEngine
import com.socialrecover.core.engine.EntryStatus
import com.socialrecover.core.engine.SrpException
import com.socialrecover.core.model.Category
import com.socialrecover.core.store.FileBackupLocation
import com.socialrecover.core.store.FileRestoreTarget
import com.socialrecover.core.store.FileSource
import com.socialrecover.core.vault.BytesSource
import com.socialrecover.core.vault.DeviceKind
import com.socialrecover.core.vault.DiffLine
import com.socialrecover.core.vault.LegacyMigrator
import com.socialrecover.core.vault.Library
import com.socialrecover.core.vault.LocalRepoBackend
import com.socialrecover.core.vault.RepoBackend
import com.socialrecover.core.vault.Retention
import com.socialrecover.core.vault.RetentionPolicy
import com.socialrecover.core.vault.TimeFilter
import com.socialrecover.core.vault.TimeMachine
import com.socialrecover.core.vault.Vault
import com.socialrecover.core.vault.VaultBackupSession
import com.socialrecover.core.vault.VaultCheck
import com.socialrecover.core.vault.VaultRestore
import com.socialrecover.core.vault.Versions
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Bóveda incremental, Máquina del tiempo, versiones, retención y migración. Datos sintéticos. */
class VaultTest {
    private val fast = CryptoParams(pbkdf2Iterations = 2_000)
    private val pw = "frase de prueba larga".toCharArray()
    private val utc: ZoneId = ZoneOffset.UTC
    private fun tmp(): File = Files.createTempDirectory("srp-vault").toFile()
    private fun rnd(n: Int, seed: Int = n) = Random(seed).nextBytes(n)
    private fun day(y: Int, m: Int, d: Int, h: Int = 12) = LocalDate.of(y, m, d).atTime(h, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun newVault(root: File = tmp()) = Vault.create(LocalRepoBackend(File(root, "repo")), pw, fast)

    private fun restoreAll(v: Vault, snapName: String, dest: File) =
        VaultRestore.restore(v, v.loadSnapshot(snapName), v.loadSnapshot(snapName).files, FileRestoreTarget(dest))

    @Test
    fun incrementalBackupStoresOnlyChanges() {
        val root = tmp(); val v = newVault(root)
        val src = File(root, "src").apply { mkdirs() }
        val a = File(src, "video.mp4").apply { writeBytes(rnd(3_500_000, 1)) }
        val b = File(src, "notas.txt").apply { writeText("hola\n".repeat(20_000)) }
        val r1 = VaultBackupSession.run(v, listOf(FileSource(a), FileSource(b)), "1", "Pixel", DeviceKind.PHONE)
        assertTrue(r1.snapshot.stats.bytesStored > 3_000_000)

        // Sin cambios: no se lee ni se guarda nada.
        val r2 = VaultBackupSession.run(v, listOf(FileSource(a), FileSource(b)), "2", "Pixel", DeviceKind.PHONE)
        assertEquals(2, r2.snapshot.stats.filesUnchanged)
        assertEquals(0L, r2.snapshot.stats.bytesStored)

        // Cambia solo el último MiB del vídeo: se guarda un bloque, no 3,5 MB.
        val bytes = a.readBytes(); bytes[bytes.size - 10] = (bytes[bytes.size - 10] + 1).toByte(); a.writeBytes(bytes)
        a.setLastModified(a.lastModified() + 5000)
        val r3 = VaultBackupSession.run(v, listOf(FileSource(a), FileSource(b)), "3", "Pixel", DeviceKind.PHONE)
        assertEquals(1, r3.snapshot.stats.filesUnchanged)
        assertTrue(r3.snapshot.stats.bytesStored in 1..(1 shl 20) + 100, "guardado=${r3.snapshot.stats.bytesStored}")

        // Las tres versiones se restauran íntegras.
        for (r in listOf(r1, r3)) {
            val rep = restoreAll(v, r.snapshot.name, File(root, "out-${r.snapshot.label}"))
            assertTrue(rep.allOk, rep.toString())
        }
        assertTrue(File(root, "out-3/video.mp4").readBytes().contentEquals(a.readBytes()))
    }

    @Test
    fun duplicatesAndCompression() {
        val v = newVault()
        val photo = rnd(800_000, 9)
        val text = "Línea de documento repetida con acentos áéíóú\n".repeat(10_000).toByteArray()
        val r = VaultBackupSession.run(v, listOf(
            BytesSource("IMG_001.jpg", photo, category = Category.PHOTOS, mimeType = "image/jpeg"),
            BytesSource("IMG_001 copia.jpg", photo, folder = "Descargas", category = Category.PHOTOS, mimeType = "image/jpeg"),
            BytesSource("informe.txt", text, mimeType = "text/plain"),
        ), "x", "Pixel", DeviceKind.PHONE)
        assertEquals(1, r.duplicates.size)
        assertEquals("IMG_001.jpg", r.duplicates[0].sameAs)
        assertEquals(800_000L, r.snapshot.stats.bytesDeduplicated)
        assertTrue(r.snapshot.stats.bytesSavedByCompression > text.size / 2, "texto comprimido")
        assertTrue(r.snapshot.stats.bytesStored < photo.size + text.size / 2)
    }

    @Test
    fun wrongPasswordAndPasswordChange() {
        val root = tmp(); val v = newVault(root)
        VaultBackupSession.run(v, listOf(BytesSource("a.txt", "x".toByteArray())), "x", "d", DeviceKind.PHONE)
        val be = LocalRepoBackend(File(root, "repo"))
        assertFailsWith<SrpException.WrongPassword> { Vault.open(be, "otra".toCharArray()) }
        v.changePassword("nueva contraseña".toCharArray(), fast)
        assertFailsWith<SrpException.WrongPassword> { Vault.open(be, pw) }
        val v2 = Vault.open(be, "nueva contraseña".toCharArray())
        assertEquals(1, v2.listSnapshots().size)
        // Apertura desatendida con la clave del llavero
        val v3 = Vault.openWithKey(be, v.exportMasterKey())
        assertEquals("x", v3.loadSnapshot(v3.listSnapshots()[0].name).label)
    }

    @Test
    fun damagedAndMissingBlobsAreDetectedAndNeverOfferedAsRecoverable() {
        val root = tmp(); val v = newVault(root)
        val r = VaultBackupSession.run(v, listOf(
            BytesSource("bueno.bin", rnd(10_000, 1)),
            BytesSource("dañado.bin", rnd(10_000, 2)),
            BytesSource("borrado.bin", rnd(10_000, 3)),
        ), "x", "d", DeviceKind.PHONE, createdAt = day(2026, 9, 14))
        val s = r.snapshot
        val damagedBlob = File(root, "repo/blobs/${s.find("dañado.bin")!!.blobs[0].take(2)}/${s.find("dañado.bin")!!.blobs[0]}")
        damagedBlob.writeBytes(damagedBlob.readBytes().also { it[30] = (it[30].toInt() xor 1).toByte() })
        File(root, "repo/blobs/${s.find("borrado.bin")!!.blobs[0].take(2)}/${s.find("borrado.bin")!!.blobs[0]}").delete()
        v.refreshBlobs()

        val check = VaultCheck.run(v, readData = true)
        assertFalse(check.ok)
        assertEquals(1, check.missingBlobs.size); assertEquals(1, check.corruptBlobs.size)
        assertEquals(setOf("dañado.bin", "borrado.bin"), check.affected.values.flatten().toSet())

        val tm = TimeMachine(Library(listOf(v), utc))
        val items = tm.filesOn(LocalDate.of(2026, 9, 14))
        assertEquals(3, items.size)
        assertEquals(listOf("bueno.bin", "dañado.bin"), tm.recoverableOn(LocalDate.of(2026, 9, 14)).map { it.file.name })

        val rep = restoreAll(v, s.name, File(root, "out"))
        assertEquals(EntryStatus.VERIFIED, rep.results.first { it.entry.name == "bueno.bin" }.status)
        assertEquals(EntryStatus.HASH_MISMATCH, rep.results.first { it.entry.name == "dañado.bin" }.status)
        assertEquals(EntryStatus.NOT_FOUND_IN_DATA, rep.results.first { it.entry.name == "borrado.bin" }.status)
        assertFalse(File(root, "out/dañado.bin").exists(), "No se deja un archivo dañado en el destino")
    }

    @Test
    fun interruptedBackupResumesReusingBlocks() {
        val root = tmp(); val v = newVault(root)
        val files = (1..6).map { BytesSource("f$it.bin", rnd(1_500_000, it)) }
        var calls = 0
        assertFailsWith<SrpException.Cancelled> {
            VaultBackupSession.run(v, files, "x", "d", DeviceKind.PHONE, isCancelled = { ++calls > 7 })
        }
        assertTrue(v.listSnapshots().isEmpty(), "Sin instantánea: la copia interrumpida no aparece como válida")
        val already = v.knownBlobs().size
        assertTrue(already > 0)
        val r = VaultBackupSession.run(v, files, "x", "d", DeviceKind.PHONE)
        assertTrue(r.snapshot.stats.bytesDeduplicated >= (already - 1) * 400_000L && r.snapshot.stats.bytesStored < 9_000_000L, "Se reutilizaron los bloques ya subidos: ${r.snapshot.stats}")
        assertTrue(restoreAll(v, r.snapshot.name, File(root, "o")).allOk)
    }

    @Test
    fun noSpaceDuringBackup() {
        val root = tmp()
        val real = LocalRepoBackend(File(root, "repo"))
        Vault.create(real, pw, fast).close()
        var written = 0
        val full = object : RepoBackend by real {
            override fun write(key: String, data: ByteArray) {
                if (key.startsWith("blobs/") && ++written > 2) throw IOException("write failed: ENOSPC (No space left on device)")
                real.write(key, data)
            }
        }
        val v = Vault.open(full, pw)
        assertFailsWith<SrpException.NoSpace> {
            VaultBackupSession.run(v, (1..5).map { BytesSource("f$it", rnd(1_200_000, it)) }, "x", "d", DeviceKind.PHONE)
        }
        assertTrue(v.listSnapshots().isEmpty())
    }

    @Test
    fun timeMachineCalendarAndFilters() {
        val v = newVault()
        VaultBackupSession.run(v, listOf(BytesSource("playa.jpg", rnd(100, 1), category = Category.PHOTOS)), "a", "Pixel", DeviceKind.PHONE, createdAt = day(2026, 9, 3))
        VaultBackupSession.run(v, listOf(BytesSource("contrato.pdf", rnd(100, 2), category = Category.DOCUMENTS)), "b", "Portátil", DeviceKind.COMPUTER, createdAt = day(2026, 9, 3, 18))
        VaultBackupSession.run(v, listOf(BytesSource("cumple.mp4", rnd(100, 3), category = Category.VIDEOS)), "c", "Pixel", DeviceKind.PHONE, createdAt = day(2026, 9, 20))
        VaultBackupSession.run(v, listOf(BytesSource("enero.txt", rnd(100, 4))), "d", "Pixel", DeviceKind.PHONE, createdAt = day(2027, 1, 2))
        val tm = TimeMachine(Library(listOf(v), utc))
        assertEquals(mapOf(3 to 2, 20 to 1), tm.daysWithBackups(YearMonth.of(2026, 9)))
        assertEquals(listOf(2026, 2027), tm.years())
        assertEquals(mapOf(9 to 3), tm.monthsWithBackups(2026))
        assertEquals(setOf("playa.jpg", "contrato.pdf"), tm.filesOn(LocalDate.of(2026, 9, 3)).map { it.file.name }.toSet())
        assertEquals(listOf("contrato.pdf"), tm.filesOn(LocalDate.of(2026, 9, 3), TimeFilter(device = "Portátil")).map { it.file.name })
        assertEquals(listOf("cumple.mp4"), tm.filesBetween(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), TimeFilter(category = Category.VIDEOS)).map { it.file.name })
        assertTrue(tm.filesOn(LocalDate.of(2026, 9, 4)).isEmpty())
        assertEquals(1, tm.filesBetween(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), TimeFilter(text = "PLAYA")).size)
    }

    @Test
    fun versionHistoryAndComparison() {
        val v = newVault()
        val texts = listOf("uno\ndos\ntres", "uno\ndos\ntres", "uno\nDOS\ntres\ncuatro")
        texts.forEachIndexed { i, t ->
            VaultBackupSession.run(v, listOf(BytesSource("lista.txt", t.toByteArray(), mimeType = "text/plain", lastModified = 1000L + i)),
                "v$i", "Pixel", DeviceKind.PHONE, createdAt = day(2026, 10, 1 + i))
        }
        val lib = Library(listOf(v), utc)
        val hist = Versions.history(lib.snapshots.map { it.ref to it.snapshot }, "lista.txt")
        assertEquals(3, hist.size)
        val distinct = Versions.distinct(hist)
        assertEquals(2, distinct.size)
        val cmp = Versions.compare(v, distinct[0], distinct[1])
        assertFalse(cmp.identical)
        val diff = cmp.textDiff!!
        assertEquals(listOf("dos"), diff.filterIsInstance<DiffLine.Removed>().map { it.text })
        assertEquals(listOf("DOS", "cuatro"), diff.filterIsInstance<DiffLine.Added>().map { it.text })
        assertTrue(Versions.compare(v, hist[0], hist[1]).identical)
    }

    @Test
    fun retentionNeverDeletesWithoutPolicyAndPrunesSafely() {
        val root = tmp(); val v = newVault(root)
        (1..6).forEach { i ->
            VaultBackupSession.run(v, listOf(BytesSource("doc.txt", "versión $i".toByteArray(), lastModified = i.toLong()),
                BytesSource("fija.bin", rnd(5000, 99), lastModified = 1L)), "c$i", "Pixel", DeviceKind.PHONE, createdAt = day(2026, 1, i))
        }
        val keepAll = Retention.plan(v.listSnapshots(), RetentionPolicy.KEEP_ALL)
        assertTrue(keepAll.remove.isEmpty())
        assertTrue(Retention.plan(v.listSnapshots(), RetentionPolicy()).remove.isEmpty(), "Política vacía = no borrar nada")

        val plan = Retention.plan(v.listSnapshots(), RetentionPolicy(keepLast = 2), now = day(2026, 1, 7))
        assertEquals(4, plan.remove.size)
        assertFailsWith<IllegalArgumentException> { Retention.prune(v, plan, confirmedByUser = false) }

        val lock = v.lock("backup")
        assertFailsWith<IllegalStateException> { Retention.prune(v, plan, confirmedByUser = true) }
        v.unlock(lock)

        val res = Retention.prune(v, plan, confirmedByUser = true)
        assertEquals(4, res.snapshotsRemoved)
        assertEquals(4, res.blobsRemoved, "Solo se borran los bloques de 'doc.txt' versiones 1-4; 'fija.bin' sigue en uso")
        assertEquals(2, v.listSnapshots().size)
        assertTrue(VaultCheck.run(v, readData = true).ok)
        val last = v.listSnapshots().last().name
        assertTrue(restoreAll(v, last, File(root, "o")).allOk)
        assertEquals("versión 6", File(root, "o/doc.txt").readText())
    }

    @Test
    fun migrationFromVersion1KeepsDateAndIntegrity() {
        val root = tmp()
        val loc = FileBackupLocation(File(root, "v1"))
        val src = File(root, "src").apply { mkdirs() }
        val files = listOf(File(src, "foto.jpg").apply { writeBytes(rnd(300_000, 5)) }, File(src, "cv.pdf").apply { writeBytes(rnd(50_000, 6)) })
        val created = day(2025, 12, 24)
        val old = BackupEngine(fast, clock = { created }).create(loc, "Navidad", "clave antigua".toCharArray(), files.map { FileSource(it) }, "Galaxy")
        val v = newVault(root)
        assertFailsWith<SrpException.WrongPassword> { LegacyMigrator.migrate(loc, old.dirName, "mal".toCharArray(), v) }
        val r = LegacyMigrator.migrate(loc, old.dirName, "clave antigua".toCharArray(), v, DeviceKind.PHONE)
        assertEquals(2, r.files)
        assertEquals("v1:${old.dirName}", r.snapshot.origin)
        val tm = TimeMachine(Library(listOf(v), utc))
        assertEquals(setOf("foto.jpg", "cv.pdf"), tm.recoverableOn(LocalDate.of(2025, 12, 24)).map { it.file.name }.toSet())
        assertTrue(restoreAll(v, r.snapshot.name, File(root, "out")).allOk)
        assertTrue(File(root, "out/foto.jpg").readBytes().contentEquals(files[0].readBytes()))
        assertFailsWith<IllegalStateException> { LegacyMigrator.migrate(loc, old.dirName, "clave antigua".toCharArray(), v) }
        assertNotNull(loc.openRead(old.dirName, "info.properties"), "La copia original no se borra")
    }
}
