package com.itantra.network

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Implements the iBFS-v1 binary framing spec — see docs/NETWORK_PROTOCOL.md.
 *
 * Frame layout (byte-aligned):
 *   Byte 0-1:  Magic            0x49 0x54 ("IT")
 *   Byte 2:    [Version:4][Type:4]
 *   Byte 3:    [Priority:4][Lang:4]
 *   Byte 4-7:  Sequence ID      uint32 big-endian
 *   Byte 8-9:  Payload Length   uint16 big-endian
 *   Byte 10..: Payload Data     N bytes
 *   Last 2:    CRC-16-CCITT over header+payload
 *
 * This file is fully self-contained and unit-testable without any ML or
 * networking dependency — see the accompanying ProtocolCodecTest.kt.
 */

enum class PacketType(val value: Int) {
    PTT_VOICE_NOTE(0x1),
    SILENT_SOS(0x2),
    ACK(0x3),
    RELAY(0x4); // store-and-forward / mesh relay — docs/ADDITIONAL_FEATURES.md #3, #5

    companion object {
        fun fromValue(v: Int) = entries.firstOrNull { it.value == v }
            ?: throw IllegalArgumentException("Unknown packet type: $v")
    }
}

enum class Priority(val value: Int) {
    ROUTINE(0x0),
    HIGH(0x1),
    EMERGENCY(0xF);

    companion object {
        fun fromValue(v: Int) = entries.firstOrNull { it.value == v }
            ?: throw IllegalArgumentException("Unknown priority: $v")
    }
}

enum class Language(val value: Int, val code: String) {
    HINDI(0x0, "hi"),
    GUJARATI(0x1, "gu"),
    MARATHI(0x2, "mr"),
    KANNADA(0x3, "kn"),
    TAMIL(0x4, "ta"),
    TELUGU(0x5, "te"),
    MALAYALAM(0x6, "ml"),
    ODIA(0x7, "or"),
    BENGALI(0x8, "bn"),
    ENGLISH_IN(0x9, "en-IN");

    companion object {
        fun fromValue(v: Int) = entries.firstOrNull { it.value == v }
            ?: throw IllegalArgumentException("Unknown language code: $v")
    }
}

/** Optional extended payload fields — docs/NETWORK_PROTOCOL.md §4. */
data class ExtendedFields(
    val gpsLat: Float? = null,
    val gpsLon: Float? = null,
    val sourceLanguage: Language? = null,
)

data class ItantraPacket(
    val version: Int = 1,
    val type: PacketType,
    val priority: Priority,
    val language: Language,
    val sequenceId: Long,
    val text: String,
    val extended: ExtendedFields? = null,
)

class FrameCorruptException(message: String) : Exception(message)

object ProtocolCodec {

    private const val MAGIC_HIGH: Byte = 0x49
    private const val MAGIC_LOW: Byte = 0x54
    private const val MAX_PAYLOAD_BYTES = 512

    fun encode(packet: ItantraPacket): ByteArray {
        val payload = buildPayload(packet)
        require(payload.size <= MAX_PAYLOAD_BYTES) {
            "Payload of ${payload.size} bytes exceeds max ${MAX_PAYLOAD_BYTES}"
        }

        val header = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
        header.put(MAGIC_HIGH)
        header.put(MAGIC_LOW)
        header.put(((packet.version and 0xF) shl 4 or (packet.type.value and 0xF)).toByte())
        header.put(((packet.priority.value and 0xF) shl 4 or (packet.language.value and 0xF)).toByte())
        header.putInt(packet.sequenceId.toInt())
        header.putShort(payload.size.toShort())

        val bodyAndHeader = ByteArrayOutputStream()
        bodyAndHeader.write(header.array())
        bodyAndHeader.write(payload)

        val crc = crc16Ccitt(bodyAndHeader.toByteArray())
        val crcBytes = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(crc).array()

        bodyAndHeader.write(crcBytes)
        return bodyAndHeader.toByteArray()
    }

