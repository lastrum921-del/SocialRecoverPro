package com.socialrecover.core.recovery

import com.socialrecover.core.social.SocialNetwork
import com.socialrecover.core.vault.DeviceKind
import com.socialrecover.core.vault.TimeMachine
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** Paso 1 de la recuperación guiada: «¿Qué has perdido?». */
enum class LostKind(val label: String) {
    PHOTO("Foto"), VIDEO("Vídeo"), DOCUMENT("Documento"), POST("Publicación"), COMMENT("Comentario"),
    LIKE("Registro de «Me gusta»"), FOLDER("Carpeta"), OTHER("Otro archivo"),
}

data class ParsedQuery(
    val kinds: Set<LostKind> = emptySet(),
    val networks: Set<SocialNetwork> = emptySet(),
    val deviceKind: DeviceKind? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val keywords: List<String> = emptyList(),
    /** Lo que se ha entendido, para mostrarlo al usuario y que pueda corregirlo. */
    val understood: List<String> = emptyList(),
)

/**
 * Interpreta frases en español como «Quiero recuperar un vídeo de TikTok de septiembre».
 * Funciona SIN conexión y sin enviar el texto a ningún servicio: reglas y diccionarios locales.
 * Lo que no reconoce como tipo, red, dispositivo o fecha se usa como palabras clave.
 */
object QueryParser {
    private val es = Locale.forLanguageTag("es-ES")

    private val kindWords: List<Pair<LostKind, List<String>>> = listOf(
        LostKind.PHOTO to listOf("foto", "fotos", "fotografia", "fotografias", "imagen", "imagenes", "selfie", "selfies", "captura", "capturas"),
        LostKind.VIDEO to listOf("video", "videos", "reel", "reels", "clip", "clips", "story", "stories", "historia", "historias"),
        LostKind.DOCUMENT to listOf("documento", "documentos", "pdf", "word", "excel", "contrato", "factura", "facturas", "trabajo", "cv", "curriculum"),
        LostKind.POST to listOf("publicacion", "publicaciones", "post", "posts", "tuit", "tuits", "tweet", "tweets", "hilo", "hilos"),
        LostKind.COMMENT to listOf("comentario", "comentarios", "respuesta", "respuestas"),
        LostKind.LIKE to listOf("like", "likes", "megusta"),
        LostKind.FOLDER to listOf("carpeta", "carpetas"),
    )

    private val networkWords: Map<String, SocialNetwork> = mapOf(
        "instagram" to SocialNetwork.INSTAGRAM, "insta" to SocialNetwork.INSTAGRAM, "ig" to SocialNetwork.INSTAGRAM,
        "tiktok" to SocialNetwork.TIKTOK, "facebook" to SocialNetwork.FACEBOOK, "fb" to SocialNetwork.FACEBOOK, "face" to SocialNetwork.FACEBOOK,
        "youtube" to SocialNetwork.YOUTUBE, "twitter" to SocialNetwork.X, "threads" to SocialNetwork.THREADS,
        "reddit" to SocialNetwork.REDDIT, "pinterest" to SocialNetwork.PINTEREST, "snapchat" to SocialNetwork.SNAPCHAT, "snap" to SocialNetwork.SNAPCHAT,
    )

    private val deviceWords: Map<String, DeviceKind> = mapOf(
        "ordenador" to DeviceKind.COMPUTER, "pc" to DeviceKind.COMPUTER, "portatil" to DeviceKind.COMPUTER, "windows" to DeviceKind.COMPUTER,
        "computadora" to DeviceKind.COMPUTER, "movil" to DeviceKind.PHONE, "telefono" to DeviceKind.PHONE, "celular" to DeviceKind.PHONE,
        "android" to DeviceKind.PHONE, "iphone" to DeviceKind.PHONE, "tablet" to DeviceKind.TABLET, "tableta" to DeviceKind.TABLET, "ipad" to DeviceKind.TABLET,
    )

    private val months = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")

    private val stop = setOf(
        "quiero", "queria", "quisiera", "recuperar", "recupera", "busco", "buscar", "encontrar", "encuentra", "necesito", "tenia", "tengo",
        "mi", "mis", "un", "una", "unos", "unas", "el", "la", "los", "las", "lo", "de", "del", "en", "con", "que", "y", "o", "a", "al",
        "por", "para", "me", "se", "su", "sus", "antiguo", "antiguos", "antigua", "antiguas", "viejo", "viejos", "perdi", "perdido", "perdida",
        "borre", "borrado", "borrada", "elimine", "eliminado", "eliminada", "archivo", "archivos", "hice", "subi", "publique", "puse",
        "donde", "esta", "estaba", "algo", "alguna", "algun", "todo", "todos", "otro", "otra", "copia", "favor", "porfa", "hola", "gusta",
        "hace", "dias", "semanas", "meses", "anos", "ano", "pasado", "pasada", "este", "esta", "semana", "mes", "hoy", "ayer", "verano", "navidad",
        "dispositivo", "nube", "red", "redes", "social", "sociales",
    )

