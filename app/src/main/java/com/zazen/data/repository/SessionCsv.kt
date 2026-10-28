package com.zazen.data.repository

import com.zazen.data.model.MeditationSession
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Builds a spreadsheet-friendly CSV of the session history. */
object SessionCsv {
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")

    private val HEADER = listOf(
        "id", "date", "weekday", "start_time", "end_time",
        "planned_minutes", "sat_minutes", "sat_seconds",
        "type", "open_ended", "completed", "extended",
        "technique", "rating", "notes",
    )

    fun build(sessions: List<MeditationSession>, zone: ZoneId = ZoneId.systemDefault()): String {
        val sb = StringBuilder()
        // UTF-8 byte-order mark so Excel shows ā and other accents correctly.
        sb.append('\uFEFF')
        sb.append(HEADER.joinToString(",")).append("\r\n")
        for (s in sessions) {
            val start = Instant.ofEpochMilli(s.startTime).atZone(zone)
            val end = Instant.ofEpochMilli(s.startTime + s.completedMillis).atZone(zone)
            val type = when {
                s.isOpenEnded -> "open"
                s.extended -> "extended"
                s.completed -> "timed"
                else -> "ended_early"
            }
            val row = listOf(
                s.id.toString(),
                start.format(dateFmt),
                start.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() },
                start.format(timeFmt),
                end.format(timeFmt),
                if (s.isOpenEnded) "" else "%.2f".format(java.util.Locale.US, s.durationMillis / 60_000.0),
                "%.2f".format(java.util.Locale.US, s.completedMillis / 60_000.0),
                (s.completedMillis / 1000).toString(),
                type,
                s.isOpenEnded.toString(),
                s.completed.toString(),
                s.extended.toString(),
                s.techniqueOrNull?.shortLabel.orEmpty(),
                if (s.rating > 0) s.rating.toString() else "",
                s.notes,
            )
            sb.append(row.joinToString(",") { escape(it) }).append("\r\n")
        }
        return sb.toString()
    }

    private fun escape(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }
}
