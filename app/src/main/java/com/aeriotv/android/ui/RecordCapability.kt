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
