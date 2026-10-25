package com.dinoe.clockwise.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dinoe.clockwise.R
import com.dinoe.clockwise.ai.AiConfig
import com.dinoe.clockwise.ai.AiModelInfo
import com.dinoe.clockwise.ai.AiProviderConfig
import com.dinoe.clockwise.ai.AiProviderId
import com.dinoe.clockwise.ai.AiReasoningEffort
import com.dinoe.clockwise.ai.AiProviders
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(
    onBack: () -> Unit,
    viewModel: AiSettingsViewModel = viewModel(),
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    var detailKey by rememberSaveable { mutableStateOf<String?>(null) }
    val detailId = detailKey?.let { AiProviderId.fromPrefKey(it) }
    val snackbarHostState = remember { SnackbarHostState() }
    val message = viewModel.message

    LaunchedEffect(message) {
        val current = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(current)
        viewModel.clearMessage()
    }

    BackHandler {
        if (detailId != null) {
            detailKey = null
        } else {
            onBack()
        }
    }

    if (detailId == null) {
        AiSettingsList(
            config = config,
            snackbarHostState = snackbarHostState,
            onBack = onBack,
            onProviderClick = { providerId ->
                viewModel.resetTestState()
                detailKey = providerId.prefKey
            },
        )
    } else {
        AiProviderDetail(
            providerId = detailId,
            provider = config?.provider(detailId),
            isActive = config?.activeProviderId == detailId,
            viewModel = viewModel,
            snackbarHostState = snackbarHostState,
            onBack = { detailKey = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiSettingsList(
    config: AiConfig?,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onProviderClick: (AiProviderId) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.ai_settings_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.ai_settings_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (config == null) {
                LoadingBox()
            } else {
                AiProviders.all.forEach { preset ->
                    ProviderCard(
                        provider = config.provider(preset.id),
                        active = config.activeProviderId == preset.id,
                        onClick = { onProviderClick(preset.id) },
                    )
                }
            }
            Text(
                text = stringResource(R.string.ai_settings_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ProviderCard(
    provider: AiProviderConfig,
    active: Boolean,
    onClick: () -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_ai),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = provider.preset.displayName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (active) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(50),
                        ) {
                            Text(
                                text = stringResource(R.string.ai_settings_active),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
                Text(
                    text = keyStatusText(provider),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (provider.hasKey && !provider.keyError) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Text(
                    text = stringResource(R.string.ai_settings_model) + ": " + provider.modelId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiProviderDetail(
    providerId: AiProviderId,
    provider: AiProviderConfig?,
    isActive: Boolean,
    viewModel: AiSettingsViewModel,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
) {
    var keyInput by remember(providerId) { mutableStateOf("") }
    var showKey by remember(providerId) { mutableStateOf(false) }
    var showClearKey by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showManualModel by remember { mutableStateOf(false) }

    LaunchedEffect(providerId) {
        keyInput = ""
        showKey = false
        viewModel.resetTestState()
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Text(AiProviders.preset(providerId).displayName)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.ai_settings_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        if (provider == null) {
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.ai_settings_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            SettingsCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.ai_settings_api_key_label),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.ai_settings_api_key_label)) },
                        placeholder = {
                            Text(
                                stringResource(
                                    R.string.ai_settings_api_key_hint,
                                    provider.preset.keyHint,
                                ),
                            )
                        },
                        singleLine = true,
                        visualTransformation = if (showKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    Icons.Filled.Lock,
                                    contentDescription = stringResource(
                                        if (showKey) {
                                            R.string.ai_settings_hide_key
                                        } else {
                                            R.string.ai_settings_show_key
                                        },
                                    ),
                                )
                            }
                        },
                    )
                    Text(
                        text = keyStatusText(provider),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (provider.hasKey && !provider.keyError) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                viewModel.saveKey(providerId, keyInput)
                                keyInput = ""
                            },
                            enabled = keyInput.isNotBlank(),
                        ) {
                            Text(stringResource(R.string.ai_settings_api_key_save))
                        }
                        if (provider.hasKey) {
                            TextButton(onClick = { showClearKey = true }) {
                                Text(stringResource(R.string.ai_settings_api_key_clear))
                            }
                        }
                    }
                }
            }

            SettingsCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.ai_settings_model),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { showModelPicker = true },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = provider.modelId,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                contextLabel(provider)?.let { label ->
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (provider.modelsFetchedAt > 0) {
                                    Text(
                                        text = stringResource(
                                            R.string.ai_settings_last_model_refresh,
                                            formatTimestamp(provider.modelsFetchedAt),
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.ai_settings_reasoning_label),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AiReasoningEffort.entries.forEach { effort ->
                            FilterChip(
                                selected = provider.reasoningEffort == effort,
                                onClick = { viewModel.setReasoningEffort(providerId, effort) },
                                label = { Text(reasoningLabel(effort)) },
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.ai_settings_reasoning_helper),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                viewModel.refreshModels(
                                    providerId,
                                    keyInput.trim().takeIf { it.isNotEmpty() },
                                )
                            },
                            enabled = viewModel.refreshingProvider == null,
                        ) {
                            if (viewModel.refreshingProvider == providerId) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                                Text(stringResource(R.string.ai_settings_model_refreshing))
                            } else {
                                Text(stringResource(R.string.ai_settings_model_refresh))
                            }
                        }
                    }
                }
            }

            SettingsCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.ai_settings_connection),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.ai_settings_test_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                viewModel.testConnection(
                                    providerId = providerId,
                                    modelId = provider.modelId,
                                    keyOverride = keyInput.trim().takeIf { it.isNotEmpty() },
                                    reasoningEffort = provider.reasoningEffort,
                                )
                            },
                            enabled = (provider.hasKey || keyInput.isNotBlank()) &&
                                viewModel.testState !is AiTestState.Testing,
                        ) {
                            Text(stringResource(R.string.ai_settings_test))
                        }
                        when (val state = viewModel.testState) {
                            is AiTestState.Testing -> if (state.providerId == providerId) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    text = stringResource(R.string.ai_settings_testing),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }

                            is AiTestState.Success -> if (state.providerId == providerId) {
                                Text(
                                    text = stringResource(
                                        R.string.ai_settings_test_success,
                                        state.latencyMillis,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }

                            is AiTestState.Failure -> if (state.providerId == providerId) {
                                Text(
                                    text = stringResource(
                                        R.string.ai_settings_test_failed,
                                        state.message,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }

                            AiTestState.Idle -> Unit
                        }
                    }
                    if (isActive) {
                        Text(
                            text = stringResource(R.string.ai_settings_active),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        OutlinedButton(
                            onClick = { viewModel.setActive(providerId) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.ai_settings_set_active))
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.ai_settings_endpoint) + ": " +
                    provider.preset.baseUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.ai_settings_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showClearKey) {
        AlertDialog(
            onDismissRequest = { showClearKey = false },
            title = { Text(stringResource(R.string.ai_settings_clear_key_title)) },
            text = { Text(stringResource(R.string.ai_settings_clear_key_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearKey(providerId)
                        showClearKey = false
                    },
                ) {
                    Text(stringResource(R.string.ai_settings_api_key_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearKey = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showModelPicker && provider != null) {
        ModelPickerDialog(
            provider = provider,
            refreshing = viewModel.refreshingProvider == providerId,
            onRefresh = {
                viewModel.refreshModels(
                    providerId,
                    keyInput.trim().takeIf { it.isNotEmpty() },
                )
            },
            onSelect = { model ->
                viewModel.setModel(providerId, model.id)
                showModelPicker = false
            },
            onManual = {
                showModelPicker = false
                showManualModel = true
            },
            onDismiss = { showModelPicker = false },
        )
    }

    if (showManualModel && provider != null) {
        ManualModelDialog(
            initial = provider.modelId,
            onConfirm = { modelId ->
                viewModel.setModel(providerId, modelId)
                showManualModel = false
            },
            onDismiss = { showManualModel = false },
        )
    }
}

@Composable
private fun ModelPickerDialog(
    provider: AiProviderConfig,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onSelect: (AiModelInfo) -> Unit,
    onManual: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(provider.models, query) {
        if (query.isBlank()) {
            provider.models
        } else {
            provider.models.filter {
                it.id.contains(query, ignoreCase = true) ||
                    it.name.contains(query, ignoreCase = true)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.ai_settings_model_picker_title),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRefresh, enabled = !refreshing) {
                    if (refreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(
                                R.string.ai_settings_model_refresh,
                            ),
                        )
                    }
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.ai_settings_model_search)) },
                    singleLine = true,
                )
                if (filtered.isEmpty()) {
                    Text(
                        text = stringResource(R.string.ai_settings_model_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 340.dp),
                    ) {
                        items(filtered, key = { it.id }) { model ->
                            val selected = model.id == provider.modelId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.medium)
                                    .clickable { onSelect(model) }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = model.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = model.id,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    model.contextLength?.let { length ->
                                        Text(
                                            text = stringResource(
                                                R.string.ai_settings_model_context,
                                                formatContext(length),
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (selected) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = stringResource(
                                            R.string.ai_settings_active,
                                        ),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = onManual) {
                    Text(stringResource(R.string.ai_settings_model_manual))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.alarm_permission_close))
            }
        },
    )
}

@Composable
private fun ManualModelDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_settings_model_manual)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.ai_settings_model_manual_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.trim()) },
                enabled = value.isNotBlank(),
            ) {
                Text(stringResource(R.string.ai_settings_manual_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun LoadingBox() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun reasoningLabel(effort: AiReasoningEffort): String = stringResource(
    when (effort) {
        AiReasoningEffort.OFF -> R.string.ai_reasoning_off
        AiReasoningEffort.LOW -> R.string.ai_reasoning_low
        AiReasoningEffort.MEDIUM -> R.string.ai_reasoning_medium
        AiReasoningEffort.HIGH -> R.string.ai_reasoning_high
    },
)

@Composable
private fun keyStatusText(provider: AiProviderConfig): String = when {
    provider.keyError -> stringResource(R.string.ai_settings_key_error)
    provider.hasKey -> stringResource(
        R.string.ai_settings_key_set,
        provider.keyLast4.orEmpty(),
    )

    else -> stringResource(R.string.ai_settings_key_missing)
}

@Composable
private fun contextLabel(provider: AiProviderConfig): String? {
    val model = provider.models.firstOrNull { it.id == provider.modelId } ?: return null
    val length = model.contextLength ?: return null
    return stringResource(R.string.ai_settings_model_context, formatContext(length))
}

private fun formatContext(value: Long): String = when {
    value >= 1_000_000 -> {
        val millions = value / 1_000_000.0
        if (millions == millions.toLong().toDouble()) {
            "${millions.toLong()}M"
        } else {
            String.format(Locale.US, "%.1fM", millions)
        }
    }

    value >= 1_000 -> "${value / 1_000}K"
    else -> value.toString()
}

@Composable
private fun formatTimestamp(millis: Long): String =
    rememberDatePatternFormatter("yMMMdHm")
        .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
