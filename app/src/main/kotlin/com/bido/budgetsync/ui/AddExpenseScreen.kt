package com.bido.budgetsync.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bido.budgetsync.MainViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddExpenseScreen(
    vm: MainViewModel,
    categories: List<String>,
    startAsIncome: Boolean,
    onSave: (amount: Double, category: String, description: String, date: LocalDate) -> Unit,
    onBack: () -> Unit,
) {
    var income by remember { mutableStateOf(startAsIncome) }
    var amount by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(categories.firstOrNull() ?: "Other") }
    var description by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var picking by remember { mutableStateOf(false) }
    val value = amount.replace(',', '.').toDoubleOrNull()
    val effectiveCategory = if (income) MainViewModel.INCOME else category
    val accent = accentFor(effectiveCategory)
    val suggestions = vm.suggestions(effectiveCategory).filter { !it.equals(description.trim(), ignoreCase = true) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (income) "Add income" else "Add expense") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(false to "Expense", true to "Income").forEachIndexed { i, (isIncome, label) ->
                    SegmentedButton(
                        selected = income == isIncome,
                        onClick = { income = isIncome },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                    ) { Text(label) }
                }
            }

            OutlinedTextField(
                value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                label = { Text("Amount") }, prefix = { Text("EGP ") },
                textStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, color = accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )

            AnimatedVisibility(!income) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Category", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        categories.forEach { c ->
                            FilterChip(
                                selected = c == category, onClick = { category = c }, label = { Text(c) },
                                leadingIcon = { Icon(categoryIcon(c), null, Modifier.size(18.dp)) },
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = description, onValueChange = { description = it },
                label = { Text(if (income) "Source (e.g. salary, freelance)" else "Description") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            if (suggestions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.forEach { s -> AssistChip(onClick = { description = s }, label = { Text(s) }) }
                }
            }

            Text("Date", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val today = LocalDate.now()
                FilterChip(selected = date == today, onClick = { date = today }, label = { Text("Today") })
                FilterChip(selected = date == today.minusDays(1), onClick = { date = today.minusDays(1) }, label = { Text("Yesterday") })
                FilterChip(
                    selected = date != today && date != today.minusDays(1),
                    onClick = { picking = true },
                    label = { Text(if (date != today && date != today.minusDays(1)) date.toString() else "Pick date") },
                )
            }

            Button(
                onClick = { onSave(value!!, effectiveCategory, description.trim(), date) },
                enabled = value != null && value > 0,
                colors = if (income) ButtonDefaults.buttonColors(containerColor = accent, contentColor = MaterialTheme.colorScheme.surface) else ButtonDefaults.buttonColors(),
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            ) { Text(if (income) "Save income" else "Save expense") }
        }
    }

    if (picking) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DatePicker(state = pickerState) }
    }
}
