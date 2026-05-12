package com.aeriotv.android.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.feature.playlist.PlaylistViewModel
import java.text.DateFormat
import java.util.Date

/**
 * Settings root — list of sub-section rows plus the legacy Playlist + About
 * cards retained at the bottom. Mirrors the iOS Settings root layout.
 *
 * Section navigation routes through [onSectionClick]; the parent owner
 * (Navigation.kt) decides which composable each section maps to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onSectionClick: (SettingsSection) -> Unit,
    onOpenPlaylistDetail: () -> Unit = {},
    onOpenPlaylists: () -> Unit = {},
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    val packageInfo = remember {
        runCatching {
            val pm = context.packageManager
            val pkg = context.packageName
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
        }.getOrNull()
    }
    val installedAt = packageInfo?.firstInstallTime ?: 0L
    val updatedAt = packageInfo?.lastUpdateTime ?: 0L
    val versionName = packageInfo?.versionName ?: "0.1.0"

    Column(modifier = Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(SettingsSection.entries, key = { it.name }) { section ->
                SectionRow(
                    section = section,
                    onClick = { onSectionClick(section) },
                )
            }
            item {
                Spacer(Modifier.size(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(Modifier.size(12.dp))
            }
            item {
                SectionLabel("Playlists")
                Spacer(Modifier.size(8.dp))
                val playlist = state.playlist
                if (playlist != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.45f))
                            .clickable(onClick = onOpenPlaylistDetail)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = playlist.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Text(
                                text = "${playlist.channelCount} channels • ${playlist.sourceType}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = "▸",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(
                        "No playlist loaded.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onOpenPlaylists,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Manage playlists")
                    }
                    OutlinedButton(
                        onClick = { confirmClear = true },
                        enabled = playlist != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Change playlist")
                    }
                }
            }
            item {
                Spacer(Modifier.size(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(Modifier.size(12.dp))
                SectionLabel("About")
                Spacer(Modifier.size(8.dp))
                LabeledValue("Device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim())
                LabeledValue("System", "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                LabeledValue("App Version", "$versionName (${packageInfo?.longVersionCode ?: 0})")
                LabeledValue("First Installed", formatInstallTime(installedAt))
                LabeledValue(
                    "Last Updated",
                    if (updatedAt > 0 && updatedAt != installedAt) formatInstallTime(updatedAt) else "Never",
                )
                Spacer(Modifier.size(12.dp))
                AboutLinkRow(
                    label = "Copy to Clipboard",
                    onClick = {
                        val text = buildAboutClipboard(
                            versionName,
                            packageInfo?.longVersionCode ?: 0L,
                            installedAt,
                            updatedAt,
                        )
                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("AerioTV diagnostics", text))
                        android.widget.Toast.makeText(context, "Copied diagnostics to clipboard.", android.widget.Toast.LENGTH_SHORT).show()
                    },
                )
                AboutLinkRow(
                    label = "Developer Website",
                    onClick = {
                        openUrl(context, "https://github.com/jonzey231/AerioTV-Android")
                    },
                )
                AboutLinkRow(
                    label = "Report an Issue",
                    onClick = {
                        openUrl(context, "https://github.com/jonzey231/AerioTV-Android/issues/new")
                    },
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Change playlist?") },
            text = {
                Text(
                    "This will remove the current playlist and EPG. You'll be asked for " +
                            "a new playlist URL. Your saved preferences are not affected.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearPlaylist()
                }) { Text("Change") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SectionRow(
    section: SettingsSection,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.45f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = section.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = section.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = section.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun AboutLinkRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "▸",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatInstallTime(ms: Long): String {
    if (ms <= 0L) return "Unknown"
    return DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
}

private fun buildAboutClipboard(
    versionName: String,
    versionCode: Long,
    installedAt: Long,
    updatedAt: Long,
): String = buildString {
    appendLine("AerioTV diagnostics")
    appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim())
    appendLine("System: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
    appendLine("App Version: $versionName ($versionCode)")
    appendLine("First Installed: ${formatInstallTime(installedAt)}")
    appendLine("Last Updated: ${if (updatedAt > 0 && updatedAt != installedAt) formatInstallTime(updatedAt) else "Never"}")
}

private fun openUrl(context: android.content.Context, url: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure {
            android.widget.Toast.makeText(
                context,
                "No browser available to open $url",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
}

/**
 * Settings tree sections, in display order. Carries title/subtitle/icon for
 * the parent SettingsScreen list and a stable identifier for navigation.
 */
enum class SettingsSection(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
) {
    Appearance(
        title = "Appearance",
        subtitle = "Theme palette, custom accent, category colours",
        icon = Icons.Filled.Palette,
    ),
    AppBehaviors(
        title = "App Behaviors",
        subtitle = "Splash, default tab, channel-flip, auto-resume",
        icon = Icons.Outlined.PlayCircle,
    ),
    Multiview(
        title = "Multiview",
        subtitle = "Audio-focus indicator, tile padding, corners",
        icon = Icons.Filled.GridView,
    ),
    Network(
        title = "Network",
        subtitle = "Buffer size, timeouts, retries, background refresh",
        icon = Icons.Filled.Wifi,
    ),
    Sync(
        title = "Sync",
        subtitle = "Google Drive sync (Block Store + AppData)",
        icon = Icons.Filled.Cloud,
    ),
    DvrSettings(
        title = "DVR Settings",
        subtitle = "Local storage cap, pre/post-roll defaults, folder",
        icon = Icons.Filled.Movie,
    ),
    Developer(
        title = "Developer",
        subtitle = "Diagnostic toggles, debug overlays",
        icon = Icons.Filled.Build,
    ),
}
