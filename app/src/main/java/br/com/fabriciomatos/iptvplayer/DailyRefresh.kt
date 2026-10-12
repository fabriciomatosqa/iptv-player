package br.com.fabriciomatos.iptvplayer

import java.util.Calendar
import java.util.TimeZone

/** The first opening on a new local calendar day may request the M3U once. */
fun shouldRefreshDaily(lastAttempt: Long, now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): Boolean {
    if (lastAttempt <= 0L) return true
    val before = Calendar.getInstance(zone).apply { timeInMillis = lastAttempt }
    val today = Calendar.getInstance(zone).apply { timeInMillis = now }
    return before.get(Calendar.ERA) != today.get(Calendar.ERA) || before.get(Calendar.YEAR) != today.get(Calendar.YEAR) || before.get(Calendar.DAY_OF_YEAR) != today.get(Calendar.DAY_OF_YEAR)
}
