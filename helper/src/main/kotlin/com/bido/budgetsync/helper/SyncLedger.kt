package com.bido.budgetsync.helper

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Where an expense or income entry lives in the workbook: sheet name and 1-based row. */
data class Slot(val sheet: String, val row: Int)

/** Maps phone entry id -> workbook slot so retries never duplicate. Stored as "Sheet:row"; old files held bare Log row numbers. */
class SyncLedger(private val file: Path) {
    private val mapper = ObjectMapper()
    private val slots = LinkedHashMap<String, Slot>()

    init {
        if (Files.exists(file)) {
            mapper.readTree(file.toFile()).fields().forEach { (id, v) ->
                slots[id] = if (v.isInt) Slot("Log", v.asInt())
                else v.asText().split(":").let { Slot(it[0], it[1].toInt()) }
            }
        }
    }

    @Synchronized
    fun slotFor(id: String): Slot? = slots[id]

    /** Rows of [sheet] that already belong to a phone entry. */
    @Synchronized
    fun claimedRows(sheet: String): Set<Int> = slots.values.filter { it.sheet == sheet }.map { it.row }.toSet()

    @Synchronized
    fun putAll(entries: Map<String, Slot>) {
        if (entries.isEmpty()) return
        slots.putAll(entries)
        save()
    }

    /** The entry in this slot was deleted from the workbook. */
    @Synchronized
    fun removeSlot(slot: Slot) {
        if (slots.values.removeAll { it == slot }) save()
    }

    /** Used by the workbook upgrade when rows move between sheets. */
    @Synchronized
    fun relocate(moves: Map<Slot, Slot>) {
        if (moves.isEmpty()) return
        slots.entries.forEach { e -> moves[e.value]?.let { e.setValue(it) } }
        save()
    }

    private fun save() {
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        mapper.writeValue(tmp.toFile(), slots.mapValues { "${it.value.sheet}:${it.value.row}" })
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
    }
}
