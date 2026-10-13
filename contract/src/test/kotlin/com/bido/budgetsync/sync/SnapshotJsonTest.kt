package com.bido.budgetsync.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnapshotJsonTest {
    private val snapshot = BudgetSnapshot(
        device = "SM-S938B", appVersion = "1.9", generatedAt = 1_760_000_000_000L, today = 20_000L,
        entries = listOf(
            SnapshotEntry("a-1", 19_999L, "Breakfast", "Foul \"and\" taamiya", 45.5, income = false, synced = true, createdAt = 11L),
            SnapshotEntry("b-2", 20_000L, "Income", "Salary / مرتب", 5000.0, income = true, synced = false, createdAt = 12L),
        ),
        categories = listOf(SnapshotCategory("Gym", 300.0, synced = false)),
        plans = listOf(SnapshotPlan("Breakfast", 2500.0, synced = false)),
        closings = listOf(SnapshotClosing("2026-09", reset = true, invested = 300.0, splurged = 100.0, synced = false)),
        summary = SnapshotSummary(
            "2026-10", 2511.0, 0.0, 5000.0, 2489.0, 5600.0,
            listOf(SnapshotCategoryLine("Breakfast", 2500.0, 900.0, "OK"), SnapshotCategoryLine("Other", 0.0, 12.0, "UNPLANNED")),
        ),
    )

    @Test fun roundTripKeepsEverything() {
        assertEquals(snapshot, SnapshotJson.decode(SnapshotJson.encode(snapshot)))
    }

    @Test fun aSnapshotWithoutASummaryRoundTrips() {
        val s = snapshot.copy(summary = null, entries = emptyList(), categories = emptyList(), plans = emptyList(), closings = emptyList())
        val back = SnapshotJson.decode(SnapshotJson.encode(s))
        assertNull(back.summary)
        assertEquals(s, back)
    }

    @Test fun unknownFutureVersionsAreRefusedNotGuessed() {
        val future = SnapshotJson.encode(snapshot).replace("\"version\":1", "\"version\":${BudgetSnapshot.VERSION + 1}")
        assertFailsWith<IllegalArgumentException> { SnapshotJson.decode(future) }
        assertFailsWith<IllegalArgumentException> { SnapshotJson.decode("""{"generatedAt":1,"today":1}""") }   // no version
    }

    @Test fun missingOptionalPartsDefaultSensibly() {
        val minimal = """{"version":1,"generatedAt":5,"today":6}"""
        val s = SnapshotJson.decode(minimal)
        assertEquals("phone", s.device)
        assertTrue(s.entries.isEmpty() && s.categories.isEmpty() && s.plans.isEmpty() && s.closings.isEmpty())
        assertNull(s.summary)
    }

    @Test fun receiptCarriesWhatWasTakenAndTheWorkbookState() {
        val r = SyncReceipt(
            entries = listOf(EntryReceipt("a-1", 7, "added"), EntryReceipt("b-2", 3, "already")),
            categories = listOf("Gym"), plans = listOf("Breakfast"), closings = listOf("2026-09"),
            stateJson = """{"month":"2026-10","log":[]}""",
        )
        val back = ReceiptJson.decode(ReceiptJson.encode(r))
        assertEquals(r.entries, back.entries)
        assertEquals(r.categories, back.categories)
        assertEquals(r.plans, back.plans)
        assertEquals(r.closings, back.closings)
        assertEquals("2026-10", org.json.JSONObject(back.stateJson!!).getString("month"))
        assertNull(ReceiptJson.decode(ReceiptJson.encode(r.copy(stateJson = null))).stateJson)
    }
}
