package com.phonemic.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Ipv4AddressTest {
    @Test
    fun acceptsAndNormalizesPrivateLanAddresses() {
        assertEquals("192.168.1.23", Ipv4Address.normalize(" 192.168.1.23 "))
        assertEquals("10.0.0.5", Ipv4Address.normalize("010.000.000.005"))
        assertEquals("172.16.31.254", Ipv4Address.normalize("172.16.31.254"))
    }

    @Test
    fun rejectsMalformedAddresses() {
        assertNull(Ipv4Address.normalize(""))
        assertNull(Ipv4Address.normalize("192.168.1"))
        assertNull(Ipv4Address.normalize("192.168.1.1.2"))
        assertNull(Ipv4Address.normalize("192.168.1.256"))
        assertNull(Ipv4Address.normalize("192.168.1.a"))
        assertNull(Ipv4Address.normalize("192.168..1"))
        assertNull(Ipv4Address.normalize("192.168.1."))
    }

    @Test
    fun rejectsAddressesThatCannotBeAComputerOnTheLan() {
        assertNull(Ipv4Address.normalize("0.0.0.0"))
        assertNull(Ipv4Address.normalize("127.0.0.1"))
        assertNull(Ipv4Address.normalize("224.0.0.1"))
        assertNull(Ipv4Address.normalize("255.255.255.255"))
    }
}
