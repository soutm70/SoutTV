package com.aeriotv.android.feature.multiview

import com.aeriotv.android.core.data.M3UChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-scoped state for the multiview surface. Mirrors iOS MultiviewStore
 * (Aerio/Features/Multiview/MultiviewStore.swift) — single source of truth
 * for which channels are tiled, which tile owns audio, and which tiles are
 * muted.
 *
 * Phase 11a wires the selection set (the picker reads + writes it) and the
 * audio-focused index. The actual MPV-per-tile render lands in 11b, the
 * audio-focus visual indicator in 11c.
 */
@Singleton
class MultiviewStore @Inject constructor() {

    /** iOS hard cap: 9 tiles. The picker enforces this. */
    val maxTiles: Int = 9

    private val _selected = MutableStateFlow<List<M3UChannel>>(emptyList())
    val selected: StateFlow<List<M3UChannel>> = _selected.asStateFlow()

    private val _audioFocusedIndex = MutableStateFlow(0)
    val audioFocusedIndex: StateFlow<Int> = _audioFocusedIndex.asStateFlow()

    fun isSelected(channel: M3UChannel): Boolean =
        _selected.value.any { it.id == channel.id }

    fun toggle(channel: M3UChannel) {
        val current = _selected.value
        val existing = current.firstOrNull { it.id == channel.id }
        if (existing != null) {
            _selected.value = current - existing
            // Slide audio focus back if the focused tile was removed past the end.
            if (_audioFocusedIndex.value >= _selected.value.size) {
                _audioFocusedIndex.value = (_selected.value.size - 1).coerceAtLeast(0)
            }
        } else if (current.size < maxTiles) {
            _selected.value = current + channel
        }
    }

    fun clear() {
        _selected.value = emptyList()
        _audioFocusedIndex.value = 0
    }

    fun setAudioFocus(index: Int) {
        if (index in 0 until _selected.value.size) {
            _audioFocusedIndex.value = index
        }
    }

    /** Swaps two tile positions. Audio focus moves with the originally-focused tile. */
    fun swap(fromIndex: Int, toIndex: Int) {
        val current = _selected.value
        if (fromIndex !in current.indices || toIndex !in current.indices) return
        if (fromIndex == toIndex) return
        val mutable = current.toMutableList()
        val tmp = mutable[fromIndex]
        mutable[fromIndex] = mutable[toIndex]
        mutable[toIndex] = tmp
        _selected.value = mutable
        // Track audio focus so it sticks to the same channel after the swap.
        val focused = _audioFocusedIndex.value
        _audioFocusedIndex.value = when (focused) {
            fromIndex -> toIndex
            toIndex -> fromIndex
            else -> focused
        }
    }

    /**
     * Replace the channel at [index] with [newChannel] in place. Mirrors iOS
     * MultiviewStore.swapTileContent (commit cc28f18 + e627ca7) — the
     * positional Tile composable preserves its AndroidView, the update
     * callback notices the URL change, and calls MPVPlayerView.playFile
     * which routes through `mpv_command loadfile` (default replace mode).
     * No tile teardown, no black flash on channel-flip.
     */
    fun replaceTile(index: Int, newChannel: M3UChannel) {
        val current = _selected.value
        if (index !in current.indices) return
        if (current[index].id == newChannel.id) return
        val mutable = current.toMutableList()
        mutable[index] = newChannel
        _selected.value = mutable
    }
}
