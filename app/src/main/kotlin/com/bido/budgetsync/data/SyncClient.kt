package com.bido.budgetsync.data

import android.content.Context
import com.bido.budgetsync.sync.ReceiptJson
import com.bido.budgetsync.sync.SnapshotJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class TrackerLine(val category: String, val planned: Double, val actual: Double, val percent: Double, val status: String)
data class LogLine(val row: Int, val date: String?, val category: String, val description: String, val amount: Double?)

data class IncomeLine(val row: Int, val date: String?, val source: String, val amount: Double?)
data class ClosingLine(val month: String, val reset: Boolean, val invested: Double, val splurged: Double)

/** What the laptop helper reports: same numbers the Tracker sheet shows. */
data class ServerState(
    val month: String,
    val categories: List<String>,
    val log: List<LogLine>,
    val tracker: List<TrackerLine>,
    val total: TrackerLine,
    val income: Double,
    val leftAfterActual: Double,
    val incomeReceived: Double = 0.0,
    val plannedIncome: Double = 0.0,
    val incomeLog: List<IncomeLine> = emptyList(),
    /** Categories added from the phone (kept on the laptop's "More categories" sheet), with their plans. */
    val extras: List<TrackerLine> = emptyList(),
    /** Alert levels from the Plan sheet, so the phone can work out OK / WATCH / OVER without the laptop. */
    val watchAt: Double = 0.8,
    val overAt: Double = 1.0,
    /** Month-end decisions kept in the workbook, so a reinstalled phone remembers them. */
    val closings: List<ClosingLine> = emptyList(),
) {
    companion object {
        fun fromJson(text: String): ServerState {
            val o = JSONObject(text)
            fun line(j: JSONObject) = TrackerLine(
                j.getString("category"), j.getDouble("planned"), j.getDouble("actual"),
                j.getDouble("percent"), j.getString("status"),
            )
            val cats = o.getJSONArray("categories").let { a -> (0 until a.length()).map { a.getString(it) } }
            val log = o.getJSONArray("log").let { a ->
                (0 until a.length()).map {
                    val j = a.getJSONObject(it)
                    LogLine(
                        j.getInt("row"), if (j.isNull("date")) null else j.getString("date"),
                        j.getString("category"), j.getString("description"),
                        if (j.isNull("amount")) null else j.getDouble("amount"),
                    )
                }
            }
            val incomeLog = (o.optJSONArray("incomeLog") ?: JSONArray()).let { a ->
                (0 until a.length()).map {
                    val j = a.getJSONObject(it)
                    IncomeLine(
                        j.getInt("row"), if (j.isNull("date")) null else j.getString("date"),
                        j.getString("source"), if (j.isNull("amount")) null else j.getDouble("amount"),
                    )
                }
            }
            val closings = (o.optJSONArray("closings") ?: JSONArray()).let { a ->
                (0 until a.length()).map {
                    val j = a.getJSONObject(it)
                    ClosingLine(j.getString("month"), j.optBoolean("reset", false), j.optDouble("invested", 0.0), j.optDouble("splurged", 0.0))
                }
            }
            val extras = (o.optJSONArray("extras") ?: JSONArray()).let { a -> (0 until a.length()).map { line(a.getJSONObject(it)) } }
            val tracker = o.getJSONArray("tracker").let { a -> (0 until a.length()).map { line(a.getJSONObject(it)) } }
            return ServerState(
                o.getString("month"), cats, log, tracker, line(o.getJSONObject("total")),
                o.getDouble("income"), o.getDouble("leftAfterActual"), o.optDouble("incomeReceived", 0.0),
                o.optDouble("plannedIncome", 0.0), incomeLog, extras,
                o.optDouble("watchAt", 0.8), o.optDouble("overAt", 1.0), closings,
            )
        }
    }
}

class SyncException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { UNREACHABLE, BUSY, AUTH, FULL, NOT_FOUND, OTHER }
}

/** Plain HTTP to the laptop helper on the home network. */
class SyncClient(host: String, token: String, private val connectMs: Int = 4000) {
    /** Every route lives under /api/budget on the single BaseCamp helper, which also serves the ToDo app. */
    private val base = "http://" + host.trim().removePrefix("http://").trimEnd('/') + "/api/budget"
    private val token = token.uppercase().filter { it.isLetterOrDigit() }

