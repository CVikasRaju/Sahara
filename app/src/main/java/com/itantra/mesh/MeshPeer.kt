package com.itantra.mesh

/**
 * Transport type used to communicate with a peer in the mesh.
 */
enum class TransportType {
    /** Bluetooth Classic RFCOMM socket. */
    BLUETOOTH_RFCOMM,

    /** Wi-Fi Direct (Wi-Fi P2P) TCP socket. */
    WIFI_DIRECT
}

/**
 * Connection lifecycle state of a mesh peer.
 */
enum class ConnectionState {
    /** Peer has been discovered via scanning but no connection attempt made. */
    DISCOVERED,

    /** An outgoing or incoming connection is being established. */
    CONNECTING,

    /** The peer has an active, bidirectional socket connection. */
    CONNECTED,

    /** A previously connected peer has lost its connection. */
    DISCONNECTED
}

/**
 * Represents a single peer node in the iTantra mesh network.
 *
 * Instances are immutable snapshots — state changes produce new copies
 * via [copy] and are tracked in [PeerRegistry].
 *
 * @param id Unique identifier (MAC address for Bluetooth, device address for Wi-Fi Direct).
 * @param name Human-readable device name (may fall back to [id] if unavailable).
 * @param address Hardware or network address used for connection establishment.
 * @param transportType The transport layer this peer was discovered on.
 * @param connectionState Current connection lifecycle state.
 */
data class MeshPeer(
    val id: String,
    val name: String,
    val address: String,
    val transportType: TransportType,
    val connectionState: ConnectionState = ConnectionState.DISCOVERED,
)
