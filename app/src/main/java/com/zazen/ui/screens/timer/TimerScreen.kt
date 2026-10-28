package com.zazen.ui.screens.timer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zazen.R
import com.zazen.data.model.SitPhase
import com.zazen.data.model.Technique
import com.zazen.data.model.TimerState
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun TimerScreen(
    onTimerDone: () -> Unit,
    viewModel: TimerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val vibrateOnly by viewModel.vibrateOnly.collectAsState()

    // Pre-compute bell positions as fractions of total duration (0..1).
    // Keyed on the config because the service populates it asynchronously.
    val cfg = viewModel.config
    val bellFractions = remember(cfg) {
        if (cfg != null && cfg.durationMillis > 0) {
            cfg.bells.map { it.triggerAtMillis.toFloat() / cfg.durationMillis }
                .filter { it in 0f..1f }
        } else emptyList()
    }

    val isActive = state is TimerState.Running || state is TimerState.Paused

    // For open-ended sits the progress ring can't convey "time left", so surface
    // the next repeating bell instead.
    val repeatEveryMillis = cfg?.repeatEveryMillis ?: 0L

    val view = LocalView.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val keepScreenOn = viewModel.screenAlwaysOn

    // Combined window-state effect: enter immersive mode FIRST, then apply the
    // FLAG_KEEP_SCREEN_ON flag. Doing it in this order matters because the OS
    // can re-apply window attributes when system bar visibility changes, which
    // may clear FLAG_KEEP_SCREEN_ON if it was set first. We also re-apply the
    // flag on every ON_RESUME as a defensive measure.
    DisposableEffect(isActive, keepScreenOn) {
        val activity = context.findActivity()
        val window = activity?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, view) }

        fun applyKeepScreenOn() {
            if (window == null) return
            if (isActive && keepScreenOn) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        val touchExplorationOn = (context.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as? AccessibilityManager)?.isTouchExplorationEnabled == true
        if (isActive && !touchExplorationOn) {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            view.requestApplyInsets()
        }

        applyKeepScreenOn()

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) applyKeepScreenOn()
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller?.show(WindowInsetsCompat.Type.systemBars())
            view.requestApplyInsets()
        }
    }

    // Warn if alarm volume is muted or low and we're not in vibrate-only mode.
    LaunchedEffect(isActive) {
        if (isActive && !vibrateOnly) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val vol = am?.getStreamVolume(AudioManager.STREAM_ALARM) ?: -1
            val maxVol = am?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 0
            val pct = if (maxVol > 0) vol.toFloat() / maxVol else 1f
            val msg = when {
                vol == 0 -> context.getString(R.string.timer_toast_volume_muted)
                pct < 0.2f -> context.getString(R.string.timer_toast_volume_low)
                else -> null
            }
            if (msg != null) Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    Scaffold { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            when (val s = state) {
                is TimerState.Running -> ActiveTimerContent(
                    remaining = s.remainingMillis,
                    total = s.totalMillis,
                    elapsed = s.elapsedMillis,
                    openEnded = s.isOpenEnded,
                    phase = s.phase,
                    autoFinishIn = s.autoFinishInMillis,
                    repeatEveryMillis = repeatEveryMillis,
                    isPaused = false,
                    bellFractions = bellFractions,
                    vibrateOnly = vibrateOnly,
                    onPause = { viewModel.pause() },
                    onResume = {},
                    onStop = { viewModel.stop() },
                    onExtend = { viewModel.extend() },
                    onToggleVibrateOnly = { viewModel.toggleVibrateOnly() },
                )

                is TimerState.Paused -> ActiveTimerContent(
                    remaining = s.remainingMillis,
                    total = s.totalMillis,
                    elapsed = s.elapsedMillis,
                    openEnded = s.isOpenEnded,
                    phase = s.phase,
                    autoFinishIn = 0L,
                    repeatEveryMillis = repeatEveryMillis,
                    isPaused = true,
                    bellFractions = bellFractions,
                    vibrateOnly = vibrateOnly,
                    onPause = {},
                    onResume = { viewModel.resume() },
                    onStop = { viewModel.stop() },
                    onExtend = {},
                    onToggleVibrateOnly = { viewModel.toggleVibrateOnly() },
                )

                is TimerState.Finished -> ReviewContent(
                    sessionId = s.sessionId,
                    completed = s.completed,
                    openEnded = s.openEnded,
                    extended = s.extended,
                    elapsedMillis = s.elapsedMillis,
                    plannedMillis = s.plannedMillis,
                    onSave = { id, rating, technique, notes, onError ->
                        viewModel.saveReviewAndDismiss(id, rating, technique, notes, onTimerDone, onError)
                    },
                    onDiscard = { id -> viewModel.discardAndDismiss(id, onTimerDone) },
                )

                is TimerState.Idle -> {
                    LaunchedEffect(Unit) { onTimerDone() }
                }
            }
        }
    }
}

