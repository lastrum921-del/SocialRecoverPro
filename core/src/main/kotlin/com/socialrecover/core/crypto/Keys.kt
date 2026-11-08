package com.socialrecover.core.crypto

import com.socialrecover.core.engine.SrpException
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Gestión de claves por sobre (envelope encryption):
 *
 *   contraseña --PBKDF2--> KEK (clave de cifrado de claves)
 *   DEK aleatoria de 256 bits (clave de datos de esta copia)
 *   DEK envuelta = AES-256-GCM(KEK, DEK)
 *
 * Ventajas: cambiar la contraseña solo requiere volver a envolver la DEK, y en la fase de nube
 * el servidor almacenará la DEK envuelta sin poder abrirla (conocimiento cero).
 */
object Keys {
    const val KEY_BYTES = 32
    const val SALT_BYTES = 16
    const val GCM_NONCE_BYTES = 12
    const val GCM_TAG_BITS = 128

    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun deriveKek(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BYTES * 8)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun wrapAad(salt: ByteArray, iterations: Int): ByteArray =
        ByteBuffer.allocate(5 + salt.size + 4)
            .put("SRPK1".toByteArray(Charsets.US_ASCII))
            .put(salt)
            .putInt(iterations)
            .array()

    data class Wrapped(val salt: ByteArray, val iterations: Int, val nonce: ByteArray, val wrappedKey: ByteArray)

    fun wrap(dek: ByteArray, password: CharArray, iterations: Int): Wrapped {
        val salt = randomBytes(SALT_BYTES)
        val nonce = randomBytes(GCM_NONCE_BYTES)
        val kek = deriveKek(password, salt, iterations)
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            c.updateAAD(wrapAad(salt, iterations))
            return Wrapped(salt, iterations, nonce, c.doFinal(dek))
        } finally {
            Arrays.fill(kek, 0)
        }
    }

    /** @throws SrpException.WrongPassword si la contraseña no es correcta (o la cabecera fue alterada). */
    fun unwrap(w: Wrapped, password: CharArray): ByteArray {
        if (w.iterations !in CryptoParams.MIN_ITERATIONS..CryptoParams.MAX_ITERATIONS ||
            w.salt.size != SALT_BYTES || w.nonce.size != GCM_NONCE_BYTES
        ) throw SrpException.CorruptedBackup("Cabecera de clave no válida")
        val kek = deriveKek(password, w.salt, w.iterations)
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(GCM_TAG_BITS, w.nonce))
            c.updateAAD(wrapAad(w.salt, w.iterations))
            val dek = c.doFinal(w.wrappedKey)
            if (dek.size != KEY_BYTES) throw SrpException.CorruptedBackup("Clave de datos con tamaño incorrecto")
            return dek
        } catch (e: AEADBadTagException) {
            throw SrpException.WrongPassword()
        } finally {
            Arrays.fill(kek, 0)
        }
    }

    /** Subclave independiente para cada uso (datos / índice), mediante HMAC-SHA256(DEK, etiqueta). */
    fun subKey(dek: ByteArray, label: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(dek, "HmacSHA256"))
        return mac.doFinal(label.toByteArray(Charsets.UTF_8))
    }

    const val LABEL_DATA = "SocialRecoverPro data v1"
    const val LABEL_MANIFEST = "SocialRecoverPro manifest v1"
}
