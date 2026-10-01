package com.bido.budgetsync.helper

import javax.jmdns.JmDNS
import kotlin.test.Test
import kotlin.test.assertTrue

/** Needs real multicast networking, so it only runs when MDNS_TEST=1 is set. */
class MdnsAdvertiserTest {
    @Test
    fun advertisedServiceCanBeDiscovered() {
        if (System.getenv("MDNS_TEST") != "1") return
        val addr = MdnsAdvertiser.lanAddresses().firstOrNull() ?: return
        val advertiser = MdnsAdvertiser(48765)
        advertiser.start()
        val finder = JmDNS.create(addr, "test-finder")
        try {
            var found = false
            repeat(10) {
                if (!found) {
                    Thread.sleep(1000)
                    found = finder.list(MdnsAdvertiser.SERVICE_TYPE, 2000).any { it.port == 48765 }
                }
            }
            assertTrue(found, "service _budgetsync._tcp was not discovered on ${addr.hostAddress}")
        } finally {
            finder.close()
            advertiser.stop()
        }
    }
}
