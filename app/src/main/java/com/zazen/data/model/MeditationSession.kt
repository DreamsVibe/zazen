package com.zazen.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class MeditationSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Epoch millis when the session started */
    val startTime: Long,
    /** Planned duration in millis */
    val durationMillis: Long,
    /** Actual elapsed meditation time in millis */
    val completedMillis: Long,
    /** Whether the planned time was reached (or an open-ended sit was stopped). */
    val completed: Boolean,
    /** Optional user notes / reflections */
    val notes: String = "",
    /** Post-sit rating, 1..5, or 0 when the sit wasn't rated. */
    val rating: Int = 0,
    /** [Technique] name, or "" when not tagged. */
    val technique: String = "",
    /** True when "Keep sitting" was used to extend past the planned time. */
    val extended: Boolean = false,
) {
    /** True when this was an open-ended sit (no planned duration). */
    val isOpenEnded: Boolean get() = durationMillis <= 0L

    val techniqueOrNull: Technique? get() = Technique.fromName(technique)
}