    fun fetchState(): String = request("GET", "/state", null)

    /** Returns ids the laptop has written (or already had). */
    fun push(items: List<Expense>): Set<String> {
        val body = JSONArray().apply {
            items.forEach {
                put(JSONObject().put("id", it.id).put("date", it.date).put("category", it.category)
                    .put("description", it.description).put("amount", it.amount))
            }
        }.toString()
        val res = JSONObject(request("POST", "/expenses", body)).getJSONArray("results")
        return (0 until res.length()).map { res.getJSONObject(it).getString("id") }.toSet()
    }

    /** Changes one entry already in the workbook. [expectDate] and [expectAmount] are what the phone saw, so a row changed in Excel is refused. */
    fun updateEntry(
        sheet: String, row: Int, expectDate: String?, expectAmount: Double?,
        date: String, category: String, description: String, amount: Double,
    ) {
        request(
            "POST", "/entries/update",
            JSONObject().put("sheet", sheet).put("row", row).put("expectDate", expectDate).put("expectAmount", expectAmount)
                .put("date", date).put("category", category).put("description", description).put("amount", amount).toString(),
        )
    }

    fun deleteEntry(sheet: String, row: Int, expectDate: String?, expectAmount: Double?) {
        request(
            "POST", "/entries/delete",
            JSONObject().put("sheet", sheet).put("row", row).put("expectDate", expectDate).put("expectAmount", expectAmount).toString(),
        )
    }

    /** What build the laptop has published for the phone, as (versionCode, versionName). */
    fun fetchVersion(): Pair<Long, String> {
        val o = JSONObject(request("GET", "/app/version", null))
        return o.getLong("versionCode") to o.optString("versionName", "")
    }

    /** Streams the published APK to [dest]. */
    fun downloadApk(dest: java.io.File) {
        val conn = URL(base + "/app/apk").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = connectMs
            conn.readTimeout = 30000
            conn.setRequestProperty("X-Token", token)
            when (val code = conn.responseCode) {
                200 -> { dest.parentFile?.mkdirs(); conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } } }
                401 -> throw SyncException(SyncException.Kind.AUTH, "Wrong pairing token")
                404 -> throw SyncException(SyncException.Kind.OTHER, "No update published on the laptop yet")
                else -> throw SyncException(SyncException.Kind.OTHER, "Laptop answered $code")
            }
        } catch (e: IOException) {
            throw SyncException(SyncException.Kind.UNREACHABLE, "Laptop not reachable")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Sends the whole snapshot in one request and returns the laptop's receipt (what it took, plus the workbook's state).
     * An older helper without this endpoint answers 404, which the caller treats as "send item by item instead".
     */
    fun pushSnapshot(json: String): String = request("POST", "/sync", json)

    /** Sends the month-end decisions. Returns the months the laptop has recorded. */
    fun pushClosings(items: List<MonthClosing>): Set<String> {
        val body = JSONArray().apply {
            items.forEach { put(JSONObject().put("month", it.month).put("reset", it.reset).put("invested", it.invested).put("splurged", it.splurged)) }
        }.toString()
        val res = JSONObject(request("POST", "/closings", body)).getJSONArray("results")
        return (0 until res.length()).map { res.getJSONObject(it).getString("name") }.toSet()
    }

    /** Sends plans set in the app. Returns the names the laptop handled (updated, or a category it doesn't have yet is skipped). */
    fun pushPlans(items: List<PlanOverride>): Set<String> {
        val body = JSONArray().apply { items.forEach { put(JSONObject().put("name", it.name).put("planned", it.planned)) } }.toString()
        val res = JSONObject(request("POST", "/plans", body)).getJSONArray("results")
        return (0 until res.length()).map { res.getJSONObject(it) }
            .filter { it.getString("status") != "unknown" }.map { it.getString("name") }.toSet()
    }

    /** Sends categories created on the phone. Returns the names the laptop now has (added, or already there). */
    fun pushCategories(items: List<CustomCategory>): Set<String> {
        val body = JSONArray().apply {
            items.forEach { put(JSONObject().put("name", it.name).put("planned", it.planned)) }
        }.toString()
        val res = JSONObject(request("POST", "/categories", body)).getJSONArray("results")
        return (0 until res.length()).map { res.getJSONObject(it).getString("name") }.toSet()
    }

    private fun request(method: String, path: String, body: String?): String {
        val conn = try {
            (URL(base + path).openConnection() as HttpURLConnection)
        } catch (e: IOException) {
            throw SyncException(SyncException.Kind.UNREACHABLE, "Bad address")
        }
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectMs
            conn.readTimeout = 15000
            conn.setRequestProperty("X-Token", token)                       // what earlier helpers and BaseCamp read
            conn.setRequestProperty("Authorization", "Bearer $token")       // the ToDo-style header
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return when (code) {
                in 200..299 -> text
                401 -> throw SyncException(SyncException.Kind.AUTH, "Wrong pairing token")
                503 -> throw SyncException(SyncException.Kind.BUSY, "Budget.xlsx is busy (Excel or OneDrive), will retry")
                409 -> throw SyncException(SyncException.Kind.OTHER, "That entry changed in Excel. Pull down to refresh, then try again.")
                507 -> throw SyncException(SyncException.Kind.FULL, "The Log sheet is full (1000 rows)")
                404 -> throw SyncException(SyncException.Kind.NOT_FOUND, "Laptop answered 404")
                413 -> throw SyncException(SyncException.Kind.OTHER, "The laptop refused the data as too large")
                else -> throw SyncException(SyncException.Kind.OTHER, "Laptop answered $code")
            }
        } catch (e: IOException) {
            throw SyncException(SyncException.Kind.UNREACHABLE, "Laptop not reachable")
        } finally {
            conn.disconnect()
        }
    }
}

