package com.socialrecover.pro.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import com.socialrecover.core.engine.SafeNames
import com.socialrecover.core.model.Category
import com.socialrecover.core.store.BackupLocation
import com.socialrecover.core.store.BackupSource
import com.socialrecover.core.store.RestoreTarget
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Un documento dentro de una carpeta elegida con el selector del sistema (Storage Access Framework). */
internal data class DocChild(val docId: String, val name: String, val mime: String, val size: Long, val lastModified: Long) {
    val isDir get() = mime == Document.MIME_TYPE_DIR
}

/** Utilidades sobre una carpeta autorizada por el usuario (árbol SAF). Solo usa APIs de la plataforma. */
internal class SafTree(private val resolver: ContentResolver, val treeUri: Uri) {
    val rootDocId: String = DocumentsContract.getTreeDocumentId(treeUri)

    fun docUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    fun children(parentDocId: String): List<DocChild> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val cols = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
        )
        val out = ArrayList<DocChild>()
        resolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out += DocChild(
                    docId = c.getString(0),
                    name = c.getString(1) ?: "",
                    mime = c.getString(2) ?: "application/octet-stream",
                    size = if (c.isNull(3)) 0L else c.getLong(3),
                    lastModified = if (c.isNull(4)) 0L else c.getLong(4),
                )
            }
        } ?: throw FileNotFoundException("No se puede leer la carpeta")
        return out
    }

    fun child(parentDocId: String, name: String): DocChild? = children(parentDocId).firstOrNull { it.name == name }

    fun createDir(parentDocId: String, name: String): Uri =
        DocumentsContract.createDocument(resolver, docUri(parentDocId), Document.MIME_TYPE_DIR, name)
            ?: throw IOException("No se pudo crear la carpeta $name")

    fun createFile(parentDocId: String, name: String, mime: String): Uri =
        DocumentsContract.createDocument(resolver, docUri(parentDocId), mime, name)
            ?: throw IOException("No se pudo crear el archivo $name")
}

/** Ubicación de copias en una carpeta elegida por el usuario: memoria interna, tarjeta SD o USB-OTG. */
class SafBackupLocation(context: Context, treeUri: Uri) : BackupLocation {
    private val resolver = context.contentResolver
    private val tree = SafTree(resolver, treeUri)

    private fun dir(name: String): DocChild =
        tree.child(tree.rootDocId, name)?.takeIf { it.isDir } ?: throw FileNotFoundException(name)

    /** Algunos proveedores añaden una extensión al crear el archivo; se acepta "data.srpk" o "data.srpk.bin". */
    private fun fileIn(dir: String, file: String): DocChild? {
        val kids = tree.children(dir(dir).docId)
        return kids.firstOrNull { it.name == file } ?: kids.firstOrNull { !it.isDir && it.name.startsWith("$file.") }
    }

    override fun listDirs(): List<String> = tree.children(tree.rootDocId).filter { it.isDir }.map { it.name }

    override fun createDir(name: String) {
        tree.createDir(tree.rootDocId, name)
    }

    override fun openWrite(dir: String, file: String): OutputStream {
        val uri = tree.createFile(dir(dir).docId, file, "application/octet-stream")
        return resolver.openOutputStream(uri, "w") ?: throw IOException("No se pudo abrir $file para escribir")
    }

    override fun openRead(dir: String, file: String): InputStream? {
        val d = tree.child(tree.rootDocId, dir) ?: return null
        if (!d.isDir) return null
        val f = fileIn(dir, file) ?: return null
        return resolver.openInputStream(tree.docUri(f.docId))
    }

    override fun renameDir(from: String, to: String) {
        DocumentsContract.renameDocument(resolver, tree.docUri(dir(from).docId), to)
            ?: throw IOException("No se pudo renombrar la copia")
    }

    override fun deleteDir(name: String) {
        val d = tree.child(tree.rootDocId, name) ?: return
        if (!DocumentsContract.deleteDocument(resolver, tree.docUri(d.docId))) throw IOException("No se pudo eliminar $name")
    }

    override fun dirSize(name: String): Long? = tree.children(dir(name).docId).sumOf { it.size }
}

