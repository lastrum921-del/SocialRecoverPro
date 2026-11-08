package com.socialrecover.core

import com.socialrecover.core.crypto.CryptoParams
import com.socialrecover.core.social.SocialExport
import com.socialrecover.core.social.SocialImport
import com.socialrecover.core.social.SocialItemType
import com.socialrecover.core.social.SocialNetwork
import com.socialrecover.core.social.SocialQuery
import com.socialrecover.core.social.SocialSearch
import com.socialrecover.core.store.FileRestoreTarget
import com.socialrecover.core.vault.DeviceKind
import com.socialrecover.core.vault.Library
import com.socialrecover.core.vault.LocalRepoBackend
import com.socialrecover.core.vault.Vault
import com.socialrecover.core.vault.VaultRestore
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exportaciones SINTÉTICAS que reproducen la estructura de las exportaciones oficiales
 * (nombres de carpetas y campos), con datos inventados.
 */
class SocialImportTest {
    private val pw = "clave de prueba".toCharArray()
    private fun vault() = Vault.create(LocalRepoBackend(Files.createTempDirectory("srp-soc").toFile()), pw, CryptoParams(2_000))
    private fun ts(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atTime(10, 0).toEpochSecond(ZoneOffset.UTC)
    private val jpg = Random(1).nextBytes(20_000)
    private val mp4 = Random(2).nextBytes(60_000)

    private fun zip(vararg entries: Pair<String, Any>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            entries.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(if (c is ByteArray) c else c.toString().toByteArray()); z.closeEntry() }
        }
        return bos.toByteArray()
    }

    private fun import(v: Vault, bytes: ByteArray, name: String, expected: SocialNetwork? = null) =
        SocialImport.import(listOf(SocialImport.Part(name) { bytes.inputStream() }), v, "Pixel", DeviceKind.PHONE, expected)

    // Meta escribe "Qué bonito día en la playa" codificado como Latin-1 (mojibake).
    private val mojibake = String("Qué bonito día en la playa".toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
        .map { if (it.code > 127) "\\u%04x".format(it.code) else it.toString() }.joinToString("")

    private fun instagram() = zip(
        "instagram-ana/your_instagram_activity/content/posts_1.json" to """[{"media":[{"uri":"media/posts/202309/playa.jpg","creation_timestamp":${ts(2023, 9, 14)},"title":"$mojibake"}]}]""",
        "instagram-ana/media/posts/202309/playa.jpg" to jpg,
        "instagram-ana/your_instagram_activity/content/reels.json" to """{"ig_reels_media":[{"media":[{"uri":"media/reels/202310/baile.mp4","creation_timestamp":${ts(2023, 10, 2)},"title":"Baile en la boda"}]}]}""",
        "instagram-ana/media/reels/202310/baile.mp4" to mp4,
        "instagram-ana/your_instagram_activity/comments/post_comments_1.json" to """[{"string_map_data":{"Comment":{"value":"¡Enhorabuena por el premio!"},"Media Owner":{"value":"amiga_1"},"Time":{"timestamp":${ts(2022, 5, 1)}}}}]""",
        "instagram-ana/your_instagram_activity/likes/liked_posts.json" to """{"likes_media_likes":[{"title":"cuenta_cocina","string_list_data":[{"href":"https://www.instagram.com/p/ABC/","value":"👍","timestamp":${ts(2023, 1, 5)}}]}]}""",
        "instagram-ana/your_instagram_activity/saved/saved_posts.json" to """{"saved_saved_media":[{"title":"receta_paella","string_map_data":{"Saved on":{"href":"https://www.instagram.com/p/XYZ/","timestamp":${ts(2023, 2, 6)}}}}]}""",
        "instagram-ana/your_instagram_activity/threads/threads_and_replies.json" to """{"text_post_app_text_posts":[{"media":[{"title":"Mi primer hilo","creation_timestamp":${ts(2023, 7, 6)}}]}]}""",
        "instagram-ana/your_instagram_activity/messages/inbox/amiga_1/message_1.json" to """{"messages":[{"sender_name":"amiga_1","content":"texto privado de otra persona","timestamp_ms":1}]}""",
    )

    @Test
    fun instagramExportIsIndexedAndMediaIsRecoverable() {
        val v = vault()
        val r = import(v, instagram(), "instagram-ana-2026.zip", SocialNetwork.INSTAGRAM)
        assertEquals(SocialNetwork.INSTAGRAM, r.network)
        assertTrue(r.detected)
        val byType = r.items.groupBy { it.type }
        val photo = byType[SocialItemType.PHOTO]!!.single()
        assertEquals("Qué bonito día en la playa", photo.text, "Corrección del texto mal codificado de Meta")
        assertNotNull(photo.mediaPath)
        assertEquals("Baile en la boda", byType[SocialItemType.REEL]!!.single().text)
        assertTrue(byType[SocialItemType.COMMENT]!!.single().text.startsWith("¡Enhorabuena"))
        assertEquals("https://www.instagram.com/p/ABC/", byType[SocialItemType.LIKE]!!.single().url)
        assertEquals("https://www.instagram.com/p/XYZ/", byType[SocialItemType.SAVED]!!.single().url)
        assertEquals(SocialNetwork.THREADS, r.items.single { it.text == "Mi primer hilo" }.network)
        assertTrue(r.items.none { it.text.contains("texto privado") }, "Los mensajes de otras personas no se indexan")

        // La foto se recupera desde la copia y es idéntica.
        val lib = Library(listOf(v))
        val (ls, item) = lib.allSocial().first { it.second.type == SocialItemType.PHOTO }
        val file = lib.mediaFor(ls, item)!!
        val dest = Files.createTempDirectory("srp-out").toFile()
        val rep = VaultRestore.restore(v, ls.snapshot, listOf(file), FileRestoreTarget(dest))
        assertTrue(rep.allOk)
        assertTrue(File(dest, rep.results[0].path!!).readBytes().contentEquals(jpg))
        assertEquals(SocialNetwork.INSTAGRAM, file.platform)
    }

    @Test
    fun facebookTikTokYoutubeXRedditSnapchat() {
        val v = vault()
        val fb = import(v, zip(
            "facebook-ana/your_facebook_activity/posts/your_posts__check_ins__photos_and_videos_1.json" to
                """[{"timestamp":${ts(2021, 8, 9)},"data":[{"post":"Vacaciones en Asturias"}],"attachments":[{"data":[{"media":{"uri":"your_facebook_activity/posts/media/Vacaciones/foto1.jpg","creation_timestamp":${ts(2021, 8, 9)}}}]}]}]""",
            "facebook-ana/your_facebook_activity/posts/media/Vacaciones/foto1.jpg" to jpg,
            "facebook-ana/your_facebook_activity/comments_and_reactions/comments.json" to
                """{"comments_v2":[{"timestamp":${ts(2020, 3, 3)},"data":[{"comment":{"comment":"Qué recuerdos","author":"Ana"}}],"title":"Ana ha comentado la publicación de Luis."}]}""",
            "facebook-ana/your_facebook_activity/comments_and_reactions/likes_and_reactions_1.json" to
                """[{"timestamp":${ts(2020, 4, 4)},"data":[{"reaction":{"reaction":"LIKE","actor":"Ana"}}],"title":"A Ana le gusta la publicación de Marta."}]""",
        ), "facebook-ana.zip")
        assertEquals(SocialNetwork.FACEBOOK, fb.network)
        assertEquals("Vacaciones en Asturias", fb.items.single { it.type == SocialItemType.POST }.text)
        assertNotNull(fb.items.single { it.type == SocialItemType.PHOTO }.mediaPath)
        assertTrue(fb.items.single { it.type == SocialItemType.COMMENT }.text.startsWith("Qué recuerdos"))
        assertEquals(1, fb.items.count { it.type == SocialItemType.LIKE })

        val tt = import(v, zip("user_data_tiktok.json" to """{"Video":{"Videos":{"VideoList":[{"Date":"2023-09-14 10:22:01","Link":"https://www.tiktokv.com/share/video/1/","Likes":"12"}]}},
            "Comment":{"Comments":{"CommentsList":[{"date":"2023-09-20 08:00:00","comment":"jajaja me encanta"}]}},
            "Activity":{"Like List":{"ItemFavoriteList":[{"date":"2023-09-21 09:00:00","link":"https://www.tiktokv.com/share/video/2/"}]},
            "Favorite Videos":{"FavoriteVideoList":[{"Date":"2023-09-22 09:00:00","Link":"https://www.tiktokv.com/share/video/3/"}]}}}"""), "TikTok_Data.zip")
        assertEquals(SocialNetwork.TIKTOK, tt.network)
        val tv = tt.items.single { it.type == SocialItemType.VIDEO }
        assertNull(tv.mediaPath, "TikTok solo da el enlace del vídeo: es un registro, no el archivo")
        assertEquals(LocalDate.of(2023, 9, 14), java.time.Instant.ofEpochMilli(tv.timestamp).atZone(ZoneOffset.UTC).toLocalDate())
        assertEquals(setOf(SocialItemType.VIDEO, SocialItemType.COMMENT, SocialItemType.LIKE, SocialItemType.SAVED), tt.items.map { it.type }.toSet())

        val yt = import(v, zip(
            "Takeout/YouTube y YouTube Music/history/watch-history.json" to """[{"header":"YouTube","title":"Has visto Receta de tortilla","titleUrl":"https://www.youtube.com/watch?v=abc","time":"2024-02-01T20:00:00.000Z"}]""",
            "Takeout/YouTube y YouTube Music/comments/comments.csv" to "Comment ID,Channel ID,Comment Create Timestamp,Price,Parent Comment ID,Post ID,Video ID,Comment Text\nc1,ch,2024-02-02T10:00:00+00:00,,,,abc,\"{\"\"text\"\":\"\"Gracias por la receta\"\"}\"\n",
            "Takeout/YouTube y YouTube Music/videos/mi_video.mp4" to mp4,
        ), "takeout-youtube.zip")
        assertEquals(SocialNetwork.YOUTUBE, yt.network)
        assertEquals("Gracias por la receta", yt.items.single { it.type == SocialItemType.COMMENT }.text)
        assertEquals(1, yt.items.count { it.type == SocialItemType.WATCHED })
        assertNotNull(yt.items.single { it.type == SocialItemType.VIDEO }.mediaPath)

        val x = import(v, zip(
            "data/tweets.js" to """window.YTD.tweets.part0 = [{"tweet":{"id_str":"111","full_text":"Primer tuit con foto","created_at":"Wed Sep 13 10:00:00 +0000 2023"}},{"tweet":{"id_str":"112","full_text":"@luis de acuerdo","in_reply_to_status_id_str":"99","created_at":"Thu Sep 14 10:00:00 +0000 2023"}}]""",
            "data/like.js" to """window.YTD.like.part0 = [{"like":{"tweetId":"5","fullText":"Un tuit ajeno","expandedUrl":"https://twitter.com/i/web/status/5"}}]""",
            "data/tweets_media/111-AbCd.jpg" to jpg,
        ), "twitter-archive.zip")
        assertEquals(SocialNetwork.X, x.network)
        assertEquals(1, x.items.count { it.type == SocialItemType.REPLY })
        assertNotNull(x.items.single { it.type == SocialItemType.PHOTO }.mediaPath)
        assertEquals(0L, x.items.single { it.type == SocialItemType.LIKE }.timestamp, "X no da fecha de los Me gusta")

        val rd = import(v, zip(
            "comments.csv" to "id,permalink,date,ip,subreddit,gildings,link,parent,body,media\nk1,https://reddit.com/r/es/k1,2022-06-01 12:00:00 UTC,,es,0,,,\"Buen consejo, gracias\",\n",
            "posts.csv" to "id,permalink,date,ip,subreddit,gildings,title,url,body\np1,https://reddit.com/r/es/p1,2022-06-02 12:00:00 UTC,,es,0,Mi pregunta,,Texto del post\n",
            "post_votes.csv" to "id,permalink,direction\nz,https://reddit.com/r/es/z,up\n",
        ), "export_usuario.zip")
        assertEquals(SocialNetwork.REDDIT, rd.network)
        assertEquals(3, rd.items.size)

        val sc = import(v, zip("json/memories_history.json" to """{"Saved Media":[{"Date":"2023-09-14 10:22:01 UTC","Media Type":"Image","Download Link":"https://app.snapchat.com/dmd/memories?x"}]}""",
            "mydata~1.html" to "<html></html>"), "mydata.zip")
        assertEquals(SocialNetwork.SNAPCHAT, sc.network)
        assertNull(sc.items.single().mediaPath)
    }

    @Test
    fun searchByWordDateNetworkAndTypeAndExport() {
        val v = vault()
        import(v, instagram(), "ig.zip")
        val lib = Library(listOf(v))
        val all = lib.allSocial().map { it.second }
        assertEquals(1, SocialSearch.search(all, SocialQuery(text = "enhorabuena premio")).size)
        assertEquals(1, SocialSearch.search(all, SocialQuery(text = "PLAYA dia")).size, "Sin acentos ni mayúsculas")
        assertEquals(setOf(SocialItemType.COMMENT), SocialSearch.search(all, SocialQuery(types = setOf(SocialItemType.COMMENT))).map { it.item.type }.toSet())
        val sept = SocialSearch.search(all, SocialQuery(from = ts(2023, 9, 1) * 1000, to = ts(2023, 9, 30) * 1000))
        assertEquals(listOf("Qué bonito día en la playa"), sept.map { it.item.text })
        assertEquals(1, SocialSearch.search(all, SocialQuery(networks = setOf(SocialNetwork.THREADS))).size)
        val csv = SocialExport.toCsv(all)
        assertTrue(csv.contains("Enhorabuena") && csv.lines().size >= all.size + 1)
        val html = SocialExport.toHtml(SocialSearch.search(all, SocialQuery(text = "boda")).map { it.item }, "Resultados <boda>")
        assertTrue(html.contains("Baile en la boda") && html.contains("&lt;boda&gt;"))
    }

    @Test
    fun brokenOrUnknownFormatsStillSaveEverything() {
        val v = vault()
        val r = import(v, zip(
            "your_instagram_activity/content/posts_1.json" to "{ esto no es json válido",
            "media/posts/1/foto.jpg" to jpg,
        ), "ig-roto.zip", SocialNetwork.INSTAGRAM)
        assertEquals(2, r.snapshot.files.size, "Todos los archivos se guardan aunque no se puedan analizar")
        assertEquals(1, r.items.count { it.type == SocialItemType.PHOTO && it.mediaPath != null })

        val u = import(v, zip("carpeta/viaje.mp4" to mp4), "otro.zip")
        assertEquals(SocialNetwork.OTHER, u.network)
        assertTrue(u.warnings.any { it.contains("No se ha reconocido") })
        assertEquals(1, u.items.size)

        assertFailsWith<IllegalArgumentException> { import(v, "no es un zip".toByteArray(), "x.zip") }
    }

    @Test
    fun multiPartExportLinksMediaAcrossParts() {
        val v = vault()
        val p1 = zip("your_instagram_activity/content/posts_1.json" to """[{"media":[{"uri":"media/posts/2/b.jpg","creation_timestamp":${ts(2024, 1, 1)},"title":"Parte dos"}]}]""")
        val p2 = zip("media/posts/2/b.jpg" to jpg)
        val r = SocialImport.import(listOf(SocialImport.Part("ig-part1.zip") { p1.inputStream() }, SocialImport.Part("ig-part2.zip") { p2.inputStream() }), v, "Pixel", DeviceKind.PHONE)
        assertNotNull(r.items.single { it.text == "Parte dos" }.mediaPath)
    }
}
