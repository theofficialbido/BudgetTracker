package com.bido.budgetsync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bido.budgetsync.data.PendingClose
import java.time.format.TextStyle
import java.util.Locale

private fun YearMonthName(m: java.time.YearMonth) = m.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

/**
 * Shown on Home once a month has ended with a decision still open: carry what is left into the new month, or use it
 * (invest, treat yourself) and start the new month at 0.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MonthCloseCard(p: PendingClose, onDecide: (reset: Boolean, invested: Double, splurged: Double) -> Unit) {
    var choosing by remember(p.month) { mutableStateOf(false) }
    val name = YearMonthName(p.month)
    val next = YearMonthName(p.month.plusMonths(1))
    val left = p.balance > 0

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (left) "$name ended with ${money(p.balance)} EGP left" else "$name ended ${money(-p.balance)} EGP over",
                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (left) "Carry it into $next, or put it to use and start $next at 0?"
                else "Carry the shortfall into $next, or start $next fresh at 0?",
                style = MaterialTheme.typography.bodyMedium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onDecide(false, 0.0, 0.0) }) { Text(if (left) "Carry it over" else "Carry the shortfall") }
                OutlinedButton(onClick = { if (left) choosing = true else onDecide(true, 0.0, 0.0) }) {
                    Text(if (left) "Invest or treat myself" else "Start at 0")
                }
            }
        }
    }

    if (choosing && left) {
        var invest by remember(p.month) { mutableStateOf((p.balance / 2).toFloat()) }
        val investAmount = invest.toDouble().let { Math.round(it).toDouble() }.coerceIn(0.0, p.balance)
        val treat = (p.balance - investAmount).coerceAtLeast(0.0)
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Put ${money(p.balance)} EGP to use") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$next will start at 0. Decide how the $name leftover is split:", style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("Invest", style = MaterialTheme.typography.labelMedium)
                            Text(money(investAmount) + " EGP", fontWeight = FontWeight.Bold, color = incomeColor())
                        }
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                            Text("Treat myself", style = MaterialTheme.typography.labelMedium)
                            Text(money(treat) + " EGP", fontWeight = FontWeight.Bold)
                        }
                    }
                    Slider(value = invest, onValueChange = { invest = it }, valueRange = 0f..p.balance.toFloat())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { invest = p.balance.toFloat() }, label = { Text("All invest") })
                        AssistChip(onClick = { invest = (p.balance / 2).toFloat() }, label = { Text("Half and half") })
                        AssistChip(onClick = { invest = 0f }, label = { Text("All treat") })
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choosing = false; onDecide(true, investAmount, treat) }) { Text("Start $next at 0") }
            },
            dismissButton = { TextButton(onClick = { choosing = false }) { Text("Cancel") } },
        )
    }
}
