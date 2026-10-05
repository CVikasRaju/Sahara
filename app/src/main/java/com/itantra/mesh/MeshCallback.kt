package com.itantra.mesh

/**
 * Typed error categories for mesh transport failures.
 *
 * Each subclass carries context about the failure — the integration layer
 * can pattern-match on these to decide how to surface errors to the user
 * (e.g., show a permission dialog vs. a retry toast).
 */
sealed class MeshError(val message: String) {
    /** Bluetooth or Wi-Fi Direct discovery could not start or failed mid-scan. */
    class DiscoveryFailed(message: String) : MeshError(message)

    /** A connection attempt to a specific peer failed. */
    class ConnectionFailed(val peerId: String, message: String) : MeshError(message)

    /** Sending a frame to a specific peer failed (socket write error). */
    class SendFailed(val peerId: String, message: String) : MeshError(message)

    /** A required runtime permission (e.g., BLUETOOTH_CONNECT) was not granted. */
    class PermissionDenied(message: String) : MeshError(message)
}

/**
 * Callback interface for mesh transport events.
 *
 * Decouples the mesh layer from any specific consumer (TransceiverService,
 * ViewModel, etc.). All callbacks are invoked on background coroutine
 * dispatchers — callers must switch to Main if updating UI.
 */
interface MeshCallback {

    /**
     * A complete iBFS-v1 frame was received from a connected peer.
     *
     * The frame is the raw byte array *without* the 2-byte length prefix
     * (the length prefix is a transport-layer concern stripped by [PeerConnection]).
     *
     * @param frame The raw iBFS-v1 frame bytes (header + payload + CRC).
     * @param fromPeerId The ID of the peer that sent the frame.
     */
    fun onFrameReceived(frame: ByteArray, fromPeerId: String)

    /**
     * A new peer connection was successfully established (inbound or outbound).
     *
     * @param peer The peer with [ConnectionState.CONNECTED].
     */
    fun onPeerConnected(peer: MeshPeer)

    /**
     * A previously connected peer has disconnected (socket closed or I/O error).
     *
     * @param peerId The ID of the disconnected peer.
     */
    fun onPeerDisconnected(peerId: String)

    /**
     * A new peer was discovered during scanning (not yet connected).
     *
     * The integration layer may choose to auto-connect or present the peer
     * in a UI list for manual connection.
     *
     * @param peer The discovered peer with [ConnectionState.DISCOVERED].
     */
    fun onPeerDiscovered(peer: MeshPeer)

    /**
     * A mesh-layer error occurred.
     *
     * @param error Typed error with context about the failure.
     */
    fun onMeshError(error: MeshError)
}
