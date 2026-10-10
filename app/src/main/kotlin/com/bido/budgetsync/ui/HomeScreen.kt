package com.bido.budgetsync.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bido.budgetsync.MainViewModel
import com.bido.budgetsync.data.Calc
import com.bido.budgetsync.data.CategorySummary
import com.bido.budgetsync.data.Entry
import com.bido.budgetsync.data.MonthSummary
import com.bido.budgetsync.data.UpdateCheck
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: MainViewModel,
    categories: List<String>,
    onAdd: (income: Boolean) -> Unit,
    onCategory: (String) -> Unit,
    onHistory: () -> Unit,
    onReview: () -> Unit,
    onSettings: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val summary by vm.summary.collectAsState()
    val forecast by vm.forecast.collectAsState()
    val pendingClose by vm.pendingClose.collectAsState()
    val templates by vm.expenseTemplates.collectAsState()
    val allEntries by vm.entries.collectAsState()
    val pending by vm.pending.collectAsState()
    val status by vm.status.collectAsState()
    val syncing by vm.syncing.collectAsState()
    val lastSync by vm.lastSyncMs.collectAsState()
    val update by vm.update.collectAsState()

    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var fabOpen by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Entry?>(null) }
    var addingCategory by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        vm.messages.collect { m ->
            val result = snackbar.showSnackbar(m.text, actionLabel = if (m.undo != null) "Undo" else null, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) m.undo?.invoke()
        }
    }

    val waiting = allEntries.count { it.waiting }
    // Waiting entries first, then newest by date.
    val entries = allEntries.sortedWith(
        compareByDescending<Entry> { it.waiting }
            .thenByDescending { it.date ?: LocalDate.MIN }
            .thenByDescending { it.local?.createdAt ?: (it.row?.toLong() ?: 0L) },
    )
    val shown = entries.filter { filter == null || it.category.equals(filter, ignoreCase = true) }.take(40)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Budget Tracker", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onHistory) { Icon(Icons.Default.BarChart, "History and trends") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AnimatedVisibility(fabOpen, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ExtendedFloatingActionButton(
                            onClick = { fabOpen = false; onAdd(true) },
                            icon = { Icon(categoryIcon(MainViewModel.INCOME), null) },
                            text = { Text("Add income") },
                            containerColor = incomeColor(),
                            contentColor = MaterialTheme.colorScheme.surface,
                        )
                        ExtendedFloatingActionButton(
                            onClick = { fabOpen = false; onAdd(false) },
                            icon = { Icon(Icons.Default.Add, null) },
                            text = { Text("Add expense") },
                        )
                    }
                }
                FloatingActionButton(onClick = { fabOpen = !fabOpen }) {
                    Icon(if (fabOpen) Icons.Default.Close else Icons.Default.Add, if (fabOpen) "Close menu" else "Add")
                }
            }
        },
    ) { pad ->
        PullToRefreshBox(isRefreshing = syncing, onRefresh = { vm.sync() }, modifier = Modifier.padding(pad)) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "summary") { SummaryCard(summary, neverSynced = state == null) }

                pendingClose?.let { p ->
                    item(key = "close") { MonthCloseCard(p, onDecide = { reset, invested, splurged -> vm.closeMonth(p.month, reset, invested, splurged) }) }
                }

                if (forecast.plannedTotal > 0 || forecast.leftToSpend != 0.0) {
                    item(key = "pace") { PaceCard(forecast) }
                }

                if (templates.isNotEmpty()) {
                    item(key = "quick") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Quick add", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(templates.take(5)) { t ->
                                    AssistChip(
                                        onClick = { vm.quickAdd(t) },
                                        label = { Text(t.label) },
                                        leadingIcon = { Icon(categoryIcon(t.category), null, Modifier.size(18.dp)) },
                                    )
                                }
                            }
                        }
                    }
                }

                if (pending.isNotEmpty()) {
                    item(key = "pending") {
                        Card(
                            Modifier.fillMaxWidth().clickable(onClick = onReview),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                        ) {
                            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${pending.size} bank message${if (pending.size == 1) "" else "s"} to review", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                                TextButton(onClick = onReview) { Text("Review") }
                            }
                        }
                    }
                }

                (update as? UpdateCheck.Available)?.let { available ->
                    item(key = "update") {
                        Card(
                            Modifier.fillMaxWidth().clickable(onClick = onSettings),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        ) {
                            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Update available (${available.info.versionName}). Your data is kept.", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                                TextButton(onClick = onSettings) { Text("Install") }
                            }
                        }
                    }
                }

                item(key = "sync") { SyncRow(waiting, status, lastSync, syncing) { vm.sync() } }

                item(key = "cat-title") {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Categories", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { addingCategory = true }) { Text("Add category") }
                    }
                }
                items(summary.categories, key = { "cat-${it.name}" }) { t ->
                    CategoryCard(t) { onCategory(t.name) }
                }

                item(key = "recent-title") {
                    Text("Recent", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
                }
                item(key = "filters") {
                    val options = listOf<String?>(null, MainViewModel.INCOME) + categories
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(options) { o ->
                            FilterChip(selected = filter == o, onClick = { filter = o }, label = { Text(o ?: "All") })
                        }
                    }
                }

                if (shown.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            if (entries.isEmpty()) "Nothing here yet. Tap + to add your first expense." else "Nothing in this filter.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                var lastLabel = ""
                shown.forEach { e ->
                    val label = dateLabel(e.date?.toString() ?: "")
                    if (label != lastLabel) {
                        lastLabel = label
                        item(key = "h-$label-${e.key}") {
                            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    item(key = e.key) {
                        EntryRow(e, Modifier.animateItem()) { editing = e }
                    }
                }
            }
        }
    }

    editing?.let { EntryEditor(vm, it, categories, onDismiss = { editing = null }) }

    // Ask once for permission to send the 9pm reminder (Android 13 and later need it to be granted).
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var askNotifications by remember {
        mutableStateOf(!com.bido.budgetsync.data.Prefs(ctx).askedNotifications && !com.bido.budgetsync.data.Alerts.hasPermission(ctx))
    }
    val notificationPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { askNotifications = false }
    if (askNotifications) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { com.bido.budgetsync.data.Prefs(ctx).askedNotifications = true; askNotifications = false },
            title = { Text("Remind you at 9pm?") },
            text = { Text("Every evening at 9pm the app can remind you to log the day's expenses, so nothing gets forgotten. It needs permission to send notifications.") },
            confirmButton = {
                TextButton(onClick = {
                    com.bido.budgetsync.data.Prefs(ctx).askedNotifications = true
                    notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = { com.bido.budgetsync.data.Prefs(ctx).askedNotifications = true; askNotifications = false }) { Text("Not now") }
            },
        )
    }

    if (addingCategory) {
        AddCategoryDialog(onAdd = { name, planned -> vm.addCategory(name, planned) }, onDismiss = { addingCategory = false })
    }
}

