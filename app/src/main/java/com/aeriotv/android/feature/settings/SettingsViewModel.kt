package com.aeriotv.android.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.ui.theme.AppTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Settings sub-screens share this ViewModel for read/write access to the
 * DataStore-backed preferences. Keeps each sub-screen stateless and lets
 * AerioTVTheme observe `selectedTheme` from MainActivity at the same time.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: AppPreferences,
) : ViewModel() {

    val selectedTheme: Flow<AppTheme> = prefs.selectedTheme

    fun setSelectedTheme(theme: AppTheme) {
        viewModelScope.launch { prefs.setSelectedTheme(theme) }
    }
}
