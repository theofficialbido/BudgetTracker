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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bido.budgetsync.MainViewModel
import com.bido.budgetsync.data.Calc
import com.bido.budgetsync.data.CategorySummary
import com.bido.budgetsync.data.Entry
import com.bido.budgetsync.data.MonthSummary
import com.bido.budgetsync.data.UpdateCheck
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date

/** Side margin for everything below the banner, which itself runs edge to edge. */
private val Side = Modifier.padding(horizontal = 16.dp)

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
    val reminderHour by vm.reminderHour.collectAsState()
    val haptics = LocalHapticFeedback.current

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
        containerColor = MaterialTheme.colorScheme.background,
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
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
                FloatingActionButton(
                    onClick = { fabOpen = !fabOpen },
                    containerColor = if (fabOpen) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.secondary,
                    contentColor = if (fabOpen) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSecondary,
                ) {
                    Icon(if (fabOpen) Icons.Default.Close else Icons.Default.Add, if (fabOpen) "Close menu" else "Add")
                }
            }
        },
    ) { pad ->
        val refresh = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = syncing,
            onRefresh = { vm.sync() },
            state = refresh,
            modifier = Modifier.fillMaxSize().padding(bottom = pad.calculateBottomPadding()),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = refresh, isRefreshing = syncing,
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
                )
            },
        ) {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "summary") { BalanceBanner(summary, neverSynced = state == null, onHistory, onSettings) }

                pendingClose?.let { p ->
                    item(key = "close") {
                        MonthCloseCard(p, onDecide = { reset, invested, splurged -> vm.closeMonth(p.month, reset, invested, splurged) }, modifier = Side)
                    }
                }

                if (pending.isNotEmpty()) {
                    item(key = "pending") {
                        NoticeCard(Icons.Default.Sms, MaterialTheme.colorScheme.tertiary, Side.clickable(onClick = onReview)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${pending.size} bank message${if (pending.size == 1) "" else "s"} to review", fontWeight = FontWeight.SemiBold)
                                    Text("Detected from SMS, waiting for you to confirm.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = onReview) { Text("Review") }
                            }
                        }
                    }
                }

                (update as? UpdateCheck.Available)?.let { available ->
                    item(key = "update") {
                        NoticeCard(Icons.Default.SystemUpdate, MaterialTheme.colorScheme.secondary, Side.clickable(onClick = onSettings)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Update available (${available.info.versionName})", fontWeight = FontWeight.SemiBold)
                                    Text("Your data is kept.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = onSettings) { Text("Install") }
                            }
                        }
                    }
                }

                if (forecast.plannedTotal > 0 || forecast.leftToSpend != 0.0) {
                    item(key = "pace") { PaceCard(forecast, Side) }
                }

                if (templates.isNotEmpty()) {
                    item(key = "quick") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SectionTitle("Quick add", Side)
                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(templates.take(5)) { t ->
                                    AssistChip(
                                        onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); vm.quickAdd(t) },
                                        label = { Text(t.label) },
                                        leadingIcon = { Icon(categoryIcon(t.category), null, Modifier.size(18.dp), tint = categoryColor(t.category)) },
                                        shape = CircleShape,
                                        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surface),
                                    )
                                }
                            }
                        }
                    }
                }

                item(key = "sync") { SyncRow(waiting, status, lastSync, syncing, Side) { vm.sync() } }

                item(key = "cat-title") {
                    Row(Side.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("Categories", Modifier.weight(1f))
                        TextButton(onClick = { addingCategory = true }, modifier = Modifier.padding(top = 8.dp)) { Text("Add category") }
                    }
                }
                items(summary.categories, key = { "cat-${it.name}" }) { t ->
                    CategoryCard(t, Side) { onCategory(t.name) }
                }

                item(key = "recent-title") { SectionTitle("Recent", Side) }
                item(key = "filters") {
                    val options = listOf<String?>(null, MainViewModel.INCOME) + categories
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(options) { o ->
                            FilterChip(selected = filter == o, onClick = { filter = o }, label = { Text(o ?: "All") })
                        }
                    }
                }

                if (shown.isEmpty()) {
                    item(key = "empty") {
                        if (entries.isEmpty()) {
                            EmptyState(Icons.AutoMirrored.Filled.ReceiptLong, "Nothing here yet", "Tap + to add your first expense.")
                        } else {
                            EmptyState(Icons.AutoMirrored.Filled.ReceiptLong, "Nothing here", "Nothing in this filter.")
                        }
                    }
                }
                var lastLabel = ""
                shown.forEach { e ->
                    val label = dateLabel(e.date?.toString() ?: "")
                    if (label != lastLabel) {
                        lastLabel = label
                        item(key = "h-$label-${e.key}") {
                            Text(
                                label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Side.padding(top = 4.dp),
                            )
                        }
                    }
                    item(key = e.key) {
                        EntryRow(e, Side.animateItem()) { editing = e }
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
            title = { Text("Remind you at ${hourLabel(reminderHour)}?") },
            text = { Text("Every evening at ${hourLabel(reminderHour)} the app can remind you to log the day's expenses, so nothing gets forgotten. It needs permission to send notifications.") },
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

/** The banner: what is left to spend this month, how far through the plan you are, and the three totals. */
@Composable
private fun BalanceBanner(summary: MonthSummary, neverSynced: Boolean, onHistory: () -> Unit, onSettings: () -> Unit) {
    GradientHeader(
        title = "Budget",
        subtitle = monthLabel(summary.month),
        actions = {
            HeaderAction(Icons.Default.BarChart, "History and trends", onHistory)
            HeaderAction(Icons.Default.Settings, "Settings", onSettings)
        },
    ) {
        Text("Left to spend", color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, modifier = Modifier.padding(top = 18.dp))
        val animated by animateFloatAsState(summary.left.toFloat(), tween(700), label = "left")
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                money(animated.toDouble()),
                color = if (summary.left < 0) onBannerStatus("OVER") else Color.White,
                fontSize = 40.sp, fontWeight = FontWeight.Bold,
            )
            Text(" EGP", color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp, modifier = Modifier.padding(bottom = 7.dp))
        }
        when {
            summary.carriedIn > 0.5 -> "Includes ${money(summary.carriedIn)} carried over"
            summary.carriedIn < -0.5 -> "Starts ${money(-summary.carriedIn)} in the red"
            else -> null
        }?.let { Text(it, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp) }

        val planned = summary.total.planned
        val spentFrac = if (planned <= 0) (if (summary.total.actual > 0) 1f else 0f) else (summary.total.actual / planned).toFloat().coerceIn(0f, 1f)
        val animFrac by animateFloatAsState(spentFrac, tween(700), label = "spent")
        HeaderProgress(animFrac, onBannerStatus(summary.total.status))
        HeaderTiles(
            Triple("Received", money(summary.incomeReceived), Color.White),
            Triple("Spent", money(summary.total.actual), Color.White),
            Triple("Planned", money(planned), Color.White),
        )
        if (neverSynced && planned <= 0) {
            Text(
                "Set a plan in each category below, or sync once to bring your plans in from the workbook. " +
                    "Left to spend only needs the income and spending you enter here.",
                color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/** What you can spend per day, and whether the month is on course, from this month's pace so far. */
@Composable
private fun PaceCard(f: Calc.Forecast, modifier: Modifier = Modifier) {
    AppCard(modifier) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Speed, null, tint = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                        Text(" EGP a day", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        "to last the month: ${money(f.leftToSpend)} EGP left, ${f.daysLeft} day${if (f.daysLeft == 1) "" else "s"} to go.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        fontSize = 13.sp, color = statusColor("WATCH"),
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncRow(waiting: Int, status: String, lastSync: Long, syncing: Boolean, modifier: Modifier = Modifier, onSync: () -> Unit) {
    val ok = waiting == 0 && status.startsWith("Synced")
    val dot = when {
        syncing -> MaterialTheme.colorScheme.outline
        ok -> incomeColor()
        waiting > 0 -> statusColor("WATCH")
        else -> statusColor("OVER")
    }
    AppCard(modifier.animateContentSize()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.size(12.dp))
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
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onSync, enabled = !syncing) { Text("Sync now") }
        }
    }
}

@Composable
private fun CategoryCard(t: CategorySummary, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val color = statusColor(t.status)
    val frac = if (t.planned <= 0) (if (t.actual > 0) 1f else 0f) else (t.actual / t.planned).toFloat().coerceIn(0f, 1f)
    val anim by animateFloatAsState(frac, tween(700), label = "cat")
    AppCard(modifier.clickable(onClick = onClick).animateContentSize()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(t.name)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.name, fontWeight = FontWeight.Medium, fontSize = 16.sp)
                    Text(
                        "${money(t.actual)} of ${money(t.planned)} EGP",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Pill(t.status, color)
            }
            CardProgress(anim, color, Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
fun IconBadge(category: String) {
    val color = categoryColor(category)
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(color.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) { Icon(categoryIcon(category), null, tint = color) }
}

@Composable
fun EntryRow(e: Entry, modifier: Modifier = Modifier, onClick: () -> Unit) {
    AppCard(modifier.clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(e.category)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.description.ifBlank { e.category }, maxLines = 1, fontSize = 16.sp)
                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    CategoryTag(e.category, Modifier.widthIn(max = 150.dp))
                    if (e.waiting) {
                        Text("  waiting to sync", fontSize = 12.sp, color = statusColor("WATCH"), maxLines = 1)
                    }
                }
            }
            Text(
                (if (e.isIncome) "+" else "") + moneyPrecise(e.amount),
                fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                color = if (e.isIncome) incomeColor() else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