    fun parse(text: String, now: LocalDate = LocalDate.now(ZoneId.systemDefault()), selectedKind: LostKind? = null): ParsedQuery {
        val raw = TimeMachine.norm(text).replace("me gusta", "megusta").replace("me gustas", "megusta")
        val tokens = raw.split(Regex("[^\\p{L}\\p{N}/]+")).filter { it.isNotEmpty() }
        val kinds = LinkedHashSet<LostKind>(); selectedKind?.let { kinds += it }
        val nets = LinkedHashSet<SocialNetwork>()
        var device: DeviceKind? = null
        val used = HashSet<Int>()
        val understood = ArrayList<String>()

        tokens.forEachIndexed { i, t ->
            kindWords.firstOrNull { t in it.second }?.let { kinds += it.first; used += i }
            networkWords[t]?.let { nets += it; used += i }
            if (t == "x" && (tokens.getOrNull(i - 1) == "de" || tokens.getOrNull(i - 1) == "en")) { nets += SocialNetwork.X; used += i }
            deviceWords[t]?.let { device = it; used += i }
        }

        // ---------- fechas
        var from: LocalDate? = null; var to: LocalDate? = null; var dateText: String? = null
        fun range(f: LocalDate, t: LocalDate, label: String) { from = f; to = t; dateText = label }
        val year = tokens.firstOrNull { it.length == 4 && it.toIntOrNull() in 1990..2100 }?.toInt()
        tokens.forEachIndexed { i, t -> if (t.length == 4 && t.toIntOrNull() in 1990..2100) used += i }
        val mIdx = tokens.indexOfFirst { it in months }
        val explicit = tokens.firstNotNullOfOrNull { t ->
            Regex("^(\\d{1,2})/(\\d{1,2})/(\\d{2,4})$").find(t)?.let { m ->
                val (d, mo, y) = m.destructured
                runCatching { LocalDate.of(if (y.length == 2) 2000 + y.toInt() else y.toInt(), mo.toInt(), d.toInt()) }.getOrNull()
            }
        }
        val haceIdx = tokens.indexOf("hace")
        when {
            explicit != null -> range(explicit, explicit, explicit.toString())
            mIdx >= 0 -> {
                used += mIdx
                val month = mIdx.let { months.indexOf(tokens[it]) + 1 }
                val y = year ?: if (month > now.monthValue) now.year - 1 else now.year
                val f = LocalDate.of(y, month, 1)
                range(f, f.plusMonths(1).minusDays(1), "${tokens[mIdx]} de $y")
            }
            raw.contains("navidad") -> {
                val y = year ?: if (now.monthValue == 12 && now.dayOfMonth >= 20) now.year else now.year - 1
                range(LocalDate.of(y, 12, 20), LocalDate.of(y + 1, 1, 6), "Navidad de $y")
            }
            raw.contains("verano") -> {
                val y = year ?: if (now.monthValue >= 7) now.year else now.year - 1
                range(LocalDate.of(y, 6, 21), LocalDate.of(y, 9, 22), "verano de $y")
            }
            year != null -> range(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), "año $year")
            raw.contains("anteayer") -> now.minusDays(2).let { range(it, it, "anteayer") }
            "ayer" in tokens -> now.minusDays(1).let { range(it, it, "ayer") }
            "hoy" in tokens -> range(now, now, "hoy")
            raw.contains("semana pasada") -> {
                val mon = now.with(DayOfWeek.MONDAY).minusWeeks(1); range(mon, mon.plusDays(6), "la semana pasada")
            }
            raw.contains("esta semana") -> range(now.with(DayOfWeek.MONDAY), now, "esta semana")
            raw.contains("mes pasado") -> {
                val f = now.withDayOfMonth(1).minusMonths(1); range(f, f.plusMonths(1).minusDays(1), "el mes pasado")
            }
            raw.contains("este mes") -> range(now.withDayOfMonth(1), now, "este mes")
            raw.contains("ano pasado") -> range(LocalDate.of(now.year - 1, 1, 1), LocalDate.of(now.year - 1, 12, 31), "el año pasado")
            raw.contains("este ano") -> range(LocalDate.of(now.year, 1, 1), now, "este año")
            haceIdx >= 0 && tokens.getOrNull(haceIdx + 1)?.toIntOrNull() != null -> {
                val n = tokens[haceIdx + 1].toLong(); used += haceIdx + 1
                val unit = tokens.getOrNull(haceIdx + 2) ?: "dias"
                val center = when {
                    unit.startsWith("semana") -> now.minusWeeks(n)
                    unit.startsWith("mes") -> now.minusMonths(n)
                    unit.startsWith("ano") -> now.minusYears(n)
                    else -> now.minusDays(n)
                }
                val w = when { unit.startsWith("ano") -> 183L; unit.startsWith("mes") -> 20L; unit.startsWith("semana") -> 4L; else -> 1L }
                range(center.minusDays(w), minOf(center.plusDays(w), now), "hace $n ${unit}")
            }
        }

        val keywords = tokens.filterIndexed { i, t -> i !in used && t !in stop && t.length >= 2 && t.toIntOrNull() == null && !t.contains('/') }
        if (kinds.isNotEmpty()) understood += "Tipo: " + kinds.joinToString { it.label.lowercase(es) }
        if (nets.isNotEmpty()) understood += "Red: " + nets.joinToString { it.label }
        device?.let { understood += "Dispositivo: " + it.label.lowercase(es) }
        dateText?.let { understood += "Fecha: $it" }
        if (keywords.isNotEmpty()) understood += "Palabras: " + keywords.joinToString(", ")
        return ParsedQuery(kinds, nets, device, from, to, keywords, understood)
    }

    fun monthName(m: Int): String = java.time.Month.of(m).getDisplayName(TextStyle.FULL, es)
}
