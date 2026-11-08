package com.socialrecover.core.crypto

import com.socialrecover.core.engine.SrpException
import java.io.EOFException
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cifrado en flujo por bloques con AES-256-GCM (construcción "STREAM", Hoang–Reyhanitabar–Rogaway–Vizár).
 *
 * Formato:
 *   cabecera (16 bytes) = magic(4) | versión(1) | tamañoBloque(4, BE) | prefijoNonce(7)
 *   bloques             = cifrado(bloque) || etiqueta(16)  ... repetido
 *
 * Nonce de cada bloque = prefijoNonce(7) | contador(4, BE) | indicadorÚltimo(1)
 * AAD de cada bloque   = cabecera completa
 *
 * Esto detecta: bloques alterados, reordenados, duplicados, eliminados y el truncamiento del final
 * (un archivo cortado no tiene ningún bloque marcado como último, así que su etiqueta no valida).
 * Permite cifrar archivos de cualquier tamaño con memoria constante (un bloque).
 */
object StreamCipher {
    const val HEADER_BYTES = 16
    const val TAG_BYTES = 16
    private const val PREFIX_BYTES = 7
    const val VERSION: Byte = 1

    internal fun nonce(prefix: ByteArray, counter: Long, last: Boolean): ByteArray {
        if (counter > 0xFFFF_FFFFL) throw IOException("Demasiados bloques")
        return ByteBuffer.allocate(12).put(prefix).putInt(counter.toInt()).put(if (last) 1 else 0).array()
    }

    fun encrypt(out: OutputStream, key: ByteArray, magic: String, chunkSize: Int): OutputStream =
        EncryptingOutputStream(out, key, magic, chunkSize)

    fun decrypt(input: InputStream, key: ByteArray, magic: String): InputStream =
        DecryptingInputStream(input, key, magic)

    private class EncryptingOutputStream(
        out: OutputStream,
        key: ByteArray,
        magic: String,
        private val chunkSize: Int,
    ) : FilterOutputStream(out) {
        private val keySpec = SecretKeySpec(key, "AES")
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private val prefix = Keys.randomBytes(PREFIX_BYTES)
        private val header: ByteArray
        private val buf = ByteArray(chunkSize)
        private var len = 0
        private var counter = 0L
        private var closed = false

        init {
            val m = magic.toByteArray(Charsets.US_ASCII)
            require(m.size == 4)
            header = ByteBuffer.allocate(HEADER_BYTES).put(m).put(VERSION).putInt(chunkSize).put(prefix).array()
            out.write(header)
        }

        private fun emit(last: Boolean) {
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, nonce(prefix, counter, last)))
            cipher.updateAAD(header)
            out.write(cipher.doFinal(buf, 0, len))
            counter++
            len = 0
        }

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, n: Int) {
            check(!closed) { "Flujo cerrado" }
            var o = off
            var remaining = n
            while (remaining > 0) {
                // Un bloque lleno solo se emite como "no último" cuando sabemos que llegan más datos.
                if (len == chunkSize) emit(last = false)
                val k = minOf(remaining, chunkSize - len)
                System.arraycopy(b, o, buf, len, k)
                len += k; o += k; remaining -= k
            }
        }

        override fun flush() {
            out.flush()
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                emit(last = true)
                out.flush()
            } finally {
                out.close()
            }
        }
    }

    private class DecryptingInputStream(
        input: InputStream,
        key: ByteArray,
        private val magic: String,
    ) : InputStream() {
        private val src = PushbackInputStream(input, 1)
        private val keySpec = SecretKeySpec(key, "AES")
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private val header = ByteArray(HEADER_BYTES)
        private val prefix: ByteArray
        private val chunkSize: Int
        private val cbuf: ByteArray
        private var plain = ByteArray(0)
        private var pos = 0
        private var counter = 0L
        private var finished = false

        init {
            if (readFully(src, header, 0, HEADER_BYTES) != HEADER_BYTES) {
                throw SrpException.CorruptedBackup("Archivo demasiado corto")
            }
            val bb = ByteBuffer.wrap(header)
            val m = ByteArray(4).also { bb.get(it) }
            if (String(m, Charsets.US_ASCII) != magic) throw SrpException.NotABackup()
            val ver = bb.get()
            if (ver != VERSION) throw SrpException.UnsupportedVersion(ver.toInt())
            chunkSize = bb.int
            if (chunkSize !in CryptoParams.MIN_CHUNK..CryptoParams.MAX_CHUNK) {
                throw SrpException.CorruptedBackup("Tamaño de bloque no válido")
            }
            prefix = ByteArray(PREFIX_BYTES).also { bb.get(it) }
            cbuf = ByteArray(chunkSize + TAG_BYTES)
        }

        private fun nextChunk(): Boolean {
            if (finished) return false
            val n = readFully(src, cbuf, 0, cbuf.size)
            val last: Boolean = if (n < cbuf.size) {
                true
            } else {
                val peek = src.read()
                if (peek == -1) true else { src.unread(peek); false }
            }
            if (n < TAG_BYTES) throw SrpException.IncompleteBackup()
            try {
                cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, nonce(prefix, counter, last)))
                cipher.updateAAD(header)
                plain = cipher.doFinal(cbuf, 0, n)
            } catch (e: AEADBadTagException) {
                // Puede ser manipulación, corrupción o truncamiento justo en un límite de bloque.
                throw SrpException.CorruptedBackup("Bloque ${counter + 1} no supera la verificación de integridad")
            }
            pos = 0
            counter++
            if (last) finished = true
            return true
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= plain.size) {
                if (!nextChunk()) return -1
            }
            val k = minOf(len, plain.size - pos)
            System.arraycopy(plain, pos, b, off, k)
            pos += k
            return k
        }

        override fun close() = src.close()
    }

    internal fun readFully(input: InputStream, b: ByteArray, off: Int, len: Int): Int {
        var total = 0
        while (total < len) {
            val r = input.read(b, off + total, len - total)
            if (r == -1) break
            total += r
        }
        return total
    }
}

internal fun InputStream.readExactly(b: ByteArray) {
    if (StreamCipher.readFully(this, b, 0, b.size) != b.size) throw EOFException()
}
