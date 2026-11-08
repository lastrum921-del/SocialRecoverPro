package com.socialrecover.core.store

import com.socialrecover.core.engine.SafeNames
import com.socialrecover.core.model.Category
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Implementación con java.io.File. La usan las pruebas automáticas y servirá de referencia
 * para la versión de escritorio. En Android se usa SafBackupLocation (Storage Access Framework).
 */
class FileBackupLocation(private val root: File) : BackupLocation {
    init { root.mkdirs() }

    override fun listDirs(): List<String> = root.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()

    override fun createDir(name: String) {
        val d = File(root, name)
        if (!d.mkdirs() && !d.isDirectory) throw IOException("No se pudo crear $d")
    }

    override fun openWrite(dir: String, file: String): OutputStream = FileOutputStream(File(File(root, dir), file))

    override fun openRead(dir: String, file: String): InputStream? =
        File(File(root, dir), file).takeIf { it.isFile }?.let { FileInputStream(it) }

    override fun renameDir(from: String, to: String) {
        if (!File(root, from).renameTo(File(root, to))) throw IOException("No se pudo renombrar $from")
    }

    override fun deleteDir(name: String) {
        File(root, name).deleteRecursively()
    }

    override fun dirSize(name: String): Long? = File(root, name).listFiles()?.sumOf { it.length() }
}

class FileSource(
    private val file: File,
    override val folder: String = "",
    override val category: Category = Category.guess(null, file.name),
    override val mimeType: String = "application/octet-stream",
) : BackupSource {
    override val displayName: String get() = file.name
    override val sizeHint: Long get() = file.length()
    override val lastModified: Long get() = file.lastModified()
    override fun open(): InputStream = FileInputStream(file)
}

class FileRestoreTarget(private val root: File) : RestoreTarget {
    override fun create(folderSegments: List<String>, name: String, mimeType: String): RestoreTarget.Created {
        var dir = root
        for (s in folderSegments) dir = File(dir, SafeNames.sanitize(s, "carpeta"))
        dir.mkdirs()
        val safe = SafeNames.sanitize(name)
        var f = File(dir, safe)
        var k = 1
        while (f.exists()) f = File(dir, SafeNames.withCounter(safe, k++))
        // Comprobación defensiva frente a salida de la carpeta de destino.
        check(f.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "Ruta fuera del destino" }
        val out = FileOutputStream(f)
        val created = f
        return RestoreTarget.Created(out, f.relativeTo(root).path, { FileInputStream(created) }, { created.delete() })
    }
}
