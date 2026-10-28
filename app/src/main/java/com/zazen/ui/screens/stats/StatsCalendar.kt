package com.zazen.ui.screens.stats

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zazen.data.model.MeditationSession
import com.zazen.data.stats.StatsSnapshot
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
internal fun CalendarTab(
    snapshot: StatsSnapshot,
    sessions: List<MeditationSession>,
    onSessionClick: (MeditationSession) -> Unit,
) {
    val today = LocalDate.now()
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    var selectedText by rememberSaveable { mutableStateOf(today.toString()) }
    val month = YearMonth.parse(monthText)
    val selected = LocalDate.parse(selectedText)

    val byDay = remember(sessions) { sessions.groupBy { it.localDate() } }
    val monthSessions = remember(sessions, month) { sessions.filter { YearMonth.from(it.localDate()) == month } }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard(
            title = month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())),
            trailing = {
                Row {
                    IconButton(onClick = { monthText = month.minusMonths(1).toString() }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month")
                    }
                    IconButton(
                        onClick = { monthText = month.plusMonths(1).toString() },
                        enabled = month.isBefore(YearMonth.from(today)),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month")
                    }
                }
            },
        ) {
            MonthGrid(
                month = month,
                weekStart = snapshot.weekStart,
                today = today,
                selected = selected,
                dayMillis = snapshot.dayMillis,
                onSelect = { selectedText = it.toString() },
            )
            Spacer(Modifier.height(12.dp))
            val monthMillis = monthSessions.sumOf { it.completedMillis }
            val monthDays = monthSessions.map { it.localDate() }.distinct().size
            Text(
                "${formatHm(monthMillis)} · ${monthSessions.size} sits · $monthDays of ${month.lengthOfMonth()} days",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val daySits = byDay[selected].orEmpty().sortedBy { it.startTime }
        SectionCard(
            title = formatDayHeader(selected),
            trailing = {
                if (daySits.isNotEmpty()) {
                    Text(
                        formatHm(daySits.sumOf { it.completedMillis }),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
        ) {
            if (daySits.isEmpty()) {
                Text(
                    "No sits this day.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    daySits.forEach { SessionRow(it, onClick = { onSessionClick(it) }) }
                }
            }
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    weekStart: java.time.DayOfWeek,
    today: LocalDate,
    selected: LocalDate,
    dayMillis: Map<LocalDate, Long>,
    onSelect: (LocalDate) -> Unit,
) {
    val first = month.atDay(1)
    val leading = ((first.dayOfWeek.value - weekStart.value) + 7) % 7
    val cells = leading + month.lengthOfMonth()
    val rows = (cells + 6) / 7
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            (0 until 7).forEach { i ->
                val dow = weekStart.plus(i.toLong())
                Text(
                    dow.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                )
            }
        }
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in 0 until 7) {
                    val index = r * 7 + c - leading
                    if (index < 0 || index >= month.lengthOfMonth()) {
                        Spacer(Modifier.weight(1f).aspectRatio(1f))
                        continue
                    }
                    val day = month.atDay(index + 1)
                    val millis = dayMillis[day] ?: 0L
                    val level = intensityLevel(millis)
                    val future = day.isAfter(today)
                    val shape = RoundedCornerShape(10.dp)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .background(if (future) MaterialTheme.colorScheme.surfaceContainerLow else intensityColor(level), shape)
                            .then(
                                if (day == selected) Modifier.border(BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface), shape)
                                else if (day == today) Modifier.border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), shape)
                                else Modifier,
                            )
                            .clickable(enabled = !future) { onSelect(day) }
                            .semantics {
                                contentDescription = "${formatLongDate(day)}: " +
                                    if (millis > 0) formatHm(millis) else "no sits"
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${day.dayOfMonth}",
                            style = MaterialTheme.typography.labelLarge,
                            color = when {
                                future -> muted.copy(alpha = 0.4f)
                                level >= 3 -> MaterialTheme.colorScheme.onPrimary
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
    }
}

/** One sit in a compact list: time range, length, tags, stars. */
@Composable
internal fun SessionRow(session: MeditationSession, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${formatClock(session.startDateTime())} – ${formatClock(session.endDateTime())}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                session.techniqueOrNull?.let { Tag(it.shortLabel, emphasized = true) }
                if (session.extended) Tag("Extended")
                if (session.isOpenEnded) Tag("Open")
                if (!session.completed && !session.isOpenEnded) Tag("Ended early")
                Stars(session.rating, size = 12.dp)
            }
        }
        Text(
            formatMinutesPrecise(session.completedMillis),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
