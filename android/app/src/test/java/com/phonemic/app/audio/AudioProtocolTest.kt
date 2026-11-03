package com.phonemic.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioProtocolTest {
    @Test
    fun packetLayoutMatchesTheSharedProtocol() {
        assertEquals(964, AudioProtocol.PACKET_BYTES)
        assertEquals(960, AudioProtocol.PCM_BYTES_PER_PACKET)
        assertEquals(480, AudioProtocol.SAMPLES_PER_PACKET)
        assertEquals(48_000, AudioProtocol.SAMPLE_RATE)
        assertEquals(48123, AudioProtocol.PORT)
    }

    @Test
    fun sequenceHeaderIsBigEndian() {
        val packet = ByteArray(AudioProtocol.PACKET_BYTES)

        AudioProtocol.writeSequenceHeader(packet, 0x01020304u)

        assertArrayEquals(
            byteArrayOf(0x01, 0x02, 0x03, 0x04),
            packet.copyOfRange(0, AudioProtocol.SEQUENCE_HEADER_BYTES),
        )
    }

    @Test
    fun sequenceHeaderWrapsThroughUInt() {
        val packet = ByteArray(AudioProtocol.PACKET_BYTES)

        AudioProtocol.writeSequenceHeader(packet, 0xFFFFFFFFu)
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            packet.copyOfRange(0, AudioProtocol.SEQUENCE_HEADER_BYTES),
        )

        AudioProtocol.writeSequenceHeader(packet, 0u)
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 0),
            packet.copyOfRange(0, AudioProtocol.SEQUENCE_HEADER_BYTES),
        )
    }

    @Test
    fun silencePcmKeepsTheSequenceHeader() {
        val packet = ByteArray(AudioProtocol.PACKET_BYTES) { 0x7F }
        AudioProtocol.writeSequenceHeader(packet, 42u)

        AudioProtocol.silencePcm(packet)

        assertArrayEquals(
            byteArrayOf(0, 0, 0, 42),
            packet.copyOfRange(0, AudioProtocol.SEQUENCE_HEADER_BYTES),
        )
        assertEquals(
            0,
            packet.copyOfRange(AudioProtocol.SEQUENCE_HEADER_BYTES, packet.size)
                .count { it != 0.toByte() },
        )
    }
}
