package com.bido.budgetsync

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bido.budgetsync.data.Alerts
import com.bido.budgetsync.data.AppDatabase
import com.bido.budgetsync.data.Calc
import com.bido.budgetsync.data.Closing
import com.bido.budgetsync.data.CustomCategory
import com.bido.budgetsync.data.Entry
import com.bido.budgetsync.data.Expense
import com.bido.budgetsync.data.MonthClosing
import com.bido.budgetsync.data.MonthSummary
import com.bido.budgetsync.data.PendingClose
import com.bido.budgetsync.data.PlanOverride
import com.bido.budgetsync.data.PendingSms
import com.bido.budgetsync.data.Prefs
import com.bido.budgetsync.data.ServerState
import com.bido.budgetsync.data.SyncException
import com.bido.budgetsync.data.SyncScheduler
import com.bido.budgetsync.data.Syncer
import com.bido.budgetsync.data.UpdateCheck
import com.bido.budgetsync.data.Updater
import com.bido.budgetsync.sms.SmsInboxScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
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

    val customCategories: StateFlow<List<CustomCategory>> = db.customCategoryDao().all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val notInCache: StateFlow<List<Expense>> = db.expenseDao().notInCache()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Everything dated, from the cached workbook data plus what was entered on the phone since. Works offline. */
    val entries: StateFlow<List<Entry>> = combine(_state, notInCache) { s, local -> Calc.entries(s, local) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Category names to offer everywhere: the workbook's, the laptop's extras, and ones made on the phone. */
    val categories: StateFlow<List<String>> = combine(_state, customCategories) { s, c -> Calc.categoryNames(s, c) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Calc.DEFAULT_CATEGORIES)

    /** The month the home screen is about. Refreshed whenever the app comes to the front, so it rolls over at month end. */
    private val currentMonth = MutableStateFlow(YearMonth.now())

    /** Plans set in the app, and what was decided at each month end (the laptop's copy fills in after a reinstall). */
    val plans: StateFlow<Map<String, Double>> = db.planDao().all().map { Calc.planMap(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    val closings: StateFlow<List<Closing>> = combine(_state, db.monthClosingDao().all()) { s, local -> Calc.mergeClosings(s, local) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val bookkeeping = combine(plans, closings) { p, c -> p to c }

    /** Totals for the current month, worked out on the phone: income declared minus spending declared, plus what carried in. */
    val summary: StateFlow<MonthSummary> = combine(_state, entries, customCategories, currentMonth, bookkeeping) { s, e, c, m, b ->
        Calc.summarize(s, e, c, m, b.first, b.second)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Calc.summarize(loadState(), emptyList(), emptyList(), YearMonth.now()))

    /** The earliest finished month still waiting for "carry it over" or "start at 0", if any. */
    val pendingClose: StateFlow<PendingClose?> = combine(entries, closings, currentMonth) { e, c, m -> Calc.pendingClose(e, c, m) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
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
    private val _autoDiscover = MutableStateFlow(prefs.autoDiscover)
    val autoDiscover = _autoDiscover.asStateFlow()
    private val _smsEnabled = MutableStateFlow(prefs.smsEnabled)
    val smsEnabled = _smsEnabled.asStateFlow()
    private val _senders = MutableStateFlow(prefs.senders)
    val senders = _senders.asStateFlow()
    private val _foundSenders = MutableStateFlow<List<String>>(emptyList())
    val foundSenders = _foundSenders.asStateFlow()

    val defaultCategories = listOf("Claude subscription", "Breakfast", "Going out", "Other")

    companion object {
        const val INCOME = Calc.INCOME
    }

    private fun loadState(): ServerState? =
        prefs.cachedState?.let { runCatching { ServerState.fromJson(it) }.getOrNull() }

    fun onOpen() {
        currentMonth.value = YearMonth.now()
        SyncScheduler.ensurePeriodic(getApplication())
        Alerts.reschedule(getApplication())   // keeps the 9pm reminder alarm set, also right after an update
        viewModelScope.launch {
            runCatching { SmsInboxScanner.scan(getApplication()) }
            syncNow()
            checkForUpdateNow()
        }
    }

    private val _update = MutableStateFlow<UpdateCheck?>(null)
    val update = _update.asStateFlow()
    private val _checkingUpdate = MutableStateFlow(false)
    val checkingUpdate = _checkingUpdate.asStateFlow()

    /** Asks the laptop whether it has a newer build of this app. */
    fun checkForUpdate() {
        viewModelScope.launch { checkForUpdateNow() }
    }

    private suspend fun checkForUpdateNow() {
        _checkingUpdate.value = true
        try {
            _update.value = Updater.check(getApplication())
        } finally {
            _checkingUpdate.value = false
        }
    }

    /** Downloads the published build and opens Android's installer; the install keeps all app data. */
    fun installUpdate() {
        viewModelScope.launch {
            _checkingUpdate.value = true
            Updater.downloadAndInstall(getApplication())?.let { say(it) }
            _checkingUpdate.value = false
        }
    }

    /** Runs in the background; use [syncNow] when the caller needs the result. */
    fun sync() {
        viewModelScope.launch { syncNow() }
    }

    /** Several syncs can be requested at once (open, save, button); the spinner stays on until the last one finishes. */
    private var activeSyncs = 0

    private suspend fun syncNow() {
        activeSyncs++
        _syncing.value = true
        try {
            Syncer.run(getApplication())
            _state.value = loadState()
            _status.value = prefs.lastSyncMessage
            _lastSyncMs.value = prefs.lastSyncMs
            com.bido.budgetsync.widget.BudgetWidget.refresh(getApplication())
            runCatching { Alerts.evaluate(getApplication()) }
        } finally {
            if (--activeSyncs == 0) _syncing.value = false
        }
    }

    /** One-off messages for the snackbar ("Saved", "Deleted"). */
    /** A snackbar message, optionally with an Undo action. */
    data class UiMessage(val text: String, val undo: (() -> Unit)? = null)

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    private fun say(text: String, undo: (() -> Unit)? = null) {
        _messages.tryEmit(UiMessage(text, undo))
    }

    // ---- the entries you repeat, and what you can spend ---------------------------------------------------------

    val expenseTemplates: StateFlow<List<Calc.Template>> = entries.map { Calc.templates(it, LocalDate.now(), income = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val incomeTemplates: StateFlow<List<Calc.Template>> = entries.map { Calc.templates(it, LocalDate.now(), income = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val forecast: StateFlow<Calc.Forecast> = combine(_state, entries, customCategories, currentMonth, bookkeeping) { s, e, c, _, b ->
        Calc.forecast(s, e, c, LocalDate.now(), b.first, b.second)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Calc.forecast(loadState(), emptyList(), emptyList(), LocalDate.now()))

    /** Records what you chose at a month end. [reset] true means "invest or treat myself, and start the next month at 0". */
    fun closeMonth(month: YearMonth, reset: Boolean, invested: Double = 0.0, splurged: Double = 0.0) {
        viewModelScope.launch {
            db.monthClosingDao().upsert(MonthClosing(month.toString(), reset, invested.coerceAtLeast(0.0), splurged.coerceAtLeast(0.0)))
            say(if (reset) "${month.month.name.lowercase().replaceFirstChar { it.uppercase() }} closed. Starting the new month at 0" else "Carrying it over")
            syncNow()
        }
    }

    /** Sets the monthly plan for one category. It is used at once and copied to the workbook on the next sync. */
    fun setPlan(category: String, planned: Double) {
        viewModelScope.launch {
            db.planDao().upsert(PlanOverride(category, planned.coerceAtLeast(0.0)))
            say("Plan saved")
            syncNow()
        }
    }

    /**
     * One-tap logging of a repeated entry, dated today. It is held back for a few seconds so Undo can still take it back
     * before it is sent to the laptop.
     */
    fun quickAdd(t: Calc.Template) {
        viewModelScope.launch {
            val e = Expense(date = LocalDate.now().toString(), category = t.category, description = t.description, amount = t.amount)
            db.expenseDao().insert(e)
            val send = launch {
                kotlinx.coroutines.delay(6000)
                SyncScheduler.enqueueNow(getApplication())
                syncNow()
            }
            say("Added ${t.label}") {
                send.cancel()
                viewModelScope.launch {
                    db.expenseDao().deleteUnsynced(e.id)
                    if (db.expenseDao().get(e.id) == null) say("Removed") else say("Already saved to Excel. Tap it in Recent to delete it.")
                    com.bido.budgetsync.widget.BudgetWidget.refresh(getApplication())
                }
            }
        }
    }

    // ---- changing entries that are already saved ---------------------------------------------------------------

    /** Returns an error to show, or null when the change was made. */
    suspend fun saveEdit(entry: Entry, date: LocalDate, category: String, description: String, amount: Double): String? {
        val local = entry.local
        if (local != null) {
            if (local.synced) return "This entry is just being saved to the laptop. Try again in a moment."
            db.expenseDao().editUnsynced(local.id, date.toString(), category, description, amount)
            say("Saved")
            return null
        }
        val sheet = entry.sheet
        val row = entry.row
        if (sheet == null || row == null) return "This entry can't be changed from here."
        val client = Syncer.connectedClient(getApplication(), force = true) ?: return "Connect to your laptop to change saved entries."
        return try {
            withContext(Dispatchers.IO) {
                client.updateEntry(sheet, row, entry.date?.toString(), entry.amount, date.toString(), category, description, amount)
            }
            syncNow()
            say("Entry updated")
            null
        } catch (e: SyncException) {
            e.message ?: "Couldn't update the entry."
        }
    }

    suspend fun deleteEntry(entry: Entry): String? {
        val local = entry.local
        if (local != null) {
            if (local.synced) return "This entry is just being saved to the laptop. Try again in a moment."
            db.expenseDao().deleteUnsynced(local.id)
            say("Deleted")
            return null
        }
        val sheet = entry.sheet
        val row = entry.row
        if (sheet == null || row == null) return "This entry can't be deleted from here."
        val client = Syncer.connectedClient(getApplication(), force = true) ?: return "Connect to your laptop to delete saved entries."
        return try {
            withContext(Dispatchers.IO) { client.deleteEntry(sheet, row, entry.date?.toString(), entry.amount) }
            syncNow()
            say("Entry deleted")
            null
        } catch (e: SyncException) {
            e.message ?: "Couldn't delete the entry."
        }
    }

    // ---- alerts -------------------------------------------------------------------------------------------------

    private val _alertsCategory = MutableStateFlow(prefs.alertsCategory)
    val alertsCategory = _alertsCategory.asStateFlow()
    private val _alertsReminder = MutableStateFlow(prefs.alertsReminder)
    val alertsReminder = _alertsReminder.asStateFlow()

    fun setAlerts(category: Boolean? = null, reminder: Boolean? = null) {
        category?.let { prefs.alertsCategory = it; _alertsCategory.value = it }
        reminder?.let { prefs.alertsReminder = it; _alertsReminder.value = it }
        Alerts.reschedule(getApplication())
        // start from where things stand now, so switching alerts on does not announce what was already true
        viewModelScope.launch { runCatching { Alerts.snapshot(getApplication()) } }
    }

    /** Expenses and income share one path; income is just the category "Income". */
    fun addEntry(amount: Double, category: String, description: String, date: LocalDate) {
        viewModelScope.launch {
            db.expenseDao().insert(Expense(date = date.toString(), category = category, description = description, amount = amount))
            val what = if (category == INCOME) "Income" else "Expense"
            say("$what saved, syncing…")
            SyncScheduler.enqueueNow(getApplication())
            syncNow()
            // Ask the database, not a cached list that may not have caught up yet.
            say(if (db.expenseDao().unsynced().isEmpty()) "$what saved and synced" else "$what saved, waiting to sync")
        }
    }

    /** Returns an error message to show, or null when the category was saved (it syncs to the laptop later if offline). */
    fun addCategory(rawName: String, planned: Double): String? {
        val name = rawName.trim()
        if (name.isEmpty() || name.length > 40) return "Use 1 to 40 characters"
        if (name.equals(INCOME, ignoreCase = true) || categories.value.any { it.equals(name, ignoreCase = true) }) {
            return "That category already exists"
        }
        viewModelScope.launch {
            db.customCategoryDao().insert(CustomCategory(name, planned.coerceAtLeast(0.0)))
            say("Category \"$name\" added")
            SyncScheduler.enqueueNow(getApplication())
            syncNow()
        }
        return null
    }

    fun deleteWaiting(e: Expense) {
        viewModelScope.launch {
            db.expenseDao().deleteUnsynced(e.id)
            say("Deleted")
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
            say("Expense confirmed")
            SyncScheduler.enqueueNow(getApplication())
            syncNow()
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

    fun setAutoDiscover(on: Boolean) {
        prefs.autoDiscover = on
        _autoDiscover.value = on
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
