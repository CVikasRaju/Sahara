package com.itantra.mesh

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * Tests for [PeerRegistry] — verifies thread-safe registration, state transitions,
 * connection attachment, capacity enforcement, and reactive StateFlow emissions.
 */
class PeerRegistryTest {

    private lateinit var registry: PeerRegistry

    @Before
    fun setUp() {
        registry = PeerRegistry()
    }

    @After
    fun tearDown() {
        registry.clear()
    }

    private fun makePeer(
        id: String,
        transport: TransportType = TransportType.BLUETOOTH_RFCOMM,
        state: ConnectionState = ConnectionState.DISCOVERED,
    ) = MeshPeer(
        id = id,
        name = "Device-$id",
        address = id,
        transportType = transport,
        connectionState = state,
    )

    private fun makeDummyConnection(peerId: String): PeerConnection {
        val pOut = PipedOutputStream()
        val pIn = PipedInputStream(pOut)
        return PeerConnection(
            peerId = peerId,
            inputStream = pIn,
            outputStream = pOut,
            onFrameReceived = { _, _ -> },
            onDisconnected = { _ -> },
        )
    }

    @Test
    fun `register adds peer and emits snapshot`() {
        val peer = makePeer("AA:BB:CC:DD:EE:01")
        registry.register(peer)

        assertTrue(registry.contains(peer.id))
        assertEquals(1, registry.getAll().size)
        assertEquals(1, registry.peers.value.size)
        assertEquals(peer, registry.peers.value[0])
    }

    @Test
    fun `unregister removes peer and emits snapshot`() {
        val peer = makePeer("AA:BB:CC:DD:EE:02")
        registry.register(peer)
        registry.unregister(peer.id)

        assertFalse(registry.contains(peer.id))
        assertEquals(0, registry.getAll().size)
        assertEquals(0, registry.peers.value.size)
    }

    @Test
    fun `updateState changes connection state`() {
        val peer = makePeer("AA:BB:CC:DD:EE:03")
        registry.register(peer)

        registry.updateState(peer.id, ConnectionState.CONNECTING)
        val updated = registry.getAll().find { it.id == peer.id }!!
        assertEquals(ConnectionState.CONNECTING, updated.connectionState)
    }

    @Test
    fun `getConnected returns only CONNECTED peers`() {
        registry.register(makePeer("01", state = ConnectionState.DISCOVERED))
        registry.register(makePeer("02", state = ConnectionState.CONNECTED))
        registry.register(makePeer("03", state = ConnectionState.DISCONNECTED))
        registry.register(makePeer("04", state = ConnectionState.CONNECTED))

        val connected = registry.getConnected()
        assertEquals(2, connected.size)
        assertTrue(connected.all { it.connectionState == ConnectionState.CONNECTED })
    }

    @Test
    fun `connectedCount returns correct count`() {
        registry.register(makePeer("01", state = ConnectionState.DISCOVERED))
        registry.register(makePeer("02", state = ConnectionState.CONNECTED))
        registry.register(makePeer("03", state = ConnectionState.CONNECTED))

        assertEquals(2, registry.connectedCount())
    }

    @Test
    fun `hasCapacity respects MAX_PEERS`() {
        // Register MAX_PEERS connected peers
        for (i in 1..MeshConfig.MAX_PEERS) {
            registry.register(makePeer(
                id = "peer-$i",
                state = ConnectionState.CONNECTED
            ))
        }

        assertFalse("Should be at capacity", registry.hasCapacity())

        // Disconnect one peer
        registry.updateState("peer-1", ConnectionState.DISCONNECTED)
        assertTrue("Should have capacity after disconnect", registry.hasCapacity())
    }

    @Test
    fun `attachConnection sets peer to CONNECTED and stores connection`() {
        val peer = makePeer("AA:BB:CC:DD:EE:05")
        registry.register(peer)

        val conn = makeDummyConnection(peer.id)
        registry.attachConnection(peer.id, conn)

        val updated = registry.getAll().find { it.id == peer.id }!!
        assertEquals(ConnectionState.CONNECTED, updated.connectionState)
        assertNotNull(registry.getConnection(peer.id))
    }

    @Test
    fun `getConnection returns null for unconnected peer`() {
        val peer = makePeer("AA:BB:CC:DD:EE:06")
        registry.register(peer)

        assertNull(registry.getConnection(peer.id))
    }

    @Test
    fun `clear removes all peers and connections`() {
        registry.register(makePeer("01", state = ConnectionState.CONNECTED))
        registry.register(makePeer("02", state = ConnectionState.CONNECTED))

        val conn = makeDummyConnection("01")
        registry.attachConnection("01", conn)

        registry.clear()

        assertEquals(0, registry.getAll().size)
        assertEquals(0, registry.connectedCount())
        assertEquals(0, registry.peers.value.size)
        assertNull(registry.getConnection("01"))
    }

    @Test
    fun `duplicate register replaces existing peer`() {
        val peer1 = makePeer("AA:BB:CC:DD:EE:07", state = ConnectionState.DISCOVERED)
        val peer2 = peer1.copy(name = "Updated-Name", connectionState = ConnectionState.CONNECTING)

        registry.register(peer1)
        registry.register(peer2)

        assertEquals(1, registry.getAll().size)
        assertEquals("Updated-Name", registry.getAll()[0].name)
        assertEquals(ConnectionState.CONNECTING, registry.getAll()[0].connectionState)
    }

    @Test
    fun `getAll returns both BT and WiFi peers`() {
        registry.register(makePeer("bt-01", transport = TransportType.BLUETOOTH_RFCOMM))
        registry.register(makePeer("wifi-01", transport = TransportType.WIFI_DIRECT))

        val all = registry.getAll()
        assertEquals(2, all.size)
        assertTrue(all.any { it.transportType == TransportType.BLUETOOTH_RFCOMM })
        assertTrue(all.any { it.transportType == TransportType.WIFI_DIRECT })
    }
}
