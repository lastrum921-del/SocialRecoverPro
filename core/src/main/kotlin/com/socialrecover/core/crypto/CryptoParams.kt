package com.socialrecover.core.crypto

/**
 * Parámetros criptográficos de una copia.
 *
 * - KDF: PBKDF2-HMAC-SHA256 (disponible de forma nativa en Android 8+ y en cualquier JVM).
 *   600 000 iteraciones siguen la recomendación OWASP vigente para PBKDF2-SHA256.
 *   El número de iteraciones se guarda en cada copia, así que puede subirse en el futuro
 *   sin romper la compatibilidad con copias antiguas.
 * - Cifrado: AES-256-GCM por bloques (construcción STREAM), etiqueta de 128 bits.
 */
data class CryptoParams(
    val pbkdf2Iterations: Int = DEFAULT_ITERATIONS,
    val chunkSize: Int = DEFAULT_CHUNK_SIZE,
) {
    init {
        require(pbkdf2Iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "Iteraciones fuera de rango" }
        require(chunkSize in MIN_CHUNK..MAX_CHUNK) { "Tamaño de bloque fuera de rango" }
    }

    companion object {
        const val DEFAULT_ITERATIONS = 600_000
        const val DEFAULT_CHUNK_SIZE = 1 shl 20 // 1 MiB

        /** Mínimo aceptado al LEER una copia (protege frente a cabeceras manipuladas). */
        const val MIN_ITERATIONS = 1_000
        const val MAX_ITERATIONS = 10_000_000
        const val MIN_CHUNK = 4 * 1024
        const val MAX_CHUNK = 16 shl 20

        val DEFAULT = CryptoParams()
    }
}
