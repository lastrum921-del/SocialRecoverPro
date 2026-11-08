package com.socialrecover.core.vault

import com.socialrecover.core.crypto.CryptoParams
import com.socialrecover.core.crypto.Keys
import com.socialrecover.core.engine.SrpException
import com.socialrecover.core.util.Bin
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Arrays
import java.util.Base64
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Bóveda incremental cifrada (formato "vault1").
 *
 * - Los archivos se dividen en bloques de 1 MiB. Cada bloque se identifica por HMAC-SHA256(claveId, contenido):
 *   el mismo contenido produce el mismo identificador → deduplicación entre archivos, copias y dispositivos,
 *   sin revelar al almacén un hash del contenido en claro.
 * - Cada bloque se comprime (Deflate) solo si ahorra espacio, y se cifra con AES-256-GCM.
 *   AAD = identificador del bloque: un bloque no puede hacerse pasar por otro.
 * - Una instantánea (snapshot) lista los archivos y sus bloques; se cifra igual y se escribe la última,
 *   así que una copia interrumpida nunca aparece como válida, y al reanudarla los bloques ya subidos se reutilizan.
 * - Clave maestra aleatoria envuelta con la contraseña (PBKDF2, igual que el formato v1).
 */
class Vault private constructor(
    val backend: RepoBackend,
    private val master: ByteArray,
    val id: String,
    val chunkSize: Int,
) : AutoCloseable {

    private val encKey = SecretKeySpec(Keys.subKey(master, "SRP vault enc v1"), "AES")
    private val idKey = SecretKeySpec(Keys.subKey(master, "SRP vault id v1"), "HmacSHA256")
    private val snapshotCache = ConcurrentHashMap<String, Snapshot>()
    @Volatile private var blobSet: MutableSet<String>? = null

    // ------------------------------------------------------------------ bloques

    fun blobId(data: ByteArray, off: Int = 0, len: Int = data.size): String {
        val mac = Mac.getInstance("HmacSHA256"); mac.init(idKey)
        mac.update(data, off, len)
        return Bin.hex(mac.doFinal())
    }

    private fun blobKey(id: String) = "blobs/${id.substring(0, 2)}/$id"

    /** Identificadores de todos los bloques presentes (se cachea; [refreshBlobs] para recargar). */
    fun knownBlobs(): MutableSet<String> {
        blobSet?.let { return it }
        val s = ConcurrentHashMap.newKeySet<String>()
        backend.list("blobs/").forEach { s.add(it.key.substringAfterLast('/')) }
        blobSet = s
        return s
    }

    fun refreshBlobs() { blobSet = null }

    fun hasBlob(id: String) = id in knownBlobs()

    /**
     * Guarda un bloque si no existe. @return bytes escritos (0 si ya existía) y bytes ahorrados por compresión.
     */
    fun putBlob(id: String, data: ByteArray, off: Int, len: Int, tryCompress: Boolean): Pair<Long, Long> {
        if (hasBlob(id)) return 0L to 0L
        var flag = 0
        var payload: ByteArray = data.copyOfRange(off, off + len)
        var saved = 0L
        if (tryCompress && len >= 512) {
            val z = Bin.deflate(data, off, len)
            if (z.size < len * 0.95) { flag = 1; saved = (len - z.size).toLong(); payload = z }
        }
        val plain = ByteArray(1 + payload.size)
        plain[0] = flag.toByte(); System.arraycopy(payload, 0, plain, 1, payload.size)
        backend.write(blobKey(id), seal(plain, "blob:$id"))
        knownBlobs().add(id)
        return payload.size.toLong() to saved
    }

    /** Lee, descifra, descomprime y VERIFICA un bloque. */
    fun getBlob(id: String): ByteArray {
        val sealed = try { backend.read(blobKey(id)) } catch (e: SrpException) { throw e } catch (e: Exception) { throw SrpException.fromIo(e) }
            ?: throw MissingBlob(id)
        val plain = open(sealed, "blob:$id")
        if (plain.isEmpty()) throw SrpException.CorruptedBackup("Bloque vacío")
        val data = when (plain[0].toInt()) {
            0 -> plain.copyOfRange(1, plain.size)
            1 -> Bin.inflate(plain, 1, plain.size - 1, chunkSize + 1024)
            else -> throw SrpException.CorruptedBackup("Bloque con formato desconocido")
        }
        if (blobId(data) != id) throw SrpException.CorruptedBackup("El bloque no coincide con su identificador")
        return data
    }

    fun deleteBlob(id: String) {
        backend.delete(blobKey(id)); blobSet?.remove(id)
    }

    class MissingBlob(val blobId: String) : SrpException.CorruptedBackup("Falta el bloque $blobId")

    // ------------------------------------------------------------------ instantáneas

    data class SnapshotRef(val name: String, val createdAt: Long, val storage: StorageKind, val vaultId: String)

    fun listSnapshots(): List<SnapshotRef> = backend.list("snapshots/")
        .map { it.key.substringAfterLast('/') }
        .mapNotNull { n -> Snapshot.timeFromName(n)?.let { SnapshotRef(n, it, backend.kind, id) } }
        .sortedBy { it.createdAt }

    fun loadSnapshot(name: String): Snapshot = snapshotCache.getOrPut(name) {
        val sealed = backend.read("snapshots/$name") ?: throw SrpException.IncompleteBackup()
        val z = open(sealed, "snap:$name")
        Snapshot.decode(Bin.inflate(z, 0, z.size, 512 shl 20))
    }

    fun saveSnapshot(s: Snapshot) {
        val z = Bin.deflate(s.encode())
        backend.write("snapshots/${s.name}", seal(z, "snap:${s.name}"))
        snapshotCache[s.name] = s
    }

    fun deleteSnapshot(name: String) {
        backend.delete("snapshots/$name"); snapshotCache.remove(name)
    }

    /** Última instantánea completa de un dispositivo (base para la copia incremental). */
    fun latestFor(device: String): Snapshot? =
        listSnapshots().asReversed().asSequence()
            .mapNotNull { runCatching { loadSnapshot(it.name) }.getOrNull() }
            .firstOrNull { it.device == device }

    // ------------------------------------------------------------------ bloqueos

    /** Marca de "copia en curso": impide que una limpieza borre bloques recién subidos. */
    fun lock(kind: String): String {
        val name = "locks/$kind-${System.currentTimeMillis()}-${Bin.hex(Keys.randomBytes(4))}"
        backend.write(name, ByteArray(0)); return name
    }

    fun unlock(key: String) = runCatching { backend.delete(key) }

    fun activeLocks(maxAgeMs: Long, now: Long = System.currentTimeMillis()): List<String> =
        backend.list("locks/").map { it.key }.filter {
            val t = it.substringAfter('-').substringBefore('-').toLongOrNull() ?: 0L
            now - t < maxAgeMs
        }

    /** Copias que dejaron su marca y nunca terminaron (la app se cerró o el teléfono se apagó). */
    fun staleLocks(olderThanMs: Long = 6 * 3_600_000L, now: Long = System.currentTimeMillis()): List<String> {
        val young = activeLocks(olderThanMs, now).toSet()
        return backend.list("locks/").map { it.key }.filter { it !in young }
    }

    // ------------------------------------------------------------------ cifrado

    private fun seal(plain: ByteArray, aad: String): ByteArray {
        val nonce = Keys.randomBytes(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, encKey, GCMParameterSpec(128, nonce))
        c.updateAAD(aad.toByteArray(Charsets.UTF_8))
        val ct = c.doFinal(plain)
        return ByteBuffer.allocate(5 + 12 + ct.size).put(SEAL_MAGIC).put(1).put(nonce).put(ct).array()
    }

    private fun open(sealed: ByteArray, aad: String): ByteArray {
        if (sealed.size < 5 + 12 + 16 || !sealed.copyOfRange(0, 4).contentEquals(SEAL_MAGIC) || sealed[4].toInt() != 1) {
            throw SrpException.CorruptedBackup("Objeto cifrado no válido ($aad)")
        }
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, encKey, GCMParameterSpec(128, sealed, 5, 12))
            c.updateAAD(aad.toByteArray(Charsets.UTF_8))
            return c.doFinal(sealed, 17, sealed.size - 17)
        } catch (e: AEADBadTagException) {
            throw SrpException.CorruptedBackup("Objeto alterado o dañado ($aad)")
        }
    }

    /** Copia de la clave maestra para guardarla protegida por el llavero del sistema (copias automáticas). */
    fun exportMasterKey(): ByteArray = master.copyOf()

    fun changePassword(newPassword: CharArray, params: CryptoParams = CryptoParams.DEFAULT) {
        writeConfig(backend, master, id, chunkSize, newPassword, params, overwrite = true)
    }

    override fun close() = Arrays.fill(master, 0)

    companion object {
        private val SEAL_MAGIC = "SRPV".toByteArray(Charsets.US_ASCII)
        const val CONFIG = "config"
        const val DEFAULT_CHUNK = 1 shl 20

        fun exists(backend: RepoBackend) = backend.exists(CONFIG)

        fun create(backend: RepoBackend, password: CharArray, params: CryptoParams = CryptoParams.DEFAULT, chunkSize: Int = DEFAULT_CHUNK): Vault {
            if (exists(backend)) throw IllegalStateException("Ya existe una bóveda en esta ubicación")
            val master = Keys.randomBytes(32)
            val id = Bin.hex(Keys.randomBytes(8))
            writeConfig(backend, master, id, chunkSize, password, params, overwrite = false)
            return Vault(backend, master, id, chunkSize)
        }

        fun open(backend: RepoBackend, password: CharArray): Vault {
            val p = readConfig(backend)
            val master = Keys.unwrap(
                Keys.Wrapped(dec(p, "salt"), p.getProperty("iterations").toInt(), dec(p, "wrapNonce"), dec(p, "wrappedKey")),
                password,
            )
            return Vault(backend, master, p.getProperty("id"), p.getProperty("chunkSize").toInt())
        }

        /** Apertura desatendida con la clave maestra guardada en el llavero del dispositivo. */
        fun openWithKey(backend: RepoBackend, masterKey: ByteArray): Vault {
            val p = readConfig(backend)
            val v = Vault(backend, masterKey.copyOf(), p.getProperty("id"), p.getProperty("chunkSize").toInt())
            // Comprobación: la clave debe abrir alguna instantánea (o la bóveda estar vacía).
            v.listSnapshots().lastOrNull()?.let { v.loadSnapshot(it.name) }
            return v
        }

        private fun dec(p: Properties, k: String) = Base64.getDecoder().decode(p.getProperty(k) ?: throw SrpException.NotABackup())

        private fun readConfig(backend: RepoBackend): Properties {
            val bytes = backend.read(CONFIG) ?: throw SrpException.NotABackup()
            val p = Properties()
            try { p.load(ByteArrayInputStream(bytes)) } catch (e: Exception) { throw SrpException.CorruptedBackup("config no válido") }
            if (p.getProperty("format") != "vault1") throw SrpException.UnsupportedVersion(0)
            return p
        }

        private fun writeConfig(backend: RepoBackend, master: ByteArray, id: String, chunk: Int, pw: CharArray, params: CryptoParams, overwrite: Boolean) {
            val w = Keys.wrap(master, pw, params.pbkdf2Iterations)
            val e = Base64.getEncoder()
            val p = Properties()
            p["format"] = "vault1"; p["id"] = id; p["chunkSize"] = chunk.toString()
            p["kdf"] = "PBKDF2-HMAC-SHA256"; p["iterations"] = w.iterations.toString()
            p["salt"] = e.encodeToString(w.salt); p["wrapNonce"] = e.encodeToString(w.nonce)
            p["wrappedKey"] = e.encodeToString(w.wrappedKey); p["cipher"] = "AES-256-GCM"
            val bos = ByteArrayOutputStream()
            p.store(bos, "SocialRecover Pro - boveda cifrada (sin datos en claro)")
            if (!overwrite && exists(backend)) throw IllegalStateException("Ya existe una bóveda")
            backend.write(CONFIG, bos.toByteArray())
        }
    }
}
