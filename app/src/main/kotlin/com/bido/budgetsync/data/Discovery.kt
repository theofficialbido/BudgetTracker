package com.bido.budgetsync.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import kotlin.coroutines.resume

/** Finds the laptop helper on the current network by its "_budgetsync._tcp" announcement (mDNS / Bonjour). */
object Discovery {
    private const val TYPE = "_budgetsync._tcp"

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
