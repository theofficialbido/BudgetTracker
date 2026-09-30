package com.bido.budgetsync.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BreakfastDining
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.bido.budgetsync.MainViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

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
