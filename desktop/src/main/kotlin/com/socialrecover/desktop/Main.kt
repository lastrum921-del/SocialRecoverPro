package com.socialrecover.desktop

import com.socialrecover.core.cloud.HttpRepoBackend
import com.socialrecover.core.crypto.CryptoParams
import com.socialrecover.core.engine.SrpException
import com.socialrecover.core.model.Category
import com.socialrecover.core.recovery.Availability
import com.socialrecover.core.recovery.GuidedRecovery
import com.socialrecover.core.recovery.QueryParser
import com.socialrecover.core.social.SocialImport
import com.socialrecover.core.status.ChangeScanner
import com.socialrecover.core.status.ListedFile
import com.socialrecover.core.status.ProtectionAnalyzer
import com.socialrecover.core.status.StorageStatus
import com.socialrecover.core.status.VaultHealth
import com.socialrecover.core.status.WatchedSource
import com.socialrecover.core.store.FileBackupLocation
import com.socialrecover.core.store.FileRestoreTarget
import com.socialrecover.core.store.FileSource
import com.socialrecover.core.vault.DeviceKind
import com.socialrecover.core.vault.Library
import com.socialrecover.core.vault.LegacyMigrator
import com.socialrecover.core.vault.LocalRepoBackend
import com.socialrecover.core.vault.RepoBackend
import com.socialrecover.core.vault.Retention
import com.socialrecover.core.vault.RetentionPolicy
import com.socialrecover.core.vault.TimeMachine
import com.socialrecover.core.vault.Vault
import com.socialrecover.core.vault.VaultBackupSession
import com.socialrecover.core.vault.VaultCheck
import com.socialrecover.core.vault.VaultRestore
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.text.DecimalFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * SocialRecover Pro para Windows (y macOS/Linux): motor completo en línea de comandos + tarea programada.
 * Usa EXACTAMENTE el mismo motor (`:core`) que Android: mismas copias, mismo cifrado, mismas pruebas.
 */
