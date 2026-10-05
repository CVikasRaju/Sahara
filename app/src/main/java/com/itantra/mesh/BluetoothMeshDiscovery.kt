package com.itantra.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Bluetooth RFCOMM discovery and connection manager for the mesh layer.
 *
 * Runs two concurrent coroutines:
 * 1. **Server listener:** Accepts incoming RFCOMM connections from peers
 *    that connect to our advertised service UUID.
 * 2. **Client scanner:** Periodically enumerates bonded (paired) devices
 *    and reports them as discovered peers. Outgoing connections to specific
 *    devices are initiated on demand via [connectToPeer].
 *
 * Uses the same [MeshConfig.MESH_SERVICE_UUID] as the existing
 * [com.itantra.network.BluetoothTransport] so that legacy point-to-point
 * peers can also connect to the mesh.
 *
 * @param adapter The system [BluetoothAdapter], or null if Bluetooth is unavailable.
 * @param onPeerDiscovered Invoked when a bonded device or incoming peer is found.
 * @param onConnectionEstablished Invoked when an RFCOMM socket is successfully connected.
 * @param onError Invoked on discovery or connection failures.
 */
class BluetoothMeshDiscovery(
    private val adapter: BluetoothAdapter?,
    private val onPeerDiscovered: (MeshPeer) -> Unit,
    private val onConnectionEstablished: (peerId: String, socket: BluetoothSocket) -> Unit,
    private val onError: (MeshError) -> Unit,
) {
    companion object {
        private const val TAG = "BtMeshDiscovery"
    }

    private var serverJob: Job? = null
    private var scanJob: Job? = null

    @Volatile
    private var running = false

    /**
     * Starts both the RFCOMM server listener and the periodic bonded-device scanner.
     * No-op if the [BluetoothAdapter] is null (Bluetooth unavailable).
     */
    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        val bt = adapter ?: run {
            onError(MeshError.DiscoveryFailed("Bluetooth adapter not available"))
            return
        }
        running = true
        startServerListener(bt, scope)
        startBondedDeviceScanner(bt, scope)
    }

    /** Stops the server listener, scanner, and all pending connection attempts. */
    fun stop() {
        running = false
        serverJob?.cancel()
        serverJob = null
        scanJob?.cancel()
        scanJob = null
    }

    // ── Server: accept incoming RFCOMM connections ─────────────────────

    @SuppressLint("MissingPermission")
    private fun startServerListener(bt: BluetoothAdapter, scope: CoroutineScope) {
        serverJob = scope.launch(Dispatchers.IO) {
            var backoff = MeshConfig.INITIAL_BACKOFF_MS
            while (running && isActive) {
                var serverSocket: BluetoothServerSocket? = null
                try {
                    serverSocket = bt.listenUsingRfcommWithServiceRecord(
                        MeshConfig.SERVICE_NAME,
                        MeshConfig.MESH_SERVICE_UUID
                    )

                    // accept() blocks until a remote device connects
                    val socket = serverSocket.accept()
                    if (socket != null) {
                        val device = socket.remoteDevice
                        val peerId = device.address
                        val peerName = safeDeviceName(device, peerId)

                        Log.d(TAG, "Accepted incoming connection from $peerName ($peerId)")

                        onPeerDiscovered(
                            MeshPeer(
                                id = peerId,
                                name = peerName,
                                address = peerId,
                                transportType = TransportType.BLUETOOTH_RFCOMM,
                                connectionState = ConnectionState.CONNECTING,
                            )
                        )
                        onConnectionEstablished(peerId, socket)
                        backoff = MeshConfig.INITIAL_BACKOFF_MS // reset on success
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "Missing BLUETOOTH_CONNECT permission", e)
                    onError(MeshError.PermissionDenied("BLUETOOTH_CONNECT permission required"))
                    break // unrecoverable without user granting the permission
                } catch (e: IOException) {
                    if (!running || !isActive) break
                    Log.d(TAG, "Server accept failed, backing off ${backoff}ms: ${e.message}")
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(MeshConfig.MAX_BACKOFF_MS)
                } finally {
                    // Close the server socket after each accept cycle so we can
                    // re-create it — this is standard RFCOMM server pattern.
                    try { serverSocket?.close() } catch (_: IOException) {}
                }
            }
        }
    }

    // ── Client: periodic bonded-device enumeration ─────────────────────

    @SuppressLint("MissingPermission")
    private fun startBondedDeviceScanner(bt: BluetoothAdapter, scope: CoroutineScope) {
        scanJob = scope.launch(Dispatchers.IO) {
            while (running && isActive) {
                try {
                    val bondedDevices: Set<BluetoothDevice> = bt.bondedDevices ?: emptySet()
                    for (device in bondedDevices) {
                        if (!running || !isActive) break

                        val peerId = device.address
                        val peerName = safeDeviceName(device, peerId)

                        onPeerDiscovered(
                            MeshPeer(
                                id = peerId,
                                name = peerName,
                                address = peerId,
                                transportType = TransportType.BLUETOOTH_RFCOMM,
                                connectionState = ConnectionState.DISCOVERED,
                            )
                        )
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "Missing BLUETOOTH_CONNECT permission for bonded scan", e)
                    onError(MeshError.PermissionDenied(
                        "BLUETOOTH_CONNECT permission required for bonded device scan"
                    ))
                    break
                }

                delay(MeshConfig.DISCOVERY_INTERVAL_MS)
            }
        }
    }

    // ── On-demand outgoing connection ──────────────────────────────────

    /**
     * Attempts an outgoing RFCOMM connection to a specific bonded device.
     *
     * Called by [MeshTransport] when it decides to connect to a discovered peer.
     * Retries up to [MeshConfig.MAX_CONNECT_ATTEMPTS] times with exponential backoff.
     *
     * @param device The [BluetoothDevice] to connect to.
     * @param scope The [CoroutineScope] for the connection coroutine.
     */
    @SuppressLint("MissingPermission")
    fun connectToPeer(device: BluetoothDevice, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            var backoff = MeshConfig.INITIAL_BACKOFF_MS
            var attempts = 0

            while (running && isActive && attempts < MeshConfig.MAX_CONNECT_ATTEMPTS) {
                try {
                    // Cancel any ongoing system discovery to speed up RFCOMM connect
                    try { adapter?.cancelDiscovery() } catch (_: SecurityException) {}

                    val socket = device.createRfcommSocketToServiceRecord(MeshConfig.MESH_SERVICE_UUID)
                    socket.connect()

                    val peerId = device.address
                    val peerName = safeDeviceName(device, peerId)

                    Log.d(TAG, "Outgoing connection established to $peerName ($peerId)")
                    onConnectionEstablished(peerId, socket)
                    return@launch // success — exit retry loop
                } catch (e: SecurityException) {
                    Log.e(TAG, "Missing BLUETOOTH_CONNECT permission", e)
                    onError(MeshError.PermissionDenied("BLUETOOTH_CONNECT permission required"))
                    break
                } catch (e: IOException) {
                    attempts++
                    if (!running || !isActive || attempts >= MeshConfig.MAX_CONNECT_ATTEMPTS) {
                        val peerId = device.address
                        Log.d(TAG, "Connection to $peerId failed after $attempts attempts: ${e.message}")
                        onError(MeshError.ConnectionFailed(
                            peerId, "RFCOMM connect failed after $attempts attempts: ${e.message}"
                        ))
                        break
                    }
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(MeshConfig.MAX_BACKOFF_MS)
                }
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Safely gets a device's friendly name, falling back to its MAC address. */
    @SuppressLint("MissingPermission")
    private fun safeDeviceName(device: BluetoothDevice, fallback: String): String {
        return try {
            device.name ?: fallback
        } catch (_: SecurityException) {
            fallback
        }
    }
}
