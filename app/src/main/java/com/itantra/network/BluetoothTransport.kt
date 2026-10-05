package com.itantra.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth RFCOMM transport per docs/NETWORK_PROTOCOL.md and docs/ARCHITECTURE.md.
 * Implements auto-reconnect with exponential backoff per docs/ARCHITECTURE.md §4.
 *
 * NOTE: Bluetooth pairing requires physical devices / Bluetooth hardware.
 * Device-specific quirks (especially around BLUETOOTH_CONNECT runtime permission on
 * Android 12+) are handled gracefully.
 */
class BluetoothTransport(
    private val adapter: BluetoothAdapter?,
    private val onFrameReceived: (ByteArray) -> Unit,
    private val onConnectionStateChanged: (connected: Boolean) -> Unit,
) {
    companion object {
        // Randomly generated UUID for this app's RFCOMM service — must match on both peers.
        val ITANTRA_SERVICE_UUID: UUID = UUID.fromString("7f7c1a3e-9c4b-4a1d-8e2f-3b6a1c9d5e01")
        private const val SERVICE_NAME = "iTantraChannel"
        private const val INITIAL_BACKOFF_MS = 1000L
        private const val MAX_RECONNECT_BACKOFF_MS = 16_000L
    }

    private var socket: BluetoothSocket? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var outputStream: OutputStream? = null
    @Volatile
    private var running = false
    private var transportJob: Job? = null

    val isConnected: Boolean
        get() = socket?.isConnected == true && outputStream != null

    @SuppressLint("MissingPermission") // caller must have requested BLUETOOTH_CONNECT at runtime
    fun startListening(scope: CoroutineScope) {
        val btAdapter = adapter ?: run {
            onConnectionStateChanged(false)
            return
        }
        running = true
        transportJob?.cancel()
        transportJob = scope.launch(Dispatchers.IO) {
            var backoff = INITIAL_BACKOFF_MS
            while (running && isActive) {
                try {
                    serverSocket = btAdapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, ITANTRA_SERVICE_UUID)
                    val accepted = serverSocket?.accept()
                    if (accepted != null) {
                        attachSocket(accepted)
                        backoff = INITIAL_BACKOFF_MS // reset backoff on success
                        readLoop(accepted)
                    }
                } catch (e: IOException) {
                    detachSocket()
                    if (!running || !isActive) break
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(MAX_RECONNECT_BACKOFF_MS)
                } catch (e: SecurityException) {
                    detachSocket()
                    break
                } finally {
                    try { serverSocket?.close() } catch (_: IOException) {}
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectTo(device: BluetoothDevice, scope: CoroutineScope) {
        val btAdapter = adapter ?: run {
            onConnectionStateChanged(false)
            return
        }
        running = true
        transportJob?.cancel()
        transportJob = scope.launch(Dispatchers.IO) {
            var backoff = INITIAL_BACKOFF_MS
            while (running && isActive) {
                try {
                    val newSocket = device.createRfcommSocketToServiceRecord(ITANTRA_SERVICE_UUID)
                    try { btAdapter.cancelDiscovery() } catch (_: SecurityException) {}
                    newSocket.connect()
                    attachSocket(newSocket)
                    backoff = INITIAL_BACKOFF_MS
                    readLoop(newSocket)
                } catch (e: IOException) {
                    detachSocket()
                    if (!running || !isActive) break
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(MAX_RECONNECT_BACKOFF_MS)
                } catch (e: SecurityException) {
                    detachSocket()
                    break
                }
            }
        }
    }

    private fun attachSocket(s: BluetoothSocket) {
        detachSocket()
        socket = s
        outputStream = s.outputStream
        onConnectionStateChanged(true)
    }

    private fun detachSocket() {
        try { outputStream?.close() } catch (_: IOException) {}
        try { socket?.close() } catch (_: IOException) {}
        outputStream = null
        socket = null
        onConnectionStateChanged(false)
    }

    private fun readLoop(s: BluetoothSocket) {
        val input: InputStream = s.inputStream
        // Simple length-prefixed framing over the raw RFCOMM stream: 2-byte
        // big-endian total-frame-length, then the iBFS-v1 frame itself.
        val lengthBuf = ByteArray(2)
        while (running) {
            var totalRead = 0
            while (totalRead < 2) {
                val n = input.read(lengthBuf, totalRead, 2 - totalRead)
                if (n < 0) return
                totalRead += n
            }
            val frameLen = ((lengthBuf[0].toInt() and 0xFF) shl 8) or (lengthBuf[1].toInt() and 0xFF)
            if (frameLen <= 0) continue

            val frameBuf = ByteArray(frameLen)
            var offset = 0
            while (offset < frameLen) {
                val n = input.read(frameBuf, offset, frameLen - offset)
                if (n < 0) return
                offset += n
            }
            onFrameReceived(frameBuf)
        }
    }

    /** Sends an already-encoded iBFS-v1 frame (see ProtocolCodec.encode). */
    @Synchronized
    fun send(frame: ByteArray): Boolean {
        val out = outputStream ?: return false
        return try {
            val lengthPrefix = byteArrayOf(
                ((frame.size shr 8) and 0xFF).toByte(),
                (frame.size and 0xFF).toByte(),
            )
            out.write(lengthPrefix)
            out.write(frame)
            out.flush()
            true
        } catch (e: IOException) {
            detachSocket()
            false
        }
    }

    fun stop() {
        running = false
        transportJob?.cancel()
        transportJob = null
        detachSocket()
        try {
            serverSocket?.close()
        } catch (_: IOException) {
            // best-effort cleanup
        }
    }
}
