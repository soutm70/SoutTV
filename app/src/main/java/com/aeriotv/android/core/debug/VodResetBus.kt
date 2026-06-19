package com.aeriotv.android.core.debug

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * App-scoped fan-out for the "Refresh Everything" nuclear data reset.
 *
 * OnDemandViewModel already runs a full VOD reset (resetVodState() + refresh()
 * + refreshSeries()) when the ACTIVE PLAYLIST ID changes. "Refresh Everything"
 * must trigger that same reset while the id is UNCHANGED, which the existing
 * observeActiveId().drop(1) collector never sees. PlaylistViewModel.refreshEverything()
 * emits here; OnDemandViewModel.init collects and runs its reset.
 *
 * Pattern mirrors [MemoryPressureBus] / [com.aeriotv.android.feature.reminders.ReminderBannerBus]:
 * a @Singleton Hilt event bus, constructor-injected, no DI module required.
 */
@Singleton
class VodResetBus @Inject constructor() {
    // No replay: a reset is a one-shot command, not a state. extraBufferCapacity=1
    // + DROP_OLDEST so tryEmit from the main thread always succeeds and a
    // double-tap coalesces instead of queueing two resets.
    private val _resets = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val resets: SharedFlow<Unit> = _resets.asSharedFlow()

    fun requestReset() { _resets.tryEmit(Unit) }
}
