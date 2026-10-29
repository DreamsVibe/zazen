package com.zazen.ui.screens.stats

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zazen.data.model.MeditationSession

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HistoryTab(
    sessions: List<MeditationSession>,
    selectedIds: Set<Long>,
    onOpen: (MeditationSession) -> Unit,
    onToggleSelect: (MeditationSession) -> Unit,
) {
    if (sessions.isEmpty()) {
        Text(
            "No sits yet. Your history will show up here.",
            modifier = Modifier.padding(24.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val selectionMode = selectedIds.isNotEmpty()
    // Newest day first; within a day, in the order you sat.
    val days = remember(sessions) {
        sessions.groupBy { it.localDate() }
            .toSortedMap(compareByDescending { it })
            .map { (day, list) -> day to list.sortedBy { it.startTime } }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        days.forEach { (day, list) ->
            stickyHeader(key = "h_$day") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(top = 12.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        formatDayHeader(day),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${list.size} sit${if (list.size == 1) "" else "s"} · ${formatHm(list.sumOf { it.completedMillis })}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(list, key = { it.id }) { session ->
                val selected = session.id in selectedIds
                HistoryItem(
                    session = session,
                    selectionMode = selectionMode,
                    selected = selected,
                    onClick = { if (selectionMode) onToggleSelect(session) else onOpen(session) },
                    onLongClick = { onToggleSelect(session) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryItem(
    session: MeditationSession,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                MaterialTheme.shapes.medium,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics {
                customActions = listOf(CustomAccessibilityAction("Select") { onLongClick(); true })
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = null)
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                "${formatClock(session.startDateTime())} – ${formatClock(session.endDateTime())}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp),
            ) {
                session.techniqueOrNull?.let { Tag(it.shortLabel, emphasized = true) }
                if (session.extended) Tag("Extended")
                if (session.isOpenEnded) Tag("Open")
                if (!session.completed && !session.isOpenEnded) Tag("Ended early")
            }
            if (session.notes.isNotBlank()) {
                Text(
                    session.notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(formatMinutesPrecise(session.completedMillis), style = MaterialTheme.typography.titleMedium)
            if (!session.isOpenEnded && session.durationMillis != session.completedMillis) {
                Text(
                    "of ${formatMinutesPrecise(session.durationMillis)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun formatSessionsForClipboard(sessions: List<MeditationSession>): String =
    sessions.sortedBy { it.startTime }.joinToString("\n") { s ->
        val parts = buildList {
            add("${formatLongDate(s.localDate())} ${formatClock(s.startDateTime())}–${formatClock(s.endDateTime())}")
            add(formatMinutesPrecise(s.completedMillis))
            s.techniqueOrNull?.let { add(it.shortLabel) }
        }
        val notes = s.notes.trim()
        "- " + parts.joinToString(" · ") + if (notes.isNotEmpty()) ": $notes" else ""
    }
