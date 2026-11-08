package com.socialrecover.core.store

import java.io.InputStream
import java.io.OutputStream

/**
 * Lugar donde se guardan las copias (carpeta del dispositivo, tarjeta SD, pendrive USB-OTG,
 * y en la fase 3: nube). Cada copia es una subcarpeta con tres archivos:
 *
 *   SRP-20261108-150200-ab12/
 *     data.srpk        datos cifrados
 *     manifest.srpm    índice cifrado (nombres, tamaños, huellas SHA-256)
 *     info.properties  datos públicos + clave de datos envuelta con la contraseña
 *
 * Mientras se crea, la carpeta termina en ".partial" y se renombra al final (si el proveedor lo permite).
 * Una copia es válida solo si info.properties existe y contiene complete=true: se escribe en último lugar.
 */
interface BackupLocation {
    /** Nombres de las subcarpetas existentes (incluidas las ".partial"). */
    fun listDirs(): List<String>
    fun createDir(name: String)
    fun openWrite(dir: String, file: String): OutputStream
    /** null si el archivo no existe. */
    fun openRead(dir: String, file: String): InputStream?
    fun renameDir(from: String, to: String)
    fun deleteDir(name: String)
    /** Bytes ocupados por una copia, o null si no se puede calcular. */
    fun dirSize(name: String): Long?
}

/** Archivo de origen elegido por el usuario. */
interface BackupSource {
    val displayName: String
    /** Subcarpeta relativa dentro de la copia ("" para archivos sueltos). */
    val folder: String
    val mimeType: String
    val sizeHint: Long?
    val lastModified: Long
    val category: com.socialrecover.core.model.Category
    fun open(): InputStream
}

/** Destino de una restauración. */
interface RestoreTarget {
    class Created(
        val output: OutputStream,
        val displayPath: String,
        val reopen: () -> InputStream?,
        /** Borra el archivo si la restauración falla a medias (no dejar archivos incompletos). */
        val discard: () -> Unit = {},
    )

    /** Crea un archivo nuevo; nunca sobrescribe uno existente (añade " (1)", " (2)"...). */
    fun create(folderSegments: List<String>, name: String, mimeType: String): Created
}
