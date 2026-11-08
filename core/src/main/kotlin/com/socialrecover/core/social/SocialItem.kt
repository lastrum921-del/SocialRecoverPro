package com.socialrecover.core.social

enum class SocialItemType(val label: String, val isMedia: Boolean = false) {
    PHOTO("Foto", true),
    VIDEO("Vídeo", true),
    POST("Publicación"),
    REEL("Reel", true),
    STORY("Story", true),
    COMMENT("Comentario"),
    REPLY("Respuesta"),
    LIKE("Me gusta"),
    SAVED("Guardado"),
    LINK("Enlace"),
    MESSAGE("Mensaje"),
    WATCHED("Visualización"),
    ACTIVITY("Actividad"),
}

/**
 * Un elemento encontrado en una exportación oficial: una publicación, un comentario, un «Me gusta»…
 *
 * @param mediaPath ruta del archivo multimedia DENTRO de la copia (si la exportación lo incluía).
 *                  null significa que solo existe el registro (texto, enlace, fecha), no el archivo.
 */
data class SocialItem(
    val network: SocialNetwork,
    val type: SocialItemType,
    /** Epoch en milisegundos; 0 si la exportación no indica fecha. */
    val timestamp: Long,
    val text: String,
    val url: String? = null,
    val mediaPath: String? = null,
    /** Archivo de la exportación del que se extrajo (para trazabilidad). */
    val sourceFile: String = "",
)
