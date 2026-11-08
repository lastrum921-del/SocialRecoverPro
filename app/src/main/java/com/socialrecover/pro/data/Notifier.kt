package com.socialrecover.pro.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.socialrecover.core.status.ProtectionReport
import com.socialrecover.core.status.Severity

/** Avisos del detector de archivos desprotegidos. Solo se envían si el usuario los ha activado y concedido. */
object Notifier {
    const val CHANNEL = "proteccion"
    private const val ID = 4201

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Estado de protección", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Avisos cuando tus archivos llevan tiempo sin copia o una copia falla"
        })
    }

    fun canNotify(context: Context) =
        Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun notify(context: Context, report: ProtectionReport) {
        val top = report.alerts.firstOrNull { it.severity != Severity.INFO } ?: return
        if (!canNotify(context)) return
        ensureChannel(context)
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply { putExtra("open", "status"); flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        val pi = open?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val more = report.alerts.size - 1
        val n = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(report.level.label + if (more > 0) " · $more aviso(s) más" else "")
            .setContentText(top.message)
            .setStyle(android.app.Notification.BigTextStyle().bigText(top.message))
            .setAutoCancel(true)
            .apply { pi?.let { setContentIntent(it) } }
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID, n)
    }
}
