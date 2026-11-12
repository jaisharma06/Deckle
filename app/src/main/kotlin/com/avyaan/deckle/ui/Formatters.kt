package com.avyaan.deckle.ui

import android.content.Context
import android.text.format.Formatter
import com.avyaan.deckle.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** "Today, 14:20" / "Yesterday, 09:05" / "9 Oct 2026" — the way people talk about recent things. */
object RelativeDate {
    private val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    private val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

    fun format(context: Context, instant: Instant, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
        val zoned = instant.atZone(zone)
        val day = zoned.toLocalDate()
        return when (day) {
            today -> context.getString(R.string.date_today, time.format(zoned))
            today.minusDays(1) -> context.getString(R.string.date_yesterday, time.format(zoned))
            else -> date.format(zoned)
        }
    }
}

fun Context.formatBytes(bytes: Long): String = Formatter.formatShortFileSize(this, bytes)
