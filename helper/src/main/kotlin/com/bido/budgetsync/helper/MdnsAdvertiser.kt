package com.bido.budgetsync.helper

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Announces this helper on the local network as "_budgetsync._tcp" so the phone can find it without a saved IP.
 * Re-announces when the laptop's network addresses change (new Wi-Fi, hotspot, waking from sleep).
 */
class MdnsAdvertiser(private val port: Int) {
    companion object {
        const val SERVICE_TYPE = "_budgetsync._tcp.local."
        private val VIRTUAL = Regex("vethernet|virtual|vmware|vbox|hyper-v|bluetooth|loopback|tailscale|wsl|docker", RegexOption.IGNORE_CASE)

        /** IPv4 addresses on real, running, multicast-capable adapters (skips VM and VPN adapters). */
        fun lanAddresses(): Set<Inet4Address> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && it.supportsMulticast() && !it.isVirtual && !VIRTUAL.containsMatchIn(it.displayName + it.name) }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLinkLocalAddress }
                .toSet()
        } catch (_: Exception) { emptySet() }
    }

    private val active = HashMap<InetAddress, JmDNS>()
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread({
            while (running) {
                runCatching { sync(lanAddresses()) }.onFailure { System.err.println("mDNS: ${it.message}") }
                Thread.sleep(15_000)
            }
        }, "mdns-advertiser").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        sync(emptySet())
    }

    @Synchronized
    private fun sync(wanted: Set<InetAddress>) {
        (active.keys - wanted).toList().forEach { addr -> active.remove(addr)?.let { runCatching { it.close() } } }
        for (addr in wanted - active.keys) {
            try {
                val dns = JmDNS.create(addr, "budgetsync-helper")
                dns.registerService(ServiceInfo.create(SERVICE_TYPE, "BudgetSync", port, "Budget Tracker sync helper"))
                active[addr] = dns
                println("Announcing on ${addr.hostAddress} as BudgetSync (auto-discovery)")
            } catch (e: Exception) {
                System.err.println("mDNS on ${addr.hostAddress} failed: ${e.message}")
            }
        }
    }
}
