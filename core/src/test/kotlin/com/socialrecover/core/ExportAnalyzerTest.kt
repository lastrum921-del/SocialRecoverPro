package com.socialrecover.core

import com.socialrecover.core.social.ExportAnalyzer
import com.socialrecover.core.social.SocialNetwork
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Exportaciones SINTÉTICAS que imitan la estructura de carpetas de cada red. */
class ExportAnalyzerTest {
    private fun zip(vararg names: String): ByteArrayInputStream {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            names.forEach { n -> z.putNextEntry(ZipEntry(n)); z.write("x".toByteArray()); z.closeEntry() }
        }
        return ByteArrayInputStream(bos.toByteArray())
    }

    @Test
    fun detectsInstagram() {
        val a = ExportAnalyzer.analyze(zip(
            "your_instagram_activity/content/posts_1.json",
            "media/posts/202401/foto1.jpg",
            "media/reels/202402/reel1.mp4",
            "your_instagram_activity/messages/inbox/amigo_123/message_1.json",
            "personal_information/personal_information/instagram_profile_information.json",
        ))
        assertEquals(SocialNetwork.INSTAGRAM, a.detected)
        assertEquals(1, a.photos); assertEquals(1, a.videos); assertEquals(1, a.messageFiles)
        assertEquals("JSON", a.format)
    }

    @Test
    fun detectsFacebook() {
        val a = ExportAnalyzer.analyze(zip(
            "your_facebook_activity/posts/your_posts__check_ins__photos_and_videos_1.html",
            "your_facebook_activity/posts/media/album/foto.jpg",
            "your_facebook_activity/messages/inbox/amiga_1/message_1.html",
        ))
        assertEquals(SocialNetwork.FACEBOOK, a.detected)
        assertEquals("HTML", a.format)
    }

    @Test
    fun detectsTikTok() {
        assertEquals(SocialNetwork.TIKTOK, ExportAnalyzer.analyze(zip("user_data_tiktok.json")).detected)
        assertEquals(SocialNetwork.TIKTOK, ExportAnalyzer.analyze(zip("TikTok_Data/Video/Videos.txt", "TikTok_Data/Profile/Profile Info.txt")).detected)
    }

    @Test
    fun unknownZipAndInvalidFile() {
        assertNull(ExportAnalyzer.analyze(zip("vacaciones/foto.jpg")).detected)
        assertFailsWith<IllegalArgumentException> { ExportAnalyzer.analyze(ByteArrayInputStream("no es un zip".toByteArray())) }
    }
}
