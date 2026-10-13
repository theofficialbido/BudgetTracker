package com.bido.budgetsync.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.sp
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import com.bido.budgetsync.MainViewModel
import com.bido.budgetsync.data.Alerts
import com.bido.budgetsync.data.UpdateCheck
import com.bido.budgetsync.data.Updater
import com.bido.budgetsync.sms.SmsInboxScanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val savedHost by vm.host.collectAsState()
    val savedToken by vm.token.collectAsState()
    // Re-read the "install unknown apps" permission when coming back from Android's settings screen.
    var canInstall by remember { mutableStateOf(Updater.canInstall(ctx)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) canInstall = Updater.canInstall(ctx) }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val alertsCategory by vm.alertsCategory.collectAsState()
    val alertsReminder by vm.alertsReminder.collectAsState()
    var pendingAlert by remember { mutableStateOf<String?>(null) }
    fun applyAlert(which: String, on: Boolean) {
        if (which == "category") vm.setAlerts(category = on) else vm.setAlerts(reminder = on)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingAlert?.let { applyAlert(it, true) }
        pendingAlert = null
    }
    fun toggleAlert(which: String, on: Boolean) {
        when {
            !on || Alerts.hasPermission(ctx) -> applyAlert(which, on)
            else -> { pendingAlert = which; notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }
    val update by vm.update.collectAsState()
    val checking by vm.checkingUpdate.collectAsState()
    val syncEnabled by vm.syncEnabled.collectAsState()
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

    val installed = remember { Updater.installedVersion(ctx) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
        Column(
            Modifier
                .padding(bottom = pad.calculateBottomPadding())
                .verticalScroll(rememberScrollState())
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GradientHeader(title = "Settings", subtitle = "Budget ${installed.second}", onBack = onBack)

            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("Laptop")
                AppCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SwitchRow(
                            "Sync with the laptop",
                            "Everything works on the phone without it. Syncing keeps a copy on the laptop and feeds your morning and evening briefs.",
                            syncEnabled,
                        ) { vm.setSyncEnabled(it) }
                        SwitchRow(
                            "Find the laptop automatically",
                            "Looks for the helper on whatever Wi-Fi you're on, so a new network or IP address doesn't stop syncing. Works when phone and laptop share a network that allows it.",
                            autoDiscover,
                        ) { vm.setAutoDiscover(it) }
                        OutlinedTextField(
                            value = host, onValueChange = { host = it },
                            label = { Text(if (autoDiscover) "Backup address (name or IP, with port)" else "Address (name or IP, with port)") },
                            singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = token, onValueChange = { token = it }, label = { Text("BaseCamp code (same as in the ToDo app)") },
                            singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
                        )
                        Button(onClick = { vm.saveConnection(host, token) }, enabled = !syncing, modifier = Modifier.fillMaxWidth()) {
                            Text("Save and test connection")
                        }
                        if (status.isNotBlank()) Text(status, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                SectionTitle("App updates")
                AppCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Installed: ${installed.second} (build ${installed.first})", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Updates come from your laptop and install over this app, so your settings, token, queued entries and categories are kept.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        when (val u = update) {
                            is UpdateCheck.Available -> Pill("Update available: ${u.info.versionName} (build ${u.info.versionCode})", MaterialTheme.colorScheme.primary)
                            is UpdateCheck.UpToDate -> Pill("You have the latest version", incomeColor())
                            is UpdateCheck.Failed -> Text(u.message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                            null -> {}
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.checkForUpdate() }, enabled = !checking) { Text(if (checking) "Working…" else "Check for update") }
                            if (update is UpdateCheck.Available) {
                                Button(
                                    onClick = { if (canInstall) vm.installUpdate() else Updater.openInstallPermissionSettings(ctx) },
                                    enabled = !checking,
                                ) { Text(if (canInstall) "Download and install" else "Allow installs, then tap again") }
                            }
                        }
                    }
                }

                SectionTitle("Alerts and reminders")
                AppCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Worked out on the phone, so they work without the laptop.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SwitchRow("Tell me when a category reaches WATCH or OVER", null, alertsCategory) { toggleAlert("category", it) }
                        SwitchRow(
                            "Remind me at 9pm to log the day's expenses",
                            "Every evening, with what you've logged so far. Tapping it opens Add expense.",
                            alertsReminder,
                        ) { toggleAlert("reminder", it) }
                    }
                }

                SectionTitle("Bank SMS detection")
                AppCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SwitchRow(
                            "Detect spending SMS",
                            "Off by default. Messages from the senders you tick are read on this phone only; nothing from them is sent anywhere. " +
                                "Detected amounts wait in To review until you confirm them.",
                            smsEnabled,
                        ) { on ->
                            if (!on) vm.setSmsEnabled(false)
                            else if (SmsInboxScanner.hasPermission(ctx)) vm.setSmsEnabled(true)
                            else permission.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS))
                        }
                        if (smsEnabled) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Tick your bank's sender", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                TextButton(onClick = { vm.refreshSenders() }) { Text("Refresh") }
                            }
                            val shown = (found + senders.filter { s -> found.none { SmsInboxScanner.sameSender(it, s) } }).take(60)
                            if (shown.isEmpty()) Text("No senders found in the inbox yet.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        }
    }
}

/** A setting with a title, an optional explanation, and a switch on the right. */
@Composable
private fun SwitchRow(title: String, explanation: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            explanation?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.padding(start = 12.dp))
    }
}
