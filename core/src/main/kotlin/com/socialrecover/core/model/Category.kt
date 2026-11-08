package com.socialrecover.core.model

/**
 * Categorías de datos. Las cinco primeras vienen activadas por defecto;
 * el resto solo se activan con el consentimiento expreso del usuario.
 */
enum class Category(val id: String, val label: String, val defaultEnabled: Boolean, val sensitive: Boolean = false) {
    PHOTOS("photos", "Fotos", true),
    VIDEOS("videos", "Vídeos", true),
    DOCUMENTS("documents", "Documentos", true),
    DOWNLOADS("downloads", "Descargas", true),
    SOCIAL_EXPORTS("social", "Exportaciones de redes sociales", true),
    MUSIC("music", "Música", false),
    ARCHIVES("archives", "Archivos comprimidos", false),
    PROJECTS("projects", "Proyectos personales", false),
    BOOKMARKS("bookmarks", "Marcadores del navegador", false),
    HISTORY("history", "Historial exportado", false),
    FORUMS("forums", "Foros y comentarios propios", false),
    APP_FILES("appfiles", "Archivos de aplicaciones compatibles", false),
    PASSWORD_EXPORTS("passwords", "Copias exportadas de gestores de contraseñas", false, sensitive = true),
    CUSTOM_FOLDERS("custom", "Carpetas personalizadas", false),
    OTHER("other", "Otros", false);

    companion object {
        fun fromId(id: String): Category = entries.firstOrNull { it.id == id } ?: OTHER

        /** Categoría sugerida a partir del tipo MIME y el nombre. */
        fun guess(mime: String?, name: String): Category {
            val m = mime?.lowercase() ?: ""
            val ext = name.substringAfterLast('.', "").lowercase()
            return when {
                m.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "dng") -> PHOTOS
                m.startsWith("video/") || ext in setOf("mp4", "mov", "mkv", "webm", "3gp", "m4v") -> VIDEOS
                m.startsWith("audio/") || ext in setOf("mp3", "m4a", "flac", "ogg", "wav", "opus") -> MUSIC
                ext in setOf("zip", "rar", "7z", "tar", "gz") || m.contains("zip") -> ARCHIVES
                ext in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "txt", "rtf", "csv", "md") ||
                    m.startsWith("text/") || m.contains("pdf") || m.contains("document") || m.contains("sheet") -> DOCUMENTS
                else -> OTHER
            }
        }
    }
}
