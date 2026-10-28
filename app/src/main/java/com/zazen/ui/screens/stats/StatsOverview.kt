package com.zazen.ui.screens.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zazen.data.stats.StatsSnapshot
import java.time.LocalDate
import java.time.format.TextStyle as JTextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

@Composable
internal fun OverviewTab(
    snapshot: StatsSnapshot,
    graceDays: Int,
    onGraceDaysChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        StreakCard(snapshot, graceDays, onGraceDaysChange)
        TotalsCard(snapshot)
        SectionCard("Last months") {
            Heatmap(snapshot.dayMillis, weekStart = snapshot.weekStart)
        }
        SectionCard("Your sits") {
            Row(Modifier.fillMaxWidth()) {
                BigNumber(formatMinutesPrecise(snapshot.avgSitMillis), "Average sit", Modifier.weight(1f))
                BigNumber(formatHm(snapshot.longestSitMillis), "Longest sit", Modifier.weight(1f))
                BigNumber(
                    formatRating(snapshot.avgRating),
                    "Avg rating",
                    Modifier.weight(1f),
                    sub = if (snapshot.ratedSits > 0) "${snapshot.ratedSits} rated" else null,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StreakCard(snapshot: StatsSnapshot, graceDays: Int, onGraceDaysChange: (Int) -> Unit) {
    val streak = snapshot.streak
    SectionCard("Streak") {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "${streak.current}",
                style = MaterialTheme.typography.displayMedium,
                color = if (streak.current > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (streak.current == 1) "day" else "days",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            Spacer(Modifier.weight(1f))
            BigNumber("${streak.best}", "Best streak")
        }
        Text(
            streakStatus(snapshot),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))
        Text(
            "Grace days — missed days in a row allowed before a streak breaks",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        val options = listOf(0, 1, 2, 3)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, value ->
                SegmentedButton(
                    selected = graceDays == value,
                    onClick = { onGraceDaysChange(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    label = { Text(if (value == 0) "None" else "$value") },
                )
            }
        }
    }
}

private fun streakStatus(snapshot: StatsSnapshot): String {
    val s = snapshot.streak
    return when {
        snapshot.all.sits == 0 -> "Your first sit starts a streak."
        !s.alive -> "No streak right now — sit today to start one."
        s.satToday -> buildString {
            append("Sat today ✓")
            if (s.graceUsedInCurrent > 0) append(" · ${s.graceUsedInCurrent} grace day${if (s.graceUsedInCurrent == 1) "" else "s"} used")
        }
        s.missedInARow == 0 -> "Sit today to keep it going."
        s.graceLeft > 0 -> "Missed ${s.missedInARow} day${if (s.missedInARow == 1) "" else "s"} — grace is covering it. Sit today."
        else -> "Last grace day — sit today or the streak resets."
    }
}

@Composable
private fun TotalsCard(snapshot: StatsSnapshot) {
    SectionCard("Totals") {
        Row(Modifier.fillMaxWidth()) {
            BigNumber(formatHm(snapshot.today.millis), "Today", Modifier.weight(1f), sub = sitsLabel(snapshot.today.sits))
            BigNumber(formatHm(snapshot.week.millis), "This week", Modifier.weight(1f), sub = sitsLabel(snapshot.week.sits))
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            BigNumber(formatHm(snapshot.month.millis), "This month", Modifier.weight(1f), sub = sitsLabel(snapshot.month.sits))
            BigNumber(formatHm(snapshot.all.millis), "All time", Modifier.weight(1f), sub = sitsLabel(snapshot.all.sits))
        }
    }
}

private fun sitsLabel(n: Int) = if (n == 1) "1 sit" else "$n sits"

/** GitHub-style grid: one column per week, newest on the right. */
@Composable
internal fun Heatmap(
    dayMillis: Map<LocalDate, Long>,
    weekStart: java.time.DayOfWeek,
    today: LocalDate = LocalDate.now(),
) {
    val levelColors = (0..4).map { intensityColor(it) }
    val todayRing = MaterialTheme.colorScheme.onSurface
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 3.dp
        val cell = 14.dp
        val columns = ((maxWidth + gap) / (cell + gap)).toInt().coerceIn(4, 53)
        val lastWeekStart = today.with(TemporalAdjusters.previousOrSame(weekStart))
        val firstWeekStart = lastWeekStart.minusWeeks((columns - 1).toLong())
        val activeDays = remember(dayMillis, firstWeekStart) {
            dayMillis.count { (d, _) -> !d.isBefore(firstWeekStart) }
        }
        val gridHeight = cell * 7 + gap * 6
        val labelHeight = 14.dp

        Column {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(gridHeight + labelHeight)
                    .semantics { contentDescription = "Heatmap of the last $columns weeks: $activeDays days with a sit" },
            ) {
                val c = cell.toPx()
                val g = gap.toPx()
                val top = labelHeight.toPx()
                var lastMonth = -1
                for (col in 0 until columns) {
                    val weekDate = firstWeekStart.plusWeeks(col.toLong())
                    val x = col * (c + g)
                    // Month label where a new month begins
                    val monthAtTop = weekDate.monthValue
                    if (monthAtTop != lastMonth && col < columns - 1) {
                        if (lastMonth != -1 || weekDate.dayOfMonth <= 7) {
                            val name = weekDate.month.getDisplayName(JTextStyle.SHORT, Locale.getDefault())
                            drawText(measurer, name, topLeft = Offset(x, 0f), style = labelStyle)
                        }
                        lastMonth = monthAtTop
                    }
                    for (row in 0 until 7) {
                        val day = weekDate.plusDays(row.toLong())
                        if (day.isAfter(today)) continue
                        val level = intensityLevel(dayMillis[day] ?: 0L)
                        val y = top + row * (c + g)
                        drawRoundRect(
                            color = levelColors[level],
                            topLeft = Offset(x, y),
                            size = Size(c, c),
                            cornerRadius = CornerRadius(c * 0.25f),
                        )
                        if (day == today) {
                            drawRoundRect(
                                color = todayRing,
                                topLeft = Offset(x, y),
                                size = Size(c, c),
                                cornerRadius = CornerRadius(c * 0.25f),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$activeDays sit days", style = MaterialTheme.typography.bodySmall, color = labelColor)
                Spacer(Modifier.weight(1f))
                Text("Less", style = MaterialTheme.typography.labelSmall, color = labelColor)
                Spacer(Modifier.width(6.dp))
                levelColors.forEach { color ->
                    Spacer(
                        Modifier
                            .padding(horizontal = 1.5.dp)
                            .size(10.dp)
                            .background(color, RoundedCornerShape(3.dp)),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text("More", style = MaterialTheme.typography.labelSmall, color = labelColor)
            }
        }
    }
}
