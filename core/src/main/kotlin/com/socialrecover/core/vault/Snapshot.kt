package com.socialrecover.core.vault

import com.socialrecover.core.engine.SrpException
import com.socialrecover.core.model.Category
import com.socialrecover.core.model.ManifestEntry
import com.socialrecover.core.social.SocialItem
import com.socialrecover.core.social.SocialItemType
import com.socialrecover.core.social.SocialNetwork
import com.socialrecover.core.util.Bin
import com.socialrecover.core.util.Bin.str
import com.socialrecover.core.util.Bin.strN
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

enum class DeviceKind(val label: String) { PHONE("Teléfono"), TABLET("Tableta"), COMPUTER("Ordenador"), UNKNOWN("Dispositivo") }

/** Un archivo dentro de una instantánea. Su contenido son los bloques [blobs] en orden. */
data class SnapshotFile(
    val folder: String,
    val name: String,
    val category: Category,
    /** Red social de origen (si viene de una exportación), o null. */
    val platform: SocialNetwork?,
    val mimeType: String,
    val size: Long,
    val lastModified: Long,
    val sha256: ByteArray,
    val blobs: List<String>,
) {
    val path: String get() = if (folder.isEmpty()) name else "$folder/$name"
    val sha256Hex: String get() = Bin.hex(sha256)

    fun toManifestEntry(id: Int) = ManifestEntry(id, folder, name, category, mimeType, size, sha256, lastModified)

    override fun equals(other: Any?) = other is SnapshotFile && other.path == path && other.sha256.contentEquals(sha256) && other.size == size
    override fun hashCode() = path.hashCode() * 31 + sha256.contentHashCode()
}

data class SnapshotStats(
    val filesTotal: Int = 0,
    val filesUnchanged: Int = 0,
    val bytesTotal: Long = 0,
    /** Bytes realmente escritos (tras deduplicar y comprimir, antes de cifrar). */
    val bytesStored: Long = 0,
    val bytesDeduplicated: Long = 0,
    val bytesSavedByCompression: Long = 0,
    val duplicateFiles: Int = 0,
)

/** Una instantánea = el estado de los archivos protegidos en un momento dado. */
data class Snapshot(
    val name: String,
    val createdAt: Long,
    val device: String,
    val deviceKind: DeviceKind,
    val label: String,
    val parent: String?,
    /** "v1:<carpeta>" si procede de una copia de la versión 1 migrada. */
    val origin: String?,
    val files: List<SnapshotFile>,
    val social: List<SocialItem>,
    val stats: SnapshotStats,
) {
    fun find(path: String) = files.firstOrNull { it.path == path }

    fun encode(): ByteArray {
        val bos = ByteArrayOutputStream()
        val d = DataOutputStream(bos)
        d.writeInt(MAGIC); d.writeByte(VERSION)
        d.str(name); d.writeLong(createdAt); d.str(device); d.str(deviceKind.name); d.str(label)
        d.strN(parent); d.strN(origin)
        d.writeInt(files.size)
        for (f in files) {
            d.str(f.folder); d.str(f.name); d.str(f.category.id); d.strN(f.platform?.name); d.str(f.mimeType)
            d.writeLong(f.size); d.writeLong(f.lastModified); d.write(f.sha256)
            d.writeInt(f.blobs.size); f.blobs.forEach { d.str(it) }
        }
        d.writeInt(social.size)
        for (s in social) {
            d.str(s.network.name); d.str(s.type.name); d.writeLong(s.timestamp); d.str(s.text)
            d.strN(s.url); d.strN(s.mediaPath); d.str(s.sourceFile)
        }
        with(stats) {
            d.writeInt(filesTotal); d.writeInt(filesUnchanged); d.writeLong(bytesTotal); d.writeLong(bytesStored)
            d.writeLong(bytesDeduplicated); d.writeLong(bytesSavedByCompression); d.writeInt(duplicateFiles)
        }
        d.flush()
        return bos.toByteArray()
    }

    companion object {
        private const val MAGIC = 0x53525053 // "SRPS"
        private const val VERSION = 1
        private val nameFmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC)

        /** El nombre contiene la fecha (UTC): permite pintar el calendario sin descifrar nada. */
        fun newName(createdAt: Long, rnd: String) = "snap-${nameFmt.format(Instant.ofEpochMilli(createdAt))}-$rnd"

        fun timeFromName(name: String): Long? = runCatching {
            val t = name.removePrefix("snap-").substringBefore('-')
            java.time.LocalDateTime.parse(t, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()

        fun decode(bytes: ByteArray): Snapshot {
            val d = DataInputStream(ByteArrayInputStream(bytes))
            try {
                if (d.readInt() != MAGIC) throw SrpException.CorruptedBackup("Instantánea no reconocida")
                val v = d.readUnsignedByte()
                if (v != VERSION) throw SrpException.UnsupportedVersion(v)
                val name = d.str(); val created = d.readLong(); val device = d.str()
                val kind = runCatching { DeviceKind.valueOf(d.str()) }.getOrDefault(DeviceKind.UNKNOWN)
                val label = d.str(); val parent = d.strN(); val origin = d.strN()
                val nf = d.readInt()
                if (nf !in 0..10_000_000) throw SrpException.CorruptedBackup("Número de archivos no válido")
                val files = ArrayList<SnapshotFile>(minOf(nf, 100_000))
                repeat(nf) {
                    val folder = d.str(); val fname = d.str(); val cat = Category.fromId(d.str())
                    val platform = d.strN()?.let { p -> runCatching { SocialNetwork.valueOf(p) }.getOrNull() }
                    val mime = d.str(); val size = d.readLong(); val lm = d.readLong()
                    val sha = ByteArray(32).also { d.readFully(it) }
                    val nb = d.readInt()
                    if (nb < 0 || nb > 50_000_000) throw SrpException.CorruptedBackup("Bloques no válidos")
                    val blobs = List(nb) { d.str(128) }
                    files += SnapshotFile(folder, fname, cat, platform, mime, size, lm, sha, blobs)
                }
                val ns = d.readInt()
                if (ns !in 0..50_000_000) throw SrpException.CorruptedBackup("Elementos sociales no válidos")
                val social = ArrayList<SocialItem>(minOf(ns, 100_000))
                repeat(ns) {
                    val net = SocialNetwork.valueOf(d.str()); val type = SocialItemType.valueOf(d.str())
                    social += SocialItem(net, type, d.readLong(), d.str(), d.strN(), d.strN(), d.str())
                }
                val stats = SnapshotStats(d.readInt(), d.readInt(), d.readLong(), d.readLong(), d.readLong(), d.readLong(), d.readInt())
                return Snapshot(name, created, device, kind, label, parent, origin, files, social, stats)
            } catch (e: EOFException) {
                throw SrpException.CorruptedBackup("Instantánea incompleta")
            } catch (e: IllegalArgumentException) {
                throw SrpException.CorruptedBackup("Instantánea con valores no válidos")
            }
        }
    }
}
