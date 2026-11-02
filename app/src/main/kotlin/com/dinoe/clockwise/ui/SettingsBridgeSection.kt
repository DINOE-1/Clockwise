package com.dinoe.clockwise.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dinoe.clockwise.R
import com.dinoe.clockwise.bridge.BridgeNetwork
import com.dinoe.clockwise.bridge.BridgePreferences
import kotlinx.coroutines.launch

/**
 * Settings section for the read-only agent bridge: enable switch, port, token, and connection
 * hints. The bridge binds 0.0.0.0 by design; the warning row explains the trade-off.
 */
@Composable
fun SettingsBridgeSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val bridgeConfig by viewModel.bridgeConfig.collectAsStateWithLifecycle()
    val bridgeRunning by viewModel.bridgeRunning.collectAsStateWithLifecycle()
    val bridgeError by viewModel.bridgeError.collectAsStateWithLifecycle()

    var showPortDialog by rememberSaveable { mutableStateOf(false) }
    var tokenVisible by rememberSaveable { mutableStateOf(false) }
    var portText by rememberSaveable { mutableStateOf("") }

    val addresses = remember { BridgeNetwork.localIpv4Addresses() }

    SettingsSectionHeader(stringResource(R.string.settings_section_agent_access))
    SettingsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.setBridgeEnabled(!bridgeConfig.enabled) }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.agent_bridge_enable_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.agent_bridge_enable_helper),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = bridgeConfig.enabled,
                onCheckedChange = { viewModel.setBridgeEnabled(it) },
            )
        }
        SettingsDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    portText = bridgeConfig.port.toString()
                    showPortDialog = true
                }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.agent_bridge_port_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = bridgeConfig.port.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SettingsDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { tokenVisible = !tokenVisible }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.agent_bridge_token_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = if (tokenVisible) bridgeConfig.token else stringResource(R.string.agent_bridge_token_hidden),
                    style = MaterialTheme.typography.bodySmall
                        .copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(bridgeConfig.token))
                Toast.makeText(context, R.string.agent_bridge_token_copied, Toast.LENGTH_SHORT).show()
            }) {
                Text(stringResource(R.string.agent_bridge_token_copy))
            }
            TextButton(onClick = {
                scope.launch {
                    viewModel.rotateBridgeToken()
                    Toast.makeText(context, R.string.agent_bridge_token_rotated, Toast.LENGTH_SHORT).show()
                }
            }) {
                Text(stringResource(R.string.agent_bridge_token_rotate))
            }
        }
        SettingsDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        if (bridgeRunning) R.string.agent_bridge_status_running
                        else R.string.agent_bridge_status_stopped,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                bridgeError?.let { error ->
                    Text(
                        text = stringResource(R.string.agent_bridge_status_error, error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        SettingsDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.agent_bridge_address_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = addresses.firstOrNull()
                        ?.let { address -> "http://$address:${bridgeConfig.port}" }
                        ?: stringResource(R.string.agent_bridge_address_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.agent_bridge_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(4.dp))

    if (showPortDialog) {
        val validPort = portText.toIntOrNull()?.takeIf { it in BridgePreferences.MIN_PORT..BridgePreferences.MAX_PORT }
        AlertDialog(
            onDismissRequest = { showPortDialog = false },
            title = { Text(stringResource(R.string.agent_bridge_port_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter { char -> char.isDigit() } },
                    isError = validPort == null,
                    supportingText = {
                        if (validPort == null) {
                            Text(stringResource(R.string.agent_bridge_port_invalid))
                        }
                    },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = validPort != null,
                    onClick = {
                        showPortDialog = false
                        validPort?.let { viewModel.setBridgePort(it) }
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPortDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
