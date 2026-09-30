package com.bido.budgetsync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bido.budgetsync.MainViewModel
import com.bido.budgetsync.data.PendingSms
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(vm: MainViewModel, categories: List<String>, onBack: () -> Unit) {
    val pending by vm.pending.collectAsState()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("To review") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { pad ->
        if (pending.isEmpty()) {
            Text("Nothing to review.", Modifier.padding(pad).padding(16.dp))
        } else {
            LazyColumn(
                Modifier.padding(pad), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(pending, key = { it.key }) { p ->
                    PendingCard(
                        p, categories,
                        onConfirm = { amount, desc, cat -> vm.confirmPending(p, amount, desc, cat) },
                        onDismiss = { vm.dismissPending(p) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PendingCard(
    p: PendingSms,
    categories: List<String>,
    onConfirm: (Double, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var amount by remember(p.key) { mutableStateOf(p.amount.toString().removeSuffix(".0")) }
    var description by remember(p.key) { mutableStateOf(p.merchant) }
    var category by remember(p.key) { mutableStateOf(categories.lastOrNull() ?: "Other") }
    val value = amount.replace(',', '.').toDoubleOrNull()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "From ${p.sender}, " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(p.receivedAt)),
            )
            OutlinedTextField(
                value = amount, onValueChange = { amount = it }, label = { Text("Amount (EGP)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = description, onValueChange = { description = it }, label = { Text("What was it?") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { c -> FilterChip(selected = c == category, onClick = { category = c }, label = { Text(c) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onConfirm(value!!, description.trim(), category) },
                    enabled = value != null && value > 0,
                ) { Text("Confirm") }
                OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}
