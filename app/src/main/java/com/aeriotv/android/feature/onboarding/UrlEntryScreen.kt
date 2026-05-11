package com.aeriotv.android.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.feature.playlist.PlaylistViewModel

@Composable
fun UrlEntryScreen(
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))

            Text(
                text = "AerioTV",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Add a source.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Spacer(Modifier.height(8.dp))

            SourceTypePicker(
                selected = state.sourceType,
                onSelect = viewModel::onSourceTypeChange,
                enabled = !state.isLoading,
            )

            when (state.sourceType) {
                SourceType.M3uUrl -> M3uFields(state, viewModel)
                SourceType.DispatcharrApiKey -> DispatcharrApiKeyFields(state, viewModel)
                SourceType.DispatcharrUserPass,
                SourceType.XtreamCodes -> NotYetSupportedNote(state.sourceType)
            }

            state.error?.let { msg ->
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = { viewModel.loadPlaylist() },
                enabled = !state.isLoading && state.url.isNotBlank() &&
                        state.sourceType.isImplemented,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 14.dp),
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Load channels")
                }
            }
        }
    }
}

@Composable
private fun SourceTypePicker(
    selected: SourceType,
    onSelect: (SourceType) -> Unit,
    enabled: Boolean,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(SourceType.entries.toList()) { type ->
            val isSelected = type == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(type) },
                label = { Text(type.displayName) },
                enabled = enabled && type.isImplemented,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        }
    }
}

@Composable
private fun M3uFields(
    state: PlaylistViewModel.UiState,
    vm: PlaylistViewModel,
) {
    OutlinedTextField(
        value = state.url,
        onValueChange = vm::onUrlChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Playlist URL (required)") },
        placeholder = { Text("https://example.com/playlist.m3u") },
        isError = state.error != null,
        enabled = !state.isLoading,
    )
    OutlinedTextField(
        value = state.epgUrl,
        onValueChange = vm::onEpgUrlChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("EPG URL (optional)") },
        placeholder = { Text("https://example.com/guide.xml.gz") },
        enabled = !state.isLoading,
    )
}

@Composable
private fun DispatcharrApiKeyFields(
    state: PlaylistViewModel.UiState,
    vm: PlaylistViewModel,
) {
    OutlinedTextField(
        value = state.url,
        onValueChange = vm::onUrlChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Dispatcharr server URL (required)") },
        placeholder = { Text("https://dispatcharr.example.com") },
        isError = state.error != null,
        enabled = !state.isLoading,
    )
    OutlinedTextField(
        value = state.apiKey,
        onValueChange = vm::onApiKeyChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Admin API key (required)") },
        placeholder = { Text("from Dispatcharr -> Users -> Edit -> API & XC") },
        visualTransformation = PasswordVisualTransformation(),
        enabled = !state.isLoading,
    )
    Text(
        text = "M3U and XMLTV are fetched from /output/m3u and /output/epg with the X-API-Key header.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NotYetSupportedNote(type: SourceType) {
    Text(
        text = "${type.displayName} support is coming in a later phase.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}
