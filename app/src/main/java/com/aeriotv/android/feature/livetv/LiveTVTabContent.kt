package com.aeriotv.android.feature.livetv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.feature.channels.ChannelListScreen
import com.aeriotv.android.feature.playlist.PlaylistViewModel

/**
 * Entry point for the Live TV tab. Picks the appropriate sub-screen
 * (List vs Guide) based on form factor + the user's runtime toggle.
 * Mirrors iOS ChannelListView dispatcher logic (`ChannelListView.swift:348`).
 */
@Composable
fun LiveTVTabContent(
    onChannelClick: (M3UChannel) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val formFactor = rememberLiveTvFormFactor()
    var mode by rememberLiveTvViewModeState(formFactor)

    when (mode) {
        LiveTVViewMode.List -> ChannelListScreen(
            onChannelClick = onChannelClick,
            viewModel = viewModel,
            modifierWrap = modifier,
            viewMode = mode,
            canToggleViewMode = formFactor.supportsToggle,
            onToggleViewMode = { mode = if (mode == LiveTVViewMode.List) LiveTVViewMode.Guide else LiveTVViewMode.List },
        )
        LiveTVViewMode.Guide -> GuideScreen(
            onChannelClick = onChannelClick,
            viewModel = viewModel,
            modifier = modifier,
            viewMode = mode,
            canToggleViewMode = formFactor.supportsToggle,
            onToggleViewMode = { mode = if (mode == LiveTVViewMode.List) LiveTVViewMode.Guide else LiveTVViewMode.List },
        )
    }
}
