package com.socialrecover.core.engine

import java.text.Normalizer

/**
 * Nombres de archivo seguros para restaurar en Android, Windows y macOS/iOS.
 * - Impide salir de la carpeta de destino ("..", rutas absolutas, separadores).
 * - Sustituye caracteres no válidos en Windows y caracteres de control.
 * - Evita nombres reservados de Windows (CON, NUL, COM1...).
 * - Conserva acentos, eñes y emojis (normalizados a NFC).
 */
object SafeNames {
    private const val MAX_UTF8_BYTES = 200
    private val invalid = Regex("[\\u0000-\\u001F\\u007F/\\\\:*?\"<>|]")
    private val reserved = Regex("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$", RegexOption.IGNORE_CASE)

    fun sanitize(name: String, fallback: String = "archivo"): String {
        var n = Normalizer.normalize(name, Normalizer.Form.NFC)
        n = invalid.replace(n, "_")
        n = n.trim().trimEnd('.', ' ').trimStart(' ')
        // Tras quitar los puntos finales, "." y ".." quedan vacíos.
        if (n.isEmpty()) n = fallback
        if (reserved.matches(n)) n = "_$n"
        return truncateUtf8(n)
    }

    /** Ruta relativa ("Fotos/Viaje") -> lista de segmentos seguros. Nunca contiene "..". */
    fun sanitizePath(path: String): List<String> =
        path.replace('\\', '/').split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .map { sanitize(it, "carpeta") }

    private fun truncateUtf8(n: String): String {
        if (n.toByteArray(Charsets.UTF_8).size <= MAX_UTF8_BYTES) return n
        val dot = n.lastIndexOf('.')
        val ext = if (dot > 0 && n.length - dot <= 10) n.substring(dot) else ""
        val base = if (ext.isEmpty()) n else n.substring(0, dot)
        val sb = StringBuilder()
        var bytes = ext.toByteArray(Charsets.UTF_8).size
        var i = 0
        while (i < base.length) {
            val cp = base.codePointAt(i)
            val s = String(Character.toChars(cp))
            val b = s.toByteArray(Charsets.UTF_8).size
            if (bytes + b > MAX_UTF8_BYTES) break
            sb.append(s); bytes += b; i += Character.charCount(cp)
        }
        return sb.toString() + ext
    }

    /** "foto.jpg" -> "foto (1).jpg", "foto (2).jpg"... */
    fun withCounter(name: String, k: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)} ($k)${name.substring(dot)}" else "$name ($k)"
    }
}
