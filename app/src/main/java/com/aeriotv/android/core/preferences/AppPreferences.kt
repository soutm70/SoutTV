package com.aeriotv.android.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aeriotv.android.ui.theme.AppTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "aerio_prefs")

/**
 * Typed DataStore wrapper, mirroring the iOS @AppStorage registry
 * (project_aeriotv_ios_architecture.md section C). Each key has a Flow getter
 * and a suspend setter so screen state can stay reactive via collectAsState.
 *
 * Phase 8a lands the keys with visible UI: selectedTheme + defaultLiveTVView.
 * Remaining keys (App Behaviors / Multiview / Network / Sync / DVR / Developer)
 * follow as their sub-screens land.
 */
@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store get() = context.appDataStore

    val selectedTheme: Flow<AppTheme> = store.data.map { prefs ->
        val raw = prefs[KEY_SELECTED_THEME] ?: AppTheme.Aerio.name
        AppTheme.entries.firstOrNull { it.name == raw } ?: AppTheme.Aerio
    }

    suspend fun setSelectedTheme(theme: AppTheme) {
        store.edit { it[KEY_SELECTED_THEME] = theme.name }
    }

    /**
     * Either "list" or "guide". Mirrors iOS `@AppStorage("defaultLiveTVView")`.
     * Phase 5 used `rememberSaveable`; with this in place LiveTVTabContent can
     * persist the user's view-mode choice across cold starts.
     */
    val defaultLiveTVView: Flow<String> = store.data.map { prefs ->
        prefs[KEY_DEFAULT_LIVE_TV_VIEW] ?: ""
    }

    suspend fun setDefaultLiveTVView(value: String) {
        store.edit { it[KEY_DEFAULT_LIVE_TV_VIEW] = value }
    }

    private companion object {
        val KEY_SELECTED_THEME = stringPreferencesKey("selected_theme")
        val KEY_DEFAULT_LIVE_TV_VIEW = stringPreferencesKey("default_live_tv_view")
    }
}
