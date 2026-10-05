package com.itantra.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive unit tests for the iTantra distress binary protocol.
 *
 * Tests cover:
 * - Round-trip encode/decode fidelity for all field combinations
 * - CRC corruption detection
 * - Frame validation (truncation, bad magic, wrong type)
 * - Payload boundary enforcement
 * - CrcVerifier standalone utilities
 * - Timestamp uint48 fidelity
 * - GPS coordinate validation
 * - Convenience builder methods
 */
class DistressProtocolTest {

    // ── 1. Basic round-trip ────────────────────────────────────────────────

    @Test
    fun `basic round-trip preserves all fields`() {
        val original = DistressPacket(
            severity = DistressSeverity.HIGH,
            language = DistressLanguage.HINDI,
            sequenceId = 42L,
            timestampEpochMs = 1724850000000L,
            message = "Need immediate help",
        )

        val frame = DistressCodecManager.encode(original)
        val decoded = DistressCodecManager.decode(frame)

        assertEquals(original.version, decoded.version)
        assertEquals(original.severity, decoded.severity)
        assertEquals(original.language, decoded.language)
        assertEquals(original.sequenceId, decoded.sequenceId)
        assertEquals(original.timestampEpochMs, decoded.timestampEpochMs)
        assertNull(decoded.gps)
        assertNull(decoded.sourceLanguage)
        assertEquals(original.message, decoded.message)
    }

    // ── 2. Round-trip with GPS ─────────────────────────────────────────────

    @Test
    fun `round-trip with GPS coordinates preserves location`() {
        val original = DistressPacket(
            severity = DistressSeverity.CRITICAL,
            language = DistressLanguage.KANNADA,
            sequenceId = 100L,
            timestampEpochMs = 1724850000000L,
            gps = GpsCoordinate(12.9716f, 77.5946f),
            message = "Flood in area",
        )

        val frame = DistressCodecManager.encode(original)
        val decoded = DistressCodecManager.decode(frame)

        assertNotNull(decoded.gps)
        assertEquals(original.gps!!.lat, decoded.gps!!.lat, 0.0001f)
        assertEquals(original.gps.lon, decoded.gps!!.lon, 0.0001f)
        assertEquals(original.message, decoded.message)
    }

    // ── 3. Round-trip with source language ──────────────────────────────────

    @Test
    fun `round-trip with source language for translation relay`() {
        val original = DistressPacket(
            severity = DistressSeverity.MEDIUM,
            language = DistressLanguage.TAMIL,
            sequenceId = 200L,
            timestampEpochMs = 1724860000000L,
            sourceLanguage = DistressLanguage.KANNADA,
            message = "Relayed distress message",
        )

        val frame = DistressCodecManager.encode(original)
        val decoded = DistressCodecManager.decode(frame)

        assertNotNull(decoded.sourceLanguage)
        assertEquals(DistressLanguage.KANNADA, decoded.sourceLanguage)
        assertEquals(original.message, decoded.message)
    }

    // ── 4. Round-trip with all extended fields ─────────────────────────────

    @Test
    fun `round-trip with all extended fields preserves everything`() {
        val original = DistressPacket(
            severity = DistressSeverity.CRITICAL,
            language = DistressLanguage.BENGALI,
            sequenceId = 999L,
            timestampEpochMs = 1724870000000L,
            gps = GpsCoordinate(-33.8688f, 151.2093f),
            sourceLanguage = DistressLanguage.ENGLISH_IN,
            message = "Multi-field distress test",
        )

        val frame = DistressCodecManager.encode(original)
        val decoded = DistressCodecManager.decode(frame)

        assertEquals(original.severity, decoded.severity)
        assertEquals(original.language, decoded.language)
        assertEquals(original.sequenceId, decoded.sequenceId)
        assertEquals(original.timestampEpochMs, decoded.timestampEpochMs)
        assertNotNull(decoded.gps)
        assertEquals(original.gps!!.lat, decoded.gps!!.lat, 0.0001f)
        assertEquals(original.gps.lon, decoded.gps!!.lon, 0.0001f)
        assertEquals(DistressLanguage.ENGLISH_IN, decoded.sourceLanguage)
        assertEquals(original.message, decoded.message)
    }

    // ── 5. CRC corruption detected ─────────────────────────────────────────

    @Test
    fun `corrupted payload byte fails CRC check`() {
        val packet = DistressPacket(
            severity = DistressSeverity.HIGH,
            language = DistressLanguage.HINDI,
            sequenceId = 1L,
            timestampEpochMs = 1724850000000L,
            message = "Test integrity",
        )

        val frame = DistressCodecManager.encode(packet)
        // Flip a bit in the middle of the payload
        val corrupted = frame.copyOf()
        corrupted[frame.size / 2] = (corrupted[frame.size / 2].toInt() xor 0xFF).toByte()

        assertThrows(DistressFrameCorruptException::class.java) {
            DistressCodecManager.decode(corrupted)
        }
    }

