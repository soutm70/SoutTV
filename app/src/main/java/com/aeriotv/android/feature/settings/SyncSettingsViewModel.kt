package com.aeriotv.android.feature.settings

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.core.sync.DriveSyncManager
import com.aeriotv.android.core.sync.DriveSyncWorker
import com.aeriotv.android.core.sync.SyncCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Backs SyncSettingsScreen. Drives the two-step sign-in (Credential Manager
 * identity → Drive scope authorization) and serializes the push-then-pull
 * sync flow.
 */
@HiltViewModel
class SyncSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPreferences,
    private val sync: DriveSyncManager,
) : ViewModel() {

    val masterEnabled: Flow<Boolean> = prefs.syncMasterEnabled
    fun setMasterEnabled(value: Boolean) {
        viewModelScope.launch {
            prefs.setSyncMasterEnabled(value)
            if (value) DriveSyncWorker.enqueuePeriodic(context)
            else DriveSyncWorker.cancel(context)
        }
    }

    val accountEmail: Flow<String> = prefs.syncAccountEmail
    val lastPushAt: Flow<Long> = prefs.syncLastPushAt
    val lastPullAt: Flow<Long> = prefs.syncLastPullAt
    val driveStatus: StateFlow<DriveSyncManager.Status> = sync.status

    fun categoryEnabled(category: SyncCategory): Flow<Boolean> =
        prefs.syncCategoryEnabled(category)

    fun setCategoryEnabled(category: SyncCategory, value: Boolean) {
        viewModelScope.launch { prefs.setSyncCategoryEnabled(category, value) }
    }

    suspend fun signInWithGoogle(activity: Activity): String? =
        sync.signInWithGoogle(activity)

    suspend fun requestDriveScope(): DriveSyncManager.RequestResult? =
        sync.requestDriveScope()

    fun acceptConsentResult(data: android.content.Intent?) {
        sync.acceptConsentResult(data)
    }

    fun signOut() {
        viewModelScope.launch {
            sync.signOut()
            DriveSyncWorker.cancel(context)
        }
    }

    suspend fun clearRemote(): Boolean {
        val token = (sync.status.value as? DriveSyncManager.Status.SignedIn)?.accessToken
            ?: return false
        return runCatching { sync.clearRemote(token); true }.getOrDefault(false)
    }

    suspend fun syncNow(): Map<SyncCategory, Boolean> {
        val token = (sync.status.value as? DriveSyncManager.Status.SignedIn)?.accessToken
            ?: return emptyMap()
        val enabled = SyncCategory.entries
            .filter { prefs.syncCategoryEnabled(it).first() }
            .toSet()
        val pushed = sync.pushAll(token, enabled)
        val pulled = sync.pullAll(token, enabled)
        return pushed.mapValues { (cat, ok) -> ok && (pulled[cat] != false) }
    }
}
