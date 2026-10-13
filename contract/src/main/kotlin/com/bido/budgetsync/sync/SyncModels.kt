package com.bido.budgetsync.sync

import org.json.JSONArray
import org.json.JSONObject

/*
 * The contract between the phone and the laptop helper.
 *
 * The phone sends everything it knows as ONE JSON document ("snapshot") in a single POST /api/sync, and the laptop
 * answers with a receipt that says what it took and carries the workbook's current numbers back. The same document is
 * kept on the laptop as a redundancy copy, and a short briefing is written from it for the morning and evening briefs.
 *
 * Both sides use this file. Change it here, and raise VERSION if the format changes in a way older readers cannot handle.
 */

/** One expense or income entry. [day] is LocalDate.toEpochDay(). Income has category "Income". */
data class SnapshotEntry(
    val id: String,
    val day: Long,
    val category: String,
    val description: String,
    val amount: Double,
    val income: Boolean,
    /** True once the laptop has confirmed it. The laptop only acts on entries that are not synced yet. */
    val synced: Boolean,
    val createdAt: Long,
)

data class SnapshotCategory(val name: String, val planned: Double, val synced: Boolean)

/** A monthly plan set in the app for one category. */
data class SnapshotPlan(val name: String, val planned: Double, val synced: Boolean)

/** What was chosen at a month end: [reset] means "invest or treat myself and start the next month at 0". */
data class SnapshotClosing(val month: String, val reset: Boolean, val invested: Double, val splurged: Double, val synced: Boolean)

data class SnapshotCategoryLine(val name: String, val planned: Double, val actual: Double, val status: String)

/** The phone's own numbers for the current month, so briefs quote exactly what the app shows. */
data class SnapshotSummary(
    val month: String,
    val leftToSpend: Double,
    val carriedIn: Double,
    val received: Double,
    val spent: Double,
    val planned: Double,
    val categories: List<SnapshotCategoryLine>,
)

data class BudgetSnapshot(
    val device: String,
    val appVersion: String,
    /** The installed build (versionCode, minutes since 2026-01-01 at build time); 0 from apps older than this field. */
    val appVersionCode: Long = 0,
    /** Epoch millis on the phone when the snapshot was taken. */
    val generatedAt: Long,
    /** The phone's "today" as an epoch day. */
    val today: Long,
    val entries: List<SnapshotEntry>,
    val categories: List<SnapshotCategory>,
    val plans: List<SnapshotPlan>,
    val closings: List<SnapshotClosing>,
    val summary: SnapshotSummary?,
) {
    companion object {
        const val VERSION = 1
    }
}

object SnapshotJson {
    fun encode(s: BudgetSnapshot): String {
        val root = JSONObject()
        root.put("version", BudgetSnapshot.VERSION)
        root.put("device", s.device)
        root.put("appVersion", s.appVersion)
        root.put("appVersionCode", s.appVersionCode)
        root.put("generatedAt", s.generatedAt)
        root.put("today", s.today)
        root.put("entries", JSONArray().also { a ->
            s.entries.forEach {
                a.put(
                    JSONObject().put("id", it.id).put("day", it.day).put("category", it.category)
                        .put("description", it.description).put("amount", it.amount).put("income", it.income)
                        .put("synced", it.synced).put("createdAt", it.createdAt)
                )
            }
        })
        root.put("categories", JSONArray().also { a ->
            s.categories.forEach { a.put(JSONObject().put("name", it.name).put("planned", it.planned).put("synced", it.synced)) }
        })
        root.put("plans", JSONArray().also { a ->
            s.plans.forEach { a.put(JSONObject().put("name", it.name).put("planned", it.planned).put("synced", it.synced)) }
        })
        root.put("closings", JSONArray().also { a ->
            s.closings.forEach {
                a.put(
                    JSONObject().put("month", it.month).put("reset", it.reset).put("invested", it.invested)
                        .put("splurged", it.splurged).put("synced", it.synced)
                )
            }
        })
        root.put("summary", s.summary?.let { sum ->
            JSONObject().put("month", sum.month).put("leftToSpend", sum.leftToSpend).put("carriedIn", sum.carriedIn)
                .put("received", sum.received).put("spent", sum.spent).put("planned", sum.planned)
                .put("categories", JSONArray().also { a ->
                    sum.categories.forEach {
                        a.put(JSONObject().put("name", it.name).put("planned", it.planned).put("actual", it.actual).put("status", it.status))
                    }
                })
        } ?: JSONObject.NULL)
        return root.toString()
    }

