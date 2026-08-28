package com.itantra.mesh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe registry of all discovered and connected peers in the mesh.
 *
 * Provides:
 * - Reactive observation via [peers] (a [StateFlow]) for UI binding.
 * - Direct lookup and mutation methods for the transport layer.
 * - Max-peer enforcement via [hasCapacity].
 *
 * Uses [ConcurrentHashMap] internally for lock-free concurrent access from
 * multiple discovery and connection coroutines.
 */
class PeerRegistry {

    /** Peer metadata indexed by peer ID. */
    private val peerMap = ConcurrentHashMap<String, MeshPeer>()

    /** Active socket connections indexed by peer ID. */
    private val connectionMap = ConcurrentHashMap<String, PeerConnection>()

    /** Reactive snapshot of all known peers — emitted on every mutation. */
    private val _peers = MutableStateFlow<List<MeshPeer>>(emptyList())

    /** Observable list of all known peers (discovered + connected + disconnected). */
    val peers: StateFlow<List<MeshPeer>> = _peers.asStateFlow()

    // ── Registration ───────────────────────────────────────────────────

    /**
     * Registers a newly discovered or reconnected peer.
     * If a peer with the same ID already exists, it is replaced.
     */
    fun register(peer: MeshPeer) {
        peerMap[peer.id] = peer
        emitSnapshot()
    }

    /**
     * Removes a peer entirely from the registry and closes its connection.
     */
    fun unregister(peerId: String) {
        peerMap.remove(peerId)
        connectionMap.remove(peerId)?.close()
        emitSnapshot()
    }

    // ── State Updates ──────────────────────────────────────────────────

    /**
     * Updates the connection state of an existing peer.
     * No-op if the peer is not registered.
     */
    fun updateState(peerId: String, state: ConnectionState) {
        peerMap.computeIfPresent(peerId) { _, existing ->
            existing.copy(connectionState = state)
        }
        emitSnapshot()
    }

    // ── Connection Management ──────────────────────────────────────────

    /**
     * Attaches a live [PeerConnection] to a registered peer and
     * transitions the peer to [ConnectionState.CONNECTED].
     *
     * If the peer is not yet registered, it is silently ignored — callers
     * should [register] the peer first.
     */
    fun attachConnection(peerId: String, connection: PeerConnection) {
        // Close any existing connection to this peer before attaching the new one
        connectionMap.put(peerId, connection)?.close()
        updateState(peerId, ConnectionState.CONNECTED)
    }

    /**
     * Retrieves the live [PeerConnection] for a peer, or null if not connected.
     */
    fun getConnection(peerId: String): PeerConnection? = connectionMap[peerId]

    // ── Queries ────────────────────────────────────────────────────────

    /** Returns all peers currently in [ConnectionState.CONNECTED]. */
    fun getConnected(): List<MeshPeer> =
        peerMap.values.filter { it.connectionState == ConnectionState.CONNECTED }

    /** Returns all known peers regardless of connection state. */
    fun getAll(): List<MeshPeer> = peerMap.values.toList()

    /** Returns the number of currently connected peers. */
    fun connectedCount(): Int =
        peerMap.values.count { it.connectionState == ConnectionState.CONNECTED }

    /** Checks whether the registry has room for another connection. */
    fun hasCapacity(): Boolean = connectedCount() < MeshConfig.MAX_PEERS

    /** Returns whether a peer with the given [peerId] is already registered. */
    fun contains(peerId: String): Boolean = peerMap.containsKey(peerId)

    // ── Lifecycle ──────────────────────────────────────────────────────

    /**
     * Disconnects and removes all peers, closing every active connection.
     */
    fun clear() {
        connectionMap.values.forEach { it.close() }
        connectionMap.clear()
        peerMap.clear()
        emitSnapshot()
    }

    // ── Internal ───────────────────────────────────────────────────────

    /** Publishes a new snapshot to the [peers] StateFlow. */
    private fun emitSnapshot() {
        _peers.value = peerMap.values.toList()
    }
}
