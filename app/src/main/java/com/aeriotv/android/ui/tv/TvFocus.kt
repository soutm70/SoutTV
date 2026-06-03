package com.aeriotv.android.ui.tv

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex

/**
 * tvOS-style focus grow.
 *
 * Apple TV grows the focused element with a quick, slightly springy scale (and
 * lifts it above its neighbours). Android TV focusables in this app previously
 * only swapped a border/background colour the instant focus landed, which is
 * exactly the "rigid vs the smoothness of the tvOS app" feel Archie called out.
 *
 * Drop [tvFocusScale] into a focusable's modifier chain BEFORE the
 * clip/background/border so the whole card scales as one unit, e.g.
 *
 * ```
 * Modifier
 *     .onFocusChanged { focused = it.isFocused }
 *     .tvFocusScale(focused)
 *     .clip(shape)
 *     .background(bg)
 *     .border(...)
 *     .focusable()
 * ```
 *
 * Keyed on [focused], which is only ever true under D-pad focus, so this is a
 * no-op on touch devices (their elements never gain focus) and is safe to apply
 * unconditionally without an `isTv` guard.
 *
 * The [zIndex] bump means the focused, scaled element draws on top of its
 * siblings (so a grown poster/cell/pill is never clipped by the next item),
 * matching how the tvOS focus engine lifts the focused view.
 */
@Composable
fun Modifier.tvFocusScale(
    focused: Boolean,
    focusedScale: Float = 1.08f,
): Modifier {
    val scale by animateFloatAsState(
        targetValue = if (focused) focusedScale else 1f,
        // A gentle spring (slight overshoot, no visible bounce) reads as
        // "alive" the way a flat tween does not. StiffnessMediumLow settles in
        // ~250ms which matches the tvOS focus cadence.
        animationSpec = spring(
            dampingRatio = 0.78f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "tvFocusScale",
    )
    return this
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
}
