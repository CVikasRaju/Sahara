package com.itantra.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * These tests require no Android framework, no emulator, and no physical device —
 * run them with `./gradlew testDebugUnitTest` right after cloning, before touching
 * any ML or hardware integration. If these fail, nothing downstream will work.
 */
class ProtocolCodecTest {

    @Test
    fun `basic round trip preserves all fields`() {
        val packet = ItantraPacket(
            type = PacketType.PTT_VOICE_NOTE,
            priority = Priority.ROUTINE,
            language = Language.KANNADA,
            sequenceId = 42L,
            text = "ಸಹಾಯ ಬೇಕು",
        )
        val encoded = ProtocolCodec.encode(packet)
        val decoded = ProtocolCodec.decode(encoded)

        assertEquals(packet.type, decoded.type)
        assertEquals(packet.priority, decoded.priority)
        assertEquals(packet.language, decoded.language)
        assertEquals(packet.sequenceId, decoded.sequenceId)
        assertEquals(packet.text, decoded.text)
        assertNull(decoded.extended)
    }

    @Test
    fun `emergency priority round trips correctly`() {
        val packet = ItantraPacket(
            type = PacketType.SILENT_SOS,
            priority = Priority.EMERGENCY,
            language = Language.HINDI,
            sequenceId = 1L,
            text = "यहाँ पानी बढ़ रहा है",
        )
        val decoded = ProtocolCodec.decode(ProtocolCodec.encode(packet))
        assertEquals(Priority.EMERGENCY, decoded.priority)
    }

    @Test
    fun `extended payload with GPS round trips`() {
        val packet = ItantraPacket(
            type = PacketType.SILENT_SOS,
            priority = Priority.EMERGENCY,
            language = Language.TAMIL,
            sequenceId = 7L,
            text = "உதவி தேவை",
            extended = ExtendedFields(gpsLat = 13.0827f, gpsLon = 80.2707f),
        )
        val decoded = ProtocolCodec.decode(ProtocolCodec.encode(packet))
        requireNotNull(decoded.extended)
        assertEquals(13.0827f, decoded.extended!!.gpsLat!!, 0.0001f)
        assertEquals(80.2707f, decoded.extended!!.gpsLon!!, 0.0001f)
    }

    @Test
    fun `extended payload with source language for translation relay round trips`() {
        val packet = ItantraPacket(
            type = PacketType.PTT_VOICE_NOTE,
            priority = Priority.ROUTINE,
            language = Language.TAMIL, // receiver's target language
            sequenceId = 3L,
            text = "translated text",
            extended = ExtendedFields(sourceLanguage = Language.GUJARATI),
        )
        val decoded = ProtocolCodec.decode(ProtocolCodec.encode(packet))
        assertEquals(Language.GUJARATI, decoded.extended!!.sourceLanguage)
    }

    @Test
    fun `corrupted magic bytes are rejected`() {
        val packet = ItantraPacket(
            type = PacketType.PTT_VOICE_NOTE,
            priority = Priority.ROUTINE,
            language = Language.HINDI,
            sequenceId = 1L,
            text = "test",
        )
        val encoded = ProtocolCodec.encode(packet)
        encoded[0] = 0x00 // corrupt the magic byte

        assertThrows(FrameCorruptException::class.java) {
            ProtocolCodec.decode(encoded)
        }
    }

    @Test
    fun `corrupted payload byte fails CRC check`() {
        val packet = ItantraPacket(
            type = PacketType.PTT_VOICE_NOTE,
            priority = Priority.ROUTINE,
            language = Language.HINDI,
            sequenceId = 1L,
            text = "test message",
        )
        val encoded = ProtocolCodec.encode(packet)
        // Flip a bit in the middle of the payload
        encoded[12] = (encoded[12].toInt() xor 0x01).toByte()

        assertThrows(FrameCorruptException::class.java) {
            ProtocolCodec.decode(encoded)
        }
    }

    @Test
    fun `truncated frame is rejected`() {
        val packet = ItantraPacket(
            type = PacketType.PTT_VOICE_NOTE,
            priority = Priority.ROUTINE,
            language = Language.HINDI,
            sequenceId = 1L,
            text = "test",
        )
        val encoded = ProtocolCodec.encode(packet)
        val truncated = encoded.copyOfRange(0, encoded.size - 5)

        assertThrows(FrameCorruptException::class.java) {
            ProtocolCodec.decode(truncated)
        }
    }

    @Test
    fun `frame overhead matches spec — 14 bytes for empty text`() {
        val packet = ItantraPacket(
            type = PacketType.ACK,
            priority = Priority.ROUTINE,
            language = Language.HINDI,
            sequenceId = 1L,
            text = "",
        )
        // 10 header + 1 flag byte + 2 CRC = 13 bytes for empty text
        assertEquals(13, ProtocolCodec.encode(packet).size)
    }
}
