package com.socialrecover.pro.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.socialrecover.pro.data.Notifier
import com.socialrecover.pro.data.VaultService
import java.util.concurrent.TimeUnit

/**
 * Tarea periódica (WorkManager): revisa las carpetas autorizadas, hace la copia automática si el usuario
 * la activó y notifica los avisos importantes. Android decide el momento exacto para ahorrar batería.
 */
class ProtectionWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val svc = VaultService(applicationContext)
        try {
            val opened = svc.unlockFromDeviceKeys()
            if (opened && svc.autoBackup && svc.watched.isNotEmpty()) svc.backupWatched(isCancelled = { isStopped })
            val report = svc.protection(scan = true)
            if (svc.monitor.notificationsEnabled) Notifier.notify(applicationContext, report)
        } catch (e: Exception) {
            return Result.retry()
        } finally {
            svc.lock()
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "srp-proteccion"

        fun schedule(context: Context, everyHours: Long) {
            val req = PeriodicWorkRequestBuilder<ProtectionWorker>(everyHours.coerceAtLeast(1), TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(NAME)
    }
}
