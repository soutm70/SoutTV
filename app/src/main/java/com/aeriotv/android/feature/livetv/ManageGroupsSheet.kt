package com.aeriotv.android.feature.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * Manage Groups bottom sheet. Mirrors iOS Settings > Manage Groups modal:
 * a scrollable checkbox list with "All / None" toggles in the header. Checked
 * groups stay visible in the Live TV filter row; unchecked groups disappear
 * from the chips but their channels remain in "All" so they can still be
 * found via search.
 *
 * Persistence layer is the [hiddenGroups] set in AppPreferences. We work on
 * a mutable copy and commit on Done so toggling rapidly doesn't churn the
 * DataStore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageGroupsSheet(
    allGroups: List<String>,
    hiddenGroups: Set<String>,
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var working by remember(hiddenGroups) { mutableStateOf(hiddenGroups.toMutableSet()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 320.dp, max = 600.dp)
                .padding(bottom = 8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Manage Groups",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    onSave(working.toSet())
                    onDismiss()
                }) {
                    Text("Done", color = MaterialTheme.colorScheme.primary)
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Check groups to show, uncheck to hide.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { working = mutableSetOf() }) {
                    Text("All", color = MaterialTheme.colorScheme.primary)
                }
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                TextButton(onClick = { working = allGroups.toMutableSet() }) {
                    Text("None", color = MaterialTheme.colorScheme.primary)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            if (allGroups.isEmpty()) {
                // Mirrors iOS ManageGroupsSheet.swift line 50-53 empty
                // state. Hit when the active playlist has no #EXTINF
                // group-title field set on any channel -- the Manage
                // button still appears but tapping it would otherwise
                // show a blank list with no explanation.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No groups available",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                items(allGroups, key = { it }) { group ->
                    val visible = group !in working
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                working = working.toMutableSet().apply {
                                    if (visible) add(group) else remove(group)
                                }
                            }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = visible,
                            onCheckedChange = { checked ->
                                working = working.toMutableSet().apply {
                                    if (checked) remove(group) else add(group)
                                }
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = group,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

/**
 * TV-native group on/off picker: a centered, D-pad-driven dialog instead of the
 * touch bottom sheet. Each row toggles live and the focused row is highlighted,
 * mirroring the tvOS guide's "Toggle groups on or off" popup. [onToggle] passes
 * the group and its new visibility so the caller updates hiddenGroups.
 */
@Composable
fun TvGroupPicker(
    allGroups: List<String>,
    hiddenGroups: Set<String>,
    onToggle: (group: String, visible: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            modifier = Modifier
                .width(640.dp)
                .heightIn(max = 640.dp),
        ) {
            Column(modifier = Modifier.padding(vertical = 20.dp)) {
                Text(
                    text = "Toggle groups on or off to show or hide them.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                if (allGroups.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No groups available",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    return@Column
                }
                val firstFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(allGroups, key = { _, g -> g }) { index, group ->
                        val visible = group !in hiddenGroups
                        var focused by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                    else Color.Transparent,
                                )
                                .border(
                                    width = if (focused) 2.dp else 0.dp,
                                    color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = RoundedCornerShape(12.dp),
                                )
                                .then(
                                    if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                                )
                                .onFocusChanged { focused = it.isFocused }
                                .clickable { onToggle(group, !visible) }
                                .padding(horizontal = 22.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = group,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = if (visible) "On" else "Off",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (visible) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
