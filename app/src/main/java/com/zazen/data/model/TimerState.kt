package com.zazen.data.model

/** Where a sit is relative to its start delay and planned end. Open-ended sits never reach [EXTENDED]. */
enum class SitPhase {
    /** The start delay is counting down; the sit (and its clock) hasn't begun. */
    PREPARING,

    /** Before the planned end (or any point of an open-ended sit). */
    MAIN,

    /** The planned end bell has rung — counting up the total time until the sit is finished. */
    EXTENDED,
}

sealed interface TimerState {
    data object Idle : TimerState

    data class Running(
        /** Always 0 for an open-ended sit — read [elapsedMillis] instead. */
        val remainingMillis: Long,
        /** Always 0 for an open-ended sit. */
        val totalMillis: Long,
        val elapsedMillis: Long = 0,
        val phase: SitPhase = SitPhase.MAIN,
        /** While [SitPhase.PREPARING]: millis until the sit begins. */
        val startsInMillis: Long = 0,
    ) : TimerState {
        val isOpenEnded: Boolean get() = totalMillis <= 0L
    }

    data class Paused(
        val remainingMillis: Long,
        val totalMillis: Long,
        val elapsedMillis: Long = 0,
        val phase: SitPhase = SitPhase.MAIN,
    ) : TimerState {
        val isOpenEnded: Boolean get() = totalMillis <= 0L
    }

    data class Finished(
        val sessionId: Long,
        val completed: Boolean,
        val elapsedMillis: Long = 0,
        val openEnded: Boolean = false,
        val extended: Boolean = false,
        /** The planned length, or 0 for an open-ended sit. */
        val plannedMillis: Long = 0,
    ) : TimerState
}
