package com.bido.budgetsync.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.text.font.FontWeight
import com.bido.budgetsync.MainViewModel
import com.bido.budgetsync.data.UpdateCheck
import com.bido.budgetsync.data.Updater
import com.bido.budgetsync.sms.SmsInboxScanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val savedHost by vm.host.collectAsState()
    val savedToken by vm.token.collectAsState()
    val update by vm.update.collectAsState()
    val checking by vm.checkingUpdate.collectAsState()
    val autoDiscover by vm.autoDiscover.collectAsState()
    val smsEnabled by vm.smsEnabled.collectAsState()
    val senders by vm.senders.collectAsState()
    val found by vm.foundSenders.collectAsState()
    val status by vm.status.collectAsState()
    val syncing by vm.syncing.collectAsState()
    var host by remember(savedHost) { mutableStateOf(savedHost) }
    var token by remember(savedToken) { mutableStateOf(savedToken) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.READ_SMS] == true) vm.setSmsEnabled(true) else vm.setSmsEnabled(false)
    }
    LaunchedEffect(smsEnabled) { if (smsEnabled) vm.refreshSenders() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Laptop", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Find the laptop automatically")
                    Text(
                        "Looks for the helper on whatever Wi-Fi you're on, so a new network or IP address doesn't stop syncing. Works when phone and laptop share a network that allows it.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = autoDiscover, onCheckedChange = { vm.setAutoDiscover(it) })
            }
            OutlinedTextField(
                value = host, onValueChange = { host = it },
                label = { Text(if (autoDiscover) "Backup address (name or IP, with port)" else "Address (name or IP, with port)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token, onValueChange = { token = it }, label = { Text("Pairing token") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { vm.saveConnection(host, token) }, enabled = !syncing) { Text("Save and test connection") }
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodyMedium)

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("App updates", style = MaterialTheme.typography.titleMedium)
            val installed = remember { Updater.installedVersion(ctx) }
            Text(
                "Installed: ${installed.second} (build ${installed.first}). Updates come from your laptop and install over this app, so your settings, token, queued entries and categories are kept.",
                style = MaterialTheme.typography.bodyMedium,
            )
            when (val u = update) {
                is UpdateCheck.Available -> Text("Update available: ${u.info.versionName} (build ${u.info.versionCode})", fontWeight = FontWeight.Bold)
                is UpdateCheck.UpToDate -> Text("You have the latest version.")
                is UpdateCheck.Failed -> Text(u.message, color = MaterialTheme.colorScheme.error)
                null -> {}
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.checkForUpdate() }, enabled = !checking) { Text(if (checking) "Working…" else "Check for update") }
                if (update is UpdateCheck.Available) {
                    Button(
                        onClick = { if (Updater.canInstall(ctx)) vm.installUpdate() else Updater.openInstallPermissionSettings(ctx) },
                        enabled = !checking,
                    ) { Text(if (Updater.canInstall(ctx)) "Download and install" else "Allow installs, then tap again") }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Bank SMS detection", style = MaterialTheme.typography.titleMedium)
            Text(
                "Off by default. Messages from the senders you tick are read on this phone only; nothing from them is sent anywhere. " +
                    "Detected amounts wait in To review until you confirm them.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Detect spending SMS", Modifier.weight(1f))
                Switch(checked = smsEnabled, onCheckedChange = { on ->
                    if (!on) vm.setSmsEnabled(false)
                    else if (SmsInboxScanner.hasPermission(ctx)) vm.setSmsEnabled(true)
                    else permission.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS))
                })
            }
            if (smsEnabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tick your bank's sender", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { vm.refreshSenders() }) { Text("Refresh") }
                }
                val shown = (found + senders.filter { s -> found.none { SmsInboxScanner.sameSender(it, s) } }).take(60)
                if (shown.isEmpty()) Text("No senders found in the inbox yet.")
                shown.forEach { s ->
                    val on = senders.any { SmsInboxScanner.sameSender(it, s) }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Checkbox(checked = on, onCheckedChange = { vm.toggleSender(s, it) })
                        Text(s)
                    }
                }
            }
        }
    }
}
