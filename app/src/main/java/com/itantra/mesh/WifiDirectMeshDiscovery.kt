package com.itantra.mesh

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Wi-Fi Direct peer discovery, group formation, and TCP socket management.
 *
 * Discovery and connection flow:
 * 1. Registers a DNS-SD local service (`_itantra._tcp`) so other iTantra
 *    devices can discover this node via service browsing.
 * 2. Periodically discovers nearby iTantra services via DNS-SD queries.
 * 3. On explicit [connectToPeer] call, initiates Wi-Fi Direct group formation.
 * 4. Once a Wi-Fi Direct group is formed (notified via BroadcastReceiver):
 *    - **Group owner** starts a [ServerSocket] on [MeshConfig.WIFI_DIRECT_PORT].
 *    - **Clients** connect via TCP to the group owner's IP address.
 * 5. Each accepted/connected socket's I/O streams are handed to the parent
 *    via [onConnectionEstablished] for wrapping in a [PeerConnection].
 *
 * @param context Application or service [Context] for system service access.
 * @param onPeerDiscovered Invoked when a Wi-Fi Direct peer is found.
 * @param onConnectionEstablished Invoked with the peer ID and connected TCP [Socket].
 * @param onError Invoked on discovery or connection failures.
 */
class WifiDirectMeshDiscovery(
    private val context: Context,
    private val onPeerDiscovered: (MeshPeer) -> Unit,
    private val onConnectionEstablished: (peerId: String, socket: Socket) -> Unit,
    private val onError: (MeshError) -> Unit,
) {
    companion object {
        private const val TAG = "WifiDirectDiscovery"
    }

    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var serverJob: Job? = null
    private var discoveryJob: Job? = null
    private var meshScope: CoroutineScope? = null

    @Volatile
    private var running = false

    @Volatile
    private var isGroupOwner = false

    @Volatile
    private var groupOwnerAddress: String? = null

    // ── Lifecycle ──────────────────────────────────────────────────────

    /**
     * Starts Wi-Fi Direct service registration, discovery, and
     * prepares for group formation / socket management.
     */
    fun start(scope: CoroutineScope) {
        running = true
        meshScope = scope

        wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        channel = wifiP2pManager?.initialize(context, Looper.getMainLooper(), null)

        if (wifiP2pManager == null || channel == null) {
            onError(MeshError.DiscoveryFailed("Wi-Fi Direct is not available on this device"))
            return
        }

        registerBroadcastReceiver()
        registerLocalService()
        startServiceDiscovery(scope)
    }

    /** Stops all discovery, closes server sockets, and unregisters services. */
    fun stop() {
        running = false
        serverJob?.cancel()
        serverJob = null
        discoveryJob?.cancel()
        discoveryJob = null
        unregisterLocalService()
        unregisterBroadcastReceiver()
        channel?.close()
        channel = null
        meshScope = null
    }

    // ── DNS-SD Local Service Registration ──────────────────────────────

    @SuppressLint("MissingPermission")
    private fun registerLocalService() {
        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
            MeshConfig.SERVICE_NAME,
            MeshConfig.WIFI_DIRECT_SERVICE_TYPE,
            mapOf(
                "port" to MeshConfig.WIFI_DIRECT_PORT.toString(),
                "app" to "iTantra",
            )
        )

        wifiP2pManager?.addLocalService(channel, serviceInfo, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Local DNS-SD service registered")
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Failed to register local service, reason=$reason")
                onError(MeshError.DiscoveryFailed(
                    "Wi-Fi Direct service registration failed (reason=$reason)"
                ))
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun unregisterLocalService() {
        try {
            wifiP2pManager?.clearLocalServices(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Local services cleared")
                }

                override fun onFailure(reason: Int) {
                    Log.d(TAG, "Failed to clear local services, reason=$reason")
                }
            })
        } catch (_: Exception) {
            // best-effort cleanup
        }
    }

    // ── DNS-SD Service Discovery ───────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startServiceDiscovery(scope: CoroutineScope) {
        val manager = wifiP2pManager ?: return
        val ch = channel ?: return

        // DNS-SD TXT record listener — filters for iTantra services
        val txtListener = WifiP2pManager.DnsSdTxtRecordListener { _, record, device ->
            if (record["app"] == "iTantra") {
                Log.d(TAG, "Discovered iTantra service on ${device.deviceName} (${device.deviceAddress})")
                onPeerDiscovered(
                    MeshPeer(
                        id = device.deviceAddress,
                        name = device.deviceName ?: device.deviceAddress,
                        address = device.deviceAddress,
                        transportType = TransportType.WIFI_DIRECT,
                        connectionState = ConnectionState.DISCOVERED,
                    )
                )
            }
        }

        // Service response listener (supplementary — main filtering is via TXT records)
        val serviceListener = WifiP2pManager.DnsSdServiceResponseListener { instanceName, registrationType, device ->
            Log.d(TAG, "Service response: $instanceName / $registrationType from ${device.deviceAddress}")
        }

        manager.setDnsSdResponseListeners(ch, serviceListener, txtListener)

        // Add a Bonjour service discovery request
        val serviceRequest = WifiP2pDnsSdServiceRequest.newInstance()
        manager.addServiceRequest(ch, serviceRequest, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Service discovery request added")
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Failed to add service request, reason=$reason")
            }
        })

        // Periodically trigger service discovery
        discoveryJob = scope.launch(Dispatchers.IO) {
            while (running && isActive) {
                manager.discoverServices(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d(TAG, "Service discovery initiated")
                    }

                    override fun onFailure(reason: Int) {
                        Log.d(TAG, "Service discovery trigger failed, reason=$reason")
                    }
                })
                delay(MeshConfig.DISCOVERY_INTERVAL_MS)
            }
        }
    }

    // ── Wi-Fi Direct Group Connection ──────────────────────────────────

    /**
     * Initiates a Wi-Fi Direct group connection to a discovered peer.
     *
     * Called by [MeshTransport] when it decides to connect. Once the group
     * is formed, the [BroadcastReceiver] handles TCP socket setup automatically.
     *
     * @param deviceAddress The Wi-Fi Direct device address to connect to.
     */
    @SuppressLint("MissingPermission")
    fun connectToPeer(deviceAddress: String) {
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
        }

        wifiP2pManager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Wi-Fi Direct connection initiated to $deviceAddress")
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Wi-Fi Direct connect failed to $deviceAddress, reason=$reason")
                onError(MeshError.ConnectionFailed(
                    deviceAddress,
                    "Wi-Fi Direct connect failed (reason=$reason)"
                ))
            }
        })
    }

    // ── TCP Socket Layer (post group formation) ────────────────────────

    /**
     * Called after a Wi-Fi Direct group is successfully formed.
     * The group owner starts a TCP server; clients connect to the owner's IP.
     */
    private fun handleGroupFormed(info: WifiP2pInfo, scope: CoroutineScope) {
        isGroupOwner = info.isGroupOwner
        groupOwnerAddress = info.groupOwnerAddress?.hostAddress

        if (isGroupOwner) {
            startTcpServer(scope)
        } else {
            val ownerAddr = groupOwnerAddress
            if (ownerAddr != null) {
                connectToGroupOwner(ownerAddr, scope)
            } else {
                Log.e(TAG, "Group formed but owner address is null")
                onError(MeshError.ConnectionFailed("unknown", "Group owner address unavailable"))
            }
        }
    }

    /**
     * Group owner: starts a TCP [ServerSocket] to accept client connections.
     */
    private fun startTcpServer(scope: CoroutineScope) {
        serverJob?.cancel()
        serverJob = scope.launch(Dispatchers.IO) {
            var serverSocket: ServerSocket? = null
            try {
                serverSocket = ServerSocket(MeshConfig.WIFI_DIRECT_PORT)
                serverSocket.reuseAddress = true
                Log.d(TAG, "TCP server listening on port ${MeshConfig.WIFI_DIRECT_PORT}")

                while (running && isActive) {
                    val clientSocket = serverSocket.accept()
                    val peerId = clientSocket.inetAddress?.hostAddress ?: continue
                    Log.d(TAG, "Accepted TCP connection from $peerId")
                    onConnectionEstablished(peerId, clientSocket)
                }
            } catch (e: IOException) {
                if (running) {
                    Log.e(TAG, "TCP server error", e)
                    onError(MeshError.DiscoveryFailed("TCP server failed: ${e.message}"))
                }
            } finally {
                try { serverSocket?.close() } catch (_: IOException) {}
            }
        }
    }

    /**
     * Client: connects to the group owner's TCP server with retry logic.
     */
    private fun connectToGroupOwner(ownerAddress: String, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            var backoff = MeshConfig.INITIAL_BACKOFF_MS
            var attempts = 0

            while (running && isActive && attempts < MeshConfig.MAX_CONNECT_ATTEMPTS) {
                try {
                    val socket = Socket()
                    socket.connect(
                        InetSocketAddress(ownerAddress, MeshConfig.WIFI_DIRECT_PORT),
                        MeshConfig.CONNECT_TIMEOUT_MS
                    )
                    Log.d(TAG, "Connected to group owner at $ownerAddress")
                    onConnectionEstablished(ownerAddress, socket)
                    return@launch
                } catch (e: IOException) {
                    attempts++
                    if (attempts >= MeshConfig.MAX_CONNECT_ATTEMPTS) {
                        onError(MeshError.ConnectionFailed(
                            ownerAddress,
                            "TCP connect to group owner failed after $attempts attempts: ${e.message}"
                        ))
                        break
                    }
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(MeshConfig.MAX_BACKOFF_MS)
                }
            }
        }
    }

    // ── Broadcast Receiver ─────────────────────────────────────────────

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerBroadcastReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        receiver = WifiDirectBroadcastReceiver()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, intentFilter)
        }
    }

    private fun unregisterBroadcastReceiver() {
        receiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
        }
        receiver = null
    }

    // ── Internal BroadcastReceiver ─────────────────────────────────────

    /**
     * Handles Wi-Fi P2P system events: state changes, peer list updates,
     * group formation notifications, and device info changes.
     */
    private inner class WifiDirectBroadcastReceiver : BroadcastReceiver() {

        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {

                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        Log.w(TAG, "Wi-Fi Direct is disabled")
                        onError(MeshError.DiscoveryFailed("Wi-Fi Direct is disabled on this device"))
                    }
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    wifiP2pManager?.requestPeers(channel) { peers: WifiP2pDeviceList? ->
                        peers?.deviceList?.forEach { device ->
                            onPeerDiscovered(
                                MeshPeer(
                                    id = device.deviceAddress,
                                    name = device.deviceName ?: device.deviceAddress,
                                    address = device.deviceAddress,
                                    transportType = TransportType.WIFI_DIRECT,
                                    connectionState = ConnectionState.DISCOVERED,
                                )
                            )
                        }
                    }
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    wifiP2pManager?.requestConnectionInfo(channel) { info: WifiP2pInfo? ->
                        if (info != null && info.groupFormed) {
                            val scope = meshScope ?: return@requestConnectionInfo
                            handleGroupFormed(info, scope)
                        }
                    }
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    // Available for future use (e.g., updating our own device info in UI)
                }
            }
        }
    }
}
