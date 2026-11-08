package com.socialrecover.core.social

import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

enum class SocialNetwork(val label: String, val wizardAvailable: Boolean) {
    INSTAGRAM("Instagram", true),
    TIKTOK("TikTok", true),
    FACEBOOK("Facebook", true),
    YOUTUBE("YouTube", true),
    X("X (Twitter)", true),
    THREADS("Threads", true),
    REDDIT("Reddit", true),
    PINTEREST("Pinterest", true),
    SNAPCHAT("Snapchat", true),
    OTHER("Otra red social", true),
}

data class ExportAnalysis(
    /** Red detectada, o null si el archivo no parece una exportación conocida. */
    val detected: SocialNetwork?,
    val totalEntries: Int,
    val photos: Int,
    val videos: Int,
    val dataFiles: Int,
    val messageFiles: Int,
    /** Tamaño descomprimido conocido (algunas entradas ZIP no lo indican hasta leerlas). */
    val knownUncompressedBytes: Long,
    val format: String,
)

/**
 * Analiza un archivo ZIP descargado con las herramientas OFICIALES de exportación de cada red
 * («Descargar tu información» en Instagram/Facebook, «Descargar tus datos» en TikTok).
 *
 * No accede a ninguna cuenta ni a internet: solo lee la lista de entradas del ZIP para mostrar
 * al usuario un resumen antes de guardarlo cifrado. Las estructuras de carpetas cambian con el
 * tiempo, por eso se usan varias pistas y, si ninguna encaja, el archivo se guarda igualmente
 * como «exportación no reconocida».
 */
object ExportAnalyzer {
    private val photoExt = setOf("jpg", "jpeg", "png", "webp", "heic", "gif")
    private val videoExt = setOf("mp4", "mov", "webm", "m4v", "3gp")
    private val dataExt = setOf("json", "html", "htm", "txt", "csv")

    private val markers: Map<SocialNetwork, List<String>> = mapOf(
        SocialNetwork.INSTAGRAM to listOf(
            "your_instagram_activity/", "instagram_profile_information", "media/posts/",
            "media/stories/", "media/reels/", "connections/followers_and_following/", "ads_information/instagram_ads",
        ),
        SocialNetwork.FACEBOOK to listOf(
            "your_facebook_activity/", "your_activity_across_facebook/", "facebook_marketplace",
            "posts/your_posts", "profile_information/profile_information", "facebook_gaming",
        ),
        SocialNetwork.TIKTOK to listOf(
            "user_data_tiktok.json", "user_data.json", "tiktok", "activity/video browsing history",
            "video/videos.txt", "profile/profile info.txt",
        ),
    )

    fun analyze(input: InputStream): ExportAnalysis {
        val scores = HashMap<SocialNetwork, Int>()
        var total = 0; var photos = 0; var videos = 0; var data = 0; var messages = 0
        var bytes = 0L
        var json = 0; var html = 0
        try {
            ZipInputStream(input).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    if (!e.isDirectory) {
                        total++
                        val name = e.name.replace('\\', '/').lowercase()
                        val ext = name.substringAfterLast('.', "")
                        when (ext) {
                            in photoExt -> photos++
                            in videoExt -> videos++
                            in dataExt -> data++
                        }
                        if (ext == "json") json++
                        if (ext == "html" || ext == "htm") html++
                        if (name.contains("messages/inbox/") || name.contains("/inbox/") || name.contains("direct messages")) messages++
                        if (e.size > 0) bytes += e.size
                        for ((net, list) in markers) {
                            if (list.any { name.contains(it) }) scores[net] = (scores[net] ?: 0) + 1
                        }
                    }
                    e = zip.nextEntry
                }
            }
        } catch (ex: ZipException) {
            throw IllegalArgumentException("El archivo no es un ZIP válido o está dañado.", ex)
        }
        if (total == 0) throw IllegalArgumentException("El archivo ZIP está vacío o no es un ZIP válido.")
        val best = scores.maxByOrNull { it.value }?.key
        val format = when {
            json > html -> "JSON"
            html > 0 -> "HTML"
            else -> "Otro"
        }
        return ExportAnalysis(best, total, photos, videos, data, messages, bytes, format)
    }
}