    // ── 6. Truncated frame rejected ────────────────────────────────────────

    @Test
    fun `truncated frame is rejected`() {
        val packet = DistressPacket(
            severity = DistressSeverity.LOW,
            language = DistressLanguage.MARATHI,
            sequenceId = 5L,
            timestampEpochMs = 1724850000000L,
            message = "This will be truncated",
        )

        val frame = DistressCodecManager.encode(packet)
        val truncated = frame.copyOfRange(0, 10) // Only header, no payload or CRC

        assertThrows(DistressFrameCorruptException::class.java) {
            DistressCodecManager.decode(truncated)
        }
    }

    // ── 7. Bad magic bytes rejected ────────────────────────────────────────

    @Test
    fun `bad magic bytes are rejected`() {
        val packet = DistressPacket(
            severity = DistressSeverity.HIGH,
            language = DistressLanguage.HINDI,
            sequenceId = 1L,
            timestampEpochMs = 1724850000000L,
            message = "Magic test",
        )

        val frame = DistressCodecManager.encode(packet)
        val badMagic = frame.copyOf()
        badMagic[0] = 0x00
        badMagic[1] = 0x00

        assertThrows(DistressFrameCorruptException::class.java) {
            DistressCodecManager.decode(badMagic)
        }
    }

    // ── 8. Frame overhead matches spec ─────────────────────────────────────

    @Test
    fun `frame overhead matches spec for empty text`() {
        val packet = DistressPacket(
            severity = DistressSeverity.LOW,
            language = DistressLanguage.HINDI,
            sequenceId = 0L,
            timestampEpochMs = 0L,
            message = "",
        )

        val frame = DistressCodecManager.encode(packet)
        // header(10) + timestamp(6) + flags(1) + CRC(2) = 19 bytes
        assertEquals(19, frame.size)
    }

    // ── 9. isDistressFrame positive ────────────────────────────────────────

    @Test
    fun `isDistressFrame returns true for valid distress frame`() {
        val packet = DistressPacket(
            severity = DistressSeverity.HIGH,
            language = DistressLanguage.TAMIL,
            sequenceId = 10L,
            timestampEpochMs = 1724850000000L,
            message = "SOS",
        )

        val frame = DistressCodecManager.encode(packet)
        assertTrue(DistressCodecManager.isDistressFrame(frame))
    }

    // ── 10. isDistressFrame negative ───────────────────────────────────────

    @Test
    fun `isDistressFrame returns false for non-distress frame`() {
        // Build a frame that looks like iBFS-v1 but with type 0x1 (PTT_VOICE_NOTE)
        val fakeFrame = byteArrayOf(
            0x49, 0x54,       // Magic "IT"
            0x11,             // Version=1, Type=1 (PTT, not SOS)
            0x00,             // Priority=ROUTINE, Lang=HINDI
            0, 0, 0, 1,      // SeqID=1
            0, 0,             // PayloadLen=0
            // (no payload)
            0, 0,             // fake CRC (doesn't matter, isDistressFrame doesn't check CRC)
        )

        assertFalse(DistressCodecManager.isDistressFrame(fakeFrame))
    }

    // ── 11. Max payload boundary ───────────────────────────────────────────

    @Test
    fun `max payload boundary enforced`() {
        // Payload = timestamp(6) + flags(1) + text = 7 + text
        // Max payload = 512, so max text bytes = 505
        val maxTextSize = DistressSerializer.MAX_PAYLOAD_BYTES -
            DistressSerializer.TIMESTAMP_SIZE - DistressSerializer.FLAGS_SIZE

        val atLimit = DistressPacket(
            severity = DistressSeverity.LOW,
            language = DistressLanguage.HINDI,
            sequenceId = 0L,
            timestampEpochMs = 0L,
            message = "A".repeat(maxTextSize),
        )
        // Should NOT throw
        val frame = DistressCodecManager.encode(atLimit)
        val decoded = DistressCodecManager.decode(frame)
        assertEquals(maxTextSize, decoded.message.length)

        // One byte over the limit should throw
        val overLimit = atLimit.copy(message = "A".repeat(maxTextSize + 1))
        assertThrows(IllegalArgumentException::class.java) {
            DistressCodecManager.encode(overLimit)
        }
    }

    // ── 12. CrcVerifier standalone ─────────────────────────────────────────

