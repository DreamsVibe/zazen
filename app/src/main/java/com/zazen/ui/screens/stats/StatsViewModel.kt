package com.zazen.ui.screens.stats

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zazen.data.model.MeditationSession
import com.zazen.data.model.Technique
import com.zazen.data.repository.BackupRepository
import com.zazen.data.repository.PreferencesRepository
import com.zazen.data.repository.SessionRepository
import com.zazen.data.stats.SitRecord
import com.zazen.data.stats.StatsCalculator
import com.zazen.data.stats.StatsSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val backupRepository: BackupRepository,
    private val prefs: PreferencesRepository,
) : ViewModel() {

    val sessions: StateFlow<List<MeditationSession>> = sessionRepository.getAllSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val graceDays: StateFlow<Int> = prefs.graceDaysFlow
    val hideStreaks: StateFlow<Boolean> = prefs.hideStreaksFlow

    /** Everything the stats tabs draw, recomputed whenever the log or grace setting changes. */
    val snapshot: StateFlow<StatsSnapshot> =
        combine(sessionRepository.getAllSessions(), prefs.graceDaysFlow) { list, grace ->
            StatsCalculator.compute(
                sits = list.map { it.toRecord() },
                today = LocalDate.now(),
                zone = ZoneId.systemDefault(),
                graceDays = grace,
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsSnapshot.EMPTY)

    private val _messages = MutableSharedFlow<StatsMessage>()
    val messages = _messages.asSharedFlow()

    fun setGraceDays(days: Int) {
        prefs.graceDays = days
    }

    fun setHideStreaks(hide: Boolean) {
        prefs.hideStreaks = hide
    }

    fun deleteSession(session: MeditationSession) {
        viewModelScope.launch { sessionRepository.deleteSession(session.id) }
    }

    fun updateReview(sessionId: Long, technique: Technique?, notes: String) {
        viewModelScope.launch {
            sessionRepository.updateReview(sessionId, technique?.name.orEmpty(), notes.trim())
        }
    }

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            try {
                backupRepository.exportToUri(uri)
                _messages.emit(StatsMessage.BackupSaved)
            } catch (e: Exception) {
                _messages.emit(StatsMessage.BackupFailed(e.message.orEmpty()))
            }
        }
    }

    fun exportCsv(uri: Uri) {
        viewModelScope.launch {
            try {
                backupRepository.exportCsvToUri(uri)
                _messages.emit(StatsMessage.CsvSaved)
            } catch (e: Exception) {
                _messages.emit(StatsMessage.BackupFailed(e.message.orEmpty()))
            }
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            try {
                val result = backupRepository.importFromUri(uri)
                _messages.emit(StatsMessage.Restored(result.presetCount, result.sessionCount))
            } catch (e: Exception) {
                _messages.emit(StatsMessage.RestoreFailed(e.message.orEmpty()))
            }
        }
    }
}

private fun MeditationSession.toRecord() = SitRecord(
    id = id,
    startMillis = startTime,
    sitMillis = completedMillis,
    technique = technique,
)

/**
 * User-facing messages kept as data rather than pre-formatted strings so the UI
 * layer can resolve them against string resources (and plurals).
 */
sealed interface StatsMessage {
    data object BackupSaved : StatsMessage
    data object CsvSaved : StatsMessage
    data class BackupFailed(val reason: String) : StatsMessage
    data class RestoreFailed(val reason: String) : StatsMessage
    data class Restored(val presetCount: Int, val sessionCount: Int) : StatsMessage
}
