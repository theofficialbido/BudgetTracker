package com.bido.budgetsync.helper

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.SecureRandom

data class Config(val port: Int, val token: String, val workbook: Path, val dataDir: Path, val bind: String? = null) {
    companion object {
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        /** Options: --workbook <path> --port <n> --data-dir <dir>. The token is created on first run and kept in config.json. */
        fun load(args: Array<String>): Config {
            val opts = args.toList().chunked(2).filter { it.size == 2 }.associate { it[0] to it[1] }
            val dataDir = Paths.get(
                opts["--data-dir"] ?: ((System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")) + "\\BudgetSync")
            )
            Files.createDirectories(dataDir)
            val file = dataDir.resolve("config.json")
            val mapper = ObjectMapper()
            val json = if (Files.exists(file)) mapper.readTree(file.toFile()) else mapper.createObjectNode()
            val token = json.path("token").asText("").ifBlank {
                val rnd = SecureRandom()
                val raw = (1..12).map { ALPHABET[rnd.nextInt(ALPHABET.length)] }.joinToString("")
                raw.chunked(4).joinToString("-").also {
                    mapper.writerWithDefaultPrettyPrinter().writeValue(
                        file.toFile(), mapper.createObjectNode().put("token", it)
                    )
                }
            }
            val workbook = Paths.get(
                opts["--workbook"] ?: json.path("workbook").asText("")
                    .ifBlank { System.getProperty("user.home") + "\\OneDrive\\Documents\\Budget\\Budget.xlsx" }
            )
            val port = (opts["--port"] ?: json.path("port").asText("")).toIntOrNull() ?: 8765
            return Config(port, token, workbook, dataDir, opts["--bind"])
        }

        fun normalize(token: String) = token.uppercase().filter { it.isLetterOrDigit() }
    }
}
