package com.zazen.ui.screens.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zazen.data.model.MeditationSession
import com.zazen.data.model.Technique
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// --- Formatting ---

internal fun MeditationSession.startDateTime(): LocalDateTime =
    Instant.ofEpochMilli(startTime).atZone(ZoneId.systemDefault()).toLocalDateTime()

internal fun MeditationSession.endDateTime(): LocalDateTime =
    Instant.ofEpochMilli(startTime + completedMillis).atZone(ZoneId.systemDefault()).toLocalDateTime()

internal fun MeditationSession.localDate(): LocalDate = startDateTime().toLocalDate()

private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())

internal fun formatClock(t: LocalDateTime): String = t.format(timeFormatter)

internal fun formatDayHeader(d: LocalDate, today: LocalDate = LocalDate.now()): String = when (d) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> d.format(
        DateTimeFormatter.ofPattern(if (d.year == today.year) "EEE, MMM d" else "EEE, MMM d, yyyy", Locale.getDefault()),
    )
}

internal fun formatLongDate(d: LocalDate): String =
    d.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()))

/** "2h 05m", "42m". */
internal fun formatHm(millis: Long): String {
    val totalMinutes = millis / 60_000
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "%dh %02dm".format(h, m) else "${m}m"
}

/** Minutes with one decimal under 10 so short sits don't read as zero. */
internal fun formatMinutesPrecise(millis: Long): String {
    val minutes = millis / 60_000.0
    return if (minutes < 10) "%.1f min".format(minutes).replace(".0 ", " ") else "${minutes.toInt()} min"
}

internal fun formatRating(r: Double?): String = r?.let { "%.1f★".format(it) } ?: "—"

/** 0..4 intensity bucket for a day's total sitting time. */
internal fun intensityLevel(millis: Long): Int {
    val minutes = millis / 60_000
    return when {
        millis <= 0 -> 0
        minutes < 15 -> 1
        minutes < 30 -> 2
        minutes < 60 -> 3
        else -> 4
    }
}

@Composable
internal fun intensityColor(level: Int): Color {
    val accent = MaterialTheme.colorScheme.primary
    return when (level) {
        0 -> MaterialTheme.colorScheme.surfaceContainerHighest
        1 -> accent.copy(alpha = 0.28f)
        2 -> accent.copy(alpha = 0.48f)
        3 -> accent.copy(alpha = 0.72f)
        else -> accent
    }
}

// --- Building blocks ---

@Composable
internal fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                trailing?.invoke()
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
internal fun BigNumber(value: String, label: String, modifier: Modifier = Modifier, sub: String? = null) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (sub != null) {
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun Stars(rating: Int, size: Dp = 14.dp) {
    if (rating <= 0) return
    Row(modifier = Modifier.semantics { contentDescription = "Rated $rating of 5" }) {
        (1..5).forEach { n ->
            Icon(
                if (n <= rating) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = null,
                modifier = Modifier.size(size),
                tint = if (n <= rating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
internal fun Tag(text: String, emphasized: Boolean = false) {
    Box(
        modifier = Modifier
            .background(
                if (emphasized) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHighest,
                RoundedCornerShape(50),
            )
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --- Review editor ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReviewEditorDialog(
    session: MeditationSession,
    onDismiss: () -> Unit,
    onSave: (rating: Int, technique: Technique?, notes: String) -> Unit,
    onDelete: () -> Unit,
) {
    var rating by remember(session.id) { mutableIntStateOf(session.rating) }
    var technique by remember(session.id) { mutableStateOf(session.techniqueOrNull) }
    var notes by remember(session.id) { mutableStateOf(session.notes) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(formatDayHeader(session.localDate()), style = MaterialTheme.typography.titleLarge)
                Text(
                    "${formatClock(session.startDateTime())} – ${formatClock(session.endDateTime())} · " +
                        formatMinutesPrecise(session.completedMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column {
                Row {
                    (1..5).forEach { n ->
                        IconButton(
                            onClick = { rating = if (rating == n) 0 else n },
                            modifier = Modifier.semantics { contentDescription = "Rate $n of 5" },
                        ) {
                            Icon(
                                if (n <= rating) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = null,
                                tint = if (n <= rating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Technique.entries.forEach { t ->
                        FilterChip(
                            selected = technique == t,
                            onClick = { technique = if (technique == t) null else t },
                            label = { Text(t.shortLabel) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(rating, technique, notes) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
