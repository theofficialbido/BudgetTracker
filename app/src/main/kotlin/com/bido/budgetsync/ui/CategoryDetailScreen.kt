package com.bido.budgetsync.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bido.budgetsync.MainViewModel
import com.bido.budgetsync.data.Calc
import com.bido.budgetsync.data.Entry
import java.time.LocalDate
import java.time.YearMonth

/** What one expense group (same description) added up to inside a category. */
private data class Group(val label: String, val entries: List<Entry>) {
    val total get() = entries.sumOf { it.amount }
}

/** Tap a category on Home to land here: where the money in it went, grouped by what it was spent on, then every entry. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryDetailScreen(vm: MainViewModel, category: String, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    val allEntries by vm.entries.collectAsState()
    val custom by vm.customCategories.collectAsState()

    var monthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    val month = YearMonth.parse(monthText)
    val isIncome = category.equals(Calc.INCOME, ignoreCase = true)

    val summary = remember(state, allEntries, custom, month) { Calc.summarize(state, allEntries, custom, month) }
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

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(category) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { pad ->
        LazyColumn(
            Modifier.padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "month") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = { monthText = month.minusMonths(1).toString() }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month")
                    }
                    Text(monthLabel(month), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { monthText = month.plusMonths(1).toString() }, enabled = month < YearMonth.now()) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month")
                    }
                }
            }

            item(key = "header") {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(category)
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (isIncome) "Received" else "Spent", style = MaterialTheme.typography.labelLarge)
                                Text(
                                    money(total) + " EGP", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                                    color = if (isIncome) incomeColor() else MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            if (line != null) {
                                Text(line.status, color = statusColor(line.status), fontWeight = FontWeight.Bold)
                            }
                        }
                        if (line != null) {
                            val frac = if (line.planned <= 0) (if (line.actual > 0) 1f else 0f) else (line.actual / line.planned).toFloat().coerceIn(0f, 1f)
                            val anim by animateFloatAsState(frac, tween(600), label = "detail")
                            LinearProgressIndicator(
                                progress = { anim },
                                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(10.dp).clip(RoundedCornerShape(5.dp)),
                                color = statusColor(line.status),
                                trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                            )
                            Text(
                                "of ${money(line.planned)} EGP planned" + if (line.planned > 0) " (${(line.percent * 100).toInt()}%)" else "",
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        if (items.isNotEmpty()) {
                            Text(
                                "${items.size} ${if (items.size == 1) "entry" else "entries"}, average ${moneyPrecise(total / items.size)} EGP",
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }

            if (items.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "Nothing in $category in ${monthLabel(month)}.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                item(key = "where-title") { Text("Where it went", style = MaterialTheme.typography.titleMedium) }
                items(groups, key = { "g-${it.label}" }) { g ->
                    val open = g.label in expanded.value
                    val share = if (total <= 0) 0f else (g.total / total).toFloat().coerceIn(0f, 1f)
                    val animShare by animateFloatAsState(share, tween(600), label = "share")
                    Card(
                        Modifier.fillMaxWidth().clickable {
                            expanded.value = if (open) expanded.value - g.label else expanded.value + g.label
                        }.animateContentSize(),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(g.label, fontWeight = FontWeight.Medium, maxLines = 1)
                                    Text(
                                        "${g.entries.size}× · ${(share * 100).toInt()}% of ${category.lowercase()}",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(moneyPrecise(g.total), fontWeight = FontWeight.SemiBold)
                            }
                            LinearProgressIndicator(
                                progress = { animShare },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = accentFor(category),
                            )
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

                item(key = "all-title") { Text("All entries", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp)) }
                items(items, key = { "e-${it.key}" }) { e ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.description.ifBlank { category }, maxLines = 1)
                            Text(
                                dateLabel(e.date?.toString() ?: "") + if (e.waiting) " · waiting to sync" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (e.waiting) statusColor("WATCH") else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            (if (isIncome) "+" else "") + moneyPrecise(e.amount), fontWeight = FontWeight.SemiBold,
                            color = if (isIncome) incomeColor() else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}
