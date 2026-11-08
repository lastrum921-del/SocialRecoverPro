package com.socialrecover.pro.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Llavero del dispositivo (Android Keystore, respaldado por hardware cuando existe).
 *
 * Guarda, cifrados con una clave que nunca sale del chip de seguridad:
 *  - la clave maestra de la bóveda, SOLO si el usuario activa "copias automáticas en este dispositivo";
 *  - el token de sesión de la nube.
 * Las contraseñas nunca se guardan. Al desactivar la opción o restablecer el teléfono, se pierden.
 */
class DeviceKeys(context: Context) {
    private val sp = context.getSharedPreferences("srp_keys", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun put(name: String, value: ByteArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(value)
        sp.edit().putString(name, Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)).apply()
    }

    fun get(name: String): ByteArray? {
        val b = sp.getString(name, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12))
            c.doFinal(b, 12, b.size - 12)
        } catch (e: Exception) {
            remove(name); null // clave del llavero invalidada (p. ej. tras restablecer el bloqueo de pantalla)
        }
    }

    fun remove(name: String) = sp.edit().remove(name).apply()

    companion object {
        private const val ALIAS = "srp_device_key_v1"
        const val VAULT_LOCAL = "vault_local_master"
        const val VAULT_CLOUD = "vault_cloud_master"
        const val CLOUD_TOKEN = "cloud_token"
    }
}
