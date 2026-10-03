package com.bido.budgetsync.data

import android.content.Context
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
            val extras = (o.optJSONArray("extras") ?: JSONArray()).let { a -> (0 until a.length()).map { line(a.getJSONObject(it)) } }
            val tracker = o.getJSONArray("tracker").let { a -> (0 until a.length()).map { line(a.getJSONObject(it)) } }
            return ServerState(
                o.getString("month"), cats, log, tracker, line(o.getJSONObject("total")),
                o.getDouble("income"), o.getDouble("leftAfterActual"), o.optDouble("incomeReceived", 0.0),
                o.optDouble("plannedIncome", 0.0), incomeLog, extras,
                o.optDouble("watchAt", 0.8), o.optDouble("overAt", 1.0),
            )
        }
    }
}

class SyncException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { UNREACHABLE, BUSY, AUTH, FULL, OTHER }
}

/** Plain HTTP to the laptop helper on the home network. */
class SyncClient(host: String, token: String, private val connectMs: Int = 4000) {
    private val base = "http://" + host.trim().removePrefix("http://").trimEnd('/')
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
            conn.setRequestProperty("X-Token", token)
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
                507 -> throw SyncException(SyncException.Kind.FULL, "The Log sheet is full (1000 rows)")
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
                if (e.kind == SyncException.Kind.UNREACHABLE) null else throw e
            }
        }
        if (prefs.autoDiscover) {
            attempt(prefs.lastHost)?.let { return it }
            attempt(Discovery.find(context))?.let { return it }
        }
        return attempt(prefs.host)
    }

    suspend fun run(context: Context): SyncOutcome = mutex.withLock {
        withContext(Dispatchers.IO) {
            val prefs = Prefs(context)
            val outcome = try {
                if (prefs.token.isBlank() || prefs.host.isBlank()) {
                    SyncOutcome(false, "Not paired yet: open Settings and enter the laptop token", false)
                } else {
                    val (client, address, firstState) = connect(context, prefs)
                        ?: throw SyncException(SyncException.Kind.UNREACHABLE, "Laptop not reachable on this network")
                    val db = AppDatabase.get(context)
                    val dao = db.expenseDao()
                    var state = firstState
                    var changed = false
                    // New categories first, so entries filed under them are recognised by the laptop.
                    val newCategories = db.customCategoryDao().unsynced()
                    if (newCategories.isNotEmpty()) {
                        db.customCategoryDao().markSynced(client.pushCategories(newCategories).toList())
                        changed = true
                    }
                    val queued = dao.unsynced()
                    if (queued.isNotEmpty()) {
                        dao.markSynced(client.push(queued).toList())
                        changed = true
                    }
                    if (changed) state = client.fetchState()
                    prefs.cachedState = state
                    dao.markInCache()   // the fresh state contains everything synced so far
                    prefs.lastHost = address
                    val left = dao.unsynced().size
                    SyncOutcome(true, if (left == 0) "Synced via $address" else "$left still waiting to sync", left > 0)
                }
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