    fun decode(text: String): BudgetSnapshot {
        val root = JSONObject(text)
        val version = root.optInt("version", 0)
        require(version in 1..BudgetSnapshot.VERSION) { "Unsupported sync version $version" }
        fun <T> JSONArray?.mapObjects(f: (JSONObject) -> T): List<T> =
            if (this == null) emptyList() else (0 until length()).map { f(getJSONObject(it)) }
        val summary = root.optJSONObject("summary")?.let { o ->
            SnapshotSummary(
                month = o.getString("month"), leftToSpend = o.getDouble("leftToSpend"), carriedIn = o.optDouble("carriedIn", 0.0),
                received = o.optDouble("received", 0.0), spent = o.optDouble("spent", 0.0), planned = o.optDouble("planned", 0.0),
                categories = o.optJSONArray("categories").mapObjects {
                    SnapshotCategoryLine(it.getString("name"), it.optDouble("planned", 0.0), it.optDouble("actual", 0.0), it.optString("status", "OK"))
                },
            )
        }
        return BudgetSnapshot(
            device = root.optString("device", "phone"),
            appVersion = root.optString("appVersion", ""),
            appVersionCode = root.optLong("appVersionCode", 0),
            generatedAt = root.getLong("generatedAt"),
            today = root.getLong("today"),
            entries = root.optJSONArray("entries").mapObjects {
                SnapshotEntry(
                    id = it.getString("id"), day = it.getLong("day"), category = it.getString("category"),
                    description = it.optString("description", ""), amount = it.getDouble("amount"),
                    income = it.optBoolean("income", false), synced = it.optBoolean("synced", false),
                    createdAt = it.optLong("createdAt", 0L),
                )
            },
            categories = root.optJSONArray("categories").mapObjects {
                SnapshotCategory(it.getString("name"), it.optDouble("planned", 0.0), it.optBoolean("synced", false))
            },
            plans = root.optJSONArray("plans").mapObjects {
                SnapshotPlan(it.getString("name"), it.getDouble("planned"), it.optBoolean("synced", false))
            },
            closings = root.optJSONArray("closings").mapObjects {
                SnapshotClosing(it.getString("month"), it.optBoolean("reset", false), it.optDouble("invested", 0.0), it.optDouble("splurged", 0.0), it.optBoolean("synced", false))
            },
            summary = summary,
        )
    }
}

/** Where one entry ended up in the workbook. [status] is "added" or "already". */
data class EntryReceipt(val id: String, val row: Int, val status: String)

/**
 * What the laptop answers with: which entries, categories, plans and closings it has taken (the phone marks exactly
 * those as synced), plus the workbook's current state as raw JSON so the phone sees rows the 9pm routine added.
 */
data class SyncReceipt(
    val entries: List<EntryReceipt>,
    val categories: List<String>,
    val plans: List<String>,
    val closings: List<String>,
    val stateJson: String?,
    /** A newer build the laptop has ready (set only after "Update phone" was pressed there); fetched from /app/apk. */
    val update: UpdateOffer? = null,
)

data class UpdateOffer(val versionCode: Long, val versionName: String)

object ReceiptJson {
    fun encode(r: SyncReceipt): String {
        val o = JSONObject().put("ok", true)
        o.put("entries", JSONArray().also { a -> r.entries.forEach { a.put(JSONObject().put("id", it.id).put("row", it.row).put("status", it.status)) } })
        o.put("categories", JSONArray(r.categories))
        o.put("plans", JSONArray(r.plans))
        o.put("closings", JSONArray(r.closings))
        o.put("state", r.stateJson?.let { JSONObject(it) } ?: JSONObject.NULL)
        r.update?.let { o.put("update", JSONObject().put("versionCode", it.versionCode).put("versionName", it.versionName)) }
        return o.toString()
    }

    fun decode(text: String): SyncReceipt {
        val o = JSONObject(text)
        fun strings(key: String): List<String> = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        val entries = o.optJSONArray("entries")?.let { a ->
            (0 until a.length()).map { val e = a.getJSONObject(it); EntryReceipt(e.getString("id"), e.optInt("row", 0), e.optString("status", "added")) }
        } ?: emptyList()
        val update = o.optJSONObject("update")?.let { UpdateOffer(it.optLong("versionCode", 0), it.optString("versionName", "")) }?.takeIf { it.versionCode > 0 }
        return SyncReceipt(entries, strings("categories"), strings("plans"), strings("closings"), o.optJSONObject("state")?.toString(), update)
    }
}