    @Test
    fun `CrcVerifier compute verify appendCrc stripAndVerify work correctly`() {
        val data = "Hello, iTantra!".toByteArray(Charsets.UTF_8)

        // compute
        val crc = CrcVerifier.compute(data)
        val crcUnsigned = CrcVerifier.computeAsUnsigned(data)
        assertEquals(crc.toInt() and 0xFFFF, crcUnsigned)

        // verify
        assertTrue(CrcVerifier.verify(data, crc))
        assertFalse(CrcVerifier.verify(data, (crc.toInt() xor 0x01).toShort()))

        // appendCrc + stripAndVerify round-trip
        val withCrc = CrcVerifier.appendCrc(data)
        assertEquals(data.size + 2, withCrc.size)

        val stripped = CrcVerifier.stripAndVerify(withCrc)
        assertArrayEquals(data, stripped)

        // Corrupted CRC should throw
        val corrupted = withCrc.copyOf()
        corrupted[corrupted.size - 1] = (corrupted[corrupted.size - 1].toInt() xor 0xFF).toByte()
        assertThrows(CrcMismatchException::class.java) {
            CrcVerifier.stripAndVerify(corrupted)
        }
    }

    // ── 13. Timestamp uint48 fidelity ──────────────────────────────────────

    @Test
    fun `timestamp uint48 survives round-trip for large values`() {
        // Max uint48 = 2^48 - 1 = 281474976710655 (year ~10889)
        val maxUint48 = (1L shl 48) - 1

        val packet = DistressPacket(
            severity = DistressSeverity.LOW,
            language = DistressLanguage.HINDI,
            sequenceId = 0L,
            timestampEpochMs = maxUint48,
            message = "max timestamp",
        )

        val frame = DistressCodecManager.encode(packet)
        val decoded = DistressCodecManager.decode(frame)
        assertEquals(maxUint48, decoded.timestampEpochMs)

        // Also test a realistic current-era timestamp
        val realisticTs = 1724850000000L // ~Aug 2024
        val packet2 = packet.copy(timestampEpochMs = realisticTs)
        val decoded2 = DistressCodecManager.decode(DistressCodecManager.encode(packet2))
        assertEquals(realisticTs, decoded2.timestampEpochMs)
    }

    // ── 14. GPS validation ─────────────────────────────────────────────────

    @Test
    fun `GPS coordinate validation rejects out-of-range values`() {
        // Valid extremes should work
        GpsCoordinate(90f, 180f)
        GpsCoordinate(-90f, -180f)
        GpsCoordinate(0f, 0f)

        // Out-of-range lat
        assertThrows(IllegalArgumentException::class.java) {
            GpsCoordinate(91f, 0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GpsCoordinate(-91f, 0f)
        }

        // Out-of-range lon
        assertThrows(IllegalArgumentException::class.java) {
            GpsCoordinate(0f, 181f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GpsCoordinate(0f, -181f)
        }
    }

    // ── 15. Convenience builders ───────────────────────────────────────────

    @Test
    fun `createSosPacket and createCriticalAlert produce valid packets`() {
        val gps = GpsCoordinate(28.6139f, 77.2090f) // New Delhi

        val sos = DistressCodecManager.createSosPacket(
            message = "SOS Help needed",
            language = DistressLanguage.HINDI,
            sequenceId = 1L,
            gps = gps,
        )
        assertEquals(DistressSeverity.HIGH, sos.severity)
        assertTrue(sos.timestampEpochMs > 0)
        assertNotNull(sos.gps)

        // Should encode and decode cleanly
        val sosFrame = DistressCodecManager.encode(sos)
        val decodedSos = DistressCodecManager.decode(sosFrame)
        assertEquals(sos.message, decodedSos.message)
        assertEquals(sos.severity, decodedSos.severity)

        val critical = DistressCodecManager.createCriticalAlert(
            message = "Earthquake detected",
            language = DistressLanguage.GUJARATI,
            sequenceId = 2L,
            gps = gps,
            sourceLanguage = DistressLanguage.ENGLISH_IN,
        )
        assertEquals(DistressSeverity.CRITICAL, critical.severity)
        assertNotNull(critical.gps)
        assertEquals(DistressLanguage.ENGLISH_IN, critical.sourceLanguage)

        // Should encode and decode cleanly
        val critFrame = DistressCodecManager.encode(critical)
        val decodedCrit = DistressCodecManager.decode(critFrame)
        assertEquals(critical.message, decodedCrit.message)
        assertEquals(critical.severity, decodedCrit.severity)
        assertEquals(critical.sourceLanguage, decodedCrit.sourceLanguage)
    }
}
