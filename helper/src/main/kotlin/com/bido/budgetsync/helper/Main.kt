package com.bido.budgetsync.helper

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

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

    HelperServer(cfg, book, ledger).start()
    if (cfg.bind == null) BackupManager(cfg.workbook, cfg.dataDir.resolve("backups").resolve("daily")).startSchedule()
    if (cfg.bind == null) MdnsAdvertiser(cfg.port).start()   // a --bind test instance stays invisible on the network

    println("Budget sync helper running on port ${cfg.port}")
    println("Workbook : ${cfg.workbook}")
    println("Token    : ${cfg.token}   (type this into the phone app)")
    println("Briefing : ${cfg.dataDir.resolve("briefs")}   (budget.md and budget.json, rewritten after every sync)")
    val host = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("this-laptop")
    println("Address  : $host.local:${cfg.port}")
    NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
        .forEach { println("Or IP    : ${it.hostAddress}:${cfg.port}") }
}
