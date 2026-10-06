package com.bido.budgetsync.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BreakfastDining
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bido.budgetsync.MainViewModel
import com.bido.budgetsync.data.Amounts
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

fun monthLabel(month: YearMonth): String = month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH))

/** Name and optional monthly plan for a new category. [onAdd] returns an error to show, or null when it was accepted. */
@Composable
fun AddCategoryDialog(onAdd: (name: String, planned: Double) -> String?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var planned by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New category") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it; error = null }, label = { Text("Name") },
                    singleLine = true, isError = error != null, supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = planned, onValueChange = { planned = Amounts.clean(it) },
                    label = { Text("Monthly plan in EGP (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "It's kept on a separate 'More categories' sheet in Budget.xlsx and counts toward your totals.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val result = onAdd(name, Amounts.parse(planned) ?: 0.0)
                if (result == null) onDismiss() else error = result
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

fun money(v: Double): String = String.format(Locale.US, "%,.0f", v)

fun moneyPrecise(v: Double): String =
    if (v % 1.0 == 0.0) money(v) else String.format(Locale.US, "%,.2f", v)

@Composable
fun incomeColor(): Color = if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)

@Composable
fun statusColor(status: String): Color {
    val dark = isSystemInDarkTheme()
    return when (status) {
        "OVER", "UNPLANNED" -> if (dark) Color(0xFFEF9A9A) else Color(0xFFC62828)
        "WATCH" -> if (dark) Color(0xFFFFCC80) else Color(0xFFB26A00)
        else -> incomeColor()
    }
}

@Composable
fun accentFor(category: String): Color =
    if (category == MainViewModel.INCOME) incomeColor() else MaterialTheme.colorScheme.primary

fun categoryIcon(category: String): ImageVector = when (category) {
    MainViewModel.INCOME -> Icons.Default.Payments
    "Claude subscription" -> Icons.Default.AutoAwesome
    "Breakfast" -> Icons.Default.BreakfastDining
    "Going out" -> Icons.Default.Restaurant
    "Other" -> Icons.Default.MoreHoriz
    else -> Icons.Default.ShoppingBag
}

/** "Today", "Yesterday", otherwise "Mon 28 Sep". */
fun dateLabel(iso: String): String {
    val d = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return "No date"
    val today = LocalDate.now()
    return when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> d.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
    }
}
