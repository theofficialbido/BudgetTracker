package com.bido.budgetsync.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Past months at a glance: a bar for each month's spending, and the selected month compared with the one before. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: MainViewModel, onCategory: (name: String, month: String) -> Unit, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    val entries by vm.entries.collectAsState()
    val custom by vm.customCategories.collectAsState()

    val now = YearMonth.now()
    var selectedText by rememberSaveable { mutableStateOf(now.toString()) }
    val selected = YearMonth.parse(selectedText)

    val plans by vm.plans.collectAsState()
    val closings by vm.closings.collectAsState()
    val points = remember(state, entries, custom, now, plans, closings) { Calc.history(state, entries, custom, now, 6, plans, closings) }
    val current = remember(state, entries, custom, selected, plans, closings) { Calc.summarize(state, entries, custom, selected, plans, closings) }
    val previous = remember(state, entries, custom, selected, plans, closings) { Calc.summarize(state, entries, custom, selected.minusMonths(1), plans, closings) }
    val rows = current.categories.filter { it.actual > 0 || previous.categories.firstOrNull { p -> p.name == it.name }?.actual.let { a -> a != null && a > 0 } }
        .sortedByDescending { it.actual }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("History and trends") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { pad ->
        LazyColumn(
            Modifier.padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "chart") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Spending by month", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Bars are what you spent; the green mark is what you received. Tap a month.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                        MonthBars(points, selected) { selectedText = it.toString() }
                    }
                }
            }

            item(key = "summary") {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(monthLabel(selected), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Stat2("Spent", money(current.total.actual), null)
                            Stat2("Received", money(current.incomeReceived), incomeColor())
                            Stat2("Left to spend", money(current.left), if (current.left < 0) statusColor("OVER") else null)
                        }
                        val diff = current.total.actual - previous.total.actual
                        if (previous.total.actual > 0 || current.total.actual > 0) {
                            Text(
                                when {
                                    previous.total.actual <= 0 -> "Nothing recorded in ${shortMonth(selected.minusMonths(1))} to compare with."
                                    diff > 0 -> "${money(diff)} EGP more than ${shortMonth(selected.minusMonths(1))}."
                                    diff < 0 -> "${money(-diff)} EGP less than ${shortMonth(selected.minusMonths(1))}."
                                    else -> "The same as ${shortMonth(selected.minusMonths(1))}."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            if (rows.isEmpty()) {
                item(key = "empty") { Text("Nothing recorded in ${monthLabel(selected)}.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                item(key = "cat-title") { Text("Where it went", style = MaterialTheme.typography.titleMedium) }
                val biggest = rows.maxOf { it.actual }.coerceAtLeast(1.0)
                items(rows, key = { "c-${it.name}" }) { c ->
                    val before = previous.categories.firstOrNull { it.name == c.name }?.actual ?: 0.0
                    val change = c.actual - before
                    val anim by animateFloatAsState((c.actual / biggest).toFloat(), tween(600), label = "bar")
                    Card(Modifier.fillMaxWidth().clickable { onCategory(c.name, selected.toString()) }) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconBadge(c.name)
                                Spacer(Modifier.size(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(c.name, fontWeight = FontWeight.Medium)
                                    Text(
                                        when {
                                            before <= 0 && c.actual > 0 -> "new this month"
                                            change > 0 -> "▲ ${money(change)} vs ${shortMonth(selected.minusMonths(1))}"
                                            change < 0 -> "▼ ${money(-change)} vs ${shortMonth(selected.minusMonths(1))}"
                                            else -> "same as ${shortMonth(selected.minusMonths(1))}"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (change > 0) statusColor("WATCH") else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(money(c.actual), fontWeight = FontWeight.SemiBold)
                            }
                            LinearProgressIndicator(
                                progress = { anim },
                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = accentFor(c.name),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun shortMonth(m: YearMonth) = m.format(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH))

@Composable
private fun Stat2(label: String, value: String, color: androidx.compose.ui.graphics.Color?) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, fontWeight = FontWeight.Bold, color = color ?: androidx.compose.ui.graphics.Color.Unspecified)
    }
}

/** A bar for each month's spending, a green tick for income, the chosen month highlighted. */
@Composable
private fun MonthBars(points: List<Calc.MonthPoint>, selected: YearMonth, onSelect: (YearMonth) -> Unit) {
    val top = points.maxOf { maxOf(it.spent, it.income) }.coerceAtLeast(1.0)
    val chartHeight = 130.dp
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
        points.forEach { p ->
            val isSelected = p.month == selected
            val bar by animateFloatAsState((p.spent / top).toFloat(), tween(600), label = "m-bar")
            val tick = (p.income / top).toFloat().coerceIn(0f, 1f)
            Column(
                Modifier.weight(1f).clickable { onSelect(p.month) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(money(p.spent), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                Box(Modifier.fillMaxWidth().height(chartHeight), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier.width(26.dp).fillMaxHeight(bar.coerceIn(0.02f, 1f)).clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    )
                    if (p.income > 0) {
                        Box(
                            Modifier.fillMaxHeight(tick).fillMaxWidth(),
                            contentAlignment = Alignment.TopCenter,
                        ) { Box(Modifier.width(38.dp).height(3.dp).background(incomeColor())) }
                    }
                }
                Text(
                    shortMonth(p.month), style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
