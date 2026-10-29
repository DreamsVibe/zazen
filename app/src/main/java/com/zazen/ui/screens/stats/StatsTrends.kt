package com.zazen.ui.screens.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zazen.data.stats.PeriodPoint
import com.zazen.data.stats.StatsSnapshot
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

private enum class TrendRange(val label: String) {
    WEEKS_12("12 weeks"), WEEKS_26("6 months"), MONTHS_12("1 year"), ALL("All")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrendsTab(snapshot: StatsSnapshot) {
    var rangeName by rememberSaveable { mutableStateOf(TrendRange.WEEKS_12.name) }
    val range = TrendRange.valueOf(rangeName)

    val byMonth = range == TrendRange.MONTHS_12 || range == TrendRange.ALL
    val points: List<PeriodPoint> = when (range) {
        TrendRange.WEEKS_12 -> snapshot.weeks.takeLast(12)
        TrendRange.WEEKS_26 -> snapshot.weeks.takeLast(26)
        TrendRange.MONTHS_12 -> snapshot.months.takeLast(12)
        TrendRange.ALL -> snapshot.months
    }
    val labelFmt = DateTimeFormatter.ofPattern(if (byMonth) "MMM" else "MMM d", Locale.getDefault())
    val labels = points.map { it.start.format(labelFmt) }
    val unit = if (byMonth) "month" else "week"

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TrendRange.entries.forEach { r ->
                FilterChip(
                    selected = r == range,
                    onClick = { rangeName = r.name },
                    label = { Text(r.label) },
                )
            }
        }

