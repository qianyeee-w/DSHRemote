package io.github.qianyeeew.dshremote.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.qianyeeew.dshremote.R

@Composable
fun ProjectPickerDialog(
    state: ProjectState,
    hostName: String,
    onBrowse: (String?) -> Unit,
    onName: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
    onStartSession: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            ProjectPickerContent(state, hostName, onBrowse, onName, onSubmit, onDismiss, onStartSession)
        }
    }
}

@Composable
fun ProjectPickerContent(
    state: ProjectState,
    hostName: String,
    onBrowse: (String?) -> Unit,
    onName: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
    onStartSession: () -> Unit,
) {
    val listing = state.listing
    val busy = state.loading || state.writing
    val blocked = state.error in setOf(ProjectError.NEEDS_PAIRING, ProjectError.FINGERPRINT, ProjectError.UPGRADE)
    var path by rememberSaveable(listing?.path) { mutableStateOf(listing?.path.orEmpty()) }
    var hidden by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(listing, state.loading, state.error) {
        if (!state.loading && state.error == null && listing != null) path = listing.path
    }
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDismiss, enabled = !state.writing) { Text(stringResource(R.string.action_back)) }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(
                        if (state.mode ==
                            ProjectMode.CREATE
                        ) {
                            R.string.project_create
                        } else {
                            R.string.project_import
                        },
                    ),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(hostName, style = MaterialTheme.typography.bodySmall)
            }
        }
        val workspace = state.workspace
        if (workspace != null) {
            Text(stringResource(R.string.project_ready), style = MaterialTheme.typography.titleMedium)
            Text(workspace.title)
            Text(workspace.path, style = MaterialTheme.typography.bodySmall)
            Button(onClick = onStartSession, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.project_start_session)) }
            return@Column
        }
        if (state.registrationPath == null) {
            OutlinedTextField(
                value = path,
                onValueChange = { path = it },
                label = { Text(stringResource(R.string.project_path)) },
                singleLine = true,
                enabled = !busy && !blocked,
                modifier = Modifier.fillMaxWidth(),
            )
            Row {
                TextButton(
                    onClick = { onBrowse(path) },
                    enabled = !busy && !blocked && path.isNotBlank(),
                ) { Text(stringResource(R.string.project_go)) }
                TextButton(onClick = {
                    onBrowse(listing?.parent)
                }, enabled = !busy && !blocked && listing?.parent != null) { Text(stringResource(R.string.project_parent)) }
                TextButton(onClick = { onBrowse(null) }, enabled = !busy && !blocked) { Text(stringResource(R.string.project_home)) }
                TextButton(
                    onClick = { onBrowse(listing?.path) },
                    enabled = !busy && !blocked,
                ) { Text(stringResource(R.string.project_refresh)) }
            }
            if (state.mode == ProjectMode.CREATE) {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = onName,
                    label = { Text(stringResource(R.string.project_name)) },
                    singleLine = true,
                    enabled = !busy && !blocked,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Text(state.registrationPath, style = MaterialTheme.typography.bodyMedium)
        }
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(if (state.writing) R.string.project_saving else R.string.project_loading))
        }
        if (state.uncertainCreation) {
            Text(stringResource(R.string.project_uncertain), color = MaterialTheme.colorScheme.error)
        } else if (state.directoryCreated && state.error != null) {
            Text(stringResource(R.string.project_partial), color = MaterialTheme.colorScheme.error)
        }
        state.error?.let { Text(stringResource(projectErrorText(it)), color = MaterialTheme.colorScheme.error) }
        if (state.registrationPath == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = hidden, onCheckedChange = { hidden = it })
                Text(stringResource(R.string.project_hidden), style = MaterialTheme.typography.bodySmall)
            }
            LazyColumn(Modifier.weight(1f)) {
                if (listing != null && listing.entries.none { hidden || !it.hidden } && !busy) {
                    item { Text(stringResource(R.string.project_empty)) }
                }
                items(listing?.entries.orEmpty().filter { hidden || !it.hidden }, key = { it.name }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.clickable(enabled = !busy && !blocked) { onBrowse(entry.path) },
                    )
                }
                if (listing?.truncated ==
                    true
                ) {
                    item { Text(stringResource(R.string.project_truncated), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        Button(
            onClick = onSubmit,
            enabled =
                !busy &&
                    !blocked &&
                    !state.uncertainCreation &&
                    listing != null &&
                    (
                        state.registrationPath != null ||
                            path == listing.path &&
                            state.error == null &&
                            (state.mode == ProjectMode.IMPORT || state.name.isNotBlank())
                    ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    when {
                        state.registrationPath != null -> R.string.project_retry_register
                        state.mode == ProjectMode.CREATE -> R.string.project_create_here
                        else -> R.string.project_import_here
                    },
                ),
            )
        }
    }
}

private fun projectErrorText(error: ProjectError): Int =
    when (error) {
        ProjectError.OFFLINE -> R.string.new_session_offline
        ProjectError.NEEDS_PAIRING -> R.string.new_session_needs_pairing
        ProjectError.FINGERPRINT -> R.string.project_fingerprint
        ProjectError.UPGRADE -> R.string.project_upgrade
        ProjectError.INVALID -> R.string.project_invalid
        ProjectError.UNAVAILABLE -> R.string.project_unavailable
        ProjectError.EXISTS -> R.string.project_exists
        ProjectError.FAILED -> R.string.project_failed
    }
