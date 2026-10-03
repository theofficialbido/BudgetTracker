package com.bido.budgetsync.helper

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.concurrent.Executors

private val mapper = ObjectMapper()

fun main(args: Array<String>) {
    val cfg = Config.load(args.filter { it != "--upgrade" }.toTypedArray())
    val book = BudgetWorkbook(cfg.workbook, cfg.dataDir)
    val ledger = SyncLedger(cfg.dataDir.resolve("synced-ids.json"))
    if ("--upgrade" in args) {
        // One-time: add the Income sheet and link the Tracker's income line to it. Stop the helper first.
        val r = try { book.upgrade(ledger) } catch (e: BusyException) { println("Not upgraded: ${e.message}. Close Excel and try again."); return }
        if (r.alreadyUpgraded) println("Workbook already upgraded, nothing changed.")
        else println("Workbook upgraded. Moved ${r.movedRows} income row(s) from Log. Backup: ${r.backup}")
        return
    }
    val server = HttpServer.create(cfg.bind?.let { InetSocketAddress(InetAddress.getByName(it), cfg.port) } ?: InetSocketAddress(cfg.port), 0)
    server.executor = Executors.newFixedThreadPool(4)

    server.createContext("/state") { ex ->
        guarded(ex, cfg) {
            if (ex.requestMethod != "GET") reply(ex, 405, mapOf("error" to "GET only"))
            else reply(ex, 200, book.readState())
        }
    }
    server.createContext("/categories") { ex ->
        guarded(ex, cfg) {
            if (ex.requestMethod != "POST") reply(ex, 405, mapOf("error" to "POST only"))
            else reply(ex, 200, mapOf("results" to book.addCategories(parseCategories(ex.requestBody.readBytes()))))
        }
    }
    server.createContext("/expenses") { ex ->
        guarded(ex, cfg) {
            if (ex.requestMethod != "POST") reply(ex, 405, mapOf("error" to "POST only"))
            else reply(ex, 200, mapOf("results" to book.append(parse(ex.requestBody.readBytes()), ledger)))
        }
    }
    server.start()
    if (cfg.bind == null) MdnsAdvertiser(cfg.port).start()   // a --bind test instance stays invisible on the network

    println("Budget sync helper running on port ${cfg.port}")
    println("Workbook : ${cfg.workbook}")
    println("Token    : ${cfg.token}   (type this into the phone app)")
    val host = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("this-laptop")
    println("Address  : $host.local:${cfg.port}")
    NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
        .forEach { println("Or IP    : ${it.hostAddress}:${cfg.port}") }
}

private fun parseCategories(body: ByteArray): List<NewCategory> {
    val root = mapper.readTree(body)
    require(root != null && root.isArray) { "Body must be a JSON array" }
    return root.map { n -> NewCategory(n.path("name").asText(""), n.path("planned").asDouble(0.0)) }
}

private fun parse(body: ByteArray): List<NewExpense> {
    val root = mapper.readTree(body)
    require(root != null && root.isArray) { "Body must be a JSON array" }
    return root.map { n ->
        val id = n.path("id").asText("").also { require(it.isNotBlank()) { "id required" } }
        val amount = n.path("amount").asDouble(Double.NaN)
        require(amount.isFinite() && amount > 0) { "amount must be positive" }
        NewExpense(
            id, LocalDate.parse(n.path("date").asText()), n.path("category").asText("").trim(),
            n.path("description").asText("").trim(), amount,
        )
    }
}

private fun guarded(ex: HttpExchange, cfg: Config, block: () -> Unit) {
    try {
        val given = Config.normalize(ex.requestHeaders.getFirst("X-Token") ?: "")
        val ok = MessageDigest.isEqual(given.toByteArray(), Config.normalize(cfg.token).toByteArray())
        if (!ok) reply(ex, 401, mapOf("error" to "bad token")) else block()
    } catch (e: BusyException) {
        reply(ex, 503, mapOf("error" to "busy", "detail" to e.message))
    } catch (e: FullException) {
        reply(ex, 507, mapOf("error" to "full", "detail" to e.message))
    } catch (e: IllegalArgumentException) {
        reply(ex, 400, mapOf("error" to "bad request", "detail" to e.message))
    } catch (e: DateTimeParseException) {
        reply(ex, 400, mapOf("error" to "bad request", "detail" to "date must be yyyy-MM-dd"))
    } catch (e: JsonProcessingException) {
        reply(ex, 400, mapOf("error" to "bad request", "detail" to "invalid JSON"))
    } catch (e: Exception) {
        System.err.println("Unexpected error: $e")
        reply(ex, 500, mapOf("error" to "server error", "detail" to e.message))
    } finally {
        ex.close()
    }
}

private fun reply(ex: HttpExchange, code: Int, body: Any) {
    val bytes = mapper.writeValueAsBytes(body)
    ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
    ex.sendResponseHeaders(code, bytes.size.toLong())
    ex.responseBody.use { it.write(bytes) }
}
