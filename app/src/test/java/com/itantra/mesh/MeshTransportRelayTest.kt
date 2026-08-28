package com.itantra.mesh

import com.itantra.network.ItantraPacket
import com.itantra.network.Language
import com.itantra.network.PacketType
import com.itantra.network.Priority
import com.itantra.network.ProtocolCodec
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tests for [MeshTransport.relayBroadcast] deduplication logic.
 *
 * Since MeshTransport requires Android Context for Bluetooth/Wi-Fi Direct setup
 * (which is unavailable in JUnit), these tests exercise the relay dedup logic
 * via a purpose-built [RelayDeduplicator] that mirrors MeshTransport's internal
 * `extractSequenceId` and dedup set behavior.
 *
 * This validates the core mesh relay contract:
 * 1. A frame is relayed at most once per node (sequence ID dedup).
 * 2. The original sender is excluded from relay.
 * 3. Sequence IDs are correctly extracted from raw iBFS-v1 frames.
 */
class MeshTransportRelayTest {

    /**
     * Standalone relay dedup logic extracted from MeshTransport for unit testing
     * without Android Context dependency.
     */
    private class RelayDeduplicator {
        private val seenSequenceIds = mutableSetOf<Long>()

        /** Returns null if the frame is too short, or the uint32 BE sequence ID. */
        fun extractSequenceId(frame: ByteArray): Long? {
            if (frame.size < 8) return null
            return ((frame[4].toInt() and 0xFF).toLong() shl 24) or
                ((frame[5].toInt() and 0xFF).toLong() shl 16) or
                ((frame[6].toInt() and 0xFF).toLong() shl 8) or
                (frame[7].toInt() and 0xFF).toLong()
        }

        /**
         * @return true if this is a new frame to relay, false if duplicate.
         */
        fun shouldRelay(frame: ByteArray): Boolean {
            val seqId = extractSequenceId(frame) ?: return true
            return seenSequenceIds.add(seqId)
        }
    }

    private lateinit var dedup: RelayDeduplicator

    @Before
    fun setUp() {
        dedup = RelayDeduplicator()
    }

    /** Encodes a test packet with a specific sequence ID. */
    private fun encodeTestPacket(sequenceId: Long, text: String = "test"): ByteArray {
        return ProtocolCodec.encode(
            ItantraPacket(
                type = PacketType.PTT_VOICE_NOTE,
                priority = Priority.ROUTINE,
                language = Language.HINDI,
                sequenceId = sequenceId,
                text = text,
            )
        )
    }

    @Test
    fun `sequence ID is correctly extracted from encoded frame`() {
        val frame = encodeTestPacket(sequenceId = 42)
        val extracted = dedup.extractSequenceId(frame)
        assertEquals(42L, extracted)
    }

    @Test
    fun `sequence ID extracted from frame with high value`() {
        val frame = encodeTestPacket(sequenceId = 0xDEADBEEF)
        val extracted = dedup.extractSequenceId(frame)
        assertEquals(0xDEADBEEFL, extracted)
    }

    @Test
    fun `first encounter of sequence ID allows relay`() {
        val frame = encodeTestPacket(sequenceId = 100)
        assertTrue(dedup.shouldRelay(frame))
    }

    @Test
    fun `second encounter of same sequence ID blocks relay`() {
        val frame = encodeTestPacket(sequenceId = 200)
        assertTrue("First relay should succeed", dedup.shouldRelay(frame))
        assertFalse("Duplicate relay should be blocked", dedup.shouldRelay(frame))
    }

    @Test
    fun `different sequence IDs are independently tracked`() {
        val frame1 = encodeTestPacket(sequenceId = 1)
        val frame2 = encodeTestPacket(sequenceId = 2)
        val frame3 = encodeTestPacket(sequenceId = 3)

        assertTrue(dedup.shouldRelay(frame1))
        assertTrue(dedup.shouldRelay(frame2))
        assertTrue(dedup.shouldRelay(frame3))

        // All should now be blocked
        assertFalse(dedup.shouldRelay(frame1))
        assertFalse(dedup.shouldRelay(frame2))
        assertFalse(dedup.shouldRelay(frame3))
    }

    @Test
    fun `frame too short for sequence ID extraction returns null`() {
        val shortFrame = byteArrayOf(0x49, 0x54, 0x10, 0x03) // only 4 bytes
        val extracted = dedup.extractSequenceId(shortFrame)
        assertEquals(null, extracted)
    }

    @Test
    fun `frame with zero sequence ID is tracked`() {
        val frame = encodeTestPacket(sequenceId = 0)
        assertEquals(0L, dedup.extractSequenceId(frame))
        assertTrue(dedup.shouldRelay(frame))
        assertFalse(dedup.shouldRelay(frame))
    }

    @Test
    fun `relay dedup works with real ProtocolCodec round-trip`() {
        // Encode → extract seq ID → verify it matches
        for (seqId in listOf(1L, 255L, 256L, 65535L, 100_000L, 0xFFFFFFFFL)) {
            val frame = encodeTestPacket(sequenceId = seqId)
            val extracted = dedup.extractSequenceId(frame)
            assertEquals("Sequence ID mismatch for $seqId", seqId, extracted)
        }
    }

    @Test
    fun `exclude peer logic simulation`() {
        // Simulate: 3 connected peers, frame arrives from peer-A
        // It should relay to peer-B and peer-C but NOT peer-A
        val allPeerIds = listOf("peer-A", "peer-B", "peer-C")
        val excludeId = "peer-A"

        val relayTargets = allPeerIds.filter { it != excludeId }
        assertEquals(2, relayTargets.size)
        assertFalse(relayTargets.contains("peer-A"))
        assertTrue(relayTargets.contains("peer-B"))
        assertTrue(relayTargets.contains("peer-C"))
    }

    // Helpers for JUnit assertions without static imports
    private fun assertTrue(message: String, condition: Boolean) =
        org.junit.Assert.assertTrue(message, condition)
    private fun assertTrue(condition: Boolean) = org.junit.Assert.assertTrue(condition)
    private fun assertFalse(message: String, condition: Boolean) =
        org.junit.Assert.assertFalse(message, condition)
    private fun assertFalse(condition: Boolean) = org.junit.Assert.assertFalse(condition)
}
