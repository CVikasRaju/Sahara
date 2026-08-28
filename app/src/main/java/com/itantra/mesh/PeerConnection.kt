package com.itantra.mesh

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Manages bidirectional I/O on a single socket connection to one mesh peer.
 *
 * Uses the same 2-byte big-endian length-prefixed wire format as the existing
 * [com.itantra.network.BluetoothTransport] for full protocol compatibility:
 *
 *     [uint16 frame-length] [iBFS-v1 frame bytes ...]
 *
 * Transport-agnostic: works with any [InputStream]/[OutputStream] pair, whether
 * from a Bluetooth RFCOMM socket or a Wi-Fi Direct TCP socket.
 *
 * @param peerId Unique identifier for the connected peer.
 * @param inputStream The socket's input stream for reading frames.
 * @param outputStream The socket's output stream for sending frames.
 * @param onFrameReceived Callback invoked when a complete frame is read.
 * @param onDisconnected Callback invoked when the connection is lost.
 */
class PeerConnection(
    val peerId: String,
    private val inputStream: InputStream,
    private val outputStream: OutputStream,
    private val onFrameReceived: (frame: ByteArray, peerId: String) -> Unit,
    private val onDisconnected: (peerId: String) -> Unit,
) {
    companion object {
        private const val TAG = "PeerConnection"

        /**
         * Maximum accepted frame size (64 KB).
         * Larger frames are rejected to prevent OOM on constrained devices.
         */
        private const val MAX_FRAME_SIZE = 65_535
    }

    @Volatile
    private var running = false
    private var readJob: Job? = null

    /** Whether this connection's read loop is still active. */
    val isActive: Boolean get() = running

    /**
     * Starts the background read loop in the given [scope].
     *
     * Incoming frames are delivered via [onFrameReceived].
     * If the socket is closed or an I/O error occurs, [onDisconnected] is called
     * and the read loop terminates.
     */
    fun startReadLoop(scope: CoroutineScope) {
        if (running) return
        running = true
        readJob = scope.launch(Dispatchers.IO) {
            try {
                readLoop()
            } catch (e: IOException) {
                Log.d(TAG, "Read loop ended for peer $peerId: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error in read loop for peer $peerId", e)
            } finally {
                running = false
                onDisconnected(peerId)
            }
        }
    }

    /**
     * Blocking read loop: reads 2-byte length prefix, then the full frame payload.
     * Exits on EOF (returns -1) or I/O error (throws IOException).
     */
    private fun readLoop() {
        val lengthBuf = ByteArray(2)
        while (running) {
            // Read 2-byte big-endian length prefix
            readFully(inputStream, lengthBuf, 2)

            val frameLen = ((lengthBuf[0].toInt() and 0xFF) shl 8) or
                (lengthBuf[1].toInt() and 0xFF)

            if (frameLen <= 0) continue

            if (frameLen > MAX_FRAME_SIZE) {
                Log.w(TAG, "Frame too large ($frameLen bytes) from peer $peerId, skipping")
                skipBytes(inputStream, frameLen)
                continue
            }

            // Read the complete frame payload
            val frameBuf = ByteArray(frameLen)
            readFully(inputStream, frameBuf, frameLen)
            onFrameReceived(frameBuf, peerId)
        }
    }

    /**
     * Sends an already-encoded iBFS-v1 frame with a 2-byte big-endian length prefix.
     *
     * Thread-safe: synchronized on the output stream to prevent interleaved writes
     * from concurrent coroutines.
     *
     * @param frame The raw iBFS-v1 frame bytes (header + payload + CRC).
     * @return true if the frame was sent successfully, false on I/O error.
     */
    fun send(frame: ByteArray): Boolean {
        if (!running) return false
        return try {
            val lengthPrefix = byteArrayOf(
                ((frame.size shr 8) and 0xFF).toByte(),
                (frame.size and 0xFF).toByte(),
            )
            synchronized(outputStream) {
                outputStream.write(lengthPrefix)
                outputStream.write(frame)
                outputStream.flush()
            }
            true
        } catch (e: IOException) {
            Log.d(TAG, "Send failed to peer $peerId: ${e.message}")
            close()
            false
        }
    }

    /**
     * Closes the connection and cancels the read loop.
     * Safe to call multiple times.
     */
    fun close() {
        running = false
        readJob?.cancel()
        readJob = null
        try { inputStream.close() } catch (_: IOException) {}
        try { outputStream.close() } catch (_: IOException) {}
    }

    /**
     * Reads exactly [count] bytes into [buffer], blocking until all bytes arrive.
     * @throws IOException on EOF or stream error.
     */
    private fun readFully(input: InputStream, buffer: ByteArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val n = input.read(buffer, offset, count - offset)
            if (n < 0) throw IOException("EOF reached after $offset/$count bytes")
            offset += n
        }
    }

    /**
     * Skips exactly [count] bytes from the stream to keep framing aligned
     * after encountering an oversized frame.
     * @throws IOException on EOF or stream error.
     */
    private fun skipBytes(input: InputStream, count: Int) {
        var remaining = count
        val skipBuf = ByteArray(minOf(remaining, MeshConfig.SOCKET_BUFFER_SIZE))
        while (remaining > 0) {
            val toRead = minOf(remaining, skipBuf.size)
            val n = input.read(skipBuf, 0, toRead)
            if (n < 0) throw IOException("EOF while skipping oversized frame")
            remaining -= n
        }
    }
}