        if (snapshot.all.sits == 0) {
            Text(
                "Trends appear once you've logged a few sits.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        TimeOfDayCard(snapshot)

        SectionCard("Total time per $unit") {
            BarChart(
                values = points.map { it.millis / 60_000f },
                labels = labels,
                formatY = { "${it.toInt()}m" },
            )
        }

        SectionCard("Average sit length") {
            LineChart(
                values = points.map { if (it.sits > 0) it.avgMillis / 60_000f else null },
                labels = labels,
                formatY = { "${it.toInt()}m" },
            )
            TrendNote(points.map { if (it.sits > 0) it.avgMillis / 60_000f else null }, suffix = " min")
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun TimeOfDayCard(snapshot: StatsSnapshot) {
    val buckets = snapshot.timeOfDay
    val busiest = buckets.maxByOrNull { it.millis }
    SectionCard("When you sit · all time") {
        BarChart(
            values = buckets.map { it.millis / 60_000f },
            labels = buckets.map { shortHour(it.startHour) },
            formatY = { "${it.toInt()}m" },
            showEveryLabel = true,
            highlightIndex = buckets.indexOf(busiest),
        )
        Spacer(Modifier.height(8.dp))
        val lines = buildList {
            if (busiest != null && busiest.sits > 0) {
                add("Most of your time is in ${busiest.label} (${busiest.sits} sits, avg ${formatMinutesPrecise(busiest.avgMillis)}).")
            }
        }
        lines.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun shortHour(h: Int): String = when {
    h == 0 -> "12a"
    h < 12 -> "${h}a"
    h == 12 -> "12p"
    else -> "${h - 12}p"
}

/** Compares the latest third of the range with the earliest third. */
@Composable
private fun TrendNote(values: List<Float?>, suffix: String, decimals: Boolean = false) {
    val present = values.filterNotNull()
    if (present.size < 4) return
    val third = max(1, present.size / 3)
    val early = present.take(third).average()
    val late = present.takeLast(third).average()
    val diff = late - early
    val fmt = { v: Double -> if (decimals) "%.1f".format(v) else "%.0f".format(v) }
    val text = when {
        kotlin.math.abs(diff) < (if (decimals) 0.15 else 1.0) -> "Holding steady around ${fmt(late)}$suffix."
        diff > 0 -> "Up from ${fmt(early)} to ${fmt(late)}$suffix across this range."
        else -> "Down from ${fmt(early)} to ${fmt(late)}$suffix across this range."
    }
    Spacer(Modifier.height(8.dp))
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// --- Charts (plain Canvas, no chart library) ---

private val CHART_HEIGHT = 150.dp

@Composable
private fun BarChart(
    values: List<Float>,
    labels: List<String>,
    formatY: (Float) -> String,
    showEveryLabel: Boolean = false,
    highlightIndex: Int = -1,
) {
    val accent = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontSize = 10.sp, color = labelColor)
    val maxV = niceMax(values.maxOrNull() ?: 0f)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(CHART_HEIGHT)
            .semantics { contentDescription = "Bar chart: " + labels.zip(values).joinToString { "${it.first} ${formatY(it.second)}" } },
    ) {
        val axisW = 34.dp.toPx()
        val bottomH = 16.dp.toPx()
        val plotW = size.width - axisW
        val plotH = size.height - bottomH
        drawGrid(measurer, style, grid, axisW, plotW, plotH, 0f, maxV, formatY)
        if (values.isEmpty()) return@Canvas
        val slot = plotW / values.size
        val barW = (slot * 0.62f).coerceAtMost(28.dp.toPx())
        values.forEachIndexed { i, v ->
            val h = if (maxV <= 0f) 0f else plotH * (v / maxV)
            val x = axisW + i * slot + (slot - barW) / 2
            if (h > 0f) {
                drawRoundRect(
                    color = if (highlightIndex == -1 || i == highlightIndex) accent else accent.copy(alpha = 0.55f),
                    topLeft = Offset(x, plotH - h),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
            }
        }
        drawXLabels(measurer, style, labels, axisW, slot, plotH, showEveryLabel)
    }
}

@Composable
private fun LineChart(
    values: List<Float?>,
    labels: List<String>,
    formatY: (Float) -> String,
    fixedMin: Float? = null,
    fixedMax: Float? = null,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontSize = 10.sp, color = labelColor)
    val present = values.filterNotNull()
    val minV = fixedMin ?: 0f
    val maxV = fixedMax ?: niceMax(present.maxOrNull() ?: 0f)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(CHART_HEIGHT)
            .semantics {
                contentDescription = "Line chart: " + labels.zip(values)
                    .filter { it.second != null }
                    .joinToString { "${it.first} ${formatY(it.second!!)}" }
            },
    ) {
        val axisW = 34.dp.toPx()
        val bottomH = 16.dp.toPx()
        val plotW = size.width - axisW
        val plotH = size.height - bottomH
        drawGrid(measurer, style, grid, axisW, plotW, plotH, minV, maxV, formatY)
        if (values.isEmpty()) return@Canvas
        val slot = plotW / values.size
        fun pointAt(i: Int, v: Float): Offset {
            val frac = if (maxV - minV <= 0f) 0f else ((v - minV) / (maxV - minV)).coerceIn(0f, 1f)
            return Offset(axisW + i * slot + slot / 2, plotH - plotH * frac)
        }
        // Solid line through consecutive points; dashed across gaps (periods with no sits).
        var prev: Offset? = null
        var prevWasGap = false
        val solid = Path()
        values.forEachIndexed { i, v ->
            if (v == null) {
                prevWasGap = prev != null
                return@forEachIndexed
            }
            val p = pointAt(i, v)
            val last = prev
            if (last == null) {
                solid.moveTo(p.x, p.y)
            } else if (prevWasGap) {
                drawLine(
                    color = color.copy(alpha = 0.45f),
                    start = last,
                    end = p,
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                )
                solid.moveTo(p.x, p.y)
            } else {
                solid.lineTo(p.x, p.y)
            }
            prev = p
            prevWasGap = false
        }
        drawPath(solid, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        values.forEachIndexed { i, v ->
            if (v != null) {
                val p = pointAt(i, v)
                drawCircle(color, radius = 3.5.dp.toPx(), center = p)
            }
        }
        drawXLabels(measurer, style, labels, axisW, slot, plotH, false)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGrid(
    measurer: androidx.compose.ui.text.TextMeasurer,
    style: TextStyle,
    grid: Color,
    axisW: Float,
    plotW: Float,
    plotH: Float,
    minV: Float,
    maxV: Float,
    formatY: (Float) -> String,
) {
    val steps = 3
    for (s in 0..steps) {
        val frac = s / steps.toFloat()
        val y = plotH - plotH * frac
        drawLine(grid, Offset(axisW, y), Offset(axisW + plotW, y), strokeWidth = 1f)
        val v = minV + (maxV - minV) * frac
        val layout = measurer.measure(formatY(v), style)
        drawText(layout, topLeft = Offset(axisW - layout.size.width - 6f, y - layout.size.height / 2f))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawXLabels(
    measurer: androidx.compose.ui.text.TextMeasurer,
    style: TextStyle,
    labels: List<String>,
    axisW: Float,
    slot: Float,
    plotH: Float,
    everyLabel: Boolean,
) {
    if (labels.isEmpty()) return
    // Show at most ~5 labels so they never collide.
    val stride = if (everyLabel) 1 else max(1, ceil(labels.size / 5f).toInt())
    labels.forEachIndexed { i, label ->
        if (i % stride != 0 && i != labels.lastIndex) return@forEachIndexed
        if (!everyLabel && i != labels.lastIndex && labels.lastIndex - i < stride / 2) return@forEachIndexed
        val layout = measurer.measure(label, style)
        val cx = axisW + i * slot + slot / 2
        val x = (cx - layout.size.width / 2f).coerceIn(axisW, size.width - layout.size.width)
        drawText(layout, topLeft = Offset(x, plotH + 3f))
    }
}

/** Rounds an axis maximum up to a friendly number (5, 10, 20, 30, 60, ...). */
private fun niceMax(v: Float): Float {
    if (v <= 0f) return 10f
    val steps = listOf(5f, 10f, 15f, 20f, 30f, 45f, 60f, 90f, 120f, 180f, 240f, 300f, 450f, 600f, 900f, 1200f, 1800f, 2400f, 3600f)
    return steps.firstOrNull { it >= v } ?: (ceil(v / 600f) * 600f)
}
