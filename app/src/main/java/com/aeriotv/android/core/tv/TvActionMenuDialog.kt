package com.aeriotv.android.core.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * One row of a [TvActionMenuDialog]. [icon] is nullable because some menus
 * (the guide programme cell) are text-only; [destructive] swaps the icon and
 * label to the error color; [enabled] keeps the row visible but inert and
 * dimmed (e.g. a "Multiview full" state).
 */
data class TvMenuAction(
    val label: String,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * The house long-press action menu for Android TV: a centered Dialog with a
 * title header and focusable rows carrying the primary 0.18 wash on focus.
 * Anchored DropdownMenus float oddly mid-screen at 10 feet, so TV branches
 * render this instead (extracted from the DVR tab's RecordingActionMenu).
 *
 * Pair with [TvMenuGuard]: arm() at the long-press that opens the dialog;
 * row clicks are wrapped here so the spurious OK-release that follows a TV
 * long-press cannot auto-pick a row. Callers compose this only while open:
 *   if (menuOpen) TvActionMenuDialog(..., onDismiss = { menuOpen = false })
 *
 * D-pad activation is latched per row, not time-based: a row only fires on
 * an OK KeyUp whose KeyDown it also saw. The release of the long-press that
 * OPENED the dialog arrives as a bare KeyUp (its KeyDown went to the
 * launching row), so it is ignored no matter how long the button was held,
 * including releases slower than the guard's grace window.
 */
@Composable
fun TvActionMenuDialog(
    title: String,
    actions: List<TvMenuAction>,
    guard: TvMenuGuard,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.padding(vertical = 10.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                actions.forEach { action ->
                    val tint = if (action.destructive) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                    var rowFocused by remember { mutableStateOf(false) }
                    // OK activates only when this row saw the press start:
                    // KeyDown (repeatCount 0) latches, KeyUp fires if latched.
                    // Repeats belong to a press that began before the dialog
                    // opened, so they must not latch.
                    var okLatched by remember { mutableStateOf(false) }
                    val activate = guard.wrap { onDismiss(); action.onClick() }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged {
                                rowFocused = it.isFocused
                                if (!it.isFocused) okLatched = false
                            }
                            .onPreviewKeyEvent { event ->
                                val isOk = event.key == Key.DirectionCenter ||
                                    event.key == Key.Enter ||
                                    event.key == Key.NumPadEnter
                                if (!isOk) return@onPreviewKeyEvent false
                                when (event.type) {
                                    KeyEventType.KeyDown -> {
                                        if (event.nativeKeyEvent.repeatCount == 0) okLatched = true
                                        true
                                    }
                                    KeyEventType.KeyUp -> {
                                        val latched = okLatched
                                        okLatched = false
                                        if (latched && action.enabled) activate()
                                        true
                                    }
                                    else -> false
                                }
                            }
                            .background(
                                if (rowFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                else Color.Transparent,
                            )
                            .clickable(
                                enabled = action.enabled,
                                onClick = activate,
                            )
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (action.icon != null) {
                            Icon(action.icon, contentDescription = null, tint = tint)
                        }
                        val textColor = if (action.destructive) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onBackground
                        Text(
                            text = action.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (action.enabled) textColor else textColor.copy(alpha = 0.4f),
                        )
                    }
                }
            }
        }
    }
}
