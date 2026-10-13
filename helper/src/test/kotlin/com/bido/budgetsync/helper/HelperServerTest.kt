package com.bido.budgetsync.helper

import com.bido.budgetsync.sync.BudgetSnapshot
import com.bido.budgetsync.sync.ReceiptJson
import com.bido.budgetsync.sync.SnapshotCategory
import com.bido.budgetsync.sync.SnapshotCategoryLine
import com.bido.budgetsync.sync.SnapshotClosing
import com.bido.budgetsync.sync.SnapshotEntry
import com.bido.budgetsync.sync.SnapshotJson
import com.bido.budgetsync.sync.SnapshotPlan
import com.bido.budgetsync.sync.SnapshotSummary
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HelperServerTest {
    private val dir: Path = Files.createTempDirectory("helper-server-test")
    private val xlsx: Path = dir.resolve("Budget.xlsx")
    private val today = LocalDate.now()
    private val token = "TEST-1234-ABCD"
    private val book = BudgetWorkbook(xlsx, dir)
    private val ledger = SyncLedger(dir.resolve("synced-ids.json"))
    private val http = HttpClient.newHttpClient()
    private var server: HelperServer? = null

    private fun start(): String {
        TestWorkbook.create(xlsx, today)
        val s = HelperServer(Config(0, token, xlsx, dir, bind = "127.0.0.1"), book, ledger).start()
        server = s
        return "http://127.0.0.1:${s.port}"
    }

    @AfterTest fun stop() { server?.stop() }

    private fun send(url: String, method: String = "GET", body: String? = null, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI(url))
        headers.forEach { (k, v) -> b.header(k, v) }
        b.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private val bearer get() = mapOf("Authorization" to "Bearer $token")

    private fun snapshot(vararg entries: SnapshotEntry, categories: List<SnapshotCategory> = emptyList(),
                         plans: List<SnapshotPlan> = emptyList(), closings: List<SnapshotClosing> = emptyList()) =
        BudgetSnapshot(
            device = "test-phone", appVersion = "1.9", generatedAt = System.currentTimeMillis(), today = today.toEpochDay(),
            entries = entries.toList(), categories = categories, plans = plans, closings = closings,
            summary = SnapshotSummary(
                today.toString().substring(0, 7), 2400.0, 0.0, 3000.0, 600.0, 5600.0,
                listOf(SnapshotCategoryLine("Breakfast", 3200.0, 600.0, "OK"), SnapshotCategoryLine("Going out", 1200.0, 0.0, "WATCH")),
            ),
        )

    private fun entry(id: String, category: String, desc: String, amount: Double, synced: Boolean = false, income: Boolean = false) =
        SnapshotEntry(id, today.toEpochDay(), category, desc, amount, income, synced, 1L)

    private fun sync(base: String, s: BudgetSnapshot) = send("$base/api/sync", "POST", SnapshotJson.encode(s), bearer)

    // ---- who may talk to it -----------------------------------------------------------------------------------

    @Test fun pingNeedsTheCodeAndAcceptsBothHeaderStyles() {
        val base = start()
        assertEquals(401, send("$base/api/ping").statusCode())
        assertEquals(200, send("$base/api/ping", headers = bearer).statusCode())
        assertEquals(200, send("$base/api/ping", headers = mapOf("X-Token" to "test1234abcd")).statusCode())   // case and dashes ignored
        assertEquals(401, send("$base/api/ping", headers = mapOf("Authorization" to "Bearer WRONG")).statusCode())
    }

    @Test fun aWrongCodeIsAnsweredAfterADelay() {
        val base = start()
        val t0 = System.nanoTime()
        assertEquals(401, send("$base/api/sync", "POST", "{}", mapOf("Authorization" to "Bearer NOPE")).statusCode())
        assertTrue((System.nanoTime() - t0) / 1_000_000 >= 250, "a bad code should be slowed down")
    }

    @Test fun onlyPrivateAndLoopbackCallersAreServed() {
        fun local(ip: String) = HelperServer.isLocal(InetAddress.getByName(ip))
        listOf("127.0.0.1", "::1", "10.245.61.227", "192.168.0.163", "172.20.128.1", "169.254.1.1", "100.64.0.1", "100.127.255.255", "fd00::1")
            .forEach { assertTrue(local(it), "$it should be local") }
        listOf("8.8.8.8", "93.184.216.34", "100.128.0.1", "100.63.0.1", "2001:db8::1", "172.32.0.1")
            .forEach { assertFalse(local(it), "$it should not be local") }
    }

    // ---- the snapshot ------------------------------------------------------------------------------------------

    @Test fun aSnapshotIsAppliedAndTheReceiptSaysWhatWasTaken() {
        val base = start()
        val s = snapshot(
            entry("e-1", "Breakfast", "Foul", 45.0),
            entry("e-2", "Going out", "Taxi", 120.0),
            entry("i-1", "Income", "Salary", 3000.0, income = true),
            entry("old", "Other", "Already there", 10.0, synced = true),           // confirmed before: never sent again
            categories = listOf(SnapshotCategory("Gym", 300.0, synced = false)),
            plans = listOf(SnapshotPlan("Breakfast", 2500.0, synced = false)),
            closings = listOf(SnapshotClosing("2026-09", reset = true, invested = 200.0, splurged = 100.0, synced = false)),
        )
        val r = sync(base, s)
        assertEquals(200, r.statusCode())
        val receipt = ReceiptJson.decode(r.body())
        assertEquals(setOf("e-1", "e-2", "i-1"), receipt.entries.map { it.id }.toSet())
        assertTrue(receipt.entries.all { it.status == "added" })
        assertEquals(listOf("Gym"), receipt.categories)
        assertEquals(listOf("Breakfast"), receipt.plans)
        assertEquals(listOf("2026-09"), receipt.closings)

        // the workbook really has them, and the receipt carries the current state back
        val state = org.json.JSONObject(receipt.stateJson!!)
        assertEquals(3, state.getJSONArray("log").length())            // the fixture's own row plus two new expenses
        assertEquals(1, state.getJSONArray("incomeLog").length())
        assertEquals(1, state.getJSONArray("closings").length())
        assertEquals(2500.0, book.readState().tracker.first { it.category == "Breakfast" }.planned)
        assertTrue(book.readState().categories.contains("Gym"))
    }

    @Test fun sendingTheSameSnapshotAgainChangesNothing() {
        val base = start()
        val s = snapshot(entry("e-1", "Breakfast", "Foul", 45.0), categories = listOf(SnapshotCategory("Gym", 300.0, false)))
        sync(base, s)
        val before = book.readState().log.size
        val again = ReceiptJson.decode(sync(base, s).body())
        assertEquals(listOf("already"), again.entries.map { it.status })
        assertEquals(before, book.readState().log.size)
        assertEquals(1, book.readState().extras.size)
    }

    @Test fun theCopyAndTheBriefingAreWrittenAfterASync() {
        val base = start()
        sync(base, snapshot(entry("e-1", "Breakfast", "Foul", 45.0)))
        val saved = Files.readString(dir.resolve("snapshot.json"))
        assertEquals("test-phone", SnapshotJson.decode(saved).device)                  // the redundancy copy
        val md = Files.readString(dir.resolve("briefs").resolve("budget.md"))
        assertTrue(md.contains("Left to spend: 2,400 EGP"), md)
        assertTrue(md.contains("| Going out | 0 | 1,200 | WATCH |"), md)
        assertTrue(md.contains("Needs attention: Going out (WATCH)"), md)
        assertTrue(md.contains("Foul"), md)
        val json = org.json.JSONObject(Files.readString(dir.resolve("briefs").resolve("budget.json")))
        assertEquals(2400.0, json.getJSONObject("thisMonth").getDouble("leftToSpend"))
        assertEquals(1, json.getInt("waitingOnPhone"))
        assertFalse(Files.exists(dir.resolve("snapshot.json.tmp")))                    // written through a temp file, none left behind
    }

    @Test fun badInputIsRefusedWithoutTouchingTheWorkbook() {
        val base = start()
        assertEquals(400, send("$base/api/sync", "POST", "not json", bearer).statusCode())
        assertEquals(400, send("$base/api/sync", "POST", """{"version":99,"generatedAt":1,"today":1}""", bearer).statusCode())
        assertEquals(405, send("$base/api/sync", "GET", headers = bearer).statusCode())
        assertEquals(1, book.readState().log.size)
    }

    @Test fun anOversizedBodyIsRefused() {
        val base = start()
        val big = "x".repeat(17 * 1024 * 1024)
        assertEquals(413, send("$base/api/sync", "POST", big, bearer).statusCode())
    }

    @Test fun anEntryTheLaptopCannotUseDoesNotFailTheBatch() {
        val base = start()
        val s = snapshot(entry("good", "Breakfast", "Foul", 45.0), entry("zero", "Breakfast", "Free", 0.0), entry("", "Breakfast", "No id", 5.0))
        val receipt = ReceiptJson.decode(sync(base, s).body())
        assertEquals(listOf("good"), receipt.entries.map { it.id })
    }

    // ---- the older endpoints keep working ----------------------------------------------------------------------

    @Test fun earlierEndpointsStillAnswerForOlderAppsAndToDoDesk() {
        val base = start()
        assertEquals(200, send("$base/state", headers = mapOf("X-Token" to token)).statusCode())
        val post = send(
            "$base/expenses", "POST",
            """[{"id":"old-app","date":"$today","category":"Breakfast","description":"Foul","amount":20}]""",
            mapOf("X-Token" to token),
        )
        assertEquals(200, post.statusCode())
        assertEquals(2, book.readState().log.size)
        assertEquals(401, send("$base/state").statusCode())
    }
}
