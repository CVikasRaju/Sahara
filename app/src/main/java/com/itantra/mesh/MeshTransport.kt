package com.itantra.mesh

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Main mesh transport orchestrator — the single entry point for the integration layer.
 *
 * Manages multi-peer discovery (Bluetooth RFCOMM + Wi-Fi Direct), connection
 * lifecycle, frame broadcasting to all connected peers, and sequence-ID-based
 * mesh relay deduplication.
 *
 * Designed as a drop-in replacement for the existing point-to-point
 * [com.itantra.network.BluetoothTransport], exposing the same callback pattern
 * (`onFrameReceived`, `onPeerConnected/Disconnected`) for straightforward
 * swap-in during the final integration merge.
 *
 * ## Usage
 * ```kotlin
 * val mesh = MeshTransport(applicationContext, object : MeshCallback {
 *     override fun onFrameReceived(frame: ByteArray, fromPeerId: String) { /* decode & process */ }
 *     override fun onPeerConnected(peer: MeshPeer) { /* drain queued messages */ }
 *     override fun onPeerDisconnected(peerId: String) { /* update UI */ }
 *     override fun onPeerDiscovered(peer: MeshPeer) { /* show in peer list */ }
 *     override fun onMeshError(error: MeshError) { /* log or toast */ }
 * })
 *
 * mesh.start(coroutineScope)
 * // ... later ...
 * mesh.broadcast(encodedFrame)
 * mesh.stop()
 * ```
 *
 * @param context Application or service [Context] for Bluetooth and Wi-Fi P2P access.
 * @param callback [MeshCallback] listener for mesh events.
 */
