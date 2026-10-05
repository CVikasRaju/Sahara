package com.itantra.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Byte-packing serializer for [DistressPacket].
 *
 * Produces a compact binary frame compatible with the iBFS-v1 header structure
 * (docs/NETWORK_PROTOCOL.md) but with a distress-specific payload layout that
 * includes a uint48 timestamp and structured GPS/source-language fields.
 *
 * ## Distress Frame Layout
 *
 * ```
 * Byte  0-1:   Magic              0x49 0x54 ("IT")
 * Byte  2:     [Version:4][Type:4] — Type = 0x2 (SILENT_SOS)
 * Byte  3:     [Severity:4][Lang:4]
 * Byte  4-7:   Sequence ID         uint32 big-endian
 * Byte  8-9:   Payload Length N    uint16 big-endian
 * --- payload (N bytes) ---
 * Byte 10-15:  Timestamp           uint48 big-endian (ms since epoch)
 * Byte 16:     Flags               [HasGPS:1][HasSourceLang:1][Reserved:6]
 * Byte 17-24:  GPS (if HasGPS)     float32 lat + float32 lon (big-endian)
 * Next byte:   SourceLang (if set) language code (low nibble)
 * Remaining:   UTF-8 message text
 * --- end payload ---
 * Last 2:      CRC-16-CCITT        over bytes 0..(10+N-1)
 * ```
 *
 * @see DistressUnpacker for the corresponding deserializer.
 */
object DistressSerializer {

    /** iBFS-v1 magic bytes: ASCII "IT". */
    internal const val MAGIC_HIGH: Byte = 0x49
    internal const val MAGIC_LOW: Byte = 0x54

    /** Packet type nibble for Silent SOS (docs/NETWORK_PROTOCOL.md §2). */
    internal const val TYPE_SILENT_SOS = 0x2

    /** Maximum payload size per iBFS-v1 spec. */
    internal const val MAX_PAYLOAD_BYTES = 512

    /** Fixed header size: magic(2) + versionType(1) + severityLang(1) + seqId(4) + payloadLen(2). */
    internal const val HEADER_SIZE = 10

    /** CRC trailer size. */
    internal const val CRC_SIZE = 2

    /** Timestamp size on the wire (uint48 = 6 bytes). */
    internal const val TIMESTAMP_SIZE = 6

    /** Flags byte size. */
    internal const val FLAGS_SIZE = 1

    /** GPS coordinate pair size: float32 lat + float32 lon. */
    internal const val GPS_SIZE = 8

    /** Source language field size. */
    internal const val SOURCE_LANG_SIZE = 1

    /** Payload flag bit: GPS coordinates present. */
    internal const val FLAG_HAS_GPS = 0b1000_0000

    /** Payload flag bit: Source language present. */
    internal const val FLAG_HAS_SOURCE_LANG = 0b0100_0000

    /**
     * Serializes a [DistressPacket] into a complete binary frame including
     * iBFS-v1 header, distress payload, and CRC-16 trailer.
     *
     * @param packet The distress packet to serialize.
     * @return The complete binary frame as a byte array.
     * @throws IllegalArgumentException if the payload exceeds [MAX_PAYLOAD_BYTES].
     */
    fun serialize(packet: DistressPacket): ByteArray {
        val payload = buildDistressPayload(packet)
        require(payload.size <= MAX_PAYLOAD_BYTES) {
            "Distress payload of ${payload.size} bytes exceeds max $MAX_PAYLOAD_BYTES"
        }

        val headerAndPayload = ByteArrayOutputStream(HEADER_SIZE + payload.size)

        // -- Header (10 bytes) --
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        header.put(MAGIC_HIGH)
        header.put(MAGIC_LOW)
        header.put(((packet.version and 0xF) shl 4 or (TYPE_SILENT_SOS and 0xF)).toByte())
        header.put(((packet.severity.value and 0xF) shl 4 or (packet.language.code and 0xF)).toByte())
        header.putInt(packet.sequenceId.toInt())
        header.putShort(payload.size.toShort())

        headerAndPayload.write(header.array())
        headerAndPayload.write(payload)

        // -- CRC-16 trailer (2 bytes) --
        return CrcVerifier.appendCrc(headerAndPayload.toByteArray())
    }

    /**
     * Builds the distress-specific payload:
     * timestamp(6) + flags(1) + [GPS(8)] + [sourceLang(1)] + text(variable).
     */
    internal fun buildDistressPayload(packet: DistressPacket): ByteArray {
        val hasGps = packet.gps != null
        val hasSourceLang = packet.sourceLanguage != null
        val textBytes = packet.message.toByteArray(Charsets.UTF_8)

        val estimatedSize = TIMESTAMP_SIZE + FLAGS_SIZE +
            (if (hasGps) GPS_SIZE else 0) +
            (if (hasSourceLang) SOURCE_LANG_SIZE else 0) +
            textBytes.size

        val out = ByteArrayOutputStream(estimatedSize)

        // Timestamp: uint48, big-endian (6 bytes, ms since epoch)
        val ts = packet.timestampEpochMs
        out.write(((ts shr 40) and 0xFF).toInt())
        out.write(((ts shr 32) and 0xFF).toInt())
        out.write(((ts shr 24) and 0xFF).toInt())
        out.write(((ts shr 16) and 0xFF).toInt())
        out.write(((ts shr 8) and 0xFF).toInt())
        out.write((ts and 0xFF).toInt())

        // Flags byte
        var flags = 0
        if (hasGps) flags = flags or FLAG_HAS_GPS
        if (hasSourceLang) flags = flags or FLAG_HAS_SOURCE_LANG
        out.write(flags)

        // Optional GPS (float32 lat + float32 lon, big-endian)
        if (hasGps) {
            val gpsBuf = ByteBuffer.allocate(GPS_SIZE).order(ByteOrder.BIG_ENDIAN)
            gpsBuf.putFloat(packet.gps!!.lat)
            gpsBuf.putFloat(packet.gps.lon)
            out.write(gpsBuf.array())
        }

        // Optional source language (1 byte)
        if (hasSourceLang) {
            out.write(packet.sourceLanguage!!.code)
        }

        // UTF-8 message text
        out.write(textBytes)

        return out.toByteArray()
    }
}
