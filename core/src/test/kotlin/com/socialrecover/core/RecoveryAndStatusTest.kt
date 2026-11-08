package com.socialrecover.core

import com.socialrecover.core.crypto.CryptoParams
import com.socialrecover.core.model.Category
import com.socialrecover.core.recovery.Availability
import com.socialrecover.core.recovery.GuidedRecovery
import com.socialrecover.core.recovery.LostKind
import com.socialrecover.core.recovery.QueryParser
import com.socialrecover.core.social.SocialImport
import com.socialrecover.core.social.SocialNetwork
import com.socialrecover.core.status.AlertAction
import com.socialrecover.core.status.ChangeScanner
import com.socialrecover.core.status.ListedFile
import com.socialrecover.core.status.MonitorSettings
import com.socialrecover.core.status.ProtectionAnalyzer
import com.socialrecover.core.status.ProtectionLevel
import com.socialrecover.core.status.StorageStatus
import com.socialrecover.core.status.VaultHealth
import com.socialrecover.core.status.WatchedSource
import com.socialrecover.core.store.FileRestoreTarget
import com.socialrecover.core.vault.BytesSource
import com.socialrecover.core.vault.DeviceKind
import com.socialrecover.core.vault.Library
import com.socialrecover.core.vault.LocalRepoBackend
import com.socialrecover.core.vault.StorageKind
import com.socialrecover.core.vault.Vault
import com.socialrecover.core.vault.VaultBackupSession
import com.socialrecover.core.vault.VaultRestore
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecoveryAndStatusTest {
    private val now = LocalDate.of(2026, 11, 8)
    private fun ms(d: LocalDate) = d.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun naturalLanguageQueriesInSpanish() {
        val q1 = QueryParser.parse("Quiero recuperar un vídeo de TikTok de septiembre", now)
        assertEquals(setOf(LostKind.VIDEO), q1.kinds)
        assertEquals(setOf(SocialNetwork.TIKTOK), q1.networks)
        assertEquals(LocalDate.of(2026, 9, 1), q1.from); assertEquals(LocalDate.of(2026, 9, 30), q1.to)
        assertTrue(q1.keywords.isEmpty(), "${q1.keywords}")

        val q2 = QueryParser.parse("Busco una foto que tenía en mi ordenador", now)
        assertEquals(setOf(LostKind.PHOTO), q2.kinds); assertEquals(DeviceKind.COMPUTER, q2.deviceKind)
        assertTrue(q2.keywords.isEmpty(), "${q2.keywords}")

        val q3 = QueryParser.parse("Quiero encontrar mis comentarios antiguos de Instagram", now)
        assertEquals(setOf(LostKind.COMMENT), q3.kinds); assertEquals(setOf(SocialNetwork.INSTAGRAM), q3.networks)

        val q4 = QueryParser.parse("las fotos de la boda de diciembre", now)
        assertEquals(LocalDate.of(2025, 12, 1), q4.from, "Diciembre aún no ha llegado: es el del año pasado")
        assertEquals(listOf("boda"), q4.keywords)

        assertEquals(LocalDate.of(2023, 1, 1), QueryParser.parse("contrato de 2023", now).from)
        assertEquals(setOf(LostKind.LIKE), QueryParser.parse("mis me gusta de facebook", now).kinds)
        assertEquals(setOf(SocialNetwork.X), QueryParser.parse("publicaciones de X del año pasado", now).networks)
        assertEquals(now.minusDays(1), QueryParser.parse("lo que borré ayer", now).from)
        assertTrue(QueryParser.parse("factura de la luz", now).understood.isNotEmpty())
    }

    private fun library(): Pair<Vault, Library> {
        val v = Vault.create(LocalRepoBackend(Files.createTempDirectory("srp-rec").toFile()), "pw-largo-123".toCharArray(), CryptoParams(2_000))
        VaultBackupSession.run(v, listOf(
            BytesSource("boda_iglesia.jpg", Random(1).nextBytes(5000), "Fotos/Boda", Category.PHOTOS, "image/jpeg", ms(LocalDate.of(2025, 12, 13))),
            BytesSource("contrato_alquiler.pdf", Random(2).nextBytes(3000), "Documentos", Category.DOCUMENTS, "application/pdf", ms(LocalDate.of(2023, 3, 1))),
        ), "Portátil", "Portátil de casa", DeviceKind.COMPUTER, createdAt = ms(LocalDate.of(2026, 1, 10)))
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("user_data_tiktok.json"))
            z.write("""{"Video":{"Videos":{"VideoList":[{"Date":"2026-09-14 10:22:01","Link":"https://www.tiktokv.com/share/video/77/"}]}},
                "Comment":{"Comments":{"CommentsList":[{"date":"2026-09-20 08:00:00","comment":"qué gran concierto"}]}}}""".toByteArray())
            z.closeEntry()
        }
        val zip = bos.toByteArray()
        SocialImport.import(listOf(SocialImport.Part("tiktok.zip") { zip.inputStream() }), v, "Pixel", DeviceKind.PHONE, SocialNetwork.TIKTOK)
        return v to Library(listOf(v))
    }

    @Test
    fun guidedRecoveryFindsAndClassifiesAvailability() {
        val (v, lib) = library()
        val photo = GuidedRecovery.search(lib, QueryParser.parse("Busco una foto que tenía en mi ordenador", now))
        assertEquals("boda_iglesia.jpg", photo.results.first().title)
        assertEquals(Availability.AVAILABLE, photo.results.first().availability)

        // Paso 3: restaurar el resultado y comprobar integridad
        val r = photo.results.first()
        val dest = Files.createTempDirectory("srp-guided").toFile()
        val rep = VaultRestore.restore(v, r.loaded.snapshot, listOf(r.file!!), FileRestoreTarget(dest))
        assertTrue(rep.allOk)

        val tiktok = GuidedRecovery.search(lib, QueryParser.parse("Quiero recuperar un vídeo de TikTok de septiembre", now))
        assertEquals(1, tiktok.results.size)
        assertEquals(Availability.RECORD_ONLY, tiktok.results[0].availability, "TikTok solo da el enlace")
        assertNull(tiktok.results[0].file)
        assertTrue(tiktok.explanations.any { it.contains("enlaces de tus vídeos") })

        val comment = GuidedRecovery.search(lib, QueryParser.parse("mi comentario del concierto en tiktok", now))
        assertEquals(Availability.TEXT_AVAILABLE, comment.results.single().availability)

        val none = GuidedRecovery.search(lib, QueryParser.parse("mis comentarios antiguos de Instagram", now))
        assertTrue(none.results.isEmpty())
        assertTrue(none.explanations.first().contains("No has importado ninguna exportación de Instagram"))

        val doc = GuidedRecovery.search(lib, QueryParser.parse("contrato del alquiler", now))
        assertEquals("contrato_alquiler.pdf", doc.results.first().title)

        // Si los datos se pierden, nunca se ofrece como disponible.
        val f = doc.results.first().file!!
        f.blobs.forEach { v.deleteBlob(it) }
        val after = GuidedRecovery.search(Library(listOf(v)), QueryParser.parse("contrato del alquiler", now))
        assertEquals(Availability.DATA_MISSING, after.results.first().availability)
    }

    @Test
    fun protectionAlertsAreClearAndCorrect() {
        val day = 86_400_000L
        val t = ms(now)
        val report = ProtectionAnalyzer.analyze(t,
            listOf(
                WatchedSource("f", "Cámara", Category.PHOTOS, t - 7 * day, t - 7 * day, true, 12, true),
                WatchedSource("d", "Documentos", Category.DOCUMENTS, t - 2 * day, t - day, false, 0, true),
                WatchedSource("u", "Pendrive", Category.VIDEOS, t - day, t - day, true, 0, false),
            ),
            listOf(
                StorageStatus(StorageKind.LOCAL, "Memoria interna", 500L shl 20, 64L shl 30, null, null, null, null),
                StorageStatus(StorageKind.CLOUD, "Nube SocialRecover", null, null, (47L shl 30) / 10, 5L shl 30, t - day, "sin conexión"),
            ),
            VaultHealth(lastCheckAt = null, lastCheckOk = null, interruptedRuns = 1, damagedSnapshots = 0),
        )
        val msgs = report.alerts.map { it.message }
        assertTrue(msgs.contains("Tus fotografías seleccionadas llevan 7 días sin una copia de seguridad correcta."), msgs.toString())
        assertTrue(msgs.contains("La última copia de tus documentos no se completó."))
        assertTrue(msgs.any { it.startsWith("Tu almacenamiento está casi lleno") })
        assertTrue(msgs.any { it.contains("12 archivo(s) nuevos o modificados") })
        assertTrue(report.alerts.any { it.code == "NO_ACCESS" && it.action == AlertAction.REAUTHORIZE })
        assertTrue(report.alerts.any { it.code == "QUOTA" })
        assertTrue(report.alerts.any { it.code == "SYNC_ERROR" })
        assertTrue(report.alerts.any { it.code == "INCOMPLETE" })
        assertTrue(report.alerts.any { it.code == "UNVERIFIED" })
        assertEquals(ProtectionLevel.AT_RISK, report.level)

        val ok = ProtectionAnalyzer.analyze(t, listOf(WatchedSource("f", "Cámara", Category.PHOTOS, t - day, t - day, true, 0, true)),
            listOf(StorageStatus(StorageKind.LOCAL, "Memoria", 30L shl 30, 64L shl 30, null, null, null, null)),
            VaultHealth(t - day, true, 0, 0))
        assertEquals(ProtectionLevel.PROTECTED, ok.level); assertTrue(ok.alerts.isEmpty())

        val onlyDocs = ProtectionAnalyzer.analyze(t, listOf(WatchedSource("f", "Cámara", Category.PHOTOS, null, null, null, 0, true)),
            emptyList(), VaultHealth(t, true, 0, 0), MonitorSettings(monitored = setOf(Category.DOCUMENTS)))
        assertTrue(onlyDocs.alerts.isEmpty(), "Las categorías no supervisadas no generan avisos")
    }

    @Test
    fun failedAndInterruptedBackupsAreDetected() {
        val dir = Files.createTempDirectory("srp-fail").toFile()
        val v = Vault.create(LocalRepoBackend(File(dir, "r")), "pw-largo-123".toCharArray(), CryptoParams(2_000))
        v.lock("backup") // copia que nunca terminó (la app se cerró)
        assertEquals(1, v.staleLocks(olderThanMs = -1).size)
        assertEquals(0, v.staleLocks().size, "Una copia reciente aún puede estar en curso")
    }

    @Test
    fun changeScannerFindsPendingFiles() {
        val v = Vault.create(LocalRepoBackend(Files.createTempDirectory("srp-scan").toFile()), "pw-largo-123".toCharArray(), CryptoParams(2_000))
        val s = VaultBackupSession.run(v, listOf(BytesSource("a.jpg", ByteArray(10), "Cámara", lastModified = 100), BytesSource("b.jpg", ByteArray(20), "Cámara", lastModified = 100)),
            "x", "Pixel", DeviceKind.PHONE).snapshot
        val sum = ChangeScanner.compare(listOf(ListedFile("Cámara/a.jpg", 10, 100), ListedFile("Cámara/b.jpg", 25, 200), ListedFile("Cámara/c.jpg", 5, 300)), s, "Cámara")
        assertEquals(1, sum.added); assertEquals(1, sum.modified); assertEquals(2, sum.pending)
    }
}