object Cli {
    private val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())
    private fun date(ms: Long) = if (ms <= 0) "—" else fmt.format(Instant.ofEpochMilli(ms))
    private fun size(b: Long): String {
        val u = listOf("B", "KB", "MB", "GB", "TB"); var v = b.toDouble(); var i = 0
        while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
        return DecimalFormat("#,##0.#").format(v) + " " + u[i]
    }

    private val device: String = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("Ordenador")
    private var params = CryptoParams.DEFAULT

    private fun password(prompt: String = "Contraseña de la bóveda: "): CharArray =
        System.getenv("SRP_PASSWORD")?.toCharArray() ?: System.console()?.readPassword(prompt)
        ?: error("Define SRP_PASSWORD o ejecuta en una consola interactiva")

    private fun backend(loc: String): RepoBackend =
        if (loc.startsWith("http://") || loc.startsWith("https://"))
            HttpRepoBackend(loc, System.getenv("SRP_CLOUD_TOKEN") ?: error("Define SRP_CLOUD_TOKEN para usar la nube"))
        else LocalRepoBackend(File(loc), File(loc).absolutePath)

    private fun open(loc: String) = Vault.open(backend(loc), password())

    private fun sources(dirs: List<String>): List<FileSource> = dirs.flatMap { d ->
        val root = File(d).absoluteFile
        require(root.isDirectory) { "No existe la carpeta $d" }
        root.walkTopDown().filter { it.isFile && !it.isHidden }.map { f ->
            // Se conserva el nombre de la carpeta elegida: "Fotos/Viaje/a.jpg"
            val rel = f.parentFile.relativeTo(root.parentFile ?: root).invariantSeparatorsPath
            val mime = runCatching { Files.probeContentType(f.toPath()) }.getOrNull() ?: "application/octet-stream"
            FileSource(f, rel, Category.guess(mime, f.name), mime)
        }.toList()
    }

    fun run(args: List<String>): Int {
        if (args.isEmpty() || args[0] in setOf("-h", "--help", "ayuda")) { help(); return 0 }
        val opts = args.filter { it.startsWith("--") }.associate { it.removePrefix("--").substringBefore('=') to it.substringAfter('=', "true") }
        val pos = args.filter { !it.startsWith("--") }
        opts["iteraciones"]?.let { params = CryptoParams(it.toInt()) }
        when (pos[0]) {
            "init" -> {
                val pw = password("Nueva contraseña (mín. 8): ")
                require(pw.size >= 8) { "La contraseña debe tener al menos 8 caracteres" }
                Vault.create(backend(pos[1]), pw, params).use { println("Bóveda creada en ${it.backend.displayName}") }
            }
            "backup" -> open(pos[1]).use { v ->
                val src = sources(pos.drop(2))
                var last = 0L
                val r = VaultBackupSession.run(v, src, opts["nombre"] ?: "Copia de $device", device, DeviceKind.COMPUTER, onProgress = { p ->
                    if (System.currentTimeMillis() - last > 1000) { last = System.currentTimeMillis(); print("\r  ${size(p.bytesDone)} ${p.currentName.take(50).padEnd(50)}") }
                })
                val s = r.snapshot.stats
                println("\rCopia completada: ${s.filesTotal} archivos (${size(s.bytesTotal)}). Sin cambios: ${s.filesUnchanged}. " +
                    "Guardado nuevo: ${size(s.bytesStored)}. Deduplicado: ${size(s.bytesDeduplicated)}. Ahorro por compresión: ${size(s.bytesSavedByCompression)}.")
                if (r.duplicates.isNotEmpty()) println("Duplicados detectados: ${r.duplicates.size} (p. ej. ${r.duplicates[0].path} = ${r.duplicates[0].sameAs})")
                r.skipped.forEach { println("  Omitido: ${it.name} — ${it.reason}") }
            }
            "list" -> open(pos[1]).use { v ->
                Library(listOf(v)).snapshots.forEach { println("${date(it.ref.createdAt)}  ${it.snapshot.label.padEnd(30)} ${it.snapshot.device.padEnd(18)} ${it.snapshot.files.size} archivos  ${it.ref.name}") }
            }
            "calendar" -> open(pos[1]).use { v ->
                val ym = YearMonth.parse(pos.getOrNull(2) ?: YearMonth.now().toString())
                val days = TimeMachine(Library(listOf(v))).daysWithBackups(ym)
                println("   ${QueryParser.monthName(ym.monthValue).uppercase()} ${ym.year}\n L  M  X  J  V  S  D")
                val first = ym.atDay(1).dayOfWeek.value
                print("   ".repeat(first - 1))
                for (d in 1..ym.lengthOfMonth()) {
                    print(if (d in days) "%2d*".format(d) else "%2d ".format(d))
                    if ((first - 1 + d) % 7 == 0) println()
                }
                println("\n(*) días con copias")
            }
            "files" -> open(pos[1]).use { v ->
                val day = LocalDate.parse(pos[2])
                val items = TimeMachine(Library(listOf(v))).filesOn(day)
                if (items.isEmpty()) println("No hay copias del $day.")
                items.forEach { println("${if (it.available) "✔" else "✘ NO DISPONIBLE"}  ${it.file.path}  (${size(it.file.size)}, ${it.loaded.snapshot.device})") }
            }
            "restore" -> open(pos[1]).use { v ->
                val snap = v.loadSnapshot(pos[2])
                val dest = File(pos[3])
                val filter = opts["ruta"]
                val files = snap.files.filter { filter == null || it.path.startsWith(filter) }
                val rep = VaultRestore.restore(v, snap, files, FileRestoreTarget(dest))
                println("Restaurados y verificados: ${rep.okCount} · Con problemas: ${rep.failedCount}")
                rep.results.filter { !it.ok }.forEach { println("  ${it.entry.path}: ${it.message}") }
                rep.fatal?.let { println(it.userMessage) }
                if (!rep.allOk) return 2
            }
            "find" -> open(pos[1]).use { v ->
                val q = QueryParser.parse(pos.drop(2).joinToString(" "))
                println("Entendido: " + q.understood.joinToString(" · ").ifEmpty { "(sin criterios)" })
                val out = GuidedRecovery.search(Library(listOf(v)), q)
                out.results.take(30).forEach { println("[${it.availability.label}] ${date(it.date)}  ${it.title}  — ${it.subtitle} · copia del ${date(it.loaded.ref.createdAt)}") }
                out.explanations.forEach { println("• $it") }
                if (out.results.none { it.availability == Availability.AVAILABLE }) return 3
            }
            "import-social" -> open(pos[1]).use { v ->
                val parts = pos.drop(2).map { p -> SocialImport.Part(File(p).name) { File(p).inputStream() } }
                val r = SocialImport.import(parts, v, device, DeviceKind.COMPUTER)
                println("Importado: ${r.network.label} (${r.importerFormat ?: "formato no reconocido"}). ${r.items.size} elementos; ${r.mediaAvailable} con archivo.")
                r.countsByType.forEach { (t, n) -> println("  ${t.label}: $n") }
                r.warnings.forEach { println("  Aviso: $it") }
            }
            "status" -> open(pos[1]).use { v ->
                val last = v.latestFor(device)
                val watched = pos.drop(2).map { d ->
                    val root = File(d).absoluteFile
                    val listed = if (root.isDirectory) root.walkTopDown().filter { it.isFile }.map {
                        ListedFile(it.relativeTo(root.parentFile ?: root).invariantSeparatorsPath, it.length(), it.lastModified())
                    }.toList() else emptyList()
                    val ch = ChangeScanner.compare(listed, last, root.name)
                    WatchedSource(d, root.name, Category.CUSTOM_FOLDERS, last?.createdAt, last?.createdAt, true, ch.pending, root.isDirectory)
                }
                val be = v.backend
                val u = be.usage()
                val st = StorageStatus(be.kind, be.displayName, be.freeSpace(), null, u?.usedBytes, u?.quotaBytes, null, null)
                val rep = ProtectionAnalyzer.analyze(System.currentTimeMillis(), watched, listOf(st), VaultHealth(null, null, v.staleLocks().size, 0))
                println("Estado: ${rep.level.label}")
                rep.alerts.forEach { println("  [${it.severity}] ${it.message}${if (it.action.label.isNotEmpty()) "  → ${it.action.label}" else ""}") }
            }
            "check" -> open(pos[1]).use { v ->
                val r = VaultCheck.run(v, readData = opts.containsKey("completa"))
                println(if (r.ok) "Todo correcto: ${r.snapshotsOk} copias verificadas." else "Problemas: ${r.missingBlobs.size} bloques ausentes, ${r.corruptBlobs.size} dañados.")
                r.affected.forEach { (s, files) -> println("  $s: ${files.joinToString()}") }
                if (!r.ok) return 2
            }
            "retention" -> open(pos[1]).use { v ->
                val pol = RetentionPolicy(keepLast = opts["ultimas"]?.toInt() ?: 0, keepDaily = opts["diarias"]?.toInt() ?: 0,
                    keepWeekly = opts["semanales"]?.toInt() ?: 0, keepMonthly = opts["mensuales"]?.toInt() ?: 0, keepYearly = opts["anuales"]?.toInt() ?: 0)
                val plan = Retention.plan(v.listSnapshots(), pol)
                println("Política: ${pol.describe()}. Se conservarían ${plan.keep.size} y se eliminarían ${plan.remove.size} copias.")
                if (opts.containsKey("aplicar") && plan.remove.isNotEmpty()) {
                    val r = Retention.prune(v, plan, confirmedByUser = true)
                    println("Eliminadas ${r.snapshotsRemoved} copias; liberado ${size(r.bytesFreed)}.")
                } else if (plan.remove.isNotEmpty()) println("Nada se ha borrado. Añade --aplicar para confirmar.")
            }
            "migrate" -> open(pos[2]).use { v ->
                val loc = FileBackupLocation(File(pos[1]))
                val legacy = com.socialrecover.core.engine.RestoreEngine().list(loc)
                legacy.filter { it.status == com.socialrecover.core.engine.BackupSummary.Status.OK }.forEach { b ->
                    val r = LegacyMigrator.migrate(loc, b.dirName, password("Contraseña de «${b.info?.label}»: "), v, DeviceKind.COMPUTER)
                    println("Migrada «${b.info?.label}»: ${r.files} archivos (${date(r.snapshot.createdAt)})")
                }
            }
            "schedule" -> {
                val time = opts["hora"] ?: "21:00"
                val jar = File(Cli::class.java.protectionDomain.codeSource.location.toURI()).absolutePath
                val folders = pos.drop(2).joinToString(" ") { "\\\"$it\\\"" }
                val cmd = "schtasks /Create /F /SC DAILY /ST $time /TN \"SocialRecover Pro\" /TR \"javaw -jar \\\"$jar\\\" backup \\\"${pos[1]}\\\" $folders\""
                println("Tarea programada diaria a las $time. Ejecuta en Windows (la contraseña se toma de la variable SRP_PASSWORD del usuario,")
                println("o mejor del Administrador de credenciales; ver docs/WINDOWS.md):\n\n$cmd")
                if (System.getProperty("os.name").startsWith("Windows") && opts.containsKey("crear")) {
                    val p = ProcessBuilder("cmd", "/c", cmd).inheritIO().start(); return p.waitFor()
                }
            }
            else -> { help(); return 1 }
        }
        return 0
    }

    private fun help() = println("""
        SocialRecover Pro · ordenador (motor compartido con Android)

          init <bóveda>                              Crear bóveda (carpeta, disco externo o URL de la nube)
          backup <bóveda> <carpeta>... [--nombre=X]  Copia incremental cifrada de carpetas autorizadas
          list <bóveda>                              Copias disponibles
          calendar <bóveda> [aaaa-mm]                Máquina del tiempo: días con copias
          files <bóveda> <aaaa-mm-dd>                Archivos guardados ese día
          restore <bóveda> <copia> <destino> [--ruta=Carpeta/]   Restaurar y verificar
          find <bóveda> <frase>                      Recuperación guiada ("la foto de la boda de diciembre")
          import-social <bóveda> <zip>...            Importar exportación oficial de una red social
          status <bóveda> <carpeta>...               Estado de protección y avisos
          check <bóveda> [--completa]                Verificar integridad
          retention <bóveda> --ultimas=N ... [--aplicar]   Política de conservación
          migrate <carpeta-v1> <bóveda>              Migrar copias de la versión 1
          schedule <bóveda> <carpeta>... [--hora=21:00] [--crear]  Copia diaria (Programador de tareas)

        Contraseña: se pide por consola o desde SRP_PASSWORD. Nube: URL + SRP_CLOUD_TOKEN.
    """.trimIndent())
}

fun main(args: Array<String>) {
    System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"))
    System.setErr(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.err), true, "UTF-8"))
    val code = try { Cli.run(args.toList()) } catch (e: SrpException) {
        System.err.println(e.userMessage); 2
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message); 1
    } catch (e: IllegalStateException) {
        System.err.println(e.message); 1
    }
    exitProcess(code)
}
