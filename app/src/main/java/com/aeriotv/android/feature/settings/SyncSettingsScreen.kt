package com.aeriotv.android.feature.settings

import com.aeriotv.android.ui.adaptive.adaptiveFormWidth

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.pip.findActivity
import com.aeriotv.android.core.sync.DriveSyncManager
import com.aeriotv.android.core.sync.SyncCategory
import com.aeriotv.android.core.sync.SyncConfig
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Settings > Sync sub-screen. Mirrors iOS Settings > iCloud Sync layout.
 *
 * Sign-in is a two-step flow:
 *   1. Tap "Sign in with Google" → Credential Manager surfaces the
 *      account picker → we get the user's email + GoogleId token.
 *   2. We immediately call AuthorizationClient for the Drive AppData
 *      scope. If the user has previously granted, we get an access
 *      token directly. Otherwise we launch the consent IntentSender via
 *      an ActivityResultLauncher and parse the result on return.
 *
 * After step 2 succeeds the per-category toggles + Sync Now / Clear
 * unlock. The "missing OAuth config" red banner shows only when the
 * BuildConfig.GOOGLE_DRIVE_WEB_CLIENT_ID field is empty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsScreen(
    onBack: () -> Unit,
    viewModel: SyncSettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val masterEnabled by viewModel.masterEnabled.collectAsStateWithLifecycle(initialValue = false)
    val accountEmail by viewModel.accountEmail.collectAsStateWithLifecycle(initialValue = "")
    val lastPush by viewModel.lastPushAt.collectAsStateWithLifecycle(initialValue = 0L)
    val lastPull by viewModel.lastPullAt.collectAsStateWithLifecycle(initialValue = 0L)
    val statusObj by viewModel.driveStatus.collectAsState()
    val configured = remember { SyncConfig.isConfigured() }

    var inFlight by remember { mutableStateOf(false) }

    // Consent intent launcher for step 2 of sign-in. AuthorizationClient may
    // return either an access token outright or a pendingIntent we have to
    // launch; this catches the result of the latter path.
    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        viewModel.acceptConsentResult(result.data)
        inFlight = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Sync", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )

        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.TopCenter,
        ) {
        LazyColumn(
            modifier = Modifier.adaptiveFormWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!configured) {
                item { ConfigBanner() }
            }
            item {
                Section(
                    header = "Drive Sync",
                    footer = "Playlists, watch progress, reminders, app preferences and credentials sync via your Drive AppData folder. Files are scoped per-app and never appear in your main Drive UI.",
                ) {
                    AccountRow(
                        signedIn = statusObj is DriveSyncManager.Status.SignedIn,
                        email = accountEmail,
                        configured = configured,
                        inFlight = inFlight,
                        onSignIn = {
                            val activity = context.findActivity() ?: return@AccountRow
                            inFlight = true
                            scope.launch {
                                val email = viewModel.signInWithGoogle(activity)
                                if (email == null) {
                                    inFlight = false
                                    Toast.makeText(context, "Sign-in cancelled or failed.", Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                val driveResult = viewModel.requestDriveScope()
                                when (driveResult) {
                                    is DriveSyncManager.RequestResult.Authorized -> {
                                        inFlight = false
                                        Toast.makeText(context, "Signed in as $email", Toast.LENGTH_SHORT).show()
                                    }
                                    is DriveSyncManager.RequestResult.NeedsConsent -> {
                                        consentLauncher.launch(
                                            IntentSenderRequest.Builder(driveResult.intentSender).build(),
                                        )
                                        // inFlight cleared by the launcher callback.
                                    }
                                    DriveSyncManager.RequestResult.Failed,
                                    null -> {
                                        inFlight = false
                                        Toast.makeText(context, "Drive authorization failed.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        onSignOut = {
                            viewModel.signOut()
                            Toast.makeText(context, "Signed out of Drive.", Toast.LENGTH_SHORT).show()
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    ToggleRow(
                        title = "Sync enabled",
                        subtitle = if (statusObj is DriveSyncManager.Status.SignedIn)
                            "Auto-syncing the categories you've toggled below."
                        else
                            "Sign in above, then enable sync to push and pull data.",
                        checked = masterEnabled,
                        onCheckedChange = viewModel::setMasterEnabled,
                    )
                }
            }

            item {
                Section(header = "Categories", footer = "Choose what syncs across your devices.") {
                    SyncCategory.entries.forEachIndexed { idx, category ->
                        val enabled by viewModel.categoryEnabled(category).collectAsStateWithLifecycle(initialValue = true)
                        ToggleRow(
                            title = category.displayName,
                            subtitle = category.subtitle,
                            checked = enabled,
                            onCheckedChange = { viewModel.setCategoryEnabled(category, it) },
                        )
                        if (idx < SyncCategory.entries.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        }
                    }
                }
            }

            item {
                Section(
                    header = "Actions",
                    footer = "Sync Now pushes local changes then pulls remote changes. Last Push: ${formatTimestamp(lastPush)}. Last Pull: ${formatTimestamp(lastPull)}.",
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Button(
                            enabled = !inFlight && statusObj is DriveSyncManager.Status.SignedIn,
                            onClick = {
                                inFlight = true
                                scope.launch {
                                    val result = viewModel.syncNow()
                                    val failed = result.entries.count { !it.value }
                                    Toast.makeText(
                                        context,
                                        if (failed == 0) "Sync complete."
                                        else "Sync finished with $failed category failure${if (failed > 1) "s" else ""}.",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    inFlight = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                            ),
                        ) {
                            Icon(imageVector = Icons.Filled.Sync, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("Sync Now")
                        }
                        Spacer(Modifier.height(10.dp))
                        TextButton(
                            onClick = {
                                inFlight = true
                                scope.launch {
                                    val ok = viewModel.clearRemote()
                                    Toast.makeText(
                                        context,
                                        if (ok) "Drive data cleared." else "Clear failed.",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    inFlight = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !inFlight && statusObj is DriveSyncManager.Status.SignedIn,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CloudOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.size(8.dp))
                            Text("Clear Drive Data", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun ConfigBanner() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.18f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column {
            Text(
                text = "Drive Sync needs a Google Cloud OAuth client",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Add GOOGLE_DRIVE_WEB_CLIENT_ID=<your-web-client-id> to local.properties (gitignored) and register this build's signing-cert SHA-1 with an Android client id in the same Cloud project. Until then, Sign in with Google will refuse.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun AccountRow(
    signedIn: Boolean,
    email: String,
    configured: Boolean,
    inFlight: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (signedIn) "Signed in to Drive" else "Sign in with Google",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = when {
                    signedIn && email.isNotBlank() -> email
                    signedIn -> "Account connected"
                    else -> "Grants Drive AppData access. Files stay private to AerioTV."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (signedIn) {
            TextButton(onClick = onSignOut, enabled = !inFlight) {
                Text(
                    text = "Sign out",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            SignInWithGoogleButton(
                enabled = configured && !inFlight,
                onClick = onSignIn,
            )
        }
    }
}

/**
 * Google-branded Sign in button. We render a Compose approximation that
 * follows Google's brand guidelines (white background, 1px outline, Google G
 * mark on the left, "Sign in with Google" label). Avoids embedding a raster
 * since the brand asset has strict size/colour rules and the rendering here
 * stays consistent across light/dark themes.
 */
@Composable
private fun SignInWithGoogleButton(enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = if (enabled) 1f else 0.55f))
            .border(1.dp, Color(0xFFDADCE0), RoundedCornerShape(20.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GoogleGMark(size = 18.dp)
        Spacer(Modifier.size(10.dp))
        Text(
            text = "Sign in with Google",
            color = Color(0xFF3C4043),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Four-quadrant "G" mark approximating Google's brand glyph in Compose.
 * The actual SVG is delivered via Material Icons Extended as
 * vector drawables, but the closest neutral analog rendered here keeps
 * everything self-contained and theme-stable.
 */
@Composable
private fun GoogleGMark(size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .border(
                    width = (size.value * 0.18f).dp,
                    color = Color(0xFF4285F4),
                    shape = androidx.compose.foundation.shape.CircleShape,
                ),
        )
        Text(
            text = "G",
            color = Color(0xFF4285F4),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
private fun Section(
    header: String,
    footer: String?,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = header.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
        ) { content() }
        if (footer != null) {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun formatTimestamp(value: Long): String {
    if (value <= 0L) return "never"
    val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    return df.format(Date(value))
}
