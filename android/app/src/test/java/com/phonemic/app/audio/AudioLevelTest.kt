package com.phonemic.app.audio

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLevelTest {

    @Test
    fun digitalSilenceIsZero() {
        assertEquals(0, AudioLevel.percent(packet { 0 }))
    }

    @Test
    fun headerBytesAreIgnored() {
        val packet = packet { 0 }
        packet[0] = 0xFF.toByte()
        packet[1] = 0xFF.toByte()
        packet[2] = 0xFF.toByte()
        packet[3] = 0xFF.toByte()

        assertEquals(0, AudioLevel.percent(packet))
    }

    @Test
    fun steadyToneUsesTheDecibelScale() {
        // 8000 amplitude is about -15.2 dBFS, which maps to 75%.
        val level = AudioLevel.percent(packet { index ->
            (sin(2 * PI * 440 * index / AudioProtocol.SAMPLE_RATE) * 8000).toInt()
        })

        assertEquals(75, level)
    }

    @Test
    fun fullScaleNegativeSamplesReachOneHundred() {
        assertEquals(100, AudioLevel.percent(packet { -32768 }))
    }

    @Test
    fun quietSignalStaysAboveZero() {
        val level = AudioLevel.percent(packet { index ->
            if (index % 2 == 0) 50 else -50
        })

        assertTrue("Expected a small but nonzero level, got $level", level in 1..10)
    }

    private fun packet(sampleAt: (Int) -> Int): ByteArray {
        val packet = ByteArray(AudioProtocol.PACKET_BYTES)

        for (index in 0 until AudioProtocol.SAMPLES_PER_PACKET) {
            val sample = sampleAt(index)
            val offset = AudioProtocol.SEQUENCE_HEADER_BYTES + index * 2
            packet[offset] = (sample and 0xFF).toByte()
            packet[offset + 1] = ((sample shr 8) and 0xFF).toByte()
        }

        return packet
    }
}
