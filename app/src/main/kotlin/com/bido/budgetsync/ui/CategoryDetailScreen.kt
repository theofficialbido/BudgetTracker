package com.bido.budgetsync.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.sp
import com.bido.budgetsync.MainViewModel
import com.bido.budgetsync.data.Calc
import com.bido.budgetsync.data.Entry
import java.time.LocalDate
import java.time.YearMonth

private val Side = Modifier.padding(horizontal = 16.dp)

/** What one expense group (same description) added up to inside a category. */
private data class Group(val label: String, val entries: List<Entry>) {
    val total get() = entries.sumOf { it.amount }
}

/** Tap a category on Home to land here: where the money in it went, grouped by what it was spent on, then every entry. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryDetailScreen(vm: MainViewModel, category: String, categories: List<String>, initialMonth: String?, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    val allEntries by vm.entries.collectAsState()
    val custom by vm.customCategories.collectAsState()
    var editing by remember { mutableStateOf<Entry?>(null) }

    var monthText by rememberSaveable { mutableStateOf(initialMonth ?: YearMonth.now().toString()) }
    val month = YearMonth.parse(monthText)
    val isIncome = category.equals(Calc.INCOME, ignoreCase = true)

    val plans by vm.plans.collectAsState()
    val closings by vm.closings.collectAsState()
    var changingPlan by remember { mutableStateOf(false) }
    val summary = remember(state, allEntries, custom, month, plans, closings) {
        Calc.summarize(state, allEntries, custom, month, plans, closings)
    }
    val line = summary.categories.firstOrNull { it.name.equals(category, ignoreCase = true) }
    val items = remember(allEntries, category, month) {
        allEntries
            .filter { it.category.equals(category, ignoreCase = true) && it.date?.let { d -> YearMonth.from(d) } == month }
            .sortedWith(compareByDescending<Entry> { it.date ?: LocalDate.MIN }.thenByDescending { it.local?.createdAt ?: (it.key.drop(2).toLongOrNull() ?: 0L) })
    }
    val total = items.sumOf { it.amount }
    val groups = remember(items) {
        items.groupBy { it.description.trim().lowercase() }
            .map { (_, list) -> Group(list.first().description.trim().ifBlank { "(no description)" }, list) }
            .sortedByDescending { it.total }
    }
    val expanded = remember { mutableStateOf(setOf<String>()) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
        LazyColumn(
            Modifier.padding(bottom = pad.calculateBottomPadding()),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") {
                GradientHeader(title = category, subtitle = if (isIncome) "Income" else "Category", onBack = onBack) {
                    // Month switcher, white on the banner.
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp).background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(50)),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        IconButton(onClick = { monthText = month.minusMonths(1).toString() }) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month", tint = Color.White)
                        }
                        Text(monthLabel(month), color = Color.White, fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = { monthText = month.plusMonths(1).toString() }, enabled = month < YearMonth.now()) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month",
                                tint = Color.White.copy(alpha = if (month < YearMonth.now()) 1f else 0.35f),
                            )
                        }
                    }

                    Text(if (isIncome) "Received" else "Spent", color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(money(total), color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
                        Text(" EGP", color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp, modifier = Modifier.padding(bottom = 6.dp))
                        Spacer(Modifier.weight(1f))
                        if (line != null) {
                            Pill(line.status, onBannerStatus(line.status), Modifier.padding(bottom = 8.dp))
                        }
                    }
                    if (line != null) {
                        val frac = if (line.planned <= 0) (if (line.actual > 0) 1f else 0f) else (line.actual / line.planned).toFloat().coerceIn(0f, 1f)
                        val anim by animateFloatAsState(frac, tween(600), label = "detail")
                        HeaderProgress(anim, onBannerStatus(line.status))
                        Text(
                            "of ${money(line.planned)} EGP planned" + if (line.planned > 0) " (${(line.percent * 100).toInt()}%)" else "",
                            color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (items.isNotEmpty()) {
                        Text(
                            "${items.size} ${if (items.size == 1) "entry" else "entries"}, average ${moneyPrecise(total / items.size)} EGP",
                            color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }

            if (line != null) {
                item(key = "plan") {
                    AppCard(Side) {
                        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Monthly plan", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (line.planned > 0) "${money(line.planned)} EGP" else "Not set", fontWeight = FontWeight.SemiBold)
                            }
                            androidx.compose.material3.TextButton(onClick = { changingPlan = true }) { Text("Change plan") }
                        }
                    }
                }
            }

            if (items.isEmpty()) {
                item(key = "empty") {
                    EmptyState(categoryIcon(category), "Nothing here", "Nothing in $category in ${monthLabel(month)}.")
                }
            } else {
                item(key = "where-title") { SectionTitle("Where it went", Side) }
                items(groups, key = { "g-${it.label}" }) { g ->
                    val open = g.label in expanded.value
                    val share = if (total <= 0) 0f else (g.total / total).toFloat().coerceIn(0f, 1f)
                    val animShare by animateFloatAsState(share, tween(600), label = "share")
                    AppCard(
                        Side.clickable {
                            expanded.value = if (open) expanded.value - g.label else expanded.value + g.label
                        }.animateContentSize(),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(g.label, fontWeight = FontWeight.Medium, maxLines = 1)
                                    Text(
                                        "${g.entries.size}× · ${(share * 100).toInt()}% of ${category.lowercase()}",
                                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(moneyPrecise(g.total), fontWeight = FontWeight.SemiBold)
                                Icon(
                                    if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    if (open) "Collapse" else "Expand",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                            CardProgress(animShare, accentFor(category), Modifier.padding(top = 10.dp))
                            AnimatedVisibility(open) {
                                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    g.entries.forEach { e ->
                                        Row {
                                            Text(dateLabel(e.date?.toString() ?: ""), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                            Text(moneyPrecise(e.amount), style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item(key = "all-title") { SectionTitle("All entries", Side) }
                items(items, key = { "e-${it.key}" }) { e ->
                    EntryRow(e, Side) { editing = e }
                }
            }
        }
    }

    editing?.let { EntryEditor(vm, it, categories, onDismiss = { editing = null }) }

    if (changingPlan && line != null) {
        var text by remember(line.name) { mutableStateOf(java.math.BigDecimal.valueOf(line.planned).stripTrailingZeros().toPlainString()) }
        val value = com.bido.budgetsync.data.Amounts.parse(text)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { changingPlan = false },
            title = { Text("Plan for ${line.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedTextField(
                        value = text, onValueChange = { text = com.bido.budgetsync.data.Amounts.clean(it) },
                        label = { Text("EGP a month") }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Used for this category's progress and alerts. It is copied to Budget.xlsx for your briefs on the next sync.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { vm.setPlan(line.name, value!!); changingPlan = false },
                    enabled = value != null && value >= 0,
                ) { Text("Save") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { changingPlan = false }) { Text("Cancel") } },
        )
    }
}
