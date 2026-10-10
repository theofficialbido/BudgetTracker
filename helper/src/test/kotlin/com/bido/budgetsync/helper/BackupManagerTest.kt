package com.bido.budgetsync.helper

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupManagerTest {
    private val dir = Files.createTempDirectory("backup-test")
    private val workbook = dir.resolve("Budget.xlsx")
    private val backups = dir.resolve("backups")

    private fun manager(keep: Int = 30) = BackupManager(workbook, backups, keep)

    private fun write(text: String) = Files.writeString(workbook, text)

    /** Makes the newest backup look [hours] old, as if time had passed. */
    private fun age(hours: Long) {
        val m = manager()
        val latest = m.backups().last()
        Files.setLastModifiedTime(latest, FileTime.from(java.time.Instant.now().minus(Duration.ofHours(hours))))
    }

    @Test
    fun firstRunMakesABackupWithTheSameContent() {
        write("v1")
        val b = manager().runIfNeeded(LocalDateTime.now())
        assertNotNull(b)
        assertEquals("v1", Files.readString(b))
    }

    @Test
    fun theBackupIsDatedWhenItWasMadeNotWhenTheWorkbookWasLastEdited() {
        write("old")
        Files.setLastModifiedTime(workbook, FileTime.from(java.time.Instant.now().minus(Duration.ofDays(10))))
        val b = manager().runIfNeeded(LocalDateTime.now())!!
        val ageMinutes = Duration.between(Files.getLastModifiedTime(b).toInstant(), java.time.Instant.now()).toMinutes()
        assertTrue(ageMinutes < 2, "backup should be dated now, was $ageMinutes minutes old")
    }

    @Test
    fun noSecondBackupWithinAFewHoursOrWhenNothingChanged() {
        write("v1")
        assertNotNull(manager().runIfNeeded(LocalDateTime.now()))
        write("v2")   // changed, but the last backup is brand new
        assertNull(manager().runIfNeeded(LocalDateTime.now()))
        age(30)
        // old enough and changed: a new one
        assertNotNull(manager().runIfNeeded(LocalDateTime.now().plusSeconds(1)))
    }

    @Test
    fun unchangedWorkbookIsNotBackedUpAgain() {
        write("same")
        assertNotNull(manager().runIfNeeded(LocalDateTime.now()))
        age(30)
        Files.setLastModifiedTime(workbook, FileTime.from(java.time.Instant.now()))   // touched, but same bytes
        assertNull(manager().runIfNeeded(LocalDateTime.now().plusSeconds(1)))
    }

    @Test
    fun keepsOnlyTheNewestCopies() {
        val m = manager(keep = 3)
        for (i in 1..5) {
            write("v$i")
            assertNotNull(m.runIfNeeded(LocalDateTime.now().plusDays(i.toLong())).also { age(30) })
        }
        val left = m.backups()
        assertEquals(3, left.size)
        assertEquals("v5", Files.readString(left.last()))
        assertTrue(left.none { Files.readString(it) == "v1" })
    }

    @Test
    fun missingWorkbookIsSkipped() {
        assertNull(manager().runIfNeeded(LocalDateTime.now()))
    }
}
