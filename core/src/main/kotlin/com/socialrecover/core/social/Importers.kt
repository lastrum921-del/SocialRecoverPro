package com.socialrecover.core.social

import com.socialrecover.core.util.Json
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Contenido de una exportación ya leída: archivos de datos (JSON, JS, CSV, HTML, TXT) en memoria
 * y la lista de TODOS los archivos con la ruta con la que se guardaron en la copia.
 */
class ExportFiles(
    /** ruta dentro del ZIP → contenido (solo archivos de datos). */
    val data: Map<String, ByteArray>,
    /** ruta dentro del ZIP → ruta guardada en la copia. */
    val stored: Map<String, String>,
    /** ruta dentro del ZIP → fecha del archivo en el ZIP (ms) o 0. */
    val times: Map<String, Long>,
) {
    val paths: List<String> get() = stored.keys.toList()

    fun text(path: String): String = data[path]?.toString(Charsets.UTF_8) ?: ""

    fun dataFiles(predicate: (String) -> Boolean) = data.keys.filter { predicate(it.lowercase()) }.sorted()

    /** Resuelve una ruta relativa citada dentro de la exportación ("media/posts/2024/a.jpg") a la ruta guardada. */
    fun resolveMedia(ref: String?): String? {
        if (ref.isNullOrBlank() || ref.startsWith("http")) return null
        val r = ref.replace('\\', '/').trimStart('/')
        stored[r]?.let { return it }
        return stored.entries.firstOrNull { it.key.endsWith("/$r") }?.value
    }
}

/**
 * Importador de un formato de exportación. El sistema es modular: si una red cambia su formato,
 * se añade un importador nuevo (o una versión nueva) a [Importers.all] sin tocar el resto.
 */
interface SocialImporter {
    val network: SocialNetwork
    /** Identificador del formato que entiende, para trazabilidad ("meta-json-2024"). */
    val formatId: String
    /** Puntuación de confianza (0 = no es de esta red) a partir de las rutas del ZIP. */
    fun score(paths: List<String>): Int
    fun parse(files: ExportFiles): List<SocialItem>
}

object Importers {
    val all: MutableList<SocialImporter> = mutableListOf(
        MetaImporter(SocialNetwork.INSTAGRAM), MetaImporter(SocialNetwork.FACEBOOK),
        TikTokImporter, YouTubeImporter, XImporter, RedditImporter, SnapchatImporter, PinterestImporter,
    )

    fun detect(paths: List<String>): SocialImporter? {
        val lower = paths.map { it.lowercase() }
        return all.map { it to it.score(lower) }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }

    fun forNetwork(n: SocialNetwork): SocialImporter? = all.firstOrNull { it.network == n }
}

// ----------------------------------------------------------------------------- utilidades