@Composable
private fun SummaryCard(summary: MonthSummary, neverSynced: Boolean) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(20.dp)) {
            Text("Left to spend", style = MaterialTheme.typography.labelLarge)
            val animated by animateFloatAsState(summary.left.toFloat(), tween(700), label = "left")
            Text(
                money(animated.toDouble()) + " EGP",
                style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
                color = if (summary.left < 0) statusColor("OVER") else MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                monthLabel(summary.month) + when {
                    summary.carriedIn > 0.5 -> " · includes ${money(summary.carriedIn)} carried over"
                    summary.carriedIn < -0.5 -> " · starts ${money(-summary.carriedIn)} in the red"
                    else -> ""
                },
                style = MaterialTheme.typography.bodySmall,
            )

            val planned = summary.total.planned
            val spentFrac = if (planned <= 0) (if (summary.total.actual > 0) 1f else 0f) else (summary.total.actual / planned).toFloat().coerceIn(0f, 1f)
            val animFrac by animateFloatAsState(spentFrac, tween(700), label = "spent")
            LinearProgressIndicator(
                progress = { animFrac },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(10.dp).clip(RoundedCornerShape(5.dp)),
                color = statusColor(summary.total.status),
                trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
            )
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("Received", money(summary.incomeReceived), incomeColor())
                Stat("Spent", money(summary.total.actual))
                Stat("Planned", money(planned))
            }
            if (neverSynced && planned <= 0) {
                Text(
                    "Set a plan in each category below, or sync once to bring your plans in from the workbook. " +
                        "Left to spend only needs the income and spending you enter here.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

/** What you can spend per day, and whether the month is on course, from this month's pace so far. */
@Composable
private fun PaceCard(f: Calc.Forecast) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (f.leftToSpend <= 0) {
                Text("Nothing left to spend", fontWeight = FontWeight.Bold, color = statusColor("OVER"))
                Text(
                    "${f.daysLeft} day${if (f.daysLeft == 1) "" else "s"} left this month. Add income when it arrives and this updates.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("About ", style = MaterialTheme.typography.bodyMedium)
                    Text(money(f.safePerDay), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(" EGP a day to last the month", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "${money(f.leftToSpend)} EGP left to spend, ${f.daysLeft} day${if (f.daysLeft == 1) "" else "s"} to go.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            f.projectedTotal?.let { projected ->
                val over = projected > f.plannedTotal
                Text(
                    "At this pace you'll spend ${money(projected)} of ${money(f.plannedTotal)} EGP planned.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (over) statusColor("WATCH") else MaterialTheme.colorScheme.onSurface,
                )
            }
            f.atRisk.forEach { c ->
                Text(
                    "${c.name} is heading for ${money(c.projected)} against a ${money(c.planned)} plan.",
                    style = MaterialTheme.typography.bodySmall, color = statusColor("WATCH"),
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color = Color.Unspecified) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun SyncRow(waiting: Int, status: String, lastSync: Long, syncing: Boolean, onSync: () -> Unit) {
    val ok = waiting == 0 && status.startsWith("Synced")
    val dot = when {
        syncing -> MaterialTheme.colorScheme.outline
        ok -> incomeColor()
        waiting > 0 -> statusColor("WATCH")
        else -> statusColor("OVER")
    }
    Row(Modifier.fillMaxWidth().animateContentSize(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    syncing -> "Syncing…"
                    waiting > 0 -> "$waiting waiting to sync. $status"
                    else -> status.ifBlank { "Not synced yet" }
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (lastSync > 0) {
                Text(
                    "Last synced " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(lastSync)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = onSync, enabled = !syncing) { Text("Sync now") }
    }
}

@Composable
private fun CategoryCard(t: CategorySummary, onClick: () -> Unit) {
    val color = statusColor(t.status)
    val frac = if (t.planned <= 0) (if (t.actual > 0) 1f else 0f) else (t.actual / t.planned).toFloat().coerceIn(0f, 1f)
    val anim by animateFloatAsState(frac, tween(700), label = "cat")
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick).animateContentSize()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(t.name)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.name, fontWeight = FontWeight.Medium)
                    Text(
                        "${money(t.actual)} of ${money(t.planned)} EGP",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(t.status, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            }
            LinearProgressIndicator(
                progress = { anim },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = color,
            )
        }
    }
}

@Composable
fun IconBadge(category: String) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(accentFor(category).copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) { Icon(categoryIcon(category), null, tint = accentFor(category)) }
}

@Composable
fun EntryRow(e: Entry, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(e.category)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.description.ifBlank { e.category }, maxLines = 1)
            Text(
                if (e.waiting) "${e.category} · waiting to sync" else e.category,
                style = MaterialTheme.typography.bodySmall,
                color = if (e.waiting) statusColor("WATCH") else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            (if (e.isIncome) "+" else "") + moneyPrecise(e.amount),
            fontWeight = FontWeight.SemiBold,
            color = if (e.isIncome) incomeColor() else MaterialTheme.colorScheme.onSurface,
        )
    }
}
