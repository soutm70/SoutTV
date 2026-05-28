package com.aeriotv.android.feature.miniplayer

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aeriotv.android.core.playback.MPVPlayerHolder

/**
 * TV mini-player chrome overlay (Phase 165).
 *
 * The VIDEO is rendered by the activity-lifetime [com.aeriotv.android
 * .feature.player.PersistentMpvWindow] mounted at MainActivity root.
 * This overlay only contributes the "Double press OK to resume" hint
 * chip below where the video shows. The chip is positioned right-
 * aligned and offset down past the mini video's bottom edge:
 *   mini top inset (12) + mini height (118) + small gap (8) = 138dp
 *   end inset matches the video's (24dp).
 *
 * No AndroidView, no acquireOrCreate, no surface lifecycle to manage.
 * Compose state on [MiniPlayerSession] drives visibility -- when state
 * is Active, the hint chip renders; otherwise nothing.
 */
@Composable
fun BoxScope.TvMiniPlayerOverlay(
    state: MiniPlayerSession.State,
    @Suppress("UNUSED_PARAMETER") mpvHolder: MPVPlayerHolder,
    @Suppress("UNUSED_PARAMETER") onResume: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onDismiss: () -> Unit,
) {
    if (state !is MiniPlayerSession.State.Active) return
    val isTv = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
        Configuration.UI_MODE_TYPE_TELEVISION
    if (!isTv) return

    // Hint chip rendered directly below the persistent mini video's
    // bottom edge. No clickable / focusable surface -- resume is owned
    // by MainActivity.dispatchKeyEvent's double-press-OK detection so
    // this overlay doesn't trap D-pad focus.
    Text(
        text = "Double press OK to resume",
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(end = 24.dp, top = 138.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
