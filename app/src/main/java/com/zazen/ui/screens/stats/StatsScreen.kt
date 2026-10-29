package com.zazen.ui.screens.stats

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zazen.R
import com.zazen.data.model.MeditationSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MILESTONES_TAB = "Milestones"
private const val HISTORY_TAB = "History"
private val TABS = listOf("Overview", "Calendar", "Trends", MILESTONES_TAB, HISTORY_TAB)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val sessions by viewModel.sessions.collectAsState()
    val snapshot by viewModel.snapshot.collectAsState()
    val graceDays by viewModel.graceDays.collectAsState()
    val hideStreaks by viewModel.hideStreaks.collectAsState()
    val tabs = if (hideStreaks) TABS - MILESTONES_TAB else TABS

    var selectedTab by rememberSaveable { mutableStateOf(TABS.first()) }
    // Falls back to Overview if the open tab has just been hidden.
    val tab = selectedTab.takeIf { it in tabs } ?: tabs.first()
    var sessionToEdit by remember { mutableStateOf<MeditationSession?>(null) }
    var sessionToDelete by remember { mutableStateOf<MeditationSession?>(null) }
    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    val selectionMode = selectedIds.isNotEmpty() && tab == HISTORY_TAB

    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let { viewModel.exportBackup(it) } }

    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> uri?.let { viewModel.exportCsv(it) } }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { pendingImportUri = it } }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            val text = when (message) {
                is StatsMessage.BackupSaved -> context.getString(R.string.backup_saved)
                is StatsMessage.CsvSaved -> "Session history exported as CSV"
                is StatsMessage.BackupFailed -> context.getString(R.string.backup_failed, message.reason)
                is StatsMessage.RestoreFailed -> context.getString(R.string.restore_failed, message.reason)
                is StatsMessage.Restored -> context.getString(
                    R.string.restore_succeeded,
                    context.resources.getQuantityString(R.plurals.preset_count, message.presetCount, message.presetCount),
                    context.resources.getQuantityString(R.plurals.session_count, message.sessionCount, message.sessionCount),
                )
            }
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }

    val barColors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    colors = barColors,
                    title = { Text("${selectedIds.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(Icons.Default.Close, stringResource(R.string.stats_cd_cancel_selection))
                        }
                    },
                    actions = {
                        IconButton(onClick = { selectedIds = sessions.map { it.id }.toSet() }) {
                            Icon(Icons.Default.DoneAll, stringResource(R.string.stats_cd_select_all))
                        }
                        IconButton(onClick = {
                            val text = formatSessionsForClipboard(sessions.filter { it.id in selectedIds })
                            clipboard.setText(AnnotatedString(text))
                            val n = selectedIds.size
                            Toast.makeText(
                                context,
                                context.resources.getQuantityString(R.plurals.sessions_copied, n, n),
                                Toast.LENGTH_SHORT,
                            ).show()
                            selectedIds = emptySet()
                        }) {
                            Icon(Icons.Default.ContentCopy, stringResource(R.string.stats_cd_copy))
                        }
                    },
                )
            } else {
                TopAppBar(
                    colors = barColors,
                    title = { Text("Your practice", style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.stats_cd_back))
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, "Export and backup")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Export history (CSV)") },
                                    onClick = {
                                        menuOpen = false
                                        csvLauncher.launch(fileName("zazen_sessions", "csv"))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Back up everything (JSON)") },
                                    onClick = {
                                        menuOpen = false
                                        backupLauncher.launch(fileName("zazen_backup", "json"))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Restore from backup…") },
                                    onClick = {
                                        menuOpen = false
                                        importLauncher.launch(arrayOf("application/json"))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Hide streaks & milestones") },
                                    trailingIcon = { Checkbox(checked = hideStreaks, onCheckedChange = null) },
                                    onClick = { viewModel.setHideStreaks(!hideStreaks) },
                                )
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ScrollableTabRow(
                selectedTabIndex = tabs.indexOf(tab),
                edgePadding = 12.dp,
                containerColor = MaterialTheme.colorScheme.background,
                divider = {},
            ) {
                tabs.forEach { title ->
                    Tab(
                        selected = tab == title,
                        onClick = {
                            selectedTab = title
                            if (title != HISTORY_TAB) selectedIds = emptySet()
                        },
                        text = { Text(title, style = MaterialTheme.typography.titleSmall) },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    "Overview" -> OverviewTab(
                        snapshot,
                        graceDays,
                        onGraceDaysChange = viewModel::setGraceDays,
                        showStreak = !hideStreaks,
                    )
                    "Calendar" -> CalendarTab(snapshot, sessions, onSessionClick = { sessionToEdit = it })
                    "Trends" -> TrendsTab(snapshot)
                    MILESTONES_TAB -> MilestonesTab(snapshot)
                    else -> HistoryTab(
                        sessions = sessions,
                        selectedIds = selectedIds,
                        onOpen = { sessionToEdit = it },
                        onToggleSelect = { s ->
                            selectedIds = if (s.id in selectedIds) selectedIds - s.id else selectedIds + s.id
                        },
                    )
                }
            }
        }
    }

    sessionToEdit?.let { session ->
        ReviewEditorDialog(
            session = session,
            onDismiss = { sessionToEdit = null },
            onSave = { technique, notes ->
                viewModel.updateReview(session.id, technique, notes)
                sessionToEdit = null
            },
            onDelete = {
                sessionToEdit = null
                sessionToDelete = session
            },
        )
    }

    sessionToDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text(stringResource(R.string.session_delete_title)) },
            text = {
                Text("Delete the ${formatMinutesPrecise(session.completedMillis)} sit from ${formatDayHeader(session.localDate())}? This can't be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSession(session)
                    sessionToDelete = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    pendingImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text(stringResource(R.string.restore_title)) },
            text = { Text(stringResource(R.string.restore_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.importBackup(uri)
                    pendingImportUri = null
                }) { Text(stringResource(R.string.action_restore)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportUri = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

private fun fileName(prefix: String, ext: String): String {
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    return "${prefix}_$timestamp.$ext"
}
