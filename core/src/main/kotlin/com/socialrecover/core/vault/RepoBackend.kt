package com.socialrecover.core.vault

import com.socialrecover.core.engine.SrpException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

enum class StorageKind(val label: String) { LOCAL("Local"), CLOUD("Nube") }

data class RepoObject(val key: String, val size: Long)

data class Usage(val usedBytes: Long, val quotaBytes: Long?)

/**
 * Almacén de objetos donde vive una bóveda. Claves tipo "config", "blobs/ab/abcd…", "snapshots/…".
 * Todo lo que se escribe aquí ya está cifrado: el almacén (disco, pendrive o servidor) nunca ve datos en claro.
 *
 * Implementaciones: [LocalRepoBackend] (archivos), SafRepoBackend (Android), HttpRepoBackend (nube).
 */
interface RepoBackend {
    val kind: StorageKind
    /** Nombre comprensible: "Memoria interna › Copias", "Nube SocialRecover"… */
    val displayName: String

    fun list(prefix: String): List<RepoObject>
    fun read(key: String): ByteArray?
    /** Escritura atómica: o queda el objeto completo o no queda nada. */
    fun write(key: String, data: ByteArray)
    fun delete(key: String)
    fun exists(key: String): Boolean = read(key) != null

    /** Espacio usado y cuota (nube), o espacio libre (local). null si no se sabe. */
    fun usage(): Usage? = null
    fun freeSpace(): Long? = null
}

/** Bóveda en una carpeta del sistema de archivos (pruebas, Windows, pendrive montado). */
class LocalRepoBackend(private val root: File, override val displayName: String = root.path) : RepoBackend {
    override val kind = StorageKind.LOCAL

    init { root.mkdirs() }

    private fun file(key: String): File {
        require(!key.contains("..") && !key.startsWith("/")) { "Clave no válida" }
        return File(root, key)
    }

    override fun list(prefix: String): List<RepoObject> {
        val base = file(prefix.trimEnd('/'))
        if (!base.exists()) return emptyList()
        return base.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }
            .map { RepoObject(it.relativeTo(root).invariantSeparatorsPath, it.length()) }.toList()
    }

    override fun read(key: String): ByteArray? = file(key).takeIf { it.isFile }?.readBytes()

    override fun write(key: String, data: ByteArray) {
        val f = file(key)
        f.parentFile.mkdirs()
        val tmp = File(f.parentFile, f.name + "." + System.nanoTime() + ".tmp")
        try {
            FileOutputStream(tmp).use { it.write(data); it.fd.sync() }
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) throw IOException("No se pudo guardar $key")
            }
        } catch (e: Exception) {
            tmp.delete()
            throw SrpException.fromIo(e)
        }
    }

    override fun delete(key: String) {
        file(key).delete()
    }

    override fun exists(key: String) = file(key).isFile
    override fun freeSpace(): Long = root.usableSpace
    override fun usage() = Usage(root.walkTopDown().filter { it.isFile }.sumOf { it.length() }, null)
}
