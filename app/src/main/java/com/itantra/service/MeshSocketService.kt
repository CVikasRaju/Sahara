package com.itantra.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service that manages peer-to-peer TCP mesh sockets.
 * It listens for incoming socket requests and handles outbound connections.
 * It maintains connection health and exposes status reactively.
 */
class MeshSocketService : Service() {

    companion object {
        private const val TAG = "MeshSocketService"
        private const val NOTIFICATION_CHANNEL_ID = "itantra_mesh_socket_service"
        private const val NOTIFICATION_ID = 2
        private const val DEFAULT_PORT = 8888
        private const val HEARTBEAT_INTERVAL_MS = 5000L
        private const val INITIAL_BACKOFF_MS = 1000L
        private const val MAX_BACKOFF_MS = 16000L
    }

    inner class LocalBinder : Binder() {
        fun getService(): MeshSocketService = this@MeshSocketService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Port to run the server socket on
    private var serverPort = DEFAULT_PORT

    // Keep track of active connections: IP -> SocketConnection
    private val connections = ConcurrentHashMap<String, SocketConnection>()

    // Expose active peer connections
    private val _activePeers = MutableStateFlow<Set<String>>(emptySet())
    val activePeers: StateFlow<Set<String>> = _activePeers.asStateFlow()

    // Expose received frames
    private val _receivedFrames = MutableSharedFlow<Pair<String, ByteArray>>(extraBufferCapacity = 64)
    val receivedFrames: SharedFlow<Pair<String, ByteArray>> = _receivedFrames.asSharedFlow()

    private var serverJob: Job? = null
    private var heartbeatJob: Job? = null
    private val clientJobs = ConcurrentHashMap<String, Job>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        
        startServer()
        startHeartbeats()
        
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Mesh Sockets Keep-Alive",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps peer mesh sockets alive in the background"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("iTantra Mesh Socket Service")
            .setContentText("Maintaining offline mesh connections in background")
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setOngoing(true)
            .build()
    }

