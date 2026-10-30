package com.dinoe.clockwise.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dinoe.clockwise.R
import com.dinoe.clockwise.data.BackupRepository
import com.dinoe.clockwise.data.IcsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Explicit JSON export/import. Android cloud backup is disabled, so this is the only supported
 * way to move calendar data between installations. The API key and AI chat history are excluded.
 */
@Composable
fun SettingsBackupSection() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val repository = remember(context) { BackupRepository(context) }
    val icsRepository = remember(context) { IcsRepository(context) }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    var pendingIcsImport by remember { mutableStateOf<Uri?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val message = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        repository.exportJson(stream)
                    } ?: error("Unable to open the selected file")
                }
                resources.getString(R.string.backup_export_success)
            }.getOrElse {
                resources.getString(R.string.backup_export_failed)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) pendingImport = uri
    }

    val icsExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/calendar"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val message = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        icsRepository.exportIcs(stream)
                    } ?: error("Unable to open the selected file")
                }
                resources.getString(R.string.ics_export_success)
            }.getOrElse {
                resources.getString(R.string.ics_export_failed)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val icsImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) pendingIcsImport = uri
    }

    SettingsSectionHeader(stringResource(R.string.settings_section_data))
    SettingsCard {
        SettingsActionRow(
            title = stringResource(R.string.backup_export_title),
            subtitle = stringResource(R.string.backup_export_subtitle),
            onClick = {
                exportLauncher.launch("clockwise-backup-${LocalDate.now()}.json")
            },
            icon = Icons.Filled.Share,
        )
        SettingsDivider()
        SettingsActionRow(
            title = stringResource(R.string.backup_import_title),
            subtitle = stringResource(R.string.backup_import_subtitle),
            onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
            icon = Icons.Filled.Refresh,
        )
        SettingsDivider()
        SettingsActionRow(
            title = stringResource(R.string.ics_export_title),
            subtitle = stringResource(R.string.ics_export_subtitle),
            onClick = {
                icsExportLauncher.launch("clockwise-calendar-${LocalDate.now()}.ics")
            },
            icon = Icons.Filled.Share,
        )
        SettingsDivider()
        SettingsActionRow(
            title = stringResource(R.string.ics_import_title),
            subtitle = stringResource(R.string.ics_import_subtitle),
            onClick = {
                icsImportLauncher.launch(
                    arrayOf(
                        "text/calendar",
                        "text/plain",
                        "application/octet-stream",
                        "*/*",
                    ),
                )
            },
            icon = Icons.Filled.Refresh,
        )
    }
    Spacer(modifier = Modifier.height(4.dp))

    pendingImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.backup_import_confirm_title)) },
            text = { Text(stringResource(R.string.backup_import_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingImport = null
                        scope.launch {
                            val message = runCatching {
                                val text = withContext(Dispatchers.IO) {
                                    val declaredSize = runCatching {
                                        context.contentResolver
                                            .openFileDescriptor(uri, "r")
                                            ?.use { it.statSize }
                                    }.getOrNull()
                                    context.contentResolver.openInputStream(uri)?.use { stream ->
                                        repository.readImportText(stream, declaredSize)
                                    } ?: error("Unable to read the selected file")
                                }
                                val result = withContext(Dispatchers.IO) {
                                    repository.importJson(text)
                                }
                                resources.getString(
                                    R.string.backup_import_success,
                                    result.events,
                                    result.alarms,
                                    result.tags,
                                )
                            }.getOrElse { error ->
                                resources.getString(
                                    R.string.backup_import_failed,
                                    error.message ?: resources.getString(R.string.backup_invalid_file),
                                )
                            }
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    },
                ) {
                    Text(
                        text = stringResource(R.string.backup_import_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    pendingIcsImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingIcsImport = null },
            title = { Text(stringResource(R.string.ics_import_confirm_title)) },
            text = { Text(stringResource(R.string.ics_import_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingIcsImport = null
                        scope.launch {
                            val message = runCatching {
                                val text = withContext(Dispatchers.IO) {
                                    val declaredSize = runCatching {
                                        context.contentResolver
                                            .openFileDescriptor(uri, "r")
                                            ?.use { it.statSize }
                                    }.getOrNull()
                                    context.contentResolver.openInputStream(uri)?.use { stream ->
                                        icsRepository.readImportText(stream, declaredSize)
                                    } ?: error("Unable to read the selected file")
                                }
                                val result = withContext(Dispatchers.IO) {
                                    icsRepository.importIcs(text)
                                }
                                if (result.skipped > 0) {
                                    resources.getString(
                                        R.string.ics_import_success_skipped,
                                        result.imported,
                                        result.skipped,
                                    )
                                } else {
                                    resources.getString(
                                        R.string.ics_import_success,
                                        result.imported,
                                    )
                                }
                            }.getOrElse { error ->
                                resources.getString(
                                    R.string.ics_import_failed,
                                    error.message ?: resources.getString(R.string.ics_invalid_file),
                                )
                            }
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    },
                ) {
                    Text(
                        text = stringResource(R.string.ics_import_action),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingIcsImport = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
