package com.aeriotv.android.feature.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Centered "Live TV" title bar whose trailing actions SHRINK on narrow
 * screens so the title stays truly screen-centered (user report: on the
 * Fold's cover display the title sat left of center). Material's
 * CenterAlignedTopAppBar cannot do this: with an empty leading slot and
 * three or four default 48dp actions it squeezes the title box and the
 * text re-aligns toward the leading edge. Here the title is absolutely
 * centered in the bar and the action cluster sizes itself to whatever
 * width is left BESIDE the centered title: 48dp buttons where they fit,
 * scaling down to a 32dp floor, stepping the title from titleLarge down
 * to titleMedium before accepting a tight fit. Measured with
 * TextMeasurer, so the math follows font scale too.
 *
 * [actions] receives the resolved per-button and glyph sizes; build each
 * action as `IconButton(modifier = Modifier.size(buttonSize)) {
 * Icon(modifier = Modifier.size(iconSize)) }` so the whole cluster obeys
 * the fit.
 */
@Composable
fun LiveTvTopBar(
    actionCount: Int,
    modifier: Modifier = Modifier,
    actions: @Composable (buttonSize: Dp, iconSize: Dp) -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(MaterialTheme.colorScheme.background),
    ) {
        val textMeasurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val titleLarge = MaterialTheme.typography.titleLarge
        val titleMedium = MaterialTheme.typography.titleMedium
        val barWidth = maxWidth
        val fit = remember(barWidth, actionCount, titleLarge, titleMedium, density) {
            fun tryFit(style: TextStyle): Pair<TextStyle, Dp>? {
                val titleWidth = with(density) {
                    textMeasurer.measure(
                        text = "Live TV",
                        style = style.copy(fontWeight = FontWeight.Bold),
                    ).size.width.toDp()
                }
                // Space beside the centered title on ONE side, minus a small
                // breathing gap so icons never kiss the text.
                val side = (barWidth - titleWidth) / 2 - 8.dp
                if (actionCount == 0) return style to 48.dp
                val perAction = side / actionCount
                return when {
                    perAction >= 48.dp -> style to 48.dp
                    perAction >= 32.dp -> style to perAction
                    else -> null
                }
            }
            tryFit(titleLarge) ?: tryFit(titleMedium) ?: (titleMedium to 32.dp)
        }
        val (titleStyle, buttonSize) = fit
        val iconSize = (buttonSize - 14.dp).coerceIn(18.dp, 24.dp)
        Text(
            text = "Live TV",
            style = titleStyle,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.align(Alignment.Center),
        )
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions(buttonSize, iconSize)
        }
    }
}