    /**
     * Starts listening for incoming connections.
     */
    @Synchronized
    private fun startServer() {
        if (serverJob != null) return
        serverJob = serviceScope.launch(Dispatchers.IO) {
            var serverSocket: ServerSocket? = null
            try {
                serverSocket = ServerSocket(serverPort)
                Log.i(TAG, "TCP Server listening on port $serverPort")
                while (isActive) {
                    val socket = serverSocket.accept()
                    val remoteIp = socket.inetAddress.hostAddress ?: "unknown"
                    Log.i(TAG, "Accepted connection from peer: $remoteIp")
                    
                    // Handle connection
                    handleIncomingSocket(remoteIp, socket)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server socket error", e)
            } finally {
                try { serverSocket?.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Regularly sends ping/heartbeats to verify connection status.
     */
    private fun startHeartbeats() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                connections.forEach { (ip, connection) ->
                    if (!connection.sendPing()) {
                        Log.w(TAG, "Heartbeat failed for peer: $ip. Disconnecting.")
                        connection.close()
                        removeConnection(ip)
                    }
                }
            }
        }
    }

    /**
     * Handles a newly accepted incoming connection.
     */
    private fun handleIncomingSocket(ip: String, socket: Socket) {
        // Disconnect old connection if exists
        connections[ip]?.close()
        
        val connection = SocketConnection(ip, socket)
        connections[ip] = connection
        updateActivePeers()
        
        // Start listening to the socket in the background
        serviceScope.launch(Dispatchers.IO) {
            connection.readLoop()
        }
    }

    /**
     * Asynchronously connects to a peer IP.
     * Retries automatically if connection fails or drops.
     */
    @Synchronized
    fun connectToPeer(ip: String) {
        if (clientJobs.containsKey(ip) || connections.containsKey(ip)) {
            Log.d(TAG, "Connection/job already exists for peer $ip")
            return
        }

        val job = serviceScope.launch(Dispatchers.IO) {
            var backoff = INITIAL_BACKOFF_MS
            while (isActive) {
                try {
                    Log.d(TAG, "Attempting to connect to peer $ip:$serverPort...")
                    val socket = Socket(ip, serverPort)
                    Log.i(TAG, "Successfully connected to peer $ip")
                    
                    val connection = SocketConnection(ip, socket)
                    connections[ip] = connection
                    updateActivePeers()
                    
                    backoff = INITIAL_BACKOFF_MS // Reset backoff on success
                    
                    // Run read loop (blocks until disconnect)
                    connection.readLoop()
                } catch (e: IOException) {
                    Log.w(TAG, "Connection attempt failed to $ip. Retrying in ${backoff}ms...")
                } finally {
                    removeConnection(ip)
                }
                
                if (!isActive) break
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
        clientJobs[ip] = job
    }

    /**
     * Stop attempting connections and disconnects from peer IP.
     */
    @Synchronized
    fun disconnectFromPeer(ip: String) {
        clientJobs[ip]?.cancel()
        clientJobs.remove(ip)
        connections[ip]?.close()
        removeConnection(ip)
    }

    /**
     * Sends raw bytes to a target peer connection.
     */
    fun sendFrame(ip: String, frame: ByteArray): Boolean {
        val connection = connections[ip] ?: return false
        return connection.send(frame)
    }

    /**
     * Broadcasts raw bytes to all active peer connections.
     */
    fun broadcastFrame(frame: ByteArray) {
        connections.forEach { (_, connection) ->
            connection.send(frame)
        }
    }

    private fun removeConnection(ip: String) {
        connections.remove(ip)
        updateActivePeers()
    }

    private fun updateActivePeers() {
        _activePeers.value = connections.keys.toSet()
    }

    override fun onDestroy() {
        serverJob?.cancel()
        heartbeatJob?.cancel()
        clientJobs.forEach { (_, job) -> job.cancel() }
        clientJobs.clear()
        connections.forEach { (_, conn) -> conn.close() }
        connections.clear()
        updateActivePeers()
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Wrapper class for TCP connections over Sockets.
     */
    private inner class SocketConnection(
        val ip: String,
        val socket: Socket
    ) {
        private val dis = DataInputStream(socket.getInputStream())
        private val dos = DataOutputStream(socket.getOutputStream())
        private var closed = false

        fun readLoop() {
            try {
                while (!closed && socket.isConnected && !socket.isClosed) {
                    val length = dis.readInt()
                    if (length < 0) {
                        Log.w(TAG, "Negative frame length received from $ip")
                        break
                    }
                    if (length == 0) {
                        // Heartbeat / ping packet
                        continue
                    }
                    
                    val payload = ByteArray(length)
                    dis.readFully(payload)
                    
                    // Emit received payload
                    serviceScope.launch {
                        _receivedFrames.emit(Pair(ip, payload))
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Socket read error / connection closed for peer: $ip")
            } finally {
                close()
            }
        }

        @Synchronized
        fun send(frame: ByteArray): Boolean {
            if (closed || !socket.isConnected || socket.isClosed) return false
            return try {
                dos.writeInt(frame.size)
                dos.write(frame)
                dos.flush()
                true
            } catch (e: IOException) {
                Log.w(TAG, "Socket write error to peer: $ip", e)
                close()
                false
            }
        }

        @Synchronized
        fun sendPing(): Boolean {
            if (closed || !socket.isConnected || socket.isClosed) return false
            return try {
                dos.writeInt(0) // 0-length signifies ping
                dos.flush()
                true
            } catch (e: IOException) {
                close()
                false
            }
        }

        @Synchronized
        fun close() {
            if (closed) return
            closed = true
            try { dis.close() } catch (_: Exception) {}
            try { dos.close() } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
            Log.d(TAG, "Socket connection to $ip closed.")
        }
    }
}
