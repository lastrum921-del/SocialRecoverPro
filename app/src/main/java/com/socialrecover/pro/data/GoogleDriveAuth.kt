package com.socialrecover.pro.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import com.socialrecover.core.cloud.AccessTokenProvider
import com.socialrecover.core.cloud.DriveRepoBackend
import com.socialrecover.core.engine.SrpException
import java.util.concurrent.TimeUnit

/**
 * Acceso a Google Drive con Google Identity Services. Solo se pide el permiso `drive.file`:
 * la app ve únicamente los archivos que ella misma crea (su carpeta de bóveda), nunca el resto del Drive.
 */
object GoogleDriveAuth {
    private fun request() = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DriveRepoBackend.SCOPE))).build()

    sealed class Start {
        class Granted(val token: String) : Start()
        class NeedsConsent(val pendingIntent: PendingIntent) : Start()
        class Failed(val message: String) : Start()
    }

    /** Desde la interfaz: si hace falta, devuelve la pantalla de consentimiento de Google para lanzarla. */
    fun start(context: Context, done: (Start) -> Unit) {
        Identity.getAuthorizationClient(context).authorize(request())
            .addOnSuccessListener { r: AuthorizationResult ->
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) done(Start.NeedsConsent(pi))
                else r.accessToken?.let { done(Start.Granted(it)) } ?: done(Start.Failed("Google no devolvió acceso"))
            }
            .addOnFailureListener { e -> done(Start.Failed(explain(e))) }
    }

    /** Resultado de la pantalla de consentimiento. */
    fun finish(context: Context, data: Intent?): Start = try {
        val r = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        r.accessToken?.let { Start.Granted(it) } ?: Start.Failed("No se concedió el acceso")
    } catch (e: Exception) {
        Start.Failed(explain(e))
    }

    private fun explain(e: Exception): String {
        val m = e.message ?: ""
        return if (m.contains("10:") || m.contains("DEVELOPER_ERROR")) {
            "Google Drive aún no está configurado para esta app (falta el cliente OAuth con la huella SHA-1 en Google Cloud). Ver docs/GOOGLE_DRIVE.md."
        } else "No se pudo conectar con Google: $m"
    }

    /** Proveedor de tokens para trabajar en segundo plano (copias automáticas). */
    class Tokens(private val context: Context) : AccessTokenProvider {
        @Volatile private var cached: String? = null

        override fun token(): String {
            cached?.let { return it }
            val r = try {
                Tasks.await(Identity.getAuthorizationClient(context).authorize(request()), 30, TimeUnit.SECONDS)
            } catch (e: Exception) {
                throw SrpException.Offline(e)
            }
            if (r.hasResolution()) throw SrpException.AuthRequired()
            return (r.accessToken ?: throw SrpException.AuthRequired()).also { cached = it }
        }

        override fun invalidate() {
            cached?.let { t -> runCatching { GoogleAuthUtil.clearToken(context, t) } }
            cached = null
        }
    }
}
