package com.socialrecover.core.util

import com.socialrecover.core.engine.SrpException
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Utilidades de serialización binaria y compresión compartidas por los módulos. */
object Bin {
    fun DataOutputStream.str(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        writeInt(b.size); write(b)
    }

    fun DataOutputStream.strN(s: String?) {
        writeBoolean(s != null); if (s != null) str(s)
    }

    fun DataInputStream.str(max: Int = 1 shl 20): String {
        val n = readInt()
        if (n < 0 || n > max) throw SrpException.CorruptedBackup("Texto no válido ($n)")
        val b = ByteArray(n); readFully(b)
        return String(b, Charsets.UTF_8)
    }

    fun DataInputStream.strN(): String? = if (readBoolean()) str() else null

    fun DataOutputStream.bytes(b: ByteArray) { writeInt(b.size); write(b) }

    fun DataInputStream.bytes(max: Int = 1 shl 20): ByteArray {
        val n = readInt()
        if (n < 0 || n > max) throw SrpException.CorruptedBackup("Bloque no válido")
        return ByteArray(n).also { readFully(it) }
    }

    fun deflate(data: ByteArray, off: Int = 0, len: Int = data.size, level: Int = 6): ByteArray {
        val d = Deflater(level)
        try {
            d.setInput(data, off, len); d.finish()
            val out = ByteArrayOutputStream(len / 2 + 64)
            val buf = ByteArray(64 * 1024)
            while (!d.finished()) { val n = d.deflate(buf); out.write(buf, 0, n) }
            return out.toByteArray()
        } finally { d.end() }
    }

    fun inflate(data: ByteArray, off: Int, len: Int, maxOut: Int): ByteArray {
        val inf = Inflater()
        try {
            inf.setInput(data, off, len)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) throw SrpException.CorruptedBackup("Datos comprimidos truncados")
                out.write(buf, 0, n)
                if (out.size() > maxOut) throw SrpException.CorruptedBackup("Datos comprimidos demasiado grandes")
            }
            return out.toByteArray()
        } finally { inf.end() }
    }

    fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) { val v = x.toInt() and 0xFF; sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 15]) }
        return sb.toString()
    }
}
