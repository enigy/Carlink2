package com.carlink.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── Tunables ────────────────────────────────────────────────────────────────
// Soft white words with a pale blue-teal glow.
private val WORD_COLOR = Color(0xFFF2FBFF)
private val GLOW_COLOR = Color(0xE68FE3EC)
private const val GLOW_BLUR_PX = 24f

// Room around the words for the glow, inside each animated layer: a layer with alpha or blur
// draws offscreen at its own bounds, which would otherwise cut the glow off.
private val GLOW_ROOM = 24.dp

// Letters condense out of the fog one by one: each rises, shrinks to size and sharpens.
private const val LETTER_STAGGER_MS = 45L
private const val LETTER_MS = 900
private const val LETTER_RISE_DP = 14f
private const val LETTER_BLUR_DP = 10f
private const val LETTER_START_SCALE = 1.18f

// While waiting, the line breathes: brightness and size swell and ease, once per cycle.
private const val BREATH_MS = 3200
private const val BREATH_MIN_ALPHA = 0.8f
private const val BREATH_SCALE = 0.015f

// On the next step the old line dissolves into mist: it drifts up, spreads and blurs away.
private const val MIST_MS = 900
private const val MIST_RISE_DP = 22f
private const val MIST_BLUR_DP = 14f
private const val MIST_SCALE = 0.08f

// A step backwards (an adapter restart during the phone search) shows only if it lasts this long,
// so a quick restart doesn't flick the words back and forth. Messages for the driver show at once.
private const val BACKWARD_HOLD_MS = 8_000L
// ────────────────────────────────────────────────────────────────────────────

/**
 * [229] The connecting screen's status: one of three steps ([LoadingSteps]) in ethereal words,
 * three dots for the steps, and a plain line under them when the driver has to do something.
 */
@Composable
internal fun LoadingStatusView(
    statusText: String,
    phoneConnected: Boolean,
    androidAuto: Boolean,
    modifier: Modifier = Modifier,
) {
    val target = LoadingSteps.classify(statusText, phoneConnected)
    var shown by remember { mutableStateOf(target) }
    LaunchedEffect(target) {
        if (target.step < shown.step && target.action == null) delay(BACKWARD_HOLD_MS)
        shown = target
    }

    val base = MaterialTheme.typography.bodyLarge
    val words =
        base.copy(
            // Two steps up the type scale (body large → headline small), same font.
            fontSize = MaterialTheme.typography.headlineSmall.fontSize,
            lineHeight = MaterialTheme.typography.headlineSmall.lineHeight,
            letterSpacing = 0.6.sp,
            color = WORD_COLOR,
            shadow = Shadow(color = GLOW_COLOR, offset = Offset.Zero, blurRadius = GLOW_BLUR_PX),
        )

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        EtherealLine(text = LoadingSteps.label(shown.step, androidAuto), style = words)
        StepDots(step = shown.step)
        Crossfade(targetState = shown.action, label = "loadingAction") { action ->
            if (action != null) {
                Text(
                    text = action,
                    style = base,
                    color = Color(0xFFDDE4E5),
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
    }
}

/** One line of ethereal words; a new [text] condenses in while the old one dissolves into mist. */
@Composable
private fun EtherealLine(
    text: String,
    style: TextStyle,
) {
    val breath =
        rememberInfiniteTransition(label = "breath").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(BREATH_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "breath",
        )
    AnimatedContent(
        targetState = text,
        transitionSpec = { EnterTransition.None togetherWith ExitTransition.None using SizeTransform(clip = false) },
        contentAlignment = Alignment.Center,
        label = "etherealLine",
    ) { line ->
        // Stays composed until the mist has cleared (AnimatedContent waits for this animation).
        val mist by transition.animateFloat(
            transitionSpec = { tween(MIST_MS, easing = LinearOutSlowInEasing) },
            label = "mist",
        ) { state -> if (state == EnterExitState.PostExit) 1f else 0f }
        Box(
            modifier =
                Modifier
                    .withGlowRoom(GLOW_ROOM)
                    .graphicsLayer {
                        val m = mist
                        val b = breath.value
                        alpha = (BREATH_MIN_ALPHA + (1f - BREATH_MIN_ALPHA) * b) * (1f - m)
                        translationY = -MIST_RISE_DP.dp.toPx() * m
                        val scale = 1f + MIST_SCALE * m + BREATH_SCALE * b
                        scaleX = scale
                        scaleY = scale
                        val radius = MIST_BLUR_DP.dp.toPx() * m
                        renderEffect = if (radius >= 0.5f) BlurEffect(radius, radius, TileMode.Decal) else null
                    }.padding(GLOW_ROOM),
        ) {
            CondensingLetters(text = line, style = style)
        }
    }
}

/** [text] letter by letter, each rising out of the fog and sharpening, [LETTER_STAGGER_MS] apart. */
@Composable
private fun CondensingLetters(
    text: String,
    style: TextStyle,
) {
    val progress = remember(text) { List(text.length) { Animatable(0f) } }
    LaunchedEffect(text) {
        progress.forEachIndexed { i, letter ->
            launch {
                delay(i * LETTER_STAGGER_MS)
                letter.animateTo(1f, tween(LETTER_MS, easing = FastOutSlowInEasing))
            }
        }
    }
    Row(modifier = Modifier.clearAndSetSemantics { contentDescription = text }) {
        text.forEachIndexed { i, char ->
            Text(
                text = char.toString(),
                style = style,
                modifier =
                    Modifier
                        .withGlowRoom(GLOW_ROOM)
                        .graphicsLayer { condense(progress[i].value) }
                        .padding(GLOW_ROOM),
            )
        }
    }
}

private fun androidx.compose.ui.graphics.GraphicsLayerScope.condense(q: Float) {
    alpha = q
    translationY = (1f - q) * LETTER_RISE_DP.dp.toPx()
    val scale = LETTER_START_SCALE - (LETTER_START_SCALE - 1f) * q
    scaleX = scale
    scaleY = scale
    val radius = (1f - q) * LETTER_BLUR_DP.dp.toPx()
    renderEffect = if (radius >= 0.5f) BlurEffect(radius, radius, TileMode.Decal) else null
}

/** Three dots: the steps done glow softly, the current one brightest, the rest faint. */
@Composable
private fun StepDots(step: LoadingStep) {
    Row(modifier = Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        LoadingStep.entries.forEach { dot ->
            val lit by animateFloatAsState(
                targetValue =
                    when {
                        dot < step -> 0.55f
                        dot == step -> 1f
                        else -> 0.18f
                    },
                animationSpec = tween(MIST_MS),
                label = "stepDot",
            )
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .graphicsLayer {
                            alpha = lit
                            val scale = 0.85f + 0.4f * (lit - 0.18f) / 0.82f
                            scaleX = scale
                            scaleY = scale
                        }.background(WORD_COLOR, CircleShape),
            )
        }
    }
}

/**
 * Lays the content out [room] smaller on every side than it draws, so a following
 * `graphicsLayer { … }.padding(room)` gets layer bounds with space for the glow, while
 * neighbours are spaced as if the padding weren't there.
 */
private fun Modifier.withGlowRoom(room: Dp): Modifier =
    layout { measurable, constraints ->
        val px = room.roundToPx()
        val placeable = measurable.measure(constraints.offset(2 * px, 2 * px))
        layout(placeable.width - 2 * px, placeable.height - 2 * px) { placeable.place(-px, -px) }
    }
