package com.zazen.ui.screens.timer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
                    startsIn = s.startsInMillis,
                    startDelay = cfg?.startDelayMillis ?: 0L,
                    repeatEveryMillis = repeatEveryMillis,
                    isPaused = false,
                    bellFractions = bellFractions,
                    vibrateOnly = vibrateOnly,
                    onPause = { viewModel.pause() },
                    onResume = {},
                    onStop = { viewModel.stop() },
                    onToggleVibrateOnly = { viewModel.toggleVibrateOnly() },
                )

                is TimerState.Paused -> ActiveTimerContent(
                    remaining = s.remainingMillis,
                    total = s.totalMillis,
                    elapsed = s.elapsedMillis,
                    openEnded = s.isOpenEnded,
                    phase = s.phase,
                    startsIn = 0L,
                    startDelay = cfg?.startDelayMillis ?: 0L,
                    repeatEveryMillis = repeatEveryMillis,
                    isPaused = true,
                    bellFractions = bellFractions,
                    vibrateOnly = vibrateOnly,
                    onPause = {},
                    onResume = { viewModel.resume() },
                    onStop = { viewModel.stop() },
                    onToggleVibrateOnly = { viewModel.toggleVibrateOnly() },
                )

                is TimerState.Finished -> ReviewContent(
                    sessionId = s.sessionId,
                    completed = s.completed,
                    openEnded = s.openEnded,
                    extended = s.extended,
                    elapsedMillis = s.elapsedMillis,
                    plannedMillis = s.plannedMillis,
                    onSave = { id, technique, notes, onError ->
                        viewModel.saveReviewAndDismiss(id, technique, notes, onTimerDone, onError)
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
    startsIn: Long,
    startDelay: Long,
    repeatEveryMillis: Long,
    isPaused: Boolean,
    bellFractions: List<Float>,
    vibrateOnly: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
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
                    phase == SitPhase.PREPARING -> "STARTING IN"
                    phase == SitPhase.EXTENDED -> "TOTAL TIME"
                    openEnded -> "OPEN SIT"
                    else -> "REMAINING"
                },
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 3.sp),
                color = if (phase == SitPhase.EXTENDED) MaterialTheme.colorScheme.primary else muted,
                modifier = if (isPaused) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
            )
            Spacer(Modifier.height(20.dp))

            Box(contentAlignment = Alignment.Center) {
                val ringProgress = when {
                    phase == SitPhase.PREPARING -> if (startDelay > 0) startsIn.toFloat() / startDelay else 0f
                    openEnded -> 0f
                    phase == SitPhase.EXTENDED -> 1f
                    total > 0 -> remaining.toFloat() / total
                    else -> 0f
                }
                val counting = phase == SitPhase.MAIN && !openEnded
                TimerRing(
                    progress = ringProgress,
                    bellFractions = if (counting) bellFractions else emptyList(),
                    // A slow orbiting dot (one lap a minute) shows an open-ended sit, or
                    // one counting up past its bell, is alive. Driven by the 4 Hz tick,
                    // not an animation, so a long sit doesn't redraw 60 times a second.
                    orbitFraction = if ((openEnded && phase == SitPhase.MAIN) || phase == SitPhase.EXTENDED) {
                        (elapsed % 60_000L) / 60_000f
                    } else null,
                    complete = phase == SitPhase.EXTENDED,
                    modifier = Modifier
                        .size(288.dp)
                        .clearAndSetSemantics { },
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val main = when {
                        // Round up so a 10 s delay starts at "00:10", not "00:09".
                        phase == SitPhase.PREPARING -> (startsIn + 999) / 1000 * 1000
                        openEnded -> elapsed
                        phase == SitPhase.MAIN -> remaining
                        else -> elapsed
                    }
                    Text(
                        text = formatTime(main),
                        style = if (main >= 3_600_000L) {
                            MaterialTheme.typography.displayMedium
                        } else {
                            MaterialTheme.typography.displayLarge
                        },
                        textAlign = TextAlign.Center,
                        color = if (phase == SitPhase.PREPARING) muted else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                    if (phase == SitPhase.EXTENDED) {
                        Text(
                            "+${formatTime(elapsed - total)} past the bell",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clearAndSetSemantics { },
                        )
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            if (openEnded && phase == SitPhase.MAIN && repeatEveryMillis > 0 && !isPaused) {
                val untilNext = repeatEveryMillis - (elapsed % repeatEveryMillis)
                Text(
                    stringResource(R.string.timer_next_bell_in, formatTime(untilNext)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                    modifier = Modifier.clearAndSetSemantics { },
                )
                Spacer(Modifier.height(20.dp))
            }

            when (phase) {
                SitPhase.PREPARING -> {
                    TextButton(onClick = onStop) {
                        Text("Cancel", style = MaterialTheme.typography.titleMedium)
                    }
                }

                SitPhase.EXTENDED -> {
                    val fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    HoldToConfirm(onConfirm = onStop, label = "Finish sit", hint = "Hold to finish") { progress ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.8f)
                                .height(72.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .drawBehind { drawRect(fill, size = Size(size.width * progress, size.height)) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "Finish sit",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    PauseResumeButton(isPaused, onPause, onResume)
                }

                SitPhase.MAIN -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        PauseResumeButton(isPaused, onPause, onResume)
                        val accent = MaterialTheme.colorScheme.primary
                        HoldToConfirm(
                            onConfirm = onStop,
                            label = stringResource(R.string.timer_cd_stop),
                            hint = "Hold to stop",
                        ) { progress ->
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), CircleShape)
                                    .drawBehind {
                                        if (progress > 0f) {
                                            val w = 3.dp.toPx()
                                            drawArc(
                                                color = accent,
                                                startAngle = -90f,
                                                sweepAngle = 360f * progress,
                                                useCenter = false,
                                                topLeft = Offset(w / 2, w / 2),
                                                size = Size(size.width - w, size.height - w),
                                                style = Stroke(width = w, cap = StrokeCap.Round),
                                            )
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Default.Stop, null, modifier = Modifier.size(28.dp), tint = muted)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Fires [onConfirm] only after being held for [holdMillis], so a stray touch can't end a
 * sit. A short tap shows [hint] instead. Screen readers get a plain click action, since
 * holding is awkward with TalkBack.
 */
@Composable
private fun HoldToConfirm(
    onConfirm: () -> Unit,
    label: String,
    hint: String,
    holdMillis: Int = 1000,
    content: @Composable (progress: Float) -> Unit,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var showHint by remember { mutableStateOf(false) }
    LaunchedEffect(showHint) {
        if (showHint) {
            delay(2000)
            showHint = false
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .clearAndSetSemantics {
                    role = Role.Button
                    contentDescription = label
                    onClick(label = label) { onConfirm(); true }
                }
                .pointerInput(onConfirm) {
                    detectTapGestures(onPress = {
                        val hold = scope.launch {
                            progress.animateTo(1f, tween(holdMillis, easing = LinearEasing))
                            onConfirm()
                        }
                        tryAwaitRelease()
                        if (progress.value < 1f) {
                            hold.cancel()
                            showHint = true
                            scope.launch { progress.animateTo(0f, tween(200)) }
                        }
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            content(progress.value)
        }
        Text(
            if (showHint) hint else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp).clearAndSetSemantics { },
        )
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

// --- Post-sit review ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewContent(
    sessionId: Long,
    completed: Boolean,
    openEnded: Boolean,
    extended: Boolean,
    elapsedMillis: Long,
    plannedMillis: Long,
    onSave: (Long, Technique?, String, () -> Unit) -> Unit,
    onDiscard: (Long) -> Unit,
) {
    var notes by rememberSaveable { mutableStateOf("") }
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
        if (completed && plannedMillis > 0 && elapsedMillis - plannedMillis >= 1_000L) {
            Text(
                "Planned ${formatDurationWords(plannedMillis)} · +${formatDurationWords(elapsedMillis - plannedMillis)} past the bell",
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
            )
        }

        Spacer(Modifier.height(28.dp))

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
                onSave(sessionId, technique, notes) { saving = false }
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
                    technique == null && notes.isBlank() -> "Save without notes"
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
