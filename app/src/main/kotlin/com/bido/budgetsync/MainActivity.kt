package com.bido.budgetsync

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.bido.budgetsync.ui.AddExpenseScreen
import com.bido.budgetsync.ui.CategoryDetailScreen
import com.bido.budgetsync.ui.HistoryScreen
import com.bido.budgetsync.ui.HomeScreen
import com.bido.budgetsync.ui.ReviewScreen
import com.bido.budgetsync.ui.SettingsScreen

enum class Screen { HOME, ADD, CATEGORY, HISTORY, REVIEW, SETTINGS }

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    /** Set by the widget and the launcher shortcuts: "expense" or "income" opens the Add screen straight away. */
    private var openRequest by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        takeOpenRequest(intent)
        setContent { BudgetTheme { App(vm, openRequest, onHandled = { openRequest = null }) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeOpenRequest(intent)
    }

    private fun takeOpenRequest(intent: Intent?) {
        val what = intent?.getStringExtra(EXTRA_OPEN) ?: return
        intent.removeExtra(EXTRA_OPEN)   // so rotating the screen does not open it again
        openRequest = what
    }

    override fun onStart() {
        super.onStart()
        vm.onOpen()
    }

    companion object {
        const val EXTRA_OPEN = "open"
    }
}

@Composable
private fun BudgetTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun App(vm: MainViewModel, openRequest: String?, onHandled: () -> Unit) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var addIncome by rememberSaveable { mutableStateOf(false) }
    var selectedCategory by rememberSaveable { mutableStateOf("") }
    var selectedMonth by rememberSaveable { mutableStateOf("") }   // "" means the current month
    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }
    val categories by vm.categories.collectAsState()

    LaunchedEffect(openRequest) {
        when (openRequest) {
            "expense" -> { addIncome = false; screen = Screen.ADD }
            "income" -> { addIncome = true; screen = Screen.ADD }
        }
        if (openRequest != null) onHandled()
    }

    when (screen) {
        Screen.HOME -> HomeScreen(
            vm,
            categories = categories,
            onAdd = { income -> addIncome = income; screen = Screen.ADD },
            onCategory = { name -> selectedCategory = name; selectedMonth = ""; screen = Screen.CATEGORY },
            onHistory = { screen = Screen.HISTORY },
            onReview = { screen = Screen.REVIEW },
            onSettings = { screen = Screen.SETTINGS },
        )
        Screen.ADD -> AddExpenseScreen(
            vm = vm,
            categories = categories,
            startAsIncome = addIncome,
            onSave = { amount, cat, desc, date -> vm.addEntry(amount, cat, desc, date); screen = Screen.HOME },
            onBack = { screen = Screen.HOME },
        )
        Screen.CATEGORY -> CategoryDetailScreen(
            vm, selectedCategory, categories = categories, initialMonth = selectedMonth.ifBlank { null },
            onBack = { screen = if (selectedMonth.isBlank()) Screen.HOME else Screen.HISTORY },
        )
        Screen.HISTORY -> HistoryScreen(
            vm,
            onCategory = { name, month -> selectedCategory = name; selectedMonth = month; screen = Screen.CATEGORY },
            onBack = { screen = Screen.HOME },
        )
        Screen.REVIEW -> ReviewScreen(vm, categories, onBack = { screen = Screen.HOME })
        Screen.SETTINGS -> SettingsScreen(vm, onBack = { screen = Screen.HOME })
    }
}
