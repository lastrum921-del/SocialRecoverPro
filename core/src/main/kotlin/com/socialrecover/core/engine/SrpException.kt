package com.socialrecover.core.engine

import java.io.IOException

/**
 * Errores del motor con mensajes comprensibles en español para el usuario final.
 * `userMessage` es lo que debe mostrarse en pantalla; `message` puede incluir detalles técnicos.
 */
sealed class SrpException(val userMessage: String, detail: String? = null, cause: Throwable? = null) :
    Exception(detail ?: userMessage, cause) {

    class WrongPassword : SrpException(
        "La contraseña no es correcta. Revisa mayúsculas, minúsculas y espacios e inténtalo de nuevo."
    )

    open class CorruptedBackup(detail: String) : SrpException(
        "La copia está dañada o ha sido modificada. Los archivos afectados no se pueden recuperar de forma segura.",
        detail,
    )

    class IncompleteBackup : SrpException(
        "La copia está incompleta (probablemente se interrumpió mientras se creaba)."
    )

    class NotABackup : SrpException("Este archivo no es una copia de SocialRecover Pro.")

    class UnsupportedVersion(v: Int) : SrpException(
        "Esta copia se creó con una versión más reciente de SocialRecover Pro. Actualiza la aplicación.",
        "Versión de formato $v",
    )

    class NoSpace(cause: Throwable? = null) : SrpException(
        "No queda espacio suficiente en la ubicación elegida. Libera espacio o elige otra ubicación.",
        cause = cause,
    )

    class PermissionDenied(what: String, cause: Throwable? = null) : SrpException(
        "SocialRecover Pro ya no tiene permiso para acceder a $what. Vuelve a seleccionarlo.",
        cause = cause,
    )

    class SourceUnreadable(name: String, cause: Throwable? = null) : SrpException(
        "No se ha podido leer «$name». Es posible que se haya movido o eliminado.",
        cause = cause,
    )

    class Cancelled : SrpException("Operación cancelada.")

    class Offline(cause: Throwable? = null) : SrpException(
        "No hay conexión con la nube. La copia se reanudará donde se quedó cuando vuelva la conexión.",
        cause?.message,
        cause,
    )

    class AuthRequired : SrpException("Tu sesión ha caducado. Vuelve a iniciar sesión en tu cuenta de SocialRecover Pro.")

    class Storage(cause: Throwable?) : SrpException(
        "No se ha podido escribir en la ubicación de las copias. Comprueba que el almacenamiento está conectado.",
        cause?.message,
        cause,
    )

    companion object {
        /** Convierte errores de E/S genéricos en errores del dominio. */
        fun fromIo(e: Throwable, what: String = "la ubicación elegida"): SrpException = when {
            e is SrpException -> e
            e is SecurityException -> PermissionDenied(what, e)
            isNoSpace(e) -> NoSpace(e)
            else -> Storage(e)
        }

        fun isNoSpace(e: Throwable): Boolean {
            var t: Throwable? = e
            while (t != null) {
                val m = t.message ?: ""
                if (t is IOException && (m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true))) return true
                t = t.cause
            }
            return false
        }
    }
}
