package com.itantra.mesh

import java.util.UUID

/**
 * Centralized configuration constants for the iTantra mesh transport layer.
 *
 * All timeouts, limits, and identifiers used across mesh discovery,
 * connection management, and relay logic are defined here.
 */
object MeshConfig {

    /** RFCOMM service UUID — matches existing BluetoothTransport for compatibility. */
    val MESH_SERVICE_UUID: UUID = UUID.fromString("7f7c1a3e-9c4b-4a1d-8e2f-3b6a1c9d5e01")

    /** Human-readable service name for Bluetooth SDP and Wi-Fi Direct DNS-SD registration. */
    const val SERVICE_NAME = "iTantraMesh"

    /** TCP port for Wi-Fi Direct data channel. */
    const val WIFI_DIRECT_PORT = 9173

    /** DNS-SD service type for Wi-Fi Direct peer discovery. */
    const val WIFI_DIRECT_SERVICE_TYPE = "_itantra._tcp"

    /** Initial reconnect backoff delay in milliseconds. */
    const val INITIAL_BACKOFF_MS = 1_000L

    /** Maximum reconnect backoff delay in milliseconds. */
    const val MAX_BACKOFF_MS = 16_000L

    /** Interval between periodic re-discovery scans in milliseconds. */
    const val DISCOVERY_INTERVAL_MS = 30_000L

    /** Maximum number of simultaneous mesh peer connections. */
    const val MAX_PEERS = 8

    /** Maximum relay hops (TTL) to prevent infinite relay loops in the mesh. */
    const val MAX_RELAY_HOPS = 3

    /** Read buffer size for socket I/O (bytes). */
    const val SOCKET_BUFFER_SIZE = 2048

    /** TCP connect timeout for Wi-Fi Direct sockets (milliseconds). */
    const val CONNECT_TIMEOUT_MS = 10_000

    /** Maximum number of outgoing connection retry attempts per peer. */
    const val MAX_CONNECT_ATTEMPTS = 3
}
