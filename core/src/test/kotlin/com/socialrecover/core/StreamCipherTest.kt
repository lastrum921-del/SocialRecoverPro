package com.socialrecover.core

import com.socialrecover.core.crypto.Keys
import com.socialrecover.core.crypto.StreamCipher
import com.socialrecover.core.engine.SrpException
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Pruebas directas del cifrado por bloques, sin la capa de contenedor. */
class StreamCipherTest {
    private val key = Keys.randomBytes(32)
    private val chunk = 4096

    private fun enc(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        StreamCipher.encrypt(bos, key, "TEST", chunk).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun dec(ct: ByteArray, k: ByteArray = key): ByteArray =
        StreamCipher.decrypt(ByteArrayInputStream(ct), k, "TEST").use { it.readBytes() }

    @Test
    fun roundTripAllBoundaries() {
        for (size in listOf(0, 1, chunk - 1, chunk, chunk + 1, 3 * chunk, 3 * chunk + 7)) {
            val p = Random(size).nextBytes(size)
            assertContentEquals(p, dec(enc(p)), "tamaño $size")
        }
    }

    @Test
    fun truncationAtChunkBoundaryIsDetected() {
        val ct = enc(Random(1).nextBytes(3 * chunk + 10))
        // Quitar el último bloque completo deja un archivo "bien formado" de 3 bloques.
        val cut = ct.copyOf(StreamCipher.HEADER_BYTES + 3 * (chunk + StreamCipher.TAG_BYTES))
        assertFailsWith<SrpException.CorruptedBackup> { dec(cut) }
        val cut2 = ct.copyOf(StreamCipher.HEADER_BYTES + 2 * (chunk + StreamCipher.TAG_BYTES))
        assertFailsWith<SrpException.CorruptedBackup> { dec(cut2) }
    }

    @Test
    fun reorderedChunksAreDetected() {
        val ct = enc(Random(2).nextBytes(3 * chunk))
        val b = chunk + StreamCipher.TAG_BYTES
        val h = StreamCipher.HEADER_BYTES
        val swapped = ct.copyOf()
        System.arraycopy(ct, h + b, swapped, h, b)
        System.arraycopy(ct, h, swapped, h + b, b)
        assertFailsWith<SrpException.CorruptedBackup> { dec(swapped) }
    }

    @Test
    fun wrongKeyAndWrongMagic() {
        val ct = enc(byteArrayOf(1, 2, 3))
        assertFailsWith<SrpException.CorruptedBackup> { dec(ct, Keys.randomBytes(32)) }
        assertFailsWith<SrpException.NotABackup> {
            StreamCipher.decrypt(ByteArrayInputStream(ct), key, "XXXX").use { it.readBytes() }
        }
    }

    @Test
    fun noncesAreUniquePerFile() {
        val a = enc(ByteArray(100)); val b = enc(ByteArray(100))
        assertTrue(!a.contentEquals(b), "Dos cifrados del mismo contenido deben diferir")
    }
}
