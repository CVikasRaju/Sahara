package com.itantra.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tests for [PeerConnection] — verifies length-prefixed framing round-trip,
 * wire-format compatibility with existing BluetoothTransport, and error handling.
 *
 * Uses [PipedInputStream]/[PipedOutputStream] pairs to simulate socket I/O
 * without requiring real Bluetooth or network hardware.
 */
class PeerConnectionTest {

    private lateinit var senderOut: PipedOutputStream
    private lateinit var senderIn: PipedInputStream
    private lateinit var receiverOut: PipedOutputStream
    private lateinit var receiverIn: PipedInputStream

    @Before
    fun setUp() {
        // Create two crossed pipe pairs to simulate a bidirectional socket
        // Sender writes to senderOut → receiverIn reads
        // Receiver writes to receiverOut → senderIn reads
        senderOut = PipedOutputStream()
        receiverIn = PipedInputStream(senderOut)
        receiverOut = PipedOutputStream()
        senderIn = PipedInputStream(receiverOut)
    }

    @After
    fun tearDown() {
        try { senderOut.close() } catch (_: Exception) {}
        try { senderIn.close() } catch (_: Exception) {}
        try { receiverOut.close() } catch (_: Exception) {}
        try { receiverIn.close() } catch (_: Exception) {}
    }

    @Test
    fun `basic frame round-trip preserves payload`() = runBlocking {
        val receivedFrames = CopyOnWriteArrayList<ByteArray>()
        val disconnected = CopyOnWriteArrayList<String>()
        val latch = CountDownLatch(1)

        val testPayload = "Hello, mesh!".toByteArray(Charsets.UTF_8)

        // Create receiving side
        val receiver = PeerConnection(
            peerId = "peer-A",
            inputStream = receiverIn,
            outputStream = receiverOut,
            onFrameReceived = { frame, peerId ->
                receivedFrames.add(frame)
                latch.countDown()
            },
            onDisconnected = { peerId -> disconnected.add(peerId) },
        )

        receiver.startReadLoop(CoroutineScope(Dispatchers.IO))

        // Send a frame using the same wire format: 2-byte big-endian length + payload
        val lengthPrefix = byteArrayOf(
            ((testPayload.size shr 8) and 0xFF).toByte(),
            (testPayload.size and 0xFF).toByte(),
        )
        senderOut.write(lengthPrefix)
        senderOut.write(testPayload)
        senderOut.flush()

        // Wait for the frame to be received
        assertTrue("Frame not received within timeout", latch.await(5, TimeUnit.SECONDS))
        assertEquals(1, receivedFrames.size)
        assertArrayEquals(testPayload, receivedFrames[0])

        receiver.close()
    }

    @Test
    fun `send method adds length prefix and delivers correctly`() = runBlocking {
        val receivedFrames = CopyOnWriteArrayList<ByteArray>()
        val latch = CountDownLatch(1)

        val testPayload = byteArrayOf(0x49, 0x54, 0x10, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, 0x02, 0x00, 0x48)

        // Receiver reads from senderIn (where sender.send() writes to senderOut,
        // but we need to cross wires differently for PeerConnection.send())
        // For this test, we create a simpler setup:
        val pipeOut = PipedOutputStream()
        val pipeIn = PipedInputStream(pipeOut)

        val sender = PeerConnection(
            peerId = "peer-B",
            inputStream = senderIn, // won't read from this in this test
            outputStream = pipeOut,
            onFrameReceived = { _, _ -> },
            onDisconnected = { _ -> },
        )

        // Manually start so isActive returns true
        val senderScope = CoroutineScope(Dispatchers.IO)
        sender.startReadLoop(senderScope)

        // Send via PeerConnection.send()
        val sent = sender.send(testPayload)
        assertTrue("send() should return true", sent)

        // Read the length prefix + payload from the other end
        val lengthBuf = ByteArray(2)
        assertEquals(2, pipeIn.read(lengthBuf, 0, 2))
        val frameLen = ((lengthBuf[0].toInt() and 0xFF) shl 8) or (lengthBuf[1].toInt() and 0xFF)
        assertEquals(testPayload.size, frameLen)

        val frameBuf = ByteArray(frameLen)
        var offset = 0
        while (offset < frameLen) {
            val n = pipeIn.read(frameBuf, offset, frameLen - offset)
            if (n < 0) break
            offset += n
        }
        assertArrayEquals(testPayload, frameBuf)

        sender.close()
        pipeIn.close()
    }

    @Test
    fun `multiple frames are delivered in order`() = runBlocking {
        val receivedFrames = CopyOnWriteArrayList<ByteArray>()
        val frameCount = 5
        val latch = CountDownLatch(frameCount)

        val receiver = PeerConnection(
            peerId = "peer-C",
            inputStream = receiverIn,
            outputStream = receiverOut,
            onFrameReceived = { frame, _ ->
                receivedFrames.add(frame)
                latch.countDown()
            },
            onDisconnected = { _ -> },
        )
        receiver.startReadLoop(CoroutineScope(Dispatchers.IO))

        // Send multiple frames
        for (i in 0 until frameCount) {
            val payload = "Frame-$i".toByteArray(Charsets.UTF_8)
            val prefix = byteArrayOf(
                ((payload.size shr 8) and 0xFF).toByte(),
                (payload.size and 0xFF).toByte(),
            )
            senderOut.write(prefix)
            senderOut.write(payload)
            senderOut.flush()
        }

        assertTrue("Not all frames received within timeout", latch.await(5, TimeUnit.SECONDS))
        assertEquals(frameCount, receivedFrames.size)

        for (i in 0 until frameCount) {
            assertEquals("Frame-$i", String(receivedFrames[i], Charsets.UTF_8))
        }

        receiver.close()
    }

    @Test
    fun `disconnection callback fires on stream close`() = runBlocking {
        val disconnected = CopyOnWriteArrayList<String>()
        val latch = CountDownLatch(1)

        val receiver = PeerConnection(
            peerId = "peer-D",
            inputStream = receiverIn,
            outputStream = receiverOut,
            onFrameReceived = { _, _ -> },
            onDisconnected = { peerId ->
                disconnected.add(peerId)
                latch.countDown()
            },
        )
        receiver.startReadLoop(CoroutineScope(Dispatchers.IO))

        // Close the sender side — receiver should detect EOF and disconnect
        delay(100) // let read loop start
        senderOut.close()

        assertTrue("Disconnect not received within timeout", latch.await(5, TimeUnit.SECONDS))
        assertEquals(1, disconnected.size)
        assertEquals("peer-D", disconnected[0])
    }

    @Test
    fun `send returns false when connection is closed`() {
        val pipeOut = PipedOutputStream()
        val pipeIn = PipedInputStream(pipeOut)

        val conn = PeerConnection(
            peerId = "peer-E",
            inputStream = pipeIn,
            outputStream = pipeOut,
            onFrameReceived = { _, _ -> },
            onDisconnected = { _ -> },
        )

        // Connection not started (running == false), send should return false
        val result = conn.send("test".toByteArray())
        assertFalse("send() should return false when not running", result)

        conn.close()
        pipeIn.close()
    }
}