data class SyncOutcome(val ok: Boolean, val message: String, val retry: Boolean)

/** One sync pass: push queued expenses, then refresh the cached Tracker/Log. Only one runs at a time. */
object Syncer {
    private val mutex = Mutex()

    /** When the laptop last failed to answer. Background checks (like the update check) skip trying again for a while. */
    @Volatile private var unreachableAt = 0L
    private const val RETRY_AFTER_MS = 20_000L

    /**
     * Finds an address that answers: the one that worked last time, then whatever the laptop announces on this network,
     * then the manual address from Settings. Only "no answer" moves on to the next candidate; a real reply
     * (wrong token, busy file) is reported as is. Returns the client, its address and the state it fetched.
     */
    private suspend fun connect(context: Context, prefs: Prefs): Triple<SyncClient, String, String>? {
        val tried = HashSet<String>()
        suspend fun attempt(address: String?): Triple<SyncClient, String, String>? {
            val a = address?.trim().orEmpty()
            if (a.isEmpty() || !tried.add(a)) return null
            return try {
                val client = SyncClient(a, prefs.token, connectMs = 2500)
                Triple(client, a, client.fetchState())
            } catch (e: SyncException) {
                // 404 and odd errors mean some other device holds that address (a stale last host, say): try the next one.
                if (e.kind == SyncException.Kind.UNREACHABLE || e.kind == SyncException.Kind.NOT_FOUND || e.kind == SyncException.Kind.OTHER) null else throw e
            }
        }
        var found: Triple<SyncClient, String, String>? = null
        if (prefs.autoDiscover) {
            found = attempt(prefs.lastHost) ?: attempt(Discovery.find(context))
            if (found == null) {
                // mDNS is silent on many hotspots; look for the helper's port on the phone's own network instead
                val port = prefs.host.substringAfterLast(':', "8765").toIntOrNull() ?: 8765
                for (candidate in Discovery.sweep(port)) {
                    found = attempt(candidate)
                    if (found != null) break
                }
            }
        }
        found = found ?: attempt(prefs.host)
        unreachableAt = if (found == null) System.currentTimeMillis() else 0L
        return found
    }

