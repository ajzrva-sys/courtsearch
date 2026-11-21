package local.courtsearch

import java.net.URLDecoder
import java.time.Instant
import java.time.ZoneId

/** Same letter rules as the server's `_clean_name`. Blank and rejected text both come back empty. */
fun cleanName(value: String, initial: Boolean = false): String {
    val trimmed = Regex("\\s+").replace(value.trim(), " ")
    if (trimmed.isEmpty()) return ""
    val pattern = if (initial) {
        Regex("[A-Za-z]")
    } else {
        Regex("[A-Za-z][A-Za-z .'\\-]{0,39}")
    }
    return if (pattern.matches(trimmed)) trimmed else ""
}

fun filenameFrom(header: String?, fallback: String): String {
    if (header.isNullOrBlank()) return fallback
    val star = Regex("filename\\*=(?:UTF-8|utf-8)''([^;]+)").find(header)
    if (star != null) return URLDecoder.decode(star.groupValues[1], "UTF-8")
    val plain = Regex("filename=\"?([^\";]+)\"?").find(header)
    return plain?.groupValues?.get(1)?.trim() ?: fallback
}

fun safeFileName(name: String): String {
    val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "-").trim('-', '.')
    return cleaned.ifEmpty { "download.pdf" }.take(80)
}

fun searchKey(last: String, first: String, middle: String): String {
    return listOf(last, first, middle).joinToString("|") { it.uppercase() }
}

/** First, middle, last, skipping blanks. Uppercase parts are shown in title case. */
fun savedDisplayName(first: String, middle: String, last: String): String {
    return listOf(first, middle, last).filter { it.isNotBlank() }.joinToString(" ") { nameForForm(it) }
}

fun nameForForm(part: String): String {
    if (part.isEmpty() || part.any { it.isLowerCase() }) return part
    return part.lowercase().replaceFirstChar { it.titlecase() }
}

/** A saved search answers only for the Virginia court day it was run. */
fun savedOnCourtDay(cachedAtMillis: Long, nowMillis: Long): Boolean {
    val zone = ZoneId.of("America/New_York")
    fun day(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    return day(cachedAtMillis) == day(nowMillis)
}

/** Sort key for a portal hearing date. Empty when the date is not month/day/year. */
fun hearingKey(date: String): String {
    val parts = date.split("/")
    if (parts.size != 3 || parts.any { part -> part.isEmpty() || part.any { !it.isDigit() } }) return ""
    val (month, day, year) = parts
    return year + month.padStart(2, '0') + day.padStart(2, '0')
}
