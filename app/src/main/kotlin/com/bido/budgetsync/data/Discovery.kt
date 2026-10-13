package com.bido.budgetsync.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import kotlin.coroutines.resume

/**
 * Finds the laptop helper on the current network: first by its "_budgetsync._tcp" announcement (mDNS / Bonjour), and when
 * that is silent, by looking for the helper's port on the phone's own small private network. mDNS often does not work over
 * a phone's hotspot, but a laptop joined to it is always in the same /24 as the phone.
 */
object Discovery {
    private const val TYPE = "_budgetsync._tcp"

    /** Wi-Fi, hotspot and USB / ethernet tethering interfaces. Never the mobile-data interface: that is the carrier's network. */
    private val LOCAL_INTERFACES = Regex("^(wlan|swlan|ap|softap|eth|rndis|usb)\\d*$")

    /** The other 253 addresses of the /24 that [own] is in. */
    fun sweepTargets(own: String): List<String> {
        val parts = own.split(".")
        if (parts.size != 4) return emptyList()
        val prefix = parts.take(3).joinToString(".")
        return (1..254).map { "$prefix.$it" }.filter { it != own }
    }

    /**
     * Addresses on the phone's own Wi-Fi / hotspot / tethering network that accept connections on the helper's [port].
     * It only touches private addresses on local interfaces, one port, with short timeouts.
     */
    suspend fun sweep(port: Int = 8765, timeoutMs: Long = 5000): List<String> = withContext(Dispatchers.IO) {
        val own = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && LOCAL_INTERFACES.matches(it.name) }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList())
        val targets = own.flatMap { sweepTargets(it) }.distinct()
        if (targets.isEmpty()) return@withContext emptyList()
        val gate = Semaphore(64)
        withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                targets.map { ip ->
                    async {
                        gate.withPermit {
                            val open = try { Socket().use { it.connect(InetSocketAddress(ip, port), 350); true } } catch (_: Exception) { false }
                            if (open) "$ip:$port" else null
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        } ?: emptyList()
    }

    /** Returns "ip:port" of the helper, or null if nothing answers within [timeoutMs]. */
    suspend fun find(context: Context, timeoutMs: Long = 4000): String? {
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return null
        var listener: NsdManager.DiscoveryListener? = null
        return try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<String?> { cont ->
                    val l = object : NsdManager.DiscoveryListener {
                        override fun onDiscoveryStarted(serviceType: String) {}
                        override fun onDiscoveryStopped(serviceType: String) {}
                        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { if (cont.isActive) cont.resume(null) }
                        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                        override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                            @Suppress("DEPRECATION")
                            nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                                override fun onServiceResolved(info: NsdServiceInfo) {
                                    @Suppress("DEPRECATION")
                                    val host = info.host
                                    if (host is Inet4Address && cont.isActive) cont.resume("${host.hostAddress}:${info.port}")
                                }
                            })
                        }
                    }
                    listener = l
                    nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, l)
                }
            }
        } catch (_: Exception) {
            null
        } finally {
            listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        }
    }
}
