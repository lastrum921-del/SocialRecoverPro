package com.socialrecover.core.model

import com.socialrecover.core.engine.SrpException
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** Un archivo dentro de una copia. */
data class ManifestEntry(
    val id: Int,
    /** Carpeta relativa dentro de la copia ("" o "Viaje/Día 1"). */
    val folder: String,
    val name: String,
    val category: Category,
    val mimeType: String,
    val size: Long,
    /** SHA-256 del contenido original (sin cifrar). */
    val sha256: ByteArray,
    val lastModified: Long,
) {
    val path: String get() = if (folder.isEmpty()) name else "$folder/$name"
    val sha256Hex: String get() = sha256.joinToString("") { "%02x".format(it) }

    override fun equals(other: Any?) = other is ManifestEntry && other.id == id && other.path == path &&
        other.size == size && other.sha256.contentEquals(sha256)

    override fun hashCode() = id * 31 + sha256.contentHashCode()
}

/** Índice cifrado de una copia: qué contiene y la huella de cada archivo. */
data class Manifest(
    val label: String,
    val createdAt: Long,
    val deviceName: String,
    val entries: List<ManifestEntry>,
) {
    val totalBytes: Long get() = entries.sumOf { it.size }

    fun writeTo(out: OutputStream) {
        val d = DataOutputStream(out)
        d.writeInt(MAGIC)
        d.writeByte(VERSION)
        d.writeStr(label)
        d.writeLong(createdAt)
        d.writeStr(deviceName)
        d.writeInt(entries.size)
        for (e in entries) {
            d.writeInt(e.id)
            d.writeStr(e.folder)
            d.writeStr(e.name)
            d.writeStr(e.category.id)
            d.writeStr(e.mimeType)
            d.writeLong(e.size)
            d.write(e.sha256)
            d.writeLong(e.lastModified)
        }
        d.flush()
    }

    companion object {
        private const val MAGIC = 0x5352504E // "SRPN"
        private const val VERSION = 1
        private const val MAX_ENTRIES = 5_000_000

        fun readFrom(input: InputStream): Manifest {
            val d = DataInputStream(input)
            try {
                if (d.readInt() != MAGIC) throw SrpException.CorruptedBackup("Índice no reconocido")
                val v = d.readUnsignedByte()
                if (v != VERSION) throw SrpException.UnsupportedVersion(v)
                val label = d.readStr()
                val created = d.readLong()
                val device = d.readStr()
                val n = d.readInt()
                if (n !in 0..MAX_ENTRIES) throw SrpException.CorruptedBackup("Número de archivos no válido")
                val list = ArrayList<ManifestEntry>(minOf(n, 10_000))
                repeat(n) {
                    val id = d.readInt()
                    val folder = d.readStr()
                    val name = d.readStr()
                    val cat = Category.fromId(d.readStr())
                    val mime = d.readStr()
                    val size = d.readLong()
                    val sha = ByteArray(32).also { d.readFully(it) }
                    val lm = d.readLong()
                    list += ManifestEntry(id, folder, name, cat, mime, size, sha, lm)
                }
                return Manifest(label, created, device, list)
            } catch (e: EOFException) {
                throw SrpException.CorruptedBackup("Índice incompleto")
            }
        }

        private fun DataOutputStream.writeStr(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            writeInt(b.size); write(b)
        }

        private fun DataInputStream.readStr(): String {
            val n = readInt()
            if (n !in 0..65_536) throw SrpException.CorruptedBackup("Texto del índice no válido")
            val b = ByteArray(n); readFully(b)
            return String(b, Charsets.UTF_8)
        }
    }
}
