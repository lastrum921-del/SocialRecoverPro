package com.socialrecover.pro.data

import android.content.Context
import android.net.Uri
import com.socialrecover.core.model.Category

/** Preferencias locales. No se guarda ninguna contraseña ni clave. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("srp_prefs", Context.MODE_PRIVATE)

    var onboardingDone: Boolean
        get() = sp.getBoolean("onboarding_done", false)
        set(v) = sp.edit().putBoolean("onboarding_done", v).apply()

    var backupTreeUri: Uri?
        get() = sp.getString("backup_tree", null)?.let(Uri::parse)
        set(v) = sp.edit().putString("backup_tree", v?.toString()).apply()

    var lastBackupAt: Long
        get() = sp.getLong("last_backup", 0L)
        set(v) = sp.edit().putLong("last_backup", v).apply()

    var enabledCategories: Set<Category>
        get() = sp.getStringSet("categories", null)?.map { Category.fromId(it) }?.toSet()
            ?: Category.entries.filter { it.defaultEnabled }.toSet()
        set(v) = sp.edit().putStringSet("categories", v.map { it.id }.toSet()).apply()
}
