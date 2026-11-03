package com.phonemic.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures the microphone on a dedicated audio thread and sends raw PCM over UDP.
 *
 * Stage 1 keeps this object inside the Activity. Stage 5 will host the same
 * capture logic in a foreground service so it survives the screen turning off.
 */
class MicStreamer(
    private val listener: (Status) -> Unit,
    private val onLevelChanged: (Int) -> Unit = {},
) {
    sealed interface Status {
        data object Starting : Status
        data class Streaming(val ipAddress: String) : Status
        data object Stopped : Status
        data class Error(val kind: ErrorKind) : Status
    }

    enum class ErrorKind {
        MICROPHONE,
        NETWORK,
        UNKNOWN,
    }

    private val running = AtomicBoolean(false)

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var socket: DatagramSocket? = null

    fun start(ipAddress: String) {
        if (!running.compareAndSet(false, true)) return

        listener(Status.Starting)
        val thread = Thread({ runStream(ipAddress) }, "PhoneMicCapture").apply {
            isDaemon = true
        }
        worker = thread
        thread.start()
    }

    /**
     * Requests a stop. AudioRecord.stop() and DatagramSocket.close() unblock the
     * capture thread, which then releases everything and reports Stopped.
     */
    fun stop() {
        if (!running.getAndSet(false)) return

        unblockCaptureThread()
        worker?.interrupt()
    }

    @SuppressLint("MissingPermission")
    private fun runStream(ipAddress: String) {
        var failure: ErrorKind? = null

        try {
            if (!running.get()) return

            // Keep capture at audio priority so UI work cannot cause packet gaps.
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

            val destination = try {
                InetAddress.getByName(ipAddress)
            } catch (e: Exception) {
                Log.e(TAG, "Could not resolve the PC address", e)
                failure = ErrorKind.NETWORK
                return
            }

            socket = try {
                DatagramSocket().apply {
                    // Connecting a UDP socket lets Android route sends correctly
                    // and report some unreachable-network errors immediately.
                    connect(destination, AudioProtocol.PORT)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not open the UDP socket", e)
                failure = ErrorKind.NETWORK
                return
            }

            if (!running.get()) return

            val record = try {
                createAudioRecord()
            } catch (e: Exception) {
                Log.e(TAG, "Could not create AudioRecord", e)
                failure = ErrorKind.MICROPHONE
                return
            }
            audioRecord = record

            try {
                record.startRecording()
            } catch (e: Exception) {
                Log.e(TAG, "Could not start recording", e)
                failure = ErrorKind.MICROPHONE
                return
            }

            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "AudioRecord did not enter the recording state")
                failure = ErrorKind.MICROPHONE
                return
            }

            if (!running.get()) return
            listener(Status.Streaming(ipAddress))

            val packet = ByteArray(AudioProtocol.PACKET_BYTES)
            var sequence = 0u

            while (running.get()) {
                val completeChunk = try {
                    readChunk(record, packet)
                } catch (e: Exception) {
                    if (running.get()) {
                        Log.e(TAG, "Microphone read failed", e)
                        failure = ErrorKind.MICROPHONE
                    }
                    break
                }
                if (!completeChunk) break

                // Report the raw microphone level about ten times per second
                // so the UI can show whether capture is actually producing
                // sound. Exact digital silence usually means Android is
                // muting the microphone.
                if (sequence % LEVEL_REPORT_INTERVAL == 0u) {
                    reportLevel(packet)
                }

                AudioProtocol.writeSequenceHeader(packet, sequence)

                try {
                    socket?.send(DatagramPacket(packet, packet.size))
                } catch (e: Exception) {
                    if (running.get()) {
                        Log.e(TAG, "UDP send failed", e)
                        failure = ErrorKind.NETWORK
                    }
                    break
                }

                // UInt wraps around automatically after 0xFFFFFFFF, matching the
                // big-endian unsigned sequence number in the protocol.
                sequence += 1u
            }
        } catch (t: Throwable) {
            if (running.get()) {
                Log.e(TAG, "Unexpected streaming failure", t)
                failure = ErrorKind.UNKNOWN
            }
        } finally {
            running.set(false)
            unblockCaptureThread()
            releaseAudioRecord()
            socket = null
            worker = null

            listener(
                if (failure == null) {
                    Status.Stopped
                } else {
                    Status.Error(failure)
                },
            )
        }
    }

    // The caller (currently MainActivity) checks RECORD_AUDIO immediately
    // before starting this stream. Any SecurityException is caught by
    // runStream() and surfaced as a microphone error.
    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            AudioProtocol.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferBytes <= 0) {
            throw IOException("AudioRecord.getMinBufferSize returned $minBufferBytes")
        }

        // Keep several chunks queued inside AudioRecord. We still read one
        // 10 ms chunk at a time, so this only protects against scheduler jitter.
        val bufferBytes = maxOf(minBufferBytes, AudioProtocol.PACKET_BYTES * 8)

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(AudioProtocol.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IOException("AudioRecord could not be initialized")
        }
        return record
    }

    /**
     * Fills bytes 4..963 with exactly one 480-sample chunk. A blocking read can
     * occasionally return a partial buffer, so loop until the packet is full.
     */
    private fun readChunk(record: AudioRecord, packet: ByteArray): Boolean {
        var offset = AudioProtocol.SEQUENCE_HEADER_BYTES

        while (offset < packet.size) {
            if (!running.get()) return false

            val bytesRead = record.read(
                packet,
                offset,
                packet.size - offset,
            )

            if (bytesRead < 0) {
                throw IOException("AudioRecord.read returned $bytesRead")
            }
            if (bytesRead == 0) continue

            offset += bytesRead
        }
        return true
    }

    /**
     * Reports the raw microphone level about ten times per second so the UI
     * can show whether capture is actually producing sound.
     */
    private fun reportLevel(packet: ByteArray) {
        val level = AudioLevel.percent(packet)
        try {
            onLevelChanged(level)
        } catch (t: Throwable) {
            // A UI callback must never stop the capture thread.
            Log.w(TAG, "Microphone level callback failed", t)
        }
    }

    private fun unblockCaptureThread() {
        // AudioRecord.stop() wakes a blocking read. Catch errors because the
        // capture thread may be releasing the same object at the same time.
        audioRecord?.let { record ->
            runCatching {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            }
        }
        runCatching { socket?.close() }
    }

    private fun releaseAudioRecord() {
        val record = audioRecord ?: return
        audioRecord = null
        runCatching {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
        }
        runCatching { record.release() }
    }

    private companion object {
        const val TAG = "MicStreamer"

        // 100 packets per second, so every tenth packet is about 100 ms.
        val LEVEL_REPORT_INTERVAL = 10u
    }
}