internal object Dates {
    private val formats = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
    )
    private val twitter = DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH)

    /** Admite epoch en segundos o milisegundos, ISO-8601, el formato de X y "aaaa-mm-dd hh:mm:ss [UTC]". */
    fun parse(v: Any?): Long {
        when (v) {
            null -> return 0
            is Number -> { val n = v.toLong(); return if (n in 1..99_999_999_999L) n * 1000 else n }
        }
        val s = v.toString().trim()
        if (s.isEmpty()) return 0
        s.toLongOrNull()?.let { return parse(it) }
        runCatching { return Instant.parse(s).toEpochMilli() }
        runCatching { return OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        runCatching { return ZonedDateTime.parse(s, twitter).toInstant().toEpochMilli() }
        for (f in formats) runCatching { return LocalDateTime.parse(s, f).toInstant(ZoneOffset.UTC).toEpochMilli() }
        runCatching { return java.time.LocalDate.parse(s.take(10)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }
        return 0
    }
}

/** Meta guarda el texto UTF-8 como si fuera Latin-1 ("Ã¡" en lugar de "á"). Se corrige si procede. */
internal fun fixMojibake(s: String): String {
    if (s.none { it.code in 0x80..0xFF } || s.any { it.code > 0xFF }) return s
    val bytes = s.toByteArray(Charsets.ISO_8859_1)
    val dec = Charsets.UTF_8.newDecoder()
    return try { dec.decode(java.nio.ByteBuffer.wrap(bytes)).toString() } catch (e: Exception) { s }
}

internal fun mediaType(path: String): SocialItemType? = when (path.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg", "png", "webp", "heic", "gif" -> SocialItemType.PHOTO
    "mp4", "mov", "webm", "m4v", "3gp" -> SocialItemType.VIDEO
    else -> null
}

/** CSV con comillas (RFC 4180). */
internal object Csv {
    fun parse(text: String): List<Map<String, String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>(); val cell = StringBuilder(); var q = false; var i = 0
        val t = text.removePrefix("﻿")
        while (i < t.length) {
            val c = t[i]
            if (q) {
                if (c == '"') { if (t.getOrNull(i + 1) == '"') { cell.append('"'); i++ } else q = false } else cell.append(c)
            } else when (c) {
                '"' -> q = true
                ',' -> { row += cell.toString(); cell.clear() }
                '\n' -> { row += cell.toString(); cell.clear(); rows += row; row = ArrayList() }
                '\r' -> {}
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row }
        if (rows.isEmpty()) return emptyList()
        val header = rows[0].map { it.trim().lowercase() }
        return rows.drop(1).filter { r -> r.any { it.isNotBlank() } }.map { r -> header.indices.associate { header[it] to (r.getOrNull(it) ?: "") } }
    }
}

private fun Any?.asMap(): Map<String, Any?>? = this as? Map<String, Any?>
private fun Any?.asList(): List<Any?>? = this as? List<Any?>
private fun Any?.str(): String? = (this as? String)?.let(::fixMojibake)

private fun parseJsonSafe(text: String): Any? = try { Json.parse(text) } catch (e: Exception) { runCatching { Json.parseJsAssignment(text) }.getOrNull() }

// ----------------------------------------------------------------------------- Limpieza del índice

/**
 * Las exportaciones de Meta mezclan TUS recuerdos con datos que no lo son: publicaciones de otras cuentas que solo
 * has visto, anuncios, temas sugeridos, inicios de sesión, dispositivos… Esos archivos se guardan igualmente en la
 * copia, pero no se muestran como recuerdos. Se aplica también a importaciones antiguas (por el archivo de origen).
 */
object SocialCleanup {
    private val hidden = listOf(
        "security_and_login", "login_activity", "logout_activity", "logged_information", "password_change", "account_activity",
        "ads_information", "ads_and_topics", "your_topics", "ads_interests", "advertisers", "information_about_you",
        "device_information", "devices", "apps_and_websites", "preferences/", "your_preferences", "followers_and_following",
        "connections/", "personal_information", "contacts", "profile_information", "logged", "cookies", "ip_address",
    )
    private val viewed = listOf("viewed", "_seen", "seen_", "impressions", "recently_", "recent_search", "search", "suggested", "recommended")
    private val meta = setOf(SocialNetwork.INSTAGRAM, SocialNetwork.FACEBOOK, SocialNetwork.THREADS)

    /** null = no es un recuerdo del usuario (se oculta del buscador). */
    fun normalize(i: SocialItem): SocialItem? {
        if (i.network !in meta || i.mediaPath != null && !i.sourceFile.lowercase().let { p -> viewed.any { p.contains(it) } }) return i
        val p = i.sourceFile.lowercase()
        if (hidden.any { p.contains(it) }) return null
        if (viewed.any { p.substringAfterLast('/').contains(it) }) {
            return if (i.mediaPath != null) null else i.copy(type = SocialItemType.WATCHED)
        }
        return i
    }
}

// ----------------------------------------------------------------------------- Meta: Instagram, Facebook, Threads

/**
 * Exportaciones JSON de Meta ("Descargar tu información"). En lugar de depender de nombres exactos de campos
 * (que Meta cambia a menudo), recorre la estructura buscando patrones estables: objetos con "uri" +
 * "creation_timestamp" (multimedia), "string_map_data" / "string_list_data" (comentarios, me gusta, guardados)
 * y "timestamp" + "data" (publicaciones y comentarios de Facebook). Los mensajes privados se guardan cifrados
 * en la copia pero NO se indexan (contienen texto de otras personas).
 */
class MetaImporter(override val network: SocialNetwork) : SocialImporter {
    override val formatId = "meta-json-v2024"

    override fun score(paths: List<String>): Int = when (network) {
        SocialNetwork.INSTAGRAM -> paths.count { it.contains("your_instagram_activity/") || it.contains("instagram_profile_information") || it.contains("media/posts/") || it.contains("media/reels/") } * 2
        SocialNetwork.FACEBOOK -> paths.count { it.contains("your_facebook_activity/") || it.contains("your_activity_across_facebook/") || it.contains("comments_and_reactions/") || it.contains("facebook_marketplace") } * 2
        else -> 0
    }

    private fun typeFromPath(p: String): SocialItemType = when {
        p.contains("comment") -> SocialItemType.COMMENT
        p.contains("like") || p.contains("reaction") -> SocialItemType.LIKE
        p.contains("saved") -> SocialItemType.SAVED
        p.contains("reel") -> SocialItemType.REEL
        p.contains("stor") -> SocialItemType.STORY
        p.contains("post") || p.contains("thread") -> SocialItemType.POST
        else -> SocialItemType.ACTIVITY
    }

    private fun netFor(p: String) = if (p.contains("threads") || p.contains("text_post_app")) SocialNetwork.THREADS else network

    override fun parse(files: ExportFiles): List<SocialItem> {
        val out = LinkedHashSet<SocialItem>()
        for (path in files.dataFiles { it.endsWith(".json") }) {
            val lp = path.lowercase()
            if (lp.contains("messages/") || lp.contains("/inbox/")) continue
            val root = parseJsonSafe(files.text(path)) ?: continue
            val net = netFor(lp)
            val baseType = typeFromPath(lp.substringAfterLast('/')).takeIf { it != SocialItemType.ACTIVITY }
                ?: typeFromPath(lp.substringAfterLast("activity/"))
            walk(root, null, path, net, baseType, files, out)
        }
        return out.mapNotNull { SocialCleanup.normalize(it) }
    }

    private fun walk(v: Any?, parentTitle: String?, src: String, net: SocialNetwork, baseType: SocialItemType, files: ExportFiles, out: MutableSet<SocialItem>) {
        val m = v.asMap()
        if (m == null) { v.asList()?.forEach { walk(it, parentTitle, src, net, baseType, files, out) }; return }
        val title = m["title"].str()?.takeIf { it.isNotBlank() } ?: parentTitle

        // 1) Multimedia: { uri, creation_timestamp, title }
        val uri = m["uri"].str()
        if (uri != null && (m.containsKey("creation_timestamp") || mediaType(uri) != null)) {
            val mt = mediaType(uri)
            val type = when {
                baseType == SocialItemType.REEL || baseType == SocialItemType.STORY -> baseType
                mt != null -> mt
                else -> baseType
            }
            out += SocialItem(net, type, Dates.parse(m["creation_timestamp"]), title ?: "", null, files.resolveMedia(uri), src)
            return
        }
        // 1b) Publicación de solo texto (p. ej. Threads): { title, creation_timestamp } sin archivo
        if (uri == null && m.containsKey("creation_timestamp") && !title.isNullOrBlank() && m.values.none { it is List<*> || it is Map<*, *> }) {
            out += SocialItem(net, if (baseType == SocialItemType.ACTIVITY) SocialItemType.POST else baseType, Dates.parse(m["creation_timestamp"]), title, null, null, src)
            return
        }
        // 2) string_map_data: comentarios, guardados ({"Comment": {"value"}, "Time": {"timestamp"}, ...})
        m["string_map_data"].asMap()?.let { smd ->
            var text: String? = null; var ts = 0L; var href: String? = null
            for ((k, e) in smd) {
                val em = e.asMap() ?: continue
                em["value"].str()?.takeIf { it.isNotBlank() }?.let { if (text == null || k.contains("omment", true) || k.contains("omentario", true)) text = it }
                if (ts == 0L) ts = Dates.parse(em["timestamp"])
                em["href"].str()?.let { href = it }
            }
            out += SocialItem(net, baseType, ts, listOfNotNull(text, title?.takeIf { it != text }).joinToString(" · "), href, null, src)
            return
        }
        // 3) string_list_data: me gusta, seguidores, enlaces ([{"href", "value", "timestamp"}])
        m["string_list_data"].asList()?.let { sld ->
            sld.mapNotNull { it.asMap() }.forEach { e ->
                out += SocialItem(net, baseType, Dates.parse(e["timestamp"]), e["value"].str() ?: title ?: "", e["href"].str(), null, src)
            }
            return
        }
        // 4) Facebook: { "timestamp", "data": [ {"post": "..."} | {"comment": {"comment": "..."}} ], "title", "attachments" }
        val data = m["data"].asList()
        if (m.containsKey("timestamp") && data != null) {
            val ts = Dates.parse(m["timestamp"])
            var emitted = false
            for (d in data.mapNotNull { it.asMap() }) {
                d["post"].str()?.let { out += SocialItem(net, SocialItemType.POST, ts, it, null, null, src); emitted = true }
                d["comment"].asMap()?.let { c ->
                    c["comment"].str()?.let { out += SocialItem(net, SocialItemType.COMMENT, ts, listOfNotNull(it, title).joinToString(" · "), null, null, src); emitted = true }
                }
                d["reaction"].asMap()?.let { out += SocialItem(net, SocialItemType.LIKE, ts, title ?: (it["reaction"].str() ?: ""), null, null, src); emitted = true }
            }
            if (!emitted && title != null && baseType in setOf(SocialItemType.LIKE, SocialItemType.SAVED, SocialItemType.COMMENT)) {
                out += SocialItem(net, baseType, ts, title, null, null, src)
            }
            m["attachments"]?.let { walk(it, title ?: data.firstNotNullOfOrNull { d -> d.asMap()?.get("post").str() }, src, net, SocialItemType.POST, files, out) }
            return
        }
        for ((k, child) in m) if (k != "title") walk(child, title, src, net, baseType, files, out)
    }
}

// ----------------------------------------------------------------------------- TikTok

object TikTokImporter : SocialImporter {
    override val network = SocialNetwork.TIKTOK
    override val formatId = "tiktok-json-v2023"

    override fun score(paths: List<String>) = paths.count {
        it.endsWith("user_data_tiktok.json") || it.endsWith("user_data.json") || it.contains("tiktok")
    } * 3

    override fun parse(files: ExportFiles): List<SocialItem> {
        val out = LinkedHashSet<SocialItem>()
        for (p in files.dataFiles { it.endsWith(".json") }) {
            val root = parseJsonSafe(files.text(p)) ?: continue
            walk(root, emptyList(), p, out)
        }
        return out.toList()
    }

    private fun walk(v: Any?, keys: List<String>, src: String, out: MutableSet<SocialItem>) {
        v.asList()?.let { l -> l.forEach { walk(it, keys, src, out) }; return }
        val m = v.asMap() ?: return
        val date = m["Date"] ?: m["date"] ?: m["CreateTime"]
        if (date != null && m.values.none { it is Map<*, *> || it is List<*> }) {
            val ctx = keys.joinToString("/").lowercase()
            val link = (m["Link"] ?: m["link"] ?: m["VideoLink"] ?: m["Url"]).str()
            val comment = (m["comment"] ?: m["Comment"] ?: m["CommentText"]).str()
            val type = when {
                comment != null || ctx.contains("comment") -> SocialItemType.COMMENT
                ctx.contains("like list") || ctx.contains("itemfavoritelist") || ctx.contains("liked") -> SocialItemType.LIKE
                ctx.contains("favorite") -> SocialItemType.SAVED
                ctx.contains("browsing") || ctx.contains("watch") -> SocialItemType.WATCHED
                ctx.contains("share") -> SocialItemType.LINK
                ctx.contains("video") -> SocialItemType.VIDEO
                else -> SocialItemType.ACTIVITY
            }
            val text = comment ?: (m["Title"] ?: m["Description"] ?: m["Sound"]).str() ?: ""
            out += SocialItem(network, type, Dates.parse(date), text, link, null, src)
            return
        }
        for ((k, c) in m) walk(c, keys + k, src, out)
    }
}

// ----------------------------------------------------------------------------- YouTube (Google Takeout)

object YouTubeImporter : SocialImporter {
    override val network = SocialNetwork.YOUTUBE
    override val formatId = "takeout-youtube-v2024"

    override fun score(paths: List<String>) = paths.count { it.contains("youtube") && (it.contains("takeout") || it.contains("history/") || it.contains("comments/")) } * 3

    override fun parse(files: ExportFiles): List<SocialItem> {
        val out = ArrayList<SocialItem>()
        for (p in files.dataFiles { it.endsWith(".json") && it.contains("history") }) {
            val type = if (p.lowercase().contains("search")) SocialItemType.ACTIVITY else SocialItemType.WATCHED
            parseJsonSafe(files.text(p)).asList()?.mapNotNull { it.asMap() }?.forEach { e ->
                out += SocialItem(network, type, Dates.parse(e["time"]), e["title"].str() ?: "", e["titleUrl"].str(), null, p)
            }
        }
        for (p in files.dataFiles { it.endsWith(".csv") && (it.contains("comment")) }) {
            Csv.parse(files.text(p)).forEach { r ->
                val raw = r.entries.firstOrNull { it.key.contains("text") }?.value ?: return@forEach
                val text = runCatching { (Json.parse(raw).asMap()?.get("text") as? String) }.getOrNull()
                    ?: runCatching { Json.parse(raw).asMap()?.get("segments").asList()?.joinToString("") { s -> s.asMap()?.get("text") as? String ?: "" } }.getOrNull()
                    ?: raw
                val ts = Dates.parse(r.entries.firstOrNull { it.key.contains("timestamp") || it.key.contains("time") }?.value)
                val vid = r.entries.firstOrNull { it.key.contains("video id") }?.value?.takeIf { it.isNotBlank() }
                out += SocialItem(network, SocialItemType.COMMENT, ts, text, vid?.let { "https://www.youtube.com/watch?v=$it" }, null, p)
            }
        }
        for (p in files.dataFiles { it.endsWith(".csv") && it.contains("playlists/") && (it.contains("liked") || it.contains("me gusta")) }) {
            Csv.parse(files.text(p)).forEach { r ->
                val vid = r.entries.firstOrNull { it.key.contains("video id") || it.key == "id del vídeo" }?.value ?: return@forEach
                val ts = Dates.parse(r.entries.firstOrNull { it.key.contains("timestamp") || it.key.contains("fecha") }?.value)
                out += SocialItem(network, SocialItemType.LIKE, ts, "Vídeo marcado con Me gusta", "https://www.youtube.com/watch?v=${vid.trim()}", null, p)
            }
        }
        return out
    }
}

// ----------------------------------------------------------------------------- X (Twitter)

object XImporter : SocialImporter {
    override val network = SocialNetwork.X
    override val formatId = "x-archive-js-v2023"

    override fun score(paths: List<String>) = paths.count { it.endsWith("data/tweets.js") || it.endsWith("data/tweet.js") || it.endsWith("data/like.js") || it.contains("data/tweets_media/") } * 4

    override fun parse(files: ExportFiles): List<SocialItem> {
        val out = ArrayList<SocialItem>()
        val media = files.paths.filter { it.lowercase().contains("tweets_media/") }
        for (p in files.dataFiles { it.endsWith("tweets.js") || it.endsWith("tweet.js") }) {
            parseJsonSafe(files.text(p)).asList()?.forEach { w ->
                val t = w.asMap()?.get("tweet").asMap() ?: return@forEach
                val id = t["id_str"].str() ?: t["id"].str() ?: ""
                val ts = Dates.parse(t["created_at"])
                val text = t["full_text"].str() ?: t["text"].str() ?: ""
                val type = if (t["in_reply_to_status_id_str"].str() != null || t["in_reply_to_status_id"] != null) SocialItemType.REPLY else SocialItemType.POST
                out += SocialItem(network, type, ts, text, "https://x.com/i/status/$id", null, p)
                media.filter { it.substringAfterLast('/').startsWith("$id-") }.forEach { mp ->
                    out += SocialItem(network, mediaType(mp) ?: SocialItemType.PHOTO, ts, text, "https://x.com/i/status/$id", files.stored[mp], p)
                }
            }
        }
        for (p in files.dataFiles { it.endsWith("like.js") }) {
            parseJsonSafe(files.text(p)).asList()?.forEach { w ->
                val l = w.asMap()?.get("like").asMap() ?: return@forEach
                out += SocialItem(network, SocialItemType.LIKE, 0, l["fullText"].str() ?: "", l["expandedUrl"].str(), null, p)
            }
        }
        return out
    }
}

// ----------------------------------------------------------------------------- Reddit

object RedditImporter : SocialImporter {
    override val network = SocialNetwork.REDDIT
    override val formatId = "reddit-gdpr-csv-v2023"

    override fun score(paths: List<String>): Int {
        val names = paths.map { it.substringAfterLast('/') }
        return if ("comments.csv" in names && ("posts.csv" in names || "post_votes.csv" in names || "saved_posts.csv" in names)) 10 else 0
    }

    override fun parse(files: ExportFiles): List<SocialItem> {
        val out = ArrayList<SocialItem>()
        fun each(name: String, f: (Map<String, String>, String) -> Unit) =
            files.dataFiles { it.substringAfterLast('/') == name }.forEach { p -> Csv.parse(files.text(p)).forEach { f(it, p) } }
        each("comments.csv") { r, p -> out += SocialItem(network, SocialItemType.COMMENT, Dates.parse(r["date"]), r["body"].orEmpty(), r["permalink"], null, p) }
        each("posts.csv") { r, p -> out += SocialItem(network, SocialItemType.POST, Dates.parse(r["date"]), listOf(r["title"].orEmpty(), r["body"].orEmpty()).filter { it.isNotBlank() }.joinToString(" — "), r["permalink"] ?: r["url"], null, p) }
        each("saved_posts.csv") { r, p -> out += SocialItem(network, SocialItemType.SAVED, 0, "Publicación guardada", r["permalink"], null, p) }
        each("saved_comments.csv") { r, p -> out += SocialItem(network, SocialItemType.SAVED, 0, "Comentario guardado", r["permalink"], null, p) }
        each("post_votes.csv") { r, p -> if (r["direction"] == "up") out += SocialItem(network, SocialItemType.LIKE, 0, "Voto positivo", r["permalink"], null, p) }
        return out
    }
}

// ----------------------------------------------------------------------------- Snapchat

/** Snapchat entrega "Memories" como ENLACES de descarga (no el archivo): son solo registros. */
object SnapchatImporter : SocialImporter {
    override val network = SocialNetwork.SNAPCHAT
    override val formatId = "snapchat-json-v2023"

    override fun score(paths: List<String>) = paths.count { it.endsWith("memories_history.json") || it.endsWith("snap_history.json") || it.contains("mydata~") } * 4

    override fun parse(files: ExportFiles): List<SocialItem> {
        val out = ArrayList<SocialItem>()
        for (p in files.dataFiles { it.endsWith("memories_history.json") }) {
            val root = parseJsonSafe(files.text(p)).asMap() ?: continue
            root.values.mapNotNull { it.asList() }.flatten().mapNotNull { it.asMap() }.forEach { e ->
                val type = if (e["Media Type"].str().equals("VIDEO", true)) SocialItemType.VIDEO else SocialItemType.PHOTO
                out += SocialItem(network, type, Dates.parse(e["Date"]), "Recuerdo de Snapchat (solo enlace; puede haber caducado)", e["Download Link"].str(), null, p)
            }
        }
        return out
    }
}

// ----------------------------------------------------------------------------- Pinterest y genérico (HTML)

/** Importación básica: enlaces y textos de los archivos HTML de la exportación. */
object PinterestImporter : SocialImporter {
    override val network = SocialNetwork.PINTEREST
    override val formatId = "html-links-basic"
    private val link = Regex("<a[^>]+href=\"(https?://[^\"]+)\"[^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val tags = Regex("<[^>]+>")

    override fun score(paths: List<String>) = paths.count { it.contains("pinterest") } * 3

    override fun parse(files: ExportFiles): List<SocialItem> = htmlLinks(files, network)

    fun htmlLinks(files: ExportFiles, net: SocialNetwork): List<SocialItem> {
        val out = LinkedHashSet<SocialItem>()
        for (p in files.dataFiles { it.endsWith(".html") || it.endsWith(".htm") }) {
            link.findAll(files.text(p)).forEach { mr ->
                val text = tags.replace(mr.groupValues[2], "").trim().replace("&amp;", "&")
                out += SocialItem(net, SocialItemType.LINK, files.times[p] ?: 0, text, mr.groupValues[1], null, p)
            }
        }
        return out.toList()
    }
}
