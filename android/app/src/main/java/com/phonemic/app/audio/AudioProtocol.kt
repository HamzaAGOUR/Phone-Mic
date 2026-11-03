package com.phonemic.app.audio

/**
 * Wire format shared with the Windows receiver.
 *
 * Every UDP datagram is exactly 964 bytes:
 *   4-byte big-endian unsigned sequence number
 *   + 960 bytes of 16-bit little-endian mono PCM at 48 kHz (480 samples).
 */
internal object AudioProtocol {
    const val PORT = 48123
    const val SAMPLE_RATE = 48_000
    const val BITS_PER_SAMPLE = 16
    const val SAMPLES_PER_PACKET = 480
    const val PCM_BYTES_PER_PACKET = 960
    const val SEQUENCE_HEADER_BYTES = 4
    const val PACKET_BYTES = SEQUENCE_HEADER_BYTES + PCM_BYTES_PER_PACKET

    fun writeSequenceHeader(packet: ByteArray, sequence: UInt) {
        require(packet.size >= SEQUENCE_HEADER_BYTES) {
            "Packet is too small for the sequence header"
        }
        packet[0] = (sequence shr 24).toByte()
        packet[1] = (sequence shr 16).toByte()
        packet[2] = (sequence shr 8).toByte()
        packet[3] = sequence.toByte()
    }

    /**
     * Replaces the PCM payload with digital silence while leaving the sequence
     * header untouched. Used by the mute button so the stream stays connected.
     */
    fun silencePcm(packet: ByteArray) {
        require(packet.size >= SEQUENCE_HEADER_BYTES) {
            "Packet is too small for the sequence header"
        }
        packet.fill(0, SEQUENCE_HEADER_BYTES, packet.size)
    }
}