    /**
     * A client for whichever laptop address answers right now, or null. Used by the app updater. If a sync just found the
     * laptop unreachable it returns null at once instead of waiting through the same slow attempts again.
     */
    suspend fun connectedClient(context: Context, force: Boolean = false): SyncClient? {
        if (!force && System.currentTimeMillis() - unreachableAt < RETRY_AFTER_MS) return null
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val prefs = Prefs(context)
                if (!prefs.syncEnabled || prefs.token.isBlank()) null else try { connect(context, prefs)?.first } catch (_: SyncException) { null }
            }
        }
    }

    /**
     * The earlier protocol: categories, month decisions, plans and entries as separate requests, then the state.
     * Returns the newest state and whether it is fresh (false when the last request for it failed).
     */
    private suspend fun sendOneByOne(client: SyncClient, db: AppDatabase, firstState: String): Pair<String, Boolean> {
        var state = firstState
        var changed = false
        val newCategories = db.customCategoryDao().unsynced()
        if (newCategories.isNotEmpty()) {
            db.customCategoryDao().markSynced(client.pushCategories(newCategories).toList())
            changed = true
        }
        val closings = db.monthClosingDao().unsynced()
        if (closings.isNotEmpty()) {
            db.monthClosingDao().markSynced(client.pushClosings(closings).toList())
            changed = true
        }
        val plans = db.planDao().unsynced()
        if (plans.isNotEmpty()) {
            db.planDao().markSynced(client.pushPlans(plans).toList())
            changed = true
        }
        val queued = db.expenseDao().unsynced()
        if (queued.isNotEmpty()) {
            db.expenseDao().markSynced(client.push(queued).toList())
            changed = true
        }
        var fresh = true
        if (changed) {
            try { state = client.fetchState() } catch (e: SyncException) {
                if (e.kind != SyncException.Kind.UNREACHABLE && e.kind != SyncException.Kind.BUSY) throw e
                fresh = false
            }
        }
        return state to fresh
    }

    suspend fun run(context: Context): SyncOutcome = mutex.withLock {
        withContext(Dispatchers.IO) {
            val prefs = Prefs(context)
            val outcome = try {
                if (!prefs.syncEnabled) {
                    SyncOutcome(false, "Laptop sync is switched off. Everything still works on the phone.", false)
                } else if (prefs.token.isBlank() || (prefs.host.isBlank() && !prefs.autoDiscover)) {
                    SyncOutcome(false, "Not paired yet: open Settings and enter the laptop token", false)
                } else {
                    val (client, address, firstState) = connect(context, prefs)
                        ?: throw SyncException(SyncException.Kind.UNREACHABLE, "Laptop not reachable on this network")
                    val db = AppDatabase.get(context)
                    val dao = db.expenseDao()
                    var state = firstState
                    var fresh = true
                    // One snapshot, one request: the laptop applies what is new and answers with a receipt and its numbers.
                    val snapshot = LaptopSync.snapshot(context, runCatching { ServerState.fromJson(firstState) }.getOrNull())
                    try {
                        val receipt = ReceiptJson.decode(client.pushSnapshot(SnapshotJson.encode(snapshot)))
                        LaptopSync.markTaken(db, receipt)
                        SelfUpdater.onOffer(context, receipt.update)
                        val back = receipt.stateJson
                        if (back != null) state = back else {
                            try { state = client.fetchState() } catch (e: SyncException) {
                                if (e.kind != SyncException.Kind.UNREACHABLE && e.kind != SyncException.Kind.BUSY) throw e
                                fresh = false
                            }
                        }
                    } catch (e: SyncException) {
                        if (e.kind != SyncException.Kind.NOT_FOUND) throw e
                        // an older helper without /sync: send item by item, as earlier versions of the app did
                        val r = sendOneByOne(client, db, firstState)
                        state = r.first
                        fresh = r.second
                    }
                    prefs.cachedState = state
                    if (fresh) dao.markInCache()   // the fresh state contains everything synced so far
                    prefs.lastHost = address
                    val left = dao.unsynced().size
                    SyncOutcome(
                        true,
                        when {
                            left > 0 -> "$left still waiting to sync"
                            !fresh -> "Synced via $address (couldn't refresh the numbers, will on the next sync)"
                            else -> "Synced via $address"
                        },
                        left > 0,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SyncException) {
                SyncOutcome(false, e.message ?: "Sync failed", e.kind == SyncException.Kind.UNREACHABLE || e.kind == SyncException.Kind.BUSY)
            } catch (e: Exception) {
                SyncOutcome(false, "Sync failed: ${e.javaClass.simpleName}", true)
            }
            if (outcome.ok) prefs.lastSyncMs = System.currentTimeMillis()
            prefs.lastSyncMessage = outcome.message
            outcome
        }
    }
}
