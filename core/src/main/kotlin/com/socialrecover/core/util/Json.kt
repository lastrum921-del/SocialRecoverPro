package com.socialrecover.core.util

/**
 * Lector JSON mínimo y tolerante (sin dependencias) para las exportaciones de redes sociales.
 * Devuelve Map<String, Any?>, List<Any?>, String, Double/Long, Boolean o null.
 */
object Json {
    class ParseException(msg: String) : Exception(msg)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value(0)
        p.ws()
        if (p.i < text.length) throw ParseException("Contenido sobrante en la posición ${p.i}")
        return v
    }

    /** Las exportaciones de X empiezan por "window.YTD.tweets.part0 = [ ... ]". */
    fun parseJsAssignment(text: String): Any? {
        val start = text.indexOfFirst { it == '[' || it == '{' }
        if (start < 0) throw ParseException("Sin JSON")
        return parse(text.substring(start).trimEnd().removeSuffix(";"))
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun value(depth: Int): Any? {
            if (depth > 200) throw ParseException("Anidamiento excesivo")
            ws()
            if (i >= s.length) throw ParseException("Fin inesperado")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw ParseException("Carácter inesperado '$c' en $i")
            }
        }

        private fun lit(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) throw ParseException("Se esperaba $w en $i")
            i += w.length; return v
        }

        private fun obj(depth: Int): Map<String, Any?> {
            i++; val m = LinkedHashMap<String, Any?>()
            ws(); if (s.getOrNull(i) == '}') { i++; return m }
            while (true) {
                ws(); if (s.getOrNull(i) != '"') throw ParseException("Se esperaba clave en $i")
                val k = str(); ws()
                if (s.getOrNull(i) != ':') throw ParseException("Se esperaba ':' en $i")
                i++; m[k] = value(depth + 1); ws()
                when (s.getOrNull(i)) { ',' -> i++; '}' -> { i++; return m }; else -> throw ParseException("Se esperaba ',' o '}' en $i") }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            i++; val l = ArrayList<Any?>()
            ws(); if (s.getOrNull(i) == ']') { i++; return l }
            while (true) {
                l += value(depth + 1); ws()
                when (s.getOrNull(i)) { ',' -> i++; ']' -> { i++; return l }; else -> throw ParseException("Se esperaba ',' o ']' en $i") }
            }
        }

        private fun str(): String {
            i++; val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw ParseException("Texto sin cerrar")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(st, i)
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: throw ParseException("Número no válido: $t")
        }
    }
}
