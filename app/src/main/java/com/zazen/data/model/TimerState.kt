package com.zazen.data.model

/** Where a timed sit is relative to its planned end. Open-ended sits stay in [MAIN]. */
enum class SitPhase {
    /** Before the planned end (or any point of an open-ended sit). */
    MAIN,

    /** The planned end bell has rung; waiting for "Keep sitting" or "Finish". */
    AWAITING_EXTEND,

    /** "Keep sitting" was tapped — counting up until the sit is finished. */
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
        /** While [SitPhase.AWAITING_EXTEND]: millis until the sit auto-finishes. */
        val autoFinishInMillis: Long = 0,
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
