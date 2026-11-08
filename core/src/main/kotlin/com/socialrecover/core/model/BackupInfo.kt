package com.socialrecover.core.model

import com.socialrecover.core.crypto.Keys
import com.socialrecover.core.engine.SrpException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.Properties

/**
 * Datos públicos (no cifrados) de una copia: se leen sin contraseña para poder listar las copias.
 * No contiene nombres de archivos: esos están en el índice cifrado.
 * Visible sin contraseña: nombre de la copia, fecha, número de archivos y tamaño total.
 */
data class BackupInfo(
    val formatVersion: Int,
    val label: String,
    val createdAt: Long,
    val fileCount: Int,
    val totalBytes: Long,
    val appVersion: String,
    val wrapped: Keys.Wrapped,
    val complete: Boolean,
) {
    fun writeTo(out: OutputStream) {
        val p = Properties()
        val b64 = Base64.getEncoder()
        p["format"] = formatVersion.toString()
        p["label"] = label
        p["created"] = createdAt.toString()
        p["fileCount"] = fileCount.toString()
        p["totalBytes"] = totalBytes.toString()
        p["appVersion"] = appVersion
        p["kdf"] = "PBKDF2-HMAC-SHA256"
        p["iterations"] = wrapped.iterations.toString()
        p["salt"] = b64.encodeToString(wrapped.salt)
        p["wrapNonce"] = b64.encodeToString(wrapped.nonce)
        p["wrappedKey"] = b64.encodeToString(wrapped.wrappedKey)
        p["cipher"] = "AES-256-GCM-STREAM"
        p["complete"] = complete.toString()
        // Properties.store escribe en ISO-8859-1 con escapes \\uXXXX: seguro para acentos y emojis.
        p.store(out, "SocialRecover Pro - informacion publica de la copia (no contiene nombres de archivos)")
        out.flush()
    }

    companion object {
        const val FORMAT_VERSION = 1

        fun readFrom(input: InputStream): BackupInfo {
            val p = Properties()
            try {
                p.load(input)
                val fmt = p.getProperty("format")?.toInt() ?: throw SrpException.NotABackup()
                if (fmt > FORMAT_VERSION) throw SrpException.UnsupportedVersion(fmt)
                if (p.getProperty("kdf") != "PBKDF2-HMAC-SHA256") throw SrpException.UnsupportedVersion(fmt)
                val b64 = Base64.getDecoder()
                return BackupInfo(
                    formatVersion = fmt,
                    label = p.getProperty("label", ""),
                    createdAt = p.getProperty("created").toLong(),
                    fileCount = p.getProperty("fileCount").toInt(),
                    totalBytes = p.getProperty("totalBytes").toLong(),
                    appVersion = p.getProperty("appVersion", "?"),
                    wrapped = Keys.Wrapped(
                        salt = b64.decode(p.getProperty("salt")),
                        iterations = p.getProperty("iterations").toInt(),
                        nonce = b64.decode(p.getProperty("wrapNonce")),
                        wrappedKey = b64.decode(p.getProperty("wrappedKey")),
                    ),
                    complete = p.getProperty("complete") == "true",
                )
            } catch (e: SrpException) {
                throw e
            } catch (e: Exception) {
                throw SrpException.CorruptedBackup("info.properties no válido: ${e.message}")
            }
        }
    }
}
