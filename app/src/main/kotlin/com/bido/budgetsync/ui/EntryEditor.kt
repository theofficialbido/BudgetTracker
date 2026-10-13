package com.bido.budgetsync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bido.budgetsync.MainViewModel
import com.bido.budgetsync.data.Amounts
import com.bido.budgetsync.data.Entry
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Change or delete one entry. Entries still waiting to sync change on the phone at once; entries already in
 * Budget.xlsx are changed there through the laptop, which refuses if the row was changed in Excel meanwhile.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EntryEditor(vm: MainViewModel, entry: Entry, categories: List<String>, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val income = entry.isIncome
    var amount by remember(entry.key) { mutableStateOf(java.math.BigDecimal.valueOf(entry.amount).stripTrailingZeros().toPlainString()) }
    var description by remember(entry.key) { mutableStateOf(entry.description) }
    var category by remember(entry.key) { mutableStateOf(categories.firstOrNull { it.equals(entry.category, true) } ?: entry.category) }
    var date by remember(entry.key) { mutableStateOf(entry.date ?: LocalDate.now()) }
    var picking by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val value = Amounts.parse(amount)

    fun run(block: suspend () -> String?) {
        scope.launch {
            busy = true
            error = block()
            busy = false
            if (error == null) onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !busy }),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(if (income) MainViewModel.INCOME else category)
                Spacer(Modifier.width(12.dp))
                Text(
                    if (income) "Edit income" else "Edit expense",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                )
            }
            OutlinedTextField(
                value = amount, onValueChange = { amount = Amounts.clean(it); error = null }, label = { Text("Amount (EGP)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = description, onValueChange = { description = it }, label = { Text(if (income) "Source" else "Description") },
                singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
            )
            if (!income) {
                Text("Category", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.forEach { c ->
                        FilterChip(
                            selected = c.equals(category, true), onClick = { category = c }, label = { Text(c) },
                            leadingIcon = { Icon(categoryIcon(c), null, Modifier.size(18.dp), tint = categoryColor(c)) },
                        )
                    }
                }
            }
            Text("Date", style = MaterialTheme.typography.titleSmall)
            FilterChip(
                selected = true,
                onClick = { picking = true },
                leadingIcon = { Icon(Icons.Default.CalendarMonth, null, Modifier.size(18.dp)) },
                label = { Text(date.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH))) },
            )
            if (entry.local == null) {
                Text(
                    "This entry is already in Budget.xlsx. Changes are made there through your laptop.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = { run { vm.saveEdit(entry, date, if (income) MainViewModel.INCOME else category, description.trim(), value!!) } },
                enabled = value != null && value > 0 && !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (busy) "Saving…" else "Save changes") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { confirmDelete = true }, enabled = !busy) {
                    Icon(Icons.Default.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text("Delete this entry", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this entry?") },
            text = {
                Text(
                    "${entry.description.ifBlank { entry.category }}, ${moneyPrecise(entry.amount)} EGP." +
                        if (entry.local == null) " It will be removed from Budget.xlsx." else " It hasn't been saved to Excel yet, so it is removed completely.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; run { vm.deleteEntry(entry) } }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
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
