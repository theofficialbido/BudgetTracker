package com.bido.budgetsync

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bido.budgetsync.data.AppDatabase
import com.bido.budgetsync.data.Expense
import com.bido.budgetsync.data.PendingSms
import com.bido.budgetsync.data.Prefs
import com.bido.budgetsync.data.ServerState
import com.bido.budgetsync.data.SyncScheduler
import com.bido.budgetsync.data.Syncer
import com.bido.budgetsync.sms.SmsInboxScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    private val prefs = Prefs(app)

    val expenses: StateFlow<List<Expense>> = db.expenseDao().recent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val pending: StateFlow<List<PendingSms>> = db.pendingSmsDao().pending()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _state = MutableStateFlow(loadState())
    val state = _state.asStateFlow()
    private val _status = MutableStateFlow(prefs.lastSyncMessage)
    val status = _status.asStateFlow()
    private val _lastSyncMs = MutableStateFlow(prefs.lastSyncMs)
    val lastSyncMs = _lastSyncMs.asStateFlow()
    private val _syncing = MutableStateFlow(false)
    val syncing = _syncing.asStateFlow()

    private val _host = MutableStateFlow(prefs.host)
    val host = _host.asStateFlow()
    private val _token = MutableStateFlow(prefs.token)
    val token = _token.asStateFlow()
    private val _smsEnabled = MutableStateFlow(prefs.smsEnabled)
    val smsEnabled = _smsEnabled.asStateFlow()
    private val _senders = MutableStateFlow(prefs.senders)
    val senders = _senders.asStateFlow()
    private val _foundSenders = MutableStateFlow<List<String>>(emptyList())
    val foundSenders = _foundSenders.asStateFlow()

    val defaultCategories = listOf("Claude subscription", "Breakfast", "Going out", "Other")

    companion object {
        const val INCOME = "Income"
    }

    private fun loadState(): ServerState? =
        prefs.cachedState?.let { runCatching { ServerState.fromJson(it) }.getOrNull() }

    fun onOpen() {
        SyncScheduler.ensurePeriodic(getApplication())
        viewModelScope.launch {
            runCatching { SmsInboxScanner.scan(getApplication()) }
            sync()
        }
    }

    fun sync() {
        viewModelScope.launch {
            _syncing.value = true
            Syncer.run(getApplication())
            _state.value = loadState()
            _status.value = prefs.lastSyncMessage
            _lastSyncMs.value = prefs.lastSyncMs
            _syncing.value = false
        }
    }

    /** One-off messages for the snackbar ("Saved", "Deleted"). */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    /** Expenses and income share one path; income is just the category "Income". */
    fun addEntry(amount: Double, category: String, description: String, date: LocalDate) {
        viewModelScope.launch {
            db.expenseDao().insert(Expense(date = date.toString(), category = category, description = description, amount = amount))
            val what = if (category == INCOME) "Income" else "Expense"
            _messages.tryEmit("$what saved, syncing…")
            SyncScheduler.enqueueNow(getApplication())
            sync()
            _messages.tryEmit(if (expenses.value.any { !it.synced }) "$what saved, waiting to sync" else "$what saved and synced")
        }
    }

    fun deleteWaiting(e: Expense) {
        viewModelScope.launch {
            db.expenseDao().deleteUnsynced(e.id)
            _messages.tryEmit("Deleted")
        }
    }

    /** Descriptions used before, most recent first, to offer as one-tap suggestions. */
    fun suggestions(category: String): List<String> {
        val local = expenses.value.filter { it.category == category }.map { it.description }
        val server = state.value?.log?.asReversed()?.filter { it.category == category }?.map { it.description } ?: emptyList()
        return (local + server).map { it.trim() }.filter { it.isNotBlank() }.distinct().take(5)
    }

    fun confirmPending(p: PendingSms, amount: Double, description: String, category: String) {
        viewModelScope.launch {
            val date = Instant.ofEpochMilli(p.receivedAt).atZone(ZoneId.systemDefault()).toLocalDate()
            db.expenseDao().insert(Expense(date = date.toString(), category = category, description = description, amount = amount))
            db.pendingSmsDao().setStatus(p.key, PendingSms.CONFIRMED)
            _messages.tryEmit("Expense confirmed")
            SyncScheduler.enqueueNow(getApplication())
            sync()
        }
    }

    fun dismissPending(p: PendingSms) {
        viewModelScope.launch { db.pendingSmsDao().setStatus(p.key, PendingSms.DISMISSED) }
    }

    fun saveConnection(host: String, token: String) {
        prefs.host = host; prefs.token = token
        _host.value = prefs.host; _token.value = prefs.token
        sync()
    }

    fun setSmsEnabled(on: Boolean) {
        prefs.smsEnabled = on
        _smsEnabled.value = on
        if (on) {
            refreshSenders()
            viewModelScope.launch { runCatching { SmsInboxScanner.scan(getApplication()) } }
        }
    }

    fun toggleSender(sender: String, on: Boolean) {
        val next = _senders.value.filterNot { SmsInboxScanner.sameSender(it, sender) }.toSet() + (if (on) setOf(sender) else emptySet())
        prefs.senders = next
        _senders.value = next
        if (on) {
            // look back over the last week for messages from a newly ticked sender
            prefs.lastScanMs = 0L
            viewModelScope.launch { runCatching { SmsInboxScanner.scan(getApplication()) } }
        }
    }

    fun refreshSenders() {
        viewModelScope.launch {
            _foundSenders.value = withContext(Dispatchers.IO) { SmsInboxScanner.listSenders(getApplication()) }
        }
    }
}
