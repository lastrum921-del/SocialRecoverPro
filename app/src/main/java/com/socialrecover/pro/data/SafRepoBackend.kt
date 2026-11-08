package com.socialrecover.pro.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.socialrecover.core.engine.SrpException
import com.socialrecover.core.vault.RepoBackend
import com.socialrecover.core.vault.RepoObject
import com.socialrecover.core.vault.StorageKind
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Bóveda en una carpeta elegida por el usuario (memoria interna, tarjeta SD, pendrive USB-OTG),
 * accedida con el Storage Access Framework. Crea dentro una carpeta "SocialRecoverPro-Boveda".
 */
class SafRepoBackend(context: Context, treeUri: Uri, override val displayName: String) : RepoBackend {
    override val kind = StorageKind.LOCAL
    private val resolver = context.contentResolver
    private val tree = SafTree(resolver, treeUri)
    private val dirIds = ConcurrentHashMap<String, String>()
    private val rootId: String

    init {
        rootId = tree.child(tree.rootDocId, ROOT)?.takeIf { it.isDir }?.docId
            ?: DocumentsContract.getDocumentId(tree.createDir(tree.rootDocId, ROOT))
        dirIds[""] = rootId
    }

    private fun dirId(path: String, create: Boolean): String? {
        if (path.isEmpty()) return rootId
        dirIds[path]?.let { return it }
        val parent = dirId(path.substringBeforeLast('/', ""), create) ?: return null
        val name = path.substringAfterLast('/')
        val id = tree.child(parent, name)?.takeIf { it.isDir }?.docId
            ?: if (create) DocumentsContract.getDocumentId(tree.createDir(parent, name)) else return null
        dirIds[path] = id
        return id
    }

    private fun fileDoc(key: String): DocChild? {
        val dir = dirId(key.substringBeforeLast('/', ""), create = false) ?: return null
        return tree.child(dir, key.substringAfterLast('/'))?.takeIf { !it.isDir }
    }

    private inline fun <T> io(block: () -> T): T = try { block() } catch (e: SrpException) { throw e } catch (e: Exception) { throw SrpException.fromIo(e, "la carpeta de la bóveda") }

    override fun list(prefix: String): List<RepoObject> = io {
        val base = prefix.trimEnd('/')
        val start = dirId(base, create = false) ?: return@io emptyList()
        val out = ArrayList<RepoObject>()
        fun walk(id: String, path: String) {
            for (c in tree.children(id)) {
                val p = if (path.isEmpty()) c.name else "$path/${c.name}"
                if (c.isDir) { dirIds[p] = c.docId; walk(c.docId, p) }
                else if (!c.name.endsWith(".tmp")) out += RepoObject(p, c.size)
            }
        }
        walk(start, base)
        out
    }

    override fun read(key: String): ByteArray? = io {
        val f = fileDoc(key) ?: return@io null
        resolver.openInputStream(tree.docUri(f.docId))?.use { s -> ByteArrayOutputStream().also { s.copyTo(it) }.toByteArray() }
            ?: throw FileNotFoundException(key)
    }

    override fun write(key: String, data: ByteArray) = io {
        val dir = dirId(key.substringBeforeLast('/', ""), create = true)!!
        val name = key.substringAfterLast('/')
        // Escritura en un temporal y cambio de nombre: no quedan objetos a medias si se corta.
        val tmpUri = tree.createFile(dir, "$name.tmp", "application/octet-stream")
        try {
            (resolver.openOutputStream(tmpUri, "w") ?: throw IOException("No se pudo escribir $key")).use { it.write(data) }
            tree.child(dir, name)?.let { DocumentsContract.deleteDocument(resolver, tree.docUri(it.docId)) }
            if (DocumentsContract.renameDocument(resolver, tmpUri, name) == null) throw IOException("No se pudo renombrar")
        } catch (e: UnsupportedOperationException) {
            // Proveedor sin renombrado: escritura directa (la verificación GCM detectaría un objeto incompleto).
            DocumentsContract.deleteDocument(resolver, tmpUri)
            val u = tree.createFile(dir, name, "application/octet-stream")
            (resolver.openOutputStream(u, "w") ?: throw IOException("No se pudo escribir $key")).use { it.write(data) }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, tmpUri) }
            throw e
        }
        Unit
    }

    override fun delete(key: String) = io {
        fileDoc(key)?.let { DocumentsContract.deleteDocument(resolver, tree.docUri(it.docId)) }
        Unit
    }

    override fun exists(key: String): Boolean = io { fileDoc(key) != null }

    companion object {
        const val ROOT = "SocialRecoverPro-Boveda"
    }
}

/** Utilidad para comprobar si existe un documento con un tipo MIME de carpeta. */
internal fun isDirMime(m: String) = m == Document.MIME_TYPE_DIR
