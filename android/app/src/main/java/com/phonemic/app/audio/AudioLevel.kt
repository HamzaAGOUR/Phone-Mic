package com.phonemic.app.audio

import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Converts a packet of 16-bit little-endian PCM into a 0..100 display level
 * on a -60..0 dBFS scale. Digital silence is exactly zero, which distinguishes
 * a muted microphone from very quiet sound.
 */
internal object AudioLevel {
    private const val SILENCE_RMS = 1.0
    private const val MIN_DECIBELS = -60.0
    private const val MAX_DECIBELS = 0.0
    private const val FULL_SCALE = 32768.0

    fun percent(
        packet: ByteArray,
        pcmOffset: Int = AudioProtocol.SEQUENCE_HEADER_BYTES,
    ): Int {
        var sumSquares = 0.0
        var sampleCount = 0
        var index = pcmOffset

        while (index + 1 < packet.size) {
            val sample = (
                ((packet[index + 1].toInt() and 0xFF) shl 8) or
                    (packet[index].toInt() and 0xFF)
                ).toShort().toInt()
            sumSquares += sample.toDouble() * sample
            sampleCount++
            index += 2
        }

        if (sampleCount == 0) return 0

        val rms = sqrt(sumSquares / sampleCount)
        if (rms < SILENCE_RMS) return 0

        val decibels = 20.0 * log10(rms / FULL_SCALE)
        val percent = (decibels - MIN_DECIBELS) /
            (MAX_DECIBELS - MIN_DECIBELS) * 100.0
        return percent.coerceIn(0.0, 100.0).roundToInt()
    }
}
