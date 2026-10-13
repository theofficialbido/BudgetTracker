package com.bido.budgetsync.helper

import com.bido.budgetsync.sync.SnapshotJson
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.concurrent.Executors

/**
 * The helper's HTTP side.
 *
 * Modelled on the ToDo app's laptop sync: the phone sends one versioned snapshot to POST /api/sync with the pairing code
 * as a bearer token, and the server is only reachable from private networks. The older per-item endpoints (/state,
 * /expenses, /categories, /plans, /closings, /entries, /app) are kept, so earlier app versions and BaseCamp keep working.
 *
 *  - every request needs the pairing code, as `Authorization: Bearer <code>` or `X-Token: <code>`
 *  - only loopback / private / link-local / carrier-grade-NAT (Tailscale) callers are answered
 *  - a wrong code is answered after a short delay, which slows down guessing
 *  - bodies are limited to 16 MB
 */
class HelperServer(
    private val cfg: Config,
    private val book: BudgetWorkbook,
    private val ledger: SyncLedger,
) {
    private val mapper = ObjectMapper()
    private val snapshots = SnapshotService(book, ledger, cfg.dataDir, mapper)
    private var server: HttpServer? = null

    val port: Int get() = server?.address?.port ?: cfg.port

    fun start(): HelperServer {
        val bind = cfg.bind?.let { InetSocketAddress(InetAddress.getByName(it), cfg.port) } ?: InetSocketAddress(cfg.port)
        val s = HttpServer.create(bind, 0)
        s.executor = Executors.newFixedThreadPool(4)
        routes(s)
        s.start()
        server = s
        return this
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    private fun routes(s: HttpServer) {
        // ---- the snapshot protocol (same shape as the ToDo app's laptop sync) ---------------------------------
        s.createContext("/api/ping") { ex -> guarded(ex) { reply(ex, 200, mapOf("ok" to true, "app" to "BudgetSync")) } }
        s.createContext("/api/sync") { ex ->
            guarded(ex) {
                if (ex.requestMethod != "POST") { reply(ex, 405, mapOf("ok" to false, "error" to "POST only")); return@guarded }
                val body = readBody(ex) ?: run { reply(ex, 413, mapOf("ok" to false, "error" to "too large")); return@guarded }
                val text = String(body, Charsets.UTF_8)
                val snapshot = try { SnapshotJson.decode(text) } catch (e: Exception) {
                    reply(ex, 400, mapOf("ok" to false, "error" to "bad snapshot", "detail" to (e.message ?: "")))
                    return@guarded
                }
                val receipt = snapshots.receive(snapshot, text)
                replyRaw(ex, 200, com.bido.budgetsync.sync.ReceiptJson.encode(receipt))
            }
        }

        // ---- per-item endpoints, kept for earlier app versions and for BaseCamp ------------------------------
        s.createContext("/state") { ex ->
            guarded(ex) { if (ex.requestMethod != "GET") reply(ex, 405, mapOf("error" to "GET only")) else reply(ex, 200, book.readState()) }
        }
        // The app updates itself from here: version.json says what is published, app.apk is the build to install.
        val updateDir = cfg.dataDir.resolve("update")
        s.createContext("/app") { ex ->
            guarded(ex) {
                val name = when (ex.requestURI.path) { "/app/version" -> "version.json"; "/app/apk" -> "app.apk"; else -> null }
                val file = name?.let { updateDir.resolve(it) }
                if (ex.requestMethod != "GET") reply(ex, 405, mapOf("error" to "GET only"))
                else if (file == null || !Files.isRegularFile(file)) reply(ex, 404, mapOf("error" to "no update published"))
                else replyFile(ex, file, if (name == "app.apk") "application/vnd.android.package-archive" else "application/json; charset=utf-8")
            }
        }
        s.createContext("/entries") { ex ->
            guarded(ex) {
                if (ex.requestMethod != "POST") { reply(ex, 405, mapOf("error" to "POST only")); return@guarded }
                val n = mapper.readTree(readBody(ex) ?: return@guarded tooLarge(ex))
                val ref = EntryRef(
                    n.path("sheet").asText(""), n.path("row").asInt(-1),
                    n.path("expectDate").takeIf { !it.isMissingNode && !it.isNull }?.asText(),
                    n.path("expectAmount").takeIf { !it.isMissingNode && !it.isNull }?.asDouble(),
                )
                when (ex.requestURI.path) {
                    "/entries/delete" -> reply(ex, 200, mapOf("status" to book.deleteEntry(ref, ledger)))
                    "/entries/update" -> {
                        val amount = n.path("amount").asDouble(Double.NaN)
                        require(amount.isFinite() && amount > 0) { "amount must be positive" }
                        val change = EntryChange(ref, LocalDate.parse(n.path("date").asText()), n.path("category").asText("").trim(),
                            n.path("description").asText("").trim(), amount)
                        reply(ex, 200, mapOf("status" to book.updateEntry(change)))
                    }
                    else -> reply(ex, 404, mapOf("error" to "unknown"))
                }
            }
        }
        s.createContext("/closings") { ex ->
            guarded(ex) {
                val root = postedArray(ex) ?: return@guarded
                val items = root.map { n ->
                    ClosingRow(n.path("month").asText(""), n.path("reset").asBoolean(false), n.path("invested").asDouble(0.0), n.path("splurged").asDouble(0.0))
                }
                reply(ex, 200, mapOf("results" to book.setClosings(items)))
            }
        }
        s.createContext("/plans") { ex ->
            guarded(ex) {
                val root = postedArray(ex) ?: return@guarded
                reply(ex, 200, mapOf("results" to book.setPlans(root.map { n -> PlanChange(n.path("name").asText(""), n.path("planned").asDouble(Double.NaN)) })))
            }
        }
        s.createContext("/categories") { ex ->
            guarded(ex) {
                val root = postedArray(ex) ?: return@guarded
                reply(ex, 200, mapOf("results" to book.addCategories(root.map { n -> NewCategory(n.path("name").asText(""), n.path("planned").asDouble(0.0)) })))
            }
        }
        s.createContext("/expenses") { ex ->
            guarded(ex) {
                val root = postedArray(ex) ?: return@guarded
                val items = root.map { n ->
                    val id = n.path("id").asText("").also { require(it.isNotBlank()) { "id required" } }
                    val amount = n.path("amount").asDouble(Double.NaN)
                    require(amount.isFinite() && amount > 0) { "amount must be positive" }
                    NewExpense(id, LocalDate.parse(n.path("date").asText()), n.path("category").asText("").trim(), n.path("description").asText("").trim(), amount)
                }
                reply(ex, 200, mapOf("results" to book.append(items, ledger)))
            }
        }
    }

    // ---- request handling ------------------------------------------------------------------------------------

    private fun guarded(ex: HttpExchange, block: () -> Unit) {
        try {
            if (!isLocal(ex.remoteAddress.address)) {
                reply(ex, 403, mapOf("ok" to false, "error" to "local network only"))
                return
            }
            val sent = ex.requestHeaders.getFirst("Authorization").orEmpty().removePrefix("Bearer ").trim()
                .ifBlank { ex.requestHeaders.getFirst("X-Token").orEmpty() }
            val ok = MessageDigest.isEqual(Config.normalize(sent).toByteArray(), Config.normalize(cfg.token).toByteArray())
            if (!ok) {
                Thread.sleep(300)   // slows down guessing
                reply(ex, 401, mapOf("ok" to false, "error" to "bad token"))
                return
            }
            block()
        } catch (e: BusyException) {
            reply(ex, 503, mapOf("ok" to false, "error" to "busy", "detail" to e.message))
        } catch (e: ConflictException) {
            reply(ex, 409, mapOf("ok" to false, "error" to "conflict", "detail" to e.message))
        } catch (e: FullException) {
            reply(ex, 507, mapOf("ok" to false, "error" to "full", "detail" to e.message))
        } catch (e: IllegalArgumentException) {
            reply(ex, 400, mapOf("ok" to false, "error" to "bad request", "detail" to e.message))
        } catch (e: DateTimeParseException) {
            reply(ex, 400, mapOf("ok" to false, "error" to "bad request", "detail" to "date must be yyyy-MM-dd"))
        } catch (e: JsonProcessingException) {
            reply(ex, 400, mapOf("ok" to false, "error" to "bad request", "detail" to "invalid JSON"))
        } catch (e: JSONException) {
            reply(ex, 400, mapOf("ok" to false, "error" to "bad request", "detail" to "invalid JSON"))
        } catch (e: Exception) {
            System.err.println("Unexpected error: $e")
            runCatching { reply(ex, 500, mapOf("ok" to false, "error" to "server error", "detail" to e.message)) }
        } finally {
            ex.close()
        }
    }

    /** Reads a POST body that must be a JSON array, answering 405 / 413 / 400 itself when it is not. */
    private fun postedArray(ex: HttpExchange): com.fasterxml.jackson.databind.JsonNode? {
        if (ex.requestMethod != "POST") { reply(ex, 405, mapOf("error" to "POST only")); return null }
        val bytes = readBody(ex) ?: run { tooLarge(ex); return null }
        val root = mapper.readTree(bytes)
        require(root != null && root.isArray) { "Body must be a JSON array" }
        return root
    }

    private fun tooLarge(ex: HttpExchange) = reply(ex, 413, mapOf("ok" to false, "error" to "too large"))

    /** The whole body, or null when it is over 16 MB (a declared length is checked first, then what actually arrives). */
    private fun readBody(ex: HttpExchange): ByteArray? {
        val limit = 16 * 1024 * 1024
        val declared = ex.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
        if (declared != null && declared > limit) return null
        val bytes = ex.requestBody.readNBytes(limit + 1)
        return if (bytes.size > limit) null else bytes
    }

    private fun replyFile(ex: HttpExchange, file: java.nio.file.Path, type: String) {
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(200, Files.size(file))
        ex.responseBody.use { out -> Files.newInputStream(file).use { it.copyTo(out) } }
    }

    private fun reply(ex: HttpExchange, code: Int, body: Any) = replyRaw(ex, code, mapper.writeValueAsString(body))

    private fun replyRaw(ex: HttpExchange, code: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    companion object {
        /** Loopback, private (10/8, 172.16/12, 192.168/16), link-local, IPv6 unique-local, and 100.64/10 (Tailscale). */
        fun isLocal(a: InetAddress): Boolean {
            if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress) return true
            if (a is Inet4Address) {
                val b = a.address
                return (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64
            }
            if (a is Inet6Address) return (a.address[0].toInt() and 0xFE) == 0xFC
            return false
        }
    }
}