class MeshTransport(
    private val context: Context,
    private val callback: MeshCallback,
) {
    companion object {
        private const val TAG = "MeshTransport"
    }

    private val registry = PeerRegistry()
    private val seenSequenceIds = ConcurrentHashMap.newKeySet<Long>()
    private var scope: CoroutineScope? = null

    private var bluetoothDiscovery: BluetoothMeshDiscovery? = null
    private var wifiDirectDiscovery: WifiDirectMeshDiscovery? = null

    /** Observable list of all known peers (discovered + connected + disconnected). */
    val connectedPeers: StateFlow<List<MeshPeer>> = registry.peers

    /** Whether at least one peer is currently connected. */
    val isAnyPeerConnected: Boolean get() = registry.connectedCount() > 0

    /** Number of currently connected peers. */
    val connectedPeerCount: Int get() = registry.connectedCount()

    // ── Lifecycle ──────────────────────────────────────────────────────

    /**
     * Starts both Bluetooth RFCOMM and Wi-Fi Direct discovery and begins
     * accepting incoming connections on both transports.
     *
     * @param scope [CoroutineScope] for all mesh coroutines (I/O, read loops, discovery).
     */
    fun start(scope: CoroutineScope) {
        this.scope = scope
        startBluetoothDiscovery(scope)
        startWifiDirectDiscovery(scope)
        Log.d(TAG, "Mesh transport started")
    }

    /**
     * Stops all discovery, closes all peer connections, and clears the registry.
     * Safe to call multiple times.
     */
    fun stop() {
        bluetoothDiscovery?.stop()
        bluetoothDiscovery = null
        wifiDirectDiscovery?.stop()
        wifiDirectDiscovery = null
        registry.clear()
        seenSequenceIds.clear()
        scope = null
        Log.d(TAG, "Mesh transport stopped")
    }

    // ── Sending ────────────────────────────────────────────────────────

    /**
     * Broadcasts an already-encoded iBFS-v1 frame to **all** connected peers.
     *
     * @param frame The raw iBFS-v1 frame bytes (header + payload + CRC).
     * @return The number of peers the frame was successfully sent to.
     */
    fun broadcast(frame: ByteArray): Int {
        var successCount = 0
        for (peer in registry.getConnected()) {
            val conn = registry.getConnection(peer.id)
            if (conn != null && conn.send(frame)) {
                successCount++
            } else {
                Log.d(TAG, "Broadcast send failed to peer ${peer.id}")
                callback.onMeshError(MeshError.SendFailed(peer.id, "Frame delivery failed"))
            }
        }
        return successCount
    }

    /**
     * Sends an already-encoded iBFS-v1 frame to a **specific** peer.
     *
     * @param peerId The target peer's unique ID.
     * @param frame The raw iBFS-v1 frame bytes.
     * @return true if the frame was successfully sent, false otherwise.
     */
    fun send(peerId: String, frame: ByteArray): Boolean {
        val conn = registry.getConnection(peerId) ?: return false
        return conn.send(frame)
    }

    /**
     * Mesh relay broadcast: forwards a received frame to all connected peers
     * **except** the original sender, with sequence-ID-based deduplication
     * to prevent infinite relay loops.
     *
     * The sequence ID is extracted directly from the raw frame bytes (bytes 4–7,
     * uint32 big-endian per the iBFS-v1 spec) without full packet decode for efficiency.
     *
     * @param frame The raw iBFS-v1 frame bytes to relay.
     * @param excludePeerId The peer ID to exclude (the peer we received it from).
     * @return The number of peers the frame was successfully relayed to,
     *         or **-1** if the frame was a duplicate (already forwarded by this node).
     */
    fun relayBroadcast(frame: ByteArray, excludePeerId: String): Int {
        val sequenceId = extractSequenceId(frame)

        // Dedup: if we've already relayed this sequence ID, drop it
        if (sequenceId != null && !seenSequenceIds.add(sequenceId)) {
            Log.d(TAG, "Duplicate relay dropped: seq=$sequenceId")
            return -1
        }

        var successCount = 0
        for (peer in registry.getConnected()) {
            if (peer.id == excludePeerId) continue
            val conn = registry.getConnection(peer.id)
            if (conn != null && conn.send(frame)) {
                successCount++
            }
        }
        Log.d(TAG, "Relayed frame (seq=$sequenceId) to $successCount peers (excluded $excludePeerId)")
        return successCount
    }

    // ── Bluetooth Discovery Setup ──────────────────────────────────────

    private fun startBluetoothDiscovery(scope: CoroutineScope) {
        val btAdapter: BluetoothAdapter? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.getSystemService(BluetoothManager::class.java)?.adapter
        } else {
            @Suppress("DEPRECATION")
            BluetoothAdapter.getDefaultAdapter()
        }

        bluetoothDiscovery = BluetoothMeshDiscovery(
            adapter = btAdapter,
            onPeerDiscovered = { peer -> handlePeerDiscovered(peer) },
            onConnectionEstablished = { peerId, socket ->
                handleBluetoothConnection(peerId, socket, scope)
            },
            onError = { error -> callback.onMeshError(error) },
        )
        bluetoothDiscovery?.start(scope)
    }

    /**
     * Wraps a newly established Bluetooth RFCOMM socket in a [PeerConnection],
     * attaches it to the registry, and starts its read loop.
     */
    private fun handleBluetoothConnection(
        peerId: String,
        socket: BluetoothSocket,
        scope: CoroutineScope,
    ) {
        if (!registry.hasCapacity()) {
            Log.w(TAG, "Max peers (${ MeshConfig.MAX_PEERS}) reached, rejecting BT connection from $peerId")
            try { socket.close() } catch (_: Exception) {}
            return
        }

        try {
            val connection = PeerConnection(
                peerId = peerId,
                inputStream = socket.inputStream,
                outputStream = socket.outputStream,
                onFrameReceived = { frame, fromId -> handleIncomingFrame(frame, fromId) },
                onDisconnected = { id -> handlePeerDisconnected(id) },
            )

            // Ensure the peer is registered before attaching the connection
            if (!registry.contains(peerId)) {
                registry.register(
                    MeshPeer(
                        id = peerId,
                        name = peerId,
                        address = peerId,
                        transportType = TransportType.BLUETOOTH_RFCOMM,
                    )
                )
            }

            registry.attachConnection(peerId, connection)
            connection.startReadLoop(scope)

            val peer = registry.getAll().find { it.id == peerId }
            if (peer != null) {
                callback.onPeerConnected(peer.copy(connectionState = ConnectionState.CONNECTED))
            }

            Log.d(TAG, "Bluetooth peer connected: $peerId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set up BT connection to $peerId", e)
            try { socket.close() } catch (_: Exception) {}
            callback.onMeshError(
                MeshError.ConnectionFailed(peerId, "Bluetooth setup failed: ${e.message}")
            )
        }
    }

    // ── Wi-Fi Direct Discovery Setup ───────────────────────────────────

    private fun startWifiDirectDiscovery(scope: CoroutineScope) {
        wifiDirectDiscovery = WifiDirectMeshDiscovery(
            context = context,
            onPeerDiscovered = { peer -> handlePeerDiscovered(peer) },
            onConnectionEstablished = { peerId, socket ->
                handleWifiDirectConnection(peerId, socket, scope)
            },
            onError = { error -> callback.onMeshError(error) },
        )
        wifiDirectDiscovery?.start(scope)
    }

    /**
     * Wraps a newly established Wi-Fi Direct TCP socket in a [PeerConnection],
     * attaches it to the registry, and starts its read loop.
     */
    private fun handleWifiDirectConnection(
        peerId: String,
        socket: Socket,
        scope: CoroutineScope,
    ) {
        if (!registry.hasCapacity()) {
            Log.w(TAG, "Max peers (${MeshConfig.MAX_PEERS}) reached, rejecting WiFi Direct connection from $peerId")
            try { socket.close() } catch (_: Exception) {}
            return
        }

        try {
            val connection = PeerConnection(
                peerId = peerId,
                inputStream = socket.getInputStream(),
                outputStream = socket.getOutputStream(),
                onFrameReceived = { frame, fromId -> handleIncomingFrame(frame, fromId) },
                onDisconnected = { id -> handlePeerDisconnected(id) },
            )

            // Ensure the peer is registered before attaching the connection
            if (!registry.contains(peerId)) {
                registry.register(
                    MeshPeer(
                        id = peerId,
                        name = peerId,
                        address = peerId,
                        transportType = TransportType.WIFI_DIRECT,
                    )
                )
            }

            registry.attachConnection(peerId, connection)
            connection.startReadLoop(scope)

            val peer = registry.getAll().find { it.id == peerId }
            if (peer != null) {
                callback.onPeerConnected(peer.copy(connectionState = ConnectionState.CONNECTED))
            }

            Log.d(TAG, "Wi-Fi Direct peer connected: $peerId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set up Wi-Fi Direct connection to $peerId", e)
            try { socket.close() } catch (_: Exception) {}
            callback.onMeshError(
                MeshError.ConnectionFailed(peerId, "Wi-Fi Direct setup failed: ${e.message}")
            )
        }
    }

    // ── Event Handlers ─────────────────────────────────────────────────

    /**
     * Handles peer discovery events from both Bluetooth and Wi-Fi Direct scanners.
     * Only registers and notifies for newly seen peers.
     */
    private fun handlePeerDiscovered(peer: MeshPeer) {
        if (!registry.contains(peer.id)) {
            registry.register(peer)
            callback.onPeerDiscovered(peer)
            Log.d(TAG, "Peer discovered: ${peer.name} (${peer.id}) via ${peer.transportType}")
        }
    }

    /**
     * Forwards incoming frames to the [MeshCallback].
     */
    private fun handleIncomingFrame(frame: ByteArray, fromPeerId: String) {
        callback.onFrameReceived(frame, fromPeerId)
    }

    /**
     * Updates registry state and notifies the callback when a peer disconnects.
     */
    private fun handlePeerDisconnected(peerId: String) {
        registry.updateState(peerId, ConnectionState.DISCONNECTED)
        callback.onPeerDisconnected(peerId)
        Log.d(TAG, "Peer disconnected: $peerId")
    }

    // ── Utilities ──────────────────────────────────────────────────────

    /**
     * Extracts the sequence ID from a raw iBFS-v1 frame without full decode.
     *
     * Per the protocol spec in `docs/NETWORK_PROTOCOL.md`, the sequence ID
     * occupies bytes 4–7 as a uint32 big-endian value:
     *
     *     Byte 0-1: Magic (0x49 0x54)
     *     Byte 2:   [Version:4][Type:4]
     *     Byte 3:   [Priority:4][Lang:4]
     *     Byte 4-7: Sequence ID (uint32 BE)  ← extracted here
     *
     * @return The sequence ID, or null if the frame is too short.
     */
    private fun extractSequenceId(frame: ByteArray): Long? {
        if (frame.size < 8) return null
        return ((frame[4].toInt() and 0xFF).toLong() shl 24) or
            ((frame[5].toInt() and 0xFF).toLong() shl 16) or
            ((frame[6].toInt() and 0xFF).toLong() shl 8) or
            (frame[7].toInt() and 0xFF).toLong()
    }
}