/** Destino de restauración: crea una subcarpeta nueva y nunca sobrescribe archivos existentes. */
class SafRestoreTarget(context: Context, treeUri: Uri, baseFolderName: String) : RestoreTarget {
    private val resolver = context.contentResolver
    private val tree = SafTree(resolver, treeUri)
    private val baseDocId: String
    private val dirCache = HashMap<String, String>()
    val baseName: String

    init {
        val existing = tree.children(tree.rootDocId).map { it.name }.toSet()
        var n = SafeNames.sanitize(baseFolderName, "SocialRecover")
        var k = 1
        val orig = n
        while (n in existing) n = "$orig (${k++})"
        val uri = tree.createDir(tree.rootDocId, n)
        baseDocId = DocumentsContract.getDocumentId(uri)
        baseName = n
    }

    private fun folderId(segments: List<String>): String {
        var id = baseDocId
        val path = StringBuilder()
        for (s in segments) {
            path.append('/').append(s)
            id = dirCache.getOrPut(path.toString()) {
                tree.child(id, s)?.takeIf { it.isDir }?.docId
                    ?: DocumentsContract.getDocumentId(tree.createDir(id, s))
            }
        }
        return id
    }

    override fun create(folderSegments: List<String>, name: String, mimeType: String): RestoreTarget.Created {
        val parent = folderId(folderSegments)
        val existing = tree.children(parent).map { it.name }.toSet()
        val safe = SafeNames.sanitize(name)
        var candidate = safe
        var k = 1
        while (candidate in existing) candidate = SafeNames.withCounter(safe, k++)
        val uri = tree.createFile(parent, candidate, mimeType.ifBlank { "application/octet-stream" })
        val out = resolver.openOutputStream(uri, "w") ?: throw IOException("No se pudo escribir $candidate")
        val display = (listOf(baseName) + folderSegments + candidate).joinToString("/")
        return RestoreTarget.Created(out, display, { resolver.openInputStream(uri) }, {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
        })
    }
}

/** Archivo elegido con el selector del sistema. */
class UriSource(
    private val resolver: ContentResolver,
    val uri: Uri,
    override val displayName: String,
    override val mimeType: String,
    override val sizeHint: Long?,
    override val lastModified: Long,
    override val category: Category,
    override val folder: String = "",
) : BackupSource {
    override fun open(): InputStream = resolver.openInputStream(uri) ?: throw FileNotFoundException(displayName)

    companion object {
        fun from(context: Context, uri: Uri, forcedCategory: Category? = null): UriSource {
            val r = context.contentResolver
            var name = uri.lastPathSegment?.substringAfterLast('/') ?: "archivo"
            var size: Long? = null
            r.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    if (!c.isNull(0)) name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
            val lastModified = try {
                r.query(uri, arrayOf(Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
                } ?: 0L
            } catch (e: Exception) {
                0L
            }
            val mime = r.getType(uri) ?: "application/octet-stream"
            return UriSource(r, uri, name, mime, size, lastModified, forcedCategory ?: Category.guess(mime, name))
        }

        /**
         * Todos los archivos de una carpeta elegida por el usuario (recursivo), conservando subcarpetas.
         * Límite de seguridad para no bloquear el teléfono con carpetas enormes.
         */
        fun fromTree(context: Context, treeUri: Uri, category: Category, maxFiles: Int = 20_000): List<UriSource> {
            val r = context.contentResolver
            val tree = SafTree(r, treeUri)
            val rootName = r.query(tree.docUri(tree.rootDocId), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "Carpeta"
            val out = ArrayList<UriSource>()
            fun walk(docId: String, folder: String, depth: Int) {
                if (depth > 30 || out.size >= maxFiles) return
                for (c in tree.children(docId)) {
                    if (out.size >= maxFiles) return
                    if (c.isDir) {
                        walk(c.docId, "$folder/${c.name}", depth + 1)
                    } else {
                        out += UriSource(r, tree.docUri(c.docId), c.name, c.mime, c.size, c.lastModified, category, folder)
                    }
                }
            }
            walk(tree.rootDocId, rootName, 0)
            return out
        }
    }
}
