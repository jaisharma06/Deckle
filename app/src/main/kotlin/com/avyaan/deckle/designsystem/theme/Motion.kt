package com.avyaan.deckle.designsystem.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Motion tokens (spec 05 §3). All animation goes through these helpers so reduced motion is honoured in
 * exactly one place.
 */
public object DeckleMotion {
    public const val INSTANT: Int = 90
    public const val SHORT: Int = 160
    public const val MEDIUM: Int = 260
    public const val LONG: Int = 400
    public const val DELIBERATE: Int = 600

    public val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    public val Decelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    public val Accelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** Springs for anything the finger touches, so it feels physical. */
    public enum class Spring(public val damping: Float, public val stiffness: Float) {
        Handle(0.8f, 900f),
        Press(0.6f, 1200f),
        Lift(0.7f, 500f),
        Overlay(1f, 300f),
        Pop(0.5f, 600f),
    }
}

/** True when the system "Remove animations" setting is on (animator duration scale 0). */
public val LocalReducedMotion: androidx.compose.runtime.ProvidableCompositionLocal<Boolean> =
    staticCompositionLocalOf { false }

internal fun Context.isReducedMotion(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

@Composable
@ReadOnlyComposable
public fun <T> deckleTween(
    durationMillis: Int = DeckleMotion.MEDIUM,
    easing: Easing = DeckleMotion.Standard,
    delayMillis: Int = 0,
): FiniteAnimationSpec<T> =
    if (LocalReducedMotion.current) tween(durationMillis = minOf(durationMillis, 120), easing = easing)
    else tween(durationMillis, delayMillis, easing)

@Composable
@ReadOnlyComposable
public fun <T> deckleSpring(kind: DeckleMotion.Spring, visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
    if (LocalReducedMotion.current) snap()
    else spring(dampingRatio = kind.damping, stiffness = kind.stiffness, visibilityThreshold = visibilityThreshold)

/** Non-composable variants for use inside `LaunchedEffect`/`Animatable.animateTo`. */
public fun <T> springSpec(kind: DeckleMotion.Spring, reduced: Boolean): AnimationSpec<T> =
    if (reduced) snap() else spring(dampingRatio = kind.damping, stiffness = kind.stiffness)

public fun <T> tweenSpec(durationMillis: Int, reduced: Boolean, easing: Easing = DeckleMotion.Standard): AnimationSpec<T> =
    tween(if (reduced) minOf(durationMillis, 120) else durationMillis, easing = easing)