@Composable
private fun ActiveTimerContent(
    remaining: Long,
    total: Long,
    elapsed: Long,
    openEnded: Boolean,
    phase: SitPhase,
    autoFinishIn: Long,
    repeatEveryMillis: Long,
    isPaused: Boolean,
    bellFractions: List<Float>,
    vibrateOnly: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onExtend: () -> Unit,
    onToggleVibrateOnly: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Box(modifier = Modifier.fillMaxSize()) {
        IconButton(
            onClick = onToggleVibrateOnly,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
        ) {
            Icon(
                imageVector = if (vibrateOnly) Icons.AutoMirrored.Filled.VolumeOff
                else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = if (vibrateOnly) stringResource(R.string.timer_cd_sound_off)
                else stringResource(R.string.timer_cd_sound_on),
                tint = muted.copy(alpha = 0.7f),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Small caption above the ring says what kind of moment this is.
            Text(
                text = when {
                    isPaused -> "PAUSED"
                    phase == SitPhase.AWAITING_EXTEND -> "SIT COMPLETE"
                    phase == SitPhase.EXTENDED -> "KEEP SITTING"
                    openEnded -> "OPEN SIT"
                    else -> "REMAINING"
                },
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 3.sp),
                color = if (phase == SitPhase.MAIN) muted else MaterialTheme.colorScheme.primary,
                modifier = if (isPaused) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
            )
            Spacer(Modifier.height(20.dp))

            Box(contentAlignment = Alignment.Center) {
                val ringProgress = when {
                    openEnded -> 0f
                    phase != SitPhase.MAIN -> 1f
                    total > 0 -> remaining.toFloat() / total
                    else -> 0f
                }
                TimerRing(
                    progress = ringProgress,
                    bellFractions = if (openEnded || phase != SitPhase.MAIN) emptyList() else bellFractions,
                    // A slow orbiting dot (one lap a minute) shows an open-ended or
                    // extended sit is alive. Driven by the 4 Hz tick, not an animation,
                    // so a long sit doesn't redraw 60 times a second.
                    orbitFraction = if (openEnded || phase == SitPhase.EXTENDED) (elapsed % 60_000L) / 60_000f else null,
                    complete = phase != SitPhase.MAIN,
                    modifier = Modifier
                        // Shrinks while the Keep sitting button is up so both fit on small phones.
                        .size(if (phase == SitPhase.AWAITING_EXTEND) 220.dp else 288.dp)
                        .clearAndSetSemantics { },
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val main = when {
                        openEnded -> elapsed
                        phase == SitPhase.MAIN -> remaining
                        phase == SitPhase.AWAITING_EXTEND -> total
                        else -> elapsed
                    }
                    Text(
                        text = formatTime(main),
                        style = if (phase == SitPhase.AWAITING_EXTEND || main >= 3_600_000L) {
                            MaterialTheme.typography.displayMedium
                        } else {
                            MaterialTheme.typography.displayLarge
                        },
                        textAlign = TextAlign.Center,
                        color = if (phase == SitPhase.AWAITING_EXTEND) muted else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                    if (phase == SitPhase.EXTENDED) {
                        Text(
                            "+${formatTime(elapsed - total)} beyond plan",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clearAndSetSemantics { },
                        )
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            if (openEnded && repeatEveryMillis > 0 && !isPaused) {
                val untilNext = repeatEveryMillis - (elapsed % repeatEveryMillis)
                Text(
                    stringResource(R.string.timer_next_bell_in, formatTime(untilNext)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                    modifier = Modifier.clearAndSetSemantics { },
                )
                Spacer(Modifier.height(20.dp))
            }

            when {
                phase == SitPhase.AWAITING_EXTEND && !isPaused -> {
                    KeepSittingButton(onClick = onExtend)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onStop) {
                        Text("Finish here", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        "Logs ${formatTime(total)} · finishes on its own in ${formatTime(autoFinishIn)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                        textAlign = TextAlign.Center,
                    )
                }

                phase == SitPhase.EXTENDED -> {
                    Button(
                        onClick = onStop,
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .height(72.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    ) {
                        Text("Finish sit", style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(Modifier.height(16.dp))
                    PauseResumeButton(isPaused, onPause, onResume)
                }

                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        PauseResumeButton(isPaused, onPause, onResume)
                        OutlinedIconButton(
                            onClick = onStop,
                            modifier = Modifier.size(64.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                stringResource(R.string.timer_cd_stop),
                                modifier = Modifier.size(28.dp),
                                tint = muted,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PauseResumeButton(isPaused: Boolean, onPause: () -> Unit, onResume: () -> Unit) {
    OutlinedIconButton(
        onClick = if (isPaused) onResume else onPause,
        modifier = Modifier.size(64.dp),
        border = BorderStroke(
            1.dp,
            if (isPaused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Icon(
            if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
            if (isPaused) stringResource(R.string.timer_cd_resume) else stringResource(R.string.timer_cd_pause),
            modifier = Modifier.size(28.dp),
            tint = if (isPaused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The big round "Keep sitting" button that appears when the planned time is up. */
@Composable
private fun KeepSittingButton(onClick: () -> Unit) {
    val breathe = rememberInfiniteTransition(label = "breathe")
    val scale = breathe.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breatheScale",
    )
    Button(
        onClick = onClick,
        modifier = Modifier
            .size(176.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .semantics { contentDescription = "Keep sitting. Extends this sit until you tap Finish." },
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("∞", fontSize = 40.sp, fontWeight = FontWeight.Light)
            Text("Keep sitting", style = MaterialTheme.typography.titleMedium)
        }
    }
}

// --- Post-sit review ---

private val RATING_WORDS = listOf("Restless", "Scattered", "Steady", "Focused", "Deep")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewContent(
    sessionId: Long,
    completed: Boolean,
    openEnded: Boolean,
    extended: Boolean,
    elapsedMillis: Long,
    plannedMillis: Long,
    onSave: (Long, Int, Technique?, String, () -> Unit) -> Unit,
    onDiscard: (Long) -> Unit,
) {
    var notes by rememberSaveable { mutableStateOf("") }
    var rating by rememberSaveable { mutableIntStateOf(0) }
    var techniqueName by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val hasValidId = sessionId > 0
    val technique = Technique.fromName(techniqueName)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            when {
                extended -> "EXTENDED SIT"
                openEnded -> "OPEN SIT"
                completed -> "SIT COMPLETE"
                else -> "SIT ENDED EARLY"
            },
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 3.sp),
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            formatDurationWords(elapsedMillis),
            style = MaterialTheme.typography.displayMedium,
            textAlign = TextAlign.Center,
        )
        if (extended && plannedMillis > 0) {
            Text(
                "Planned ${formatDurationWords(plannedMillis)} · +${formatDurationWords(elapsedMillis - plannedMillis)} extra",
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
            )
        }

        Spacer(Modifier.height(28.dp))

        // Rating
        SectionLabel("How was it?")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (1..5).forEach { n ->
                IconButton(
                    onClick = { rating = if (rating == n) 0 else n },
                    modifier = Modifier
                        .size(52.dp)
                        .semantics { contentDescription = "Rate $n of 5, ${RATING_WORDS[n - 1]}" },
                ) {
                    Icon(
                        if (n <= rating) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = if (n <= rating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
        Text(
            if (rating == 0) "Tap to rate" else RATING_WORDS[rating - 1],
            style = MaterialTheme.typography.bodyMedium,
            color = if (rating == 0) muted else MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(24.dp))

        // Technique
        SectionLabel("What did you practise?")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Technique.entries.forEach { t ->
                FilterChip(
                    selected = technique == t,
                    onClick = { techniqueName = if (technique == t) "" else t.name },
                    label = { Text(t.shortLabel, style = MaterialTheme.typography.titleSmall) },
                    modifier = Modifier.height(40.dp),
                )
            }
        }
        if (technique == Technique.VIPASSANA) {
            Spacer(Modifier.height(4.dp))
            Text("Includes ānāpāna", style = MaterialTheme.typography.bodySmall, color = muted)
        }

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text("Notes") },
            placeholder = { Text("Anything worth remembering about this sit…") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 6,
            enabled = !saving,
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                saving = true
                onSave(sessionId, rating, technique, notes) { saving = false }
            },
            enabled = !saving && hasValidId,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = CircleShape,
        ) {
            Text(
                when {
                    saving -> stringResource(R.string.action_saving)
                    rating == 0 && technique == null && notes.isBlank() -> "Save without review"
                    else -> "Save sit"
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { onDiscard(sessionId) }, enabled = !saving && hasValidId) {
            Text(stringResource(R.string.finished_discard), color = muted)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// --- Ring ---

@Composable
private fun TimerRing(
    progress: Float,
    modifier: Modifier = Modifier,
    bellFractions: List<Float> = emptyList(),
    orbitFraction: Float? = null,
    complete: Boolean = false,
    strokeWidth: Dp = 5.dp,
) {
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.primary
    val bellColor = MaterialTheme.colorScheme.tertiary

    Canvas(modifier = modifier) {
        val stroke = strokeWidth.toPx()
        val glow = stroke * 4f
        val diameter = size.minDimension - glow
        val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
        val arcSize = Size(diameter, diameter)
        val cx = size.width / 2
        val cy = size.height / 2
        val radius = diameter / 2

        // Hairline track
        drawArc(
            color = trackColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke * 0.4f),
        )

        if (progress > 0f) {
            val sweep = 360f * progress
            // Soft glow under the arc
            drawArc(
                color = accent.copy(alpha = if (complete) 0.22f else 0.12f),
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = glow, cap = StrokeCap.Round),
            )
            // The arc itself, fading from lamplight to full saffron
            val brush = Brush.sweepGradient(
                0f to accent.copy(alpha = 0.55f),
                1f to accent,
                center = Offset(cx, cy),
            )
            rotate(degrees = -90f, pivot = Offset(cx, cy)) {
                drawArc(
                    brush = brush,
                    startAngle = 0f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }

        for (fraction in bellFractions) {
            val angleDeg = -90f + (1f - fraction) * 360f
            val angleRad = Math.toRadians(angleDeg.toDouble())
            drawCircle(
                color = bellColor,
                radius = stroke * 0.9f,
                center = Offset(cx + radius * cos(angleRad).toFloat(), cy + radius * sin(angleRad).toFloat()),
            )
        }

        if (orbitFraction != null) {
            val angleRad = Math.toRadians((orbitFraction * 360f - 90f).toDouble())
            val center = Offset(cx + radius * cos(angleRad).toFloat(), cy + radius * sin(angleRad).toFloat())
            drawCircle(color = accent.copy(alpha = 0.25f), radius = stroke * 2.4f, center = center)
            drawCircle(color = accent, radius = stroke * 1.1f, center = center)
        }
    }
}

// --- Formatting ---

private fun formatTime(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}

/** "1 h 05 m", "42 min", "45 s". */
private fun formatDurationWords(millis: Long): String {
    val totalSec = millis.coerceAtLeast(0) / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "%d h %02d m".format(h, m)
        m > 0 && s >= 30 && m < 10 -> "$m min $s s"
        m > 0 -> "$m min"
        else -> "$s s"
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
