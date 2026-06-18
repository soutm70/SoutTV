package com.aeriotv.android.ui

import androidx.compose.runtime.compositionLocalOf

/**
 * Whether the active source lets the user create server-side recordings: a
 * Dispatcharr admin account (user_level >= 10). Provided at the Live TV and
 * Player surfaces from the active playlist; the Record affordances (channel
 * long-press, guide cell, player More menu) read it and hide Record for
 * Standard / Streamer Dispatcharr accounts, which 403 on POST recordings.
 *
 * Defaults to true so a read outside any provider stays recording-capable
 * (back-compat). Mirrors iOS dispatcharrCanRecordToServer (commit d8aa76b).
 */
val LocalCanRecordToServer = compositionLocalOf { true }

/**
 * Whether the active playlist is a Dispatcharr Direct Connect source (API key
 * or user/pass) -- the only source with the per-channel streams list +
 * change_stream endpoint behind the player's Switch Stream picker. Provided at
 * the Player surface from the active playlist; Switch Stream reads it (alongside
 * the per-channel dispatcharrChannelId) and stays hidden for XC / M3U sources,
 * which expose one URL per channel with nothing to switch between.
 *
 * Defaults to true so a read outside any provider falls back to the per-channel
 * dispatcharrChannelId gate, which is itself only set for Direct Connect.
 */
val LocalIsDispatcharrDirectConnect = compositionLocalOf { true }
