package com.zazen.ui.screens.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.zazen.data.stats.Milestone
import com.zazen.data.stats.StatsSnapshot
import com.zazen.data.stats.Tier

@Composable
internal fun MilestonesTab(snapshot: StatsSnapshot) {
    val all = snapshot.milestones
    val earned = all.count { it.achieved }
    // Closest unearned goals, by how far along you are.
    val next = all.filter { !it.achieved }.sortedByDescending { it.fraction }.take(3)

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard("Progress") {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$earned", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    "of ${all.size} milestones",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Tier.entries.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    pair.forEach { tier ->
                        val inTier = all.filter { it.tier == tier }
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(tierColor(tier), CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "${tier.label} · ${inTier.count { it.achieved }}/${inTier.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (next.isNotEmpty()) {
            SectionCard("Up next") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    next.forEach { MilestoneRow(it) }
                }
            }
        }

        all.groupBy { it.category }.forEach { (category, items) ->
            SectionCard(category, trailing = {
                Text(
                    "${items.count { it.achieved }}/${items.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items.forEach { MilestoneRow(it) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun tierColor(tier: Tier): Color = when (tier) {
    Tier.EASY -> MaterialTheme.colorScheme.tertiary
    Tier.MEDIUM -> MaterialTheme.colorScheme.secondary
    Tier.HARD -> MaterialTheme.colorScheme.primary
    Tier.LEGENDARY -> MaterialTheme.colorScheme.error
}

@Composable
private fun MilestoneRow(m: Milestone) {
    val color = tierColor(m.tier)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .then(
                    if (m.achieved) Modifier.background(color, CircleShape)
                    else Modifier.border(1.5.dp, color.copy(alpha = 0.5f), CircleShape),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (m.achieved) Icons.Filled.Check else Icons.Filled.Lock,
                contentDescription = if (m.achieved) "Earned" else "Locked",
                modifier = Modifier.size(18.dp),
                tint = if (m.achieved) MaterialTheme.colorScheme.surface else color.copy(alpha = 0.7f),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    m.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (m.achieved) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    m.tier.label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    modifier = Modifier
                        .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            Text(m.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (m.achieved) {
                Text(
                    "Earned ${formatLongDate(m.achievedOn!!)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                )
            } else {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { m.fraction },
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp),
                        color = color,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        strokeCap = StrokeCap.Round,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${m.progress.coerceAtMost(m.target)}/${m.target}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
