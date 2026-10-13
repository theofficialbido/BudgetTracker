package com.bido.budgetsync.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryTest {
    @Test fun sweepCoversTheOtherAddressesOfTheSameSubnet() {
        val t = Discovery.sweepTargets("10.245.61.196")
        assertEquals(253, t.size)
        assertFalse("10.245.61.196" in t)                 // not itself
        assertTrue("10.245.61.227" in t)                  // the laptop from the hotspot
        assertTrue(t.all { it.startsWith("10.245.61.") })
        assertEquals("10.245.61.1", t.first())
        assertEquals("10.245.61.254", t.last())
    }

    @Test fun nothingToSweepWithoutAUsableAddress() {
        assertTrue(Discovery.sweepTargets("").isEmpty())
        assertTrue(Discovery.sweepTargets("not-an-ip").isEmpty())
        assertTrue(Discovery.sweepTargets("fe80::1").isEmpty())
    }
}