    /**
     * Decodes and CRC-validates a frame. Per docs/NETWORK_PROTOCOL.md §5, a corrupted
     * frame is dropped, not auto-retransmitted at this layer — callers should catch
     * FrameCorruptException and simply discard the frame.
     */
    fun decode(bytes: ByteArray): ItantraPacket {
        if (bytes.size < 12) throw FrameCorruptException("Frame too short: ${bytes.size} bytes")

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val m1 = buf.get()
        val m2 = buf.get()
        if (m1 != MAGIC_HIGH || m2 != MAGIC_LOW) {
            throw FrameCorruptException("Bad magic bytes: $m1 $m2")
        }

        val versionType = buf.get().toInt() and 0xFF
        val version = (versionType shr 4) and 0xF
        val type = PacketType.fromValue(versionType and 0xF)

        val priorityLang = buf.get().toInt() and 0xFF
        val priority = Priority.fromValue((priorityLang shr 4) and 0xF)
        val language = Language.fromValue(priorityLang and 0xF)

        val sequenceId = buf.int.toLong() and 0xFFFFFFFFL
        val payloadLen = buf.short.toInt() and 0xFFFF

        if (bytes.size != 10 + payloadLen + 2) {
            throw FrameCorruptException(
                "Length mismatch: header says $payloadLen payload bytes, " +
                    "frame is ${bytes.size} bytes total"
            )
        }

        val payloadBytes = ByteArray(payloadLen)
        buf.get(payloadBytes)

        val receivedCrc = buf.short
        val computedCrc = crc16Ccitt(bytes.copyOfRange(0, 10 + payloadLen))
        if (receivedCrc != computedCrc) {
            throw FrameCorruptException("CRC mismatch: got $receivedCrc, expected $computedCrc")
        }

        val (text, extended) = parsePayload(payloadBytes)

        return ItantraPacket(
            version = version,
            type = type,
            priority = priority,
            language = language,
            sequenceId = sequenceId,
            text = text,
            extended = extended,
        )
    }

    // --- Extended payload (docs/NETWORK_PROTOCOL.md §4) ---
    // Byte 0 of payload: [HasGPS:1][HasSourceLang:1][Reserved:6]
    // then optional gps (float32 lat + float32 lon), optional source lang byte, then UTF-8 text.

    private fun buildPayload(packet: ItantraPacket): ByteArray {
        val ext = packet.extended
        val hasGps = ext?.gpsLat != null && ext.gpsLon != null
        val hasSourceLang = ext?.sourceLanguage != null

        if (!hasGps && !hasSourceLang) {
            // Common case: zero overhead beyond the flag byte.
            val out = ByteArrayOutputStream()
            out.write(0x00)
            out.write(packet.text.toByteArray(Charsets.UTF_8))
            return out.toByteArray()
        }

        var flags = 0
        if (hasGps) flags = flags or 0b1000_0000
        if (hasSourceLang) flags = flags or 0b0100_0000

        val out = ByteArrayOutputStream()
        out.write(flags)
        if (hasGps) {
            val gpsBuf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            gpsBuf.putFloat(ext!!.gpsLat!!)
            gpsBuf.putFloat(ext.gpsLon!!)
            out.write(gpsBuf.array())
        }
        if (hasSourceLang) {
            out.write(ext!!.sourceLanguage!!.value)
        }
        out.write(packet.text.toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    private fun parsePayload(payload: ByteArray): Pair<String, ExtendedFields?> {
        if (payload.isEmpty()) return "" to null
        val flags = payload[0].toInt() and 0xFF
        val hasGps = (flags and 0b1000_0000) != 0
        val hasSourceLang = (flags and 0b0100_0000) != 0

        var offset = 1
        var lat: Float? = null
        var lon: Float? = null
        var sourceLang: Language? = null

        if (hasGps) {
            val buf = ByteBuffer.wrap(payload, offset, 8).order(ByteOrder.BIG_ENDIAN)
            lat = buf.float
            lon = buf.float
            offset += 8
        }
        if (hasSourceLang) {
            sourceLang = Language.fromValue(payload[offset].toInt() and 0xFF)
            offset += 1
        }

        val text = String(payload, offset, payload.size - offset, Charsets.UTF_8)
        val extended = if (hasGps || hasSourceLang) {
            ExtendedFields(gpsLat = lat, gpsLon = lon, sourceLanguage = sourceLang)
        } else null

        return text to extended
    }

    /** Standard CRC-16-CCITT (polynomial 0x1021, initial value 0xFFFF). */
    fun crc16Ccitt(data: ByteArray): Short {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) {
                    (crc shl 1) xor 0x1021
                } else {
                    crc shl 1
                }
                crc = crc and 0xFFFF
            }
        }
        return crc.toShort()
    }
}
