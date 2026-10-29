package com.zazen.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.zazen.MainActivity
import com.zazen.R
import com.zazen.data.model.IntervalBell
import com.zazen.data.model.MeditationSession
import com.zazen.data.model.SitPhase
import com.zazen.data.model.TimerConfig
import com.zazen.data.model.TimerState
import com.zazen.data.repository.SessionRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class TimerService : Service() {

    @Inject lateinit var soundPlayer: SoundPlayer
    @Inject lateinit var sessionRepository: SessionRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    // Absolute-time tracking (immune to tick drift)
    private var startedAtRealtime = 0L
    private var pausedAtRealtime = 0L
    private var accumulatedPauseMillis = 0L
    private var totalDurationMillis = 0L

    /** Wall-clock start, used for the session's logged start time. */
    private var wallStartMillis = 0L

    private var bells = listOf<IntervalBell>()
    private var nextBellIndex = 0

    /** 0 disables repeating bells. */
    private var repeatEveryMillis = 0L
    private var nextRepeatAtMillis = 0L
    private var openEnded = false
    private var phase = SitPhase.MAIN

    /** Guards against the session being ended twice while teardown is in flight. */
    private var finishing = false

    /** Last notification content key; used to throttle re-posts to ~1 Hz. */
    private var lastNotifiedKey = ""

    /**
     * Keeps the CPU awake while a sit runs. Without it the 4 Hz tick stops once the
     * screen is off and the phone dozes, and bells ring late or not until it wakes.
     */
    private var wakeLock: PowerManager.WakeLock? = null

    private val tickRunnable = object : Runnable {
        override fun run() {
            tick()
            // tick() may have ended the session. Re-arming here would let a
            // finished timer fire again before the async teardown completes.
            if (!finishing) handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    companion object {
        const val ACTION_START = "com.zazen.START"
        const val ACTION_PAUSE = "com.zazen.PAUSE"
        const val ACTION_RESUME = "com.zazen.RESUME"
        const val ACTION_STOP = "com.zazen.STOP"
        const val EXTRA_CONFIG = "config"

        private const val TICK_INTERVAL_MS = 250L

        /**
         * How long past the planned end (or past now, for an open-ended sit) the wake
         * lock may be held. A safety net so a lost stop can't drain the battery.
         */
        private const val WAKE_LOCK_SLACK_MS = 4 * 60 * 60_000L

        /** Time past the end bell before a sit counts as extended. */
        private const val EXTENDED_THRESHOLD_MS = 60_000L
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "zazen_timer"
        private const val DND_PREFS = "zazen_dnd"

        private val _timerState = MutableStateFlow<TimerState>(TimerState.Idle)
        val timerState: StateFlow<TimerState> = _timerState.asStateFlow()

        private val _vibrateOnly = MutableStateFlow(false)
        val vibrateOnly: StateFlow<Boolean> = _vibrateOnly.asStateFlow()

        var currentConfig: TimerConfig? = null
            private set

        fun toggleVibrateOnly() {
            _vibrateOnly.value = !_vibrateOnly.value
        }

        fun resetState() {
            _timerState.value = TimerState.Idle
            _vibrateOnly.value = false
            currentConfig = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                @Suppress("DEPRECATION")
                val config = intent.getParcelableExtra<TimerConfig>(EXTRA_CONFIG)
                if (config != null) startTimer(config)
            }
            ACTION_PAUSE -> pauseTimer()
            ACTION_RESUME -> resumeTimer()
            ACTION_STOP -> stopTimer()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        releaseWakeLock()
        restoreDnd()
        serviceScope.cancel()
        super.onDestroy()
    }

    // --- Timer logic ---

    private fun startTimer(config: TimerConfig) {
        currentConfig = config
        _vibrateOnly.value = config.vibrateOnly
        totalDurationMillis = config.durationMillis
        openEnded = config.isOpenEnded
        bells = config.bells.sortedBy { it.triggerAtMillis }
        nextBellIndex = 0
        repeatEveryMillis = config.repeatEveryMillis.coerceAtLeast(0)
        // First repeat lands one full interval in, not at t=0
        nextRepeatAtMillis = repeatEveryMillis
        accumulatedPauseMillis = 0
        lastNotifiedKey = ""
        finishing = false
        // A start delay pushes the sit's zero point into the future, so elapsed time
        // runs negative while it counts down and nothing else needs to know about it.
        val startDelay = config.startDelayMillis.coerceAtLeast(0)
        phase = if (startDelay > 0) SitPhase.PREPARING else SitPhase.MAIN
        startedAtRealtime = SystemClock.elapsedRealtime() + startDelay
        wallStartMillis = System.currentTimeMillis() + startDelay

        // Preload all bell sounds + the end sound
        bells.forEach { soundPlayer.preload(it.soundResId) }
        soundPlayer.preload(config.endSoundResId)
        if (repeatEveryMillis > 0) soundPlayer.preload(config.repeatSoundResId)

        if (config.dndEnabled) enableDnd()

        val elapsedNow = computeElapsedMillis()
        startForeground(NOTIFICATION_ID, buildNotification(notificationText(elapsedNow)))

        if (phase == SitPhase.MAIN) playStartSound()

        acquireWakeLock()
        handler.post(tickRunnable)
        publishRunning(elapsedNow)
    }

    private fun pauseTimer() {
        // The start delay is only seconds long; it can be cancelled but not paused.
        if (_timerState.value !is TimerState.Running || phase == SitPhase.PREPARING) return
        val elapsedNow = computeElapsedMillis()
        // Ring the end bell first if it's due, so the paused sit shows as counting up.
        checkPlannedEnd(elapsedNow)
        pausedAtRealtime = SystemClock.elapsedRealtime()
        handler.removeCallbacks(tickRunnable)
        releaseWakeLock()
        _timerState.value = TimerState.Paused(
            remainingMillis = if (openEnded) 0 else computeRemainingMillis(),
            totalMillis = if (openEnded) 0 else totalDurationMillis,
            elapsedMillis = elapsedNow,
            phase = phase,
        )
        updateNotification(notificationText(elapsedNow, paused = true))
    }

    private fun resumeTimer() {
        if (_timerState.value !is TimerState.Paused) return
        accumulatedPauseMillis += SystemClock.elapsedRealtime() - pausedAtRealtime
        acquireWakeLock()
        handler.post(tickRunnable)
        publishRunning(computeElapsedMillis())
    }

    private fun stopTimer() {
        if (finishing || _timerState.value !is TimerState.Running && _timerState.value !is TimerState.Paused) return
        finishing = true
        handler.removeCallbacks(tickRunnable)
        releaseWakeLock()
        // Stopped during the start delay: the sit never began, so there's nothing to log.
        if (phase == SitPhase.PREPARING) {
            _timerState.value = TimerState.Idle
            currentConfig = null
            restoreDnd()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        // Stopping while paused: the in-progress pause hasn't been folded into
        // accumulatedPauseMillis yet, so settle it first or the time spent
        // paused gets logged as sitting time.
        if (_timerState.value is TimerState.Paused) {
            accumulatedPauseMillis += SystemClock.elapsedRealtime() - pausedAtRealtime
        }
        val elapsed = computeElapsedMillis()
        val wasOpenEnded = openEnded

        // Work out what gets logged and whether the closing bell rings now.
        val loggedMillis: Long
        val completed: Boolean
        val extended: Boolean
        when {
            // Stopping *is* how an open-ended sit ends — ring and log all of it.
            wasOpenEnded -> {
                playEndSound()
                loggedMillis = elapsed
                completed = true
                extended = false
            }
            // Past the planned end: the clock has been counting up since the end bell,
            // and the whole sit is logged. The bell already rang, unless it's due right
            // now because the tick hadn't caught up yet.
            phase == SitPhase.EXTENDED || elapsed >= totalDurationMillis -> {
                if (phase == SitPhase.MAIN) playEndSound()
                loggedMillis = elapsed
                completed = true
                extended = elapsed - totalDurationMillis >= EXTENDED_THRESHOLD_MS
            }
            // Ended early.
            else -> {
                loggedMillis = elapsed
                completed = false
                extended = false
            }
        }

        serviceScope.launch {
            val sessionId = withContext(NonCancellable) {
                saveSession(loggedMillis, completed = completed, extended = extended)
            }
            _timerState.value = TimerState.Finished(
                sessionId = sessionId,
                completed = completed,
                elapsedMillis = loggedMillis,
                openEnded = wasOpenEnded,
                extended = extended,
                plannedMillis = if (wasOpenEnded) 0 else totalDurationMillis,
            )
            currentConfig = null
            restoreDnd()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun tick() {
        val elapsed = computeElapsedMillis()

        if (phase == SitPhase.PREPARING) {
            if (elapsed < 0) {
                publishRunning(elapsed)
                updateNotification(notificationText(elapsed))
                return
            }
            // The delay is over: the sit begins now. Pin the logged start time to the
            // moment it actually began, in case the tick ran late.
            phase = SitPhase.MAIN
            wallStartMillis = System.currentTimeMillis() - elapsed
            playStartSound()
        }

        val volume = currentConfig?.bellVolume ?: 1f

        // Fire any one-shot bells whose trigger time has passed
        while (nextBellIndex < bells.size && bells[nextBellIndex].triggerAtMillis <= elapsed) {
            val bell = bells[nextBellIndex]
            val useVibrate = bell.vibrateOnly || _vibrateOnly.value
            if (useVibrate) soundPlayer.vibrate() else soundPlayer.playFull(bell.soundResId, volume)
            nextBellIndex++
        }

        // Fire repeating bells. If the device slept through several intervals we
        // ring once and fast-forward to the next boundary, rather than firing a
        // burst of overlapping bells for every interval that was missed.
        if (repeatEveryMillis > 0 && nextRepeatAtMillis <= elapsed) {
            val collidesWithEnd = !openEnded && nextRepeatAtMillis == totalDurationMillis
            if (!collidesWithEnd) {
                if (_vibrateOnly.value) {
                    soundPlayer.vibrate()
                } else {
                    soundPlayer.playFull(currentConfig?.repeatSoundResId ?: R.raw.bell, volume)
                }
            }
            val intervalsMissed = (elapsed - nextRepeatAtMillis) / repeatEveryMillis + 1
            nextRepeatAtMillis += intervalsMissed * repeatEveryMillis
        }

        checkPlannedEnd(elapsed)
        publishRunning(elapsed)
        updateNotification(notificationText(elapsed))
    }

    /** Rings the planned end bell and switches the clock to counting up the total. */
    private fun checkPlannedEnd(elapsedMillis: Long) {
        if (!openEnded && phase == SitPhase.MAIN && elapsedMillis >= totalDurationMillis) {
            phase = SitPhase.EXTENDED
            playEndSound()
        }
    }

    private fun publishRunning(elapsedMillis: Long) {
        val sitElapsed = elapsedMillis.coerceAtLeast(0)
        _timerState.value = TimerState.Running(
            remainingMillis = if (openEnded) 0 else (totalDurationMillis - sitElapsed).coerceAtLeast(0),
            totalMillis = if (openEnded) 0 else totalDurationMillis,
            elapsedMillis = sitElapsed,
            phase = phase,
            startsInMillis = if (phase == SitPhase.PREPARING) -elapsedMillis else 0L,
        )
    }

    private fun playStartSound() {
        val config = currentConfig ?: return
        if (config.startSoundResId == 0) return
        if (_vibrateOnly.value) soundPlayer.vibrate()
        else soundPlayer.playFull(config.startSoundResId, config.bellVolume)
    }

    private fun playEndSound() {
        val config = currentConfig
        if (_vibrateOnly.value) {
            soundPlayer.vibrate(strong = true)
        } else {
            soundPlayer.playFull(config?.endSoundResId ?: R.raw.bell, config?.bellVolume ?: 1f)
        }
    }

    private fun acquireWakeLock() {
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zazen:timer")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        // Includes any start delay still to run, since elapsed is negative then.
        val untilPlannedEnd = if (openEnded) 0L else (totalDurationMillis - computeElapsedMillis()).coerceAtLeast(0)
        lock.acquire(untilPlannedEnd + WAKE_LOCK_SLACK_MS)
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    private fun computeElapsedMillis(): Long =
        SystemClock.elapsedRealtime() - startedAtRealtime - accumulatedPauseMillis

    private fun computeRemainingMillis(): Long =
        (totalDurationMillis - computeElapsedMillis()).coerceAtLeast(0)

    private suspend fun saveSession(loggedMillis: Long, completed: Boolean, extended: Boolean): Long {
        return sessionRepository.logSession(
            MeditationSession(
                startTime = wallStartMillis,
                durationMillis = totalDurationMillis,
                completedMillis = loggedMillis,
                completed = completed,
                extended = extended,
            )
        )
    }

    // --- Do Not Disturb ---

    private fun enableDnd() {
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return
        val previousFilter = nm.currentInterruptionFilter
        getSharedPreferences(DND_PREFS, Context.MODE_PRIVATE).edit()
            .putInt("prev_filter", previousFilter)
            .putBoolean("dnd_active", true)
            .apply()
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
    }

    private fun restoreDnd() {
        val prefs = getSharedPreferences(DND_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("dnd_active", false)) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.isNotificationPolicyAccessGranted) {
            val filter = prefs.getInt("prev_filter", NotificationManager.INTERRUPTION_FILTER_ALL)
            nm.setInterruptionFilter(filter)
        }
        prefs.edit().putBoolean("dnd_active", false).apply()
    }

    // --- Notifications ---

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.notification_channel_description) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notificationText(elapsedMillis: Long, paused: Boolean = false): String {
        val base = when {
            // Round up so a 10 s delay starts at "00:10", not "00:09".
            phase == SitPhase.PREPARING -> "Starting in ${formatTime((-elapsedMillis + 999) / 1000 * 1000)}"
            openEnded -> getString(R.string.notification_text, formatTime(elapsedMillis))
            phase == SitPhase.EXTENDED ->
                "${formatTime(elapsedMillis)} total · +${formatTime(elapsedMillis - totalDurationMillis)} past the bell"
            else -> getString(
                R.string.notification_text,
                formatTime((totalDurationMillis - elapsedMillis).coerceAtLeast(0)),
            )
        }
        return if (paused) "Paused · $base" else base
    }

    private fun servicePendingIntent(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, TimerService::class.java).apply { this.action = action },
            PendingIntent.FLAG_IMMUTABLE,
        )

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)

        when (phase) {
            SitPhase.PREPARING -> {
                builder.addAction(0, "Cancel", servicePendingIntent(2, ACTION_STOP))
            }
            SitPhase.EXTENDED -> {
                builder.addAction(0, getString(R.string.timer_cd_pause), servicePendingIntent(1, ACTION_PAUSE))
                builder.addAction(0, "Finish", servicePendingIntent(2, ACTION_STOP))
            }
            SitPhase.MAIN -> {
                builder.addAction(0, getString(R.string.timer_cd_pause), servicePendingIntent(1, ACTION_PAUSE))
                builder.addAction(0, getString(R.string.timer_cd_stop), servicePendingIntent(2, ACTION_STOP))
            }
        }
        return builder.build()
    }

    /**
     * The timer ticks four times a second, but the notification only shows whole
     * seconds. Re-posting on every tick just floods the notification manager (and
     * the accessibility event stream), so only re-post when the text changes.
     */
    private fun updateNotification(text: String) {
        val key = "$phase|$text"
        if (key == lastNotifiedKey) return
        lastNotifiedKey = key
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = millis.coerceAtLeast(0) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%02d:%02d".format(minutes, seconds)
    }
}
