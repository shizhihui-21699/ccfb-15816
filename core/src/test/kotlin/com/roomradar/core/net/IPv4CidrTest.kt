package com.roomradar.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IPv4CidrTest {
    @Test
    fun local24ProducesBoundedHostList() {
        val cidr = IPv4Cidr.local24("10.20.30.45")
        val hosts = cidr.hosts().toList()

        assertEquals(254, hosts.size)
        assertEquals("10.20.30.1", hosts.first())
        assertEquals("10.20.30.254", hosts.last())
    }

    @Test
    fun parsesCidrAndChecksContains() {
        val cidr = IPv4Cidr.parse("192.168.1.10/24")
        assertEquals("192.168.1.0", formatIPv4(cidr.network))
        assertTrue(cidr.contains(parseIPv4("192.168.1.99")))
        assertFalse(cidr.contains(parseIPv4("192.168.2.99")))
    }
}
