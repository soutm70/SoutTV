package com.aeriotv.android.feature.livetv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.feature.channels.ChannelListScreen
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import com.aeriotv.android.feature.settings.SettingsViewModel
import com.aeriotv.android.ui.scale.WithDisplayScale

/**
 * Entry point for the Live TV tab. Picks the appropriate sub-screen
 * (List vs Guide) based on form factor + the user's runtime toggle.
 * Mirrors iOS ChannelListView dispatcher logic (`ChannelListView.swift:348`).
 *
 * Phase 8b: view-mode preference moved from `rememberSaveable` to DataStore
 * via [SettingsViewModel] so the user's List/Guide choice survives cold start.
 * An empty stored value falls back to the form-factor default.
 */
@Composable
fun LiveTVTabContent(
    onChannelClick: (M3UChannel) -> Unit,
    modifier: Modifier = Modifier,
    onLaunchMultiview: () -> Unit = {},
    viewModel: PlaylistViewModel = hiltViewModel(),
    settingsVm: SettingsViewModel = hiltViewModel(),
) {
    val formFactor = rememberLiveTvFormFactor()
    val stored by settingsVm.defaultLiveTVView.collectAsStateWithLifecycle(initialValue = "")
    val scale by settingsVm.displayScaleLiveTV.collectAsStateWithLifecycle(initialValue = 1.0f)

    // The List view is disabled on Android TV: the 10-foot Live TV experience is
    // Guide-only (the List's now-playing rows are a phone / tablet affordance).
    // Force Guide regardless of any saved "list" preference, and hide the toggle.
    val mode = if (formFactor.isTv) {
        LiveTVViewMode.Guide
    } else when (stored.lowercase()) {
        "list" -> LiveTVViewMode.List
        "guide" -> LiveTVViewMode.Guide
        else -> formFactor.defaultMode
    }
    val canToggle = formFactor.supportsToggle && !formFactor.isTv
    val toggleMode: () -> Unit = {
        val next = if (mode == LiveTVViewMode.List) LiveTVViewMode.Guide else LiveTVViewMode.List
        settingsVm.setDefaultLiveTVView(next.storageKey())
    }

    // iOS canon scopes the "Live TV List" Display Scale slider to List mode
    // only (the Guide grid is a strict-pitch layout that the user shouldn't
    // be free-scaling). Match that scoping rule here.
    when (mode) {
        LiveTVViewMode.List -> WithDisplayScale(scale = scale) {
            ChannelListScreen(
                onChannelClick = onChannelClick,
                viewModel = viewModel,
                modifierWrap = modifier,
                viewMode = mode,
                canToggleViewMode = canToggle,
                onToggleViewMode = toggleMode,
            )
        }
        LiveTVViewMode.Guide -> GuideScreen(
            onChannelClick = onChannelClick,
            viewModel = viewModel,
            modifier = modifier,
            viewMode = mode,
            canToggleViewMode = canToggle,
            onToggleViewMode = toggleMode,
            onLaunchMultiview = onLaunchMultiview,
        )
    }
}

private fun LiveTVViewMode.storageKey(): String = when (this) {
    LiveTVViewMode.List -> "list"
    LiveTVViewMode.Guide -> "guide"
}
