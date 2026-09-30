package com.bido.budgetsync.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bido.budgetsync.MainViewModel
import com.bido.budgetsync.data.Expense
import com.bido.budgetsync.data.ServerState
import com.bido.budgetsync.data.TrackerLine
import java.text.DateFormat
import java.util.Date

/** One line in the recent list: a row already in Budget.xlsx, or a phone entry still waiting to sync. */
private data class Entry(
    val key: String,
    val date: String,
    val category: String,
    val description: String,
    val amount: Double,
    val waiting: Expense?,
) {
    val isIncome get() = category == MainViewModel.INCOME
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel, onAdd: (income: Boolean) -> Unit, onReview: () -> Unit, onSettings: () -> Unit) {
    val state by vm.state.collectAsState()
    val expenses by vm.expenses.collectAsState()
    val pending by vm.pending.collectAsState()
    val status by vm.status.collectAsState()
    val syncing by vm.syncing.collectAsState()
    val lastSync by vm.lastSyncMs.collectAsState()

    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var fabOpen by rememberSaveable { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Expense?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val waiting = expenses.filter { !it.synced }
    val fromWorkbook = (state?.log?.map { Entry("r-${it.row}", it.date ?: "", it.category, it.description, it.amount ?: 0.0, null) } ?: emptyList()) +
        (state?.incomeLog?.map { Entry("i-${it.row}", it.date ?: "", MainViewModel.INCOME, it.source, it.amount ?: 0.0, null) } ?: emptyList())
    // newest first: by date, then workbook row
    val entries = waiting.map { Entry("w-${it.id}", it.date, it.category, it.description, it.amount, it) } +
        fromWorkbook.sortedWith(compareByDescending<Entry> { it.date }.thenByDescending { it.key.drop(2).toIntOrNull() ?: 0 }).take(60)
    val shown = entries.filter { filter == null || it.category == filter }.take(40)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Budget Tracker", fontWeight = FontWeight.Bold) },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") } },
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
                item(key = "summary") { SummaryCard(state) }

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

                item(key = "sync") { SyncRow(waiting.size, status, lastSync, syncing) { vm.sync() } }

                state?.let { s ->
                    item(key = "cat-title") {
                        Text("Categories", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
                    }
                    items(s.tracker, key = { "cat-${it.category}" }) { t ->
                        CategoryCard(t, selected = filter == t.category) { filter = if (filter == t.category) null else t.category }
                    }
                }

                item(key = "recent-title") {
                    Text("Recent", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
                }
                item(key = "filters") {
                    val options = listOf<String?>(null, MainViewModel.INCOME) + (state?.categories ?: vm.defaultCategories)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(options) { o ->
                            FilterChip(
                                selected = filter == o,
                                onClick = { filter = o },
                                label = { Text(o ?: "All") },
                            )
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
                    val label = dateLabel(e.date)
                    if (label != lastLabel) {
                        lastLabel = label
                        item(key = "h-$label-${e.key}") {
                            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    item(key = e.key) {
                        EntryRow(e, Modifier.animateItem()) { e.waiting?.let { toDelete = it } }
                    }
                }
            }
        }
    }

    toDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete this entry?") },
            text = { Text("${e.description.ifBlank { e.category }}, ${moneyPrecise(e.amount)} EGP. It hasn't reached Budget.xlsx yet, so this removes it completely.") },
            confirmButton = { TextButton(onClick = { vm.deleteWaiting(e); toDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun SummaryCard(state: ServerState?) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(20.dp)) {
            if (state == null) {
                Text("Nothing from the laptop yet", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Open Settings, enter the pairing token, and keep the laptop helper running. You can still add expenses; they'll sync later.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp),
                )
                return@Column
            }
            Text("Left after actual spending", style = MaterialTheme.typography.labelLarge)
            val animated by animateFloatAsState(state.leftAfterActual.toFloat(), tween(700), label = "left")
            Text(
                money(animated.toDouble()) + " EGP",
                style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
                color = if (state.leftAfterActual < 0) statusColor("OVER") else MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text("Month ${state.month}", style = MaterialTheme.typography.bodySmall)

            val planned = state.total.planned
            val spentFrac = if (planned <= 0) (if (state.total.actual > 0) 1f else 0f) else (state.total.actual / planned).toFloat().coerceIn(0f, 1f)
            val animFrac by animateFloatAsState(spentFrac, tween(700), label = "spent")
            LinearProgressIndicator(
                progress = { animFrac },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(10.dp).clip(RoundedCornerShape(5.dp)),
                color = statusColor(state.total.status),
                trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
            )
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("Spent", money(state.total.actual))
                Stat("Planned", money(planned))
                Stat("Received", money(state.incomeReceived), incomeColor())
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified) {
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
                    waiting > 0 -> "$waiting waiting to sync. ${status}"
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
private fun CategoryCard(t: TrackerLine, selected: Boolean, onClick: () -> Unit) {
    val color = statusColor(t.status)
    val frac = if (t.planned <= 0) (if (t.actual > 0) 1f else 0f) else (t.actual / t.planned).toFloat().coerceIn(0f, 1f)
    val anim by animateFloatAsState(frac, tween(700), label = "cat")
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick).animateContentSize(),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(t.category)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.category, fontWeight = FontWeight.Medium)
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
private fun IconBadge(category: String) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(accentFor(category).copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) { Icon(categoryIcon(category), null, tint = accentFor(category)) }
}

@Composable
private fun EntryRow(e: Entry, modifier: Modifier, onWaitingClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .then(if (e.waiting != null) Modifier.clickable(onClick = onWaitingClick) else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(e.category)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.description.ifBlank { e.category }, maxLines = 1)
            Text(
                if (e.waiting != null) "${e.category} · waiting to sync (tap to delete)" else e.category,
                style = MaterialTheme.typography.bodySmall,
                color = if (e.waiting != null) statusColor("WATCH") else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            (if (e.isIncome) "+" else "") + moneyPrecise(e.amount),
            fontWeight = FontWeight.SemiBold,
            color = if (e.isIncome) incomeColor() else MaterialTheme.colorScheme.onSurface,
        )
    }
}
