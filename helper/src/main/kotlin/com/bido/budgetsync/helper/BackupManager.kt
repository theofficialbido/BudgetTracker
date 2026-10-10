package com.bido.budgetsync.helper

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Keeps dated copies of Budget.xlsx outside OneDrive, so a deleted or damaged workbook can always be restored.
 * A copy is made when the workbook has changed since the last one and the last one is at least [minAge] old.
 * The newest [keep] copies are kept.
 */
class BackupManager(
    private val workbook: Path,
    private val dir: Path,
    private val keep: Int = 30,
    private val minAge: Duration = Duration.ofHours(20),
) {
    private val prefix = "Budget-"
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** Returns the new backup, or null if none was needed or the workbook could not be read right now. */
    @Synchronized
    fun runIfNeeded(now: LocalDateTime = LocalDateTime.now()): Path? {
        if (!Files.isRegularFile(workbook)) return null
        val latest = backups().lastOrNull()
        if (latest != null) {
            val age = Duration.between(Files.getLastModifiedTime(latest).toInstant(), now.atZone(java.time.ZoneId.systemDefault()).toInstant())
            val unchanged = Files.getLastModifiedTime(workbook) <= Files.getLastModifiedTime(latest) ||
                Files.size(workbook) == Files.size(latest) && Files.mismatch(workbook, latest) == -1L
            if (age < minAge || unchanged) return null
        }
        return try {
            Files.createDirectories(dir)
            val target = dir.resolve(prefix + now.format(stamp) + ".xlsx")
            Files.copy(workbook, target)
            // Windows keeps the original's modified time on a copy; stamp the real backup time so "how old is the last
            // backup" and "has the workbook changed since" are answered correctly.
            Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.from(java.time.Instant.now()))
            prune()
            target
        } catch (_: java.io.IOException) {
            null   // locked right now (Excel, OneDrive): the next check tries again
        }
    }

    /** Oldest first. */
    fun backups(): List<Path> =
        if (!Files.isDirectory(dir)) emptyList()
        else Files.list(dir).use { s -> s.filter { it.fileName.toString().startsWith(prefix) && it.toString().endsWith(".xlsx") }.sorted().toList() }

    private fun prune() {
        val all = backups()
        all.take((all.size - keep).coerceAtLeast(0)).forEach { runCatching { Files.deleteIfExists(it) } }
    }

    /** Checks now and then in the background for as long as the helper runs. */
    fun startSchedule() {
        Thread({
            Thread.sleep(15_000)   // let the helper finish starting first
            while (true) {
                runCatching { runIfNeeded()?.let { println("Backup saved: $it") } }
                Thread.sleep(Duration.ofHours(3).toMillis())
            }
        }, "backup").apply { isDaemon = true; start() }
    }
}
