package com.socialrecover.pro.ui

import android.content.Context
import android.text.format.Formatter
import java.text.DateFormat
import java.util.Date
import java.util.Locale

object Format {
    private val es: Locale = Locale.forLanguageTag("es-ES")

    fun size(context: Context, bytes: Long): String =
        if (bytes < 0) "—" else Formatter.formatShortFileSize(context, bytes)

    fun date(ms: Long): String =
        if (ms <= 0) "Nunca" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, es).format(Date(ms))
}
