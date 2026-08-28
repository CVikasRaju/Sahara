package com.itantra.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Deserializer for distress binary frames produced by [DistressSerializer].
 *
 * Validates magic bytes, frame length, CRC-16, and payload structure before
 * returning a [DistressPacket]. Corrupted or malformed frames cause a
 * [DistressFrameCorruptException] — callers should catch and discard, per
 * docs/NETWORK_PROTOCOL.md §5.
 *
 * @see DistressSerializer for the frame layout specification.
 */
object DistressUnpacker {

    /** Minimum frame size: header(10) + timestamp(6) + flags(1) + CRC(2) = 19 bytes. */
    private const val MIN_FRAME_SIZE =
        DistressSerializer.HEADER_SIZE +
            DistressSerializer.TIMESTAMP_SIZE +
            DistressSerializer.FLAGS_SIZE +
            DistressSerializer.CRC_SIZE

    /**
     * Deserializes a raw binary frame into a [DistressPacket].
     *
     * Performs full validation:
     * 1. Minimum frame size check
     * 2. Magic byte verification
     * 3. Packet type assertion (must be SILENT_SOS / 0x2)
     * 4. Payload length vs frame length consistency
     * 5. CRC-16-CCITT verification
     * 6. Payload structure validation (timestamp, flags, optional fields)
     *
     * @param rawFrame The complete binary frame including header, payload, and CRC.
     * @return The deserialized [DistressPacket].
     * @throws DistressFrameCorruptException on any validation failure.
     */
    fun unpack(rawFrame: ByteArray): DistressPacket {
        if (rawFrame.size < MIN_FRAME_SIZE) {
            throw DistressFrameCorruptException(
                "Frame too short: ${rawFrame.size} bytes (minimum $MIN_FRAME_SIZE)"
            )
        }

        val buf = ByteBuffer.wrap(rawFrame).order(ByteOrder.BIG_ENDIAN)

        // -- Magic validation --
        val m1 = buf.get()
        val m2 = buf.get()
        if (m1 != DistressSerializer.MAGIC_HIGH || m2 != DistressSerializer.MAGIC_LOW) {
            throw DistressFrameCorruptException(
                "Bad magic bytes: 0x${(m1.toInt() and 0xFF).toString(16)} " +
                    "0x${(m2.toInt() and 0xFF).toString(16)}"
            )
        }

        // -- Version / Type --
        val versionType = buf.get().toInt() and 0xFF
        val version = (versionType shr 4) and 0xF
        val type = versionType and 0xF
        if (type != DistressSerializer.TYPE_SILENT_SOS) {
            throw DistressFrameCorruptException(
                "Not a distress packet: type=0x${type.toString(16)} (expected 0x2)"
            )
        }

        // -- Severity / Language --
        val severityLang = buf.get().toInt() and 0xFF
        val severity = try {
            DistressSeverity.fromValue((severityLang shr 4) and 0xF)
        } catch (e: IllegalArgumentException) {
            throw DistressFrameCorruptException("Invalid severity nibble: ${e.message}")
        }
        val language = try {
            DistressLanguage.fromCode(severityLang and 0xF)
        } catch (e: IllegalArgumentException) {
            throw DistressFrameCorruptException("Invalid language nibble: ${e.message}")
        }

        // -- Sequence ID --
        val sequenceId = buf.int.toLong() and 0xFFFFFFFFL

        // -- Payload length --
        val payloadLen = buf.short.toInt() and 0xFFFF

        // -- Frame length validation --
        val expectedFrameSize = DistressSerializer.HEADER_SIZE + payloadLen + DistressSerializer.CRC_SIZE
        if (rawFrame.size != expectedFrameSize) {
            throw DistressFrameCorruptException(
                "Length mismatch: header says $payloadLen payload bytes, " +
                    "frame is ${rawFrame.size} bytes (expected $expectedFrameSize)"
            )
        }

        // -- CRC validation --
        val dataWithoutCrc = rawFrame.copyOfRange(0, DistressSerializer.HEADER_SIZE + payloadLen)
        val receivedCrc = ByteBuffer.wrap(
            rawFrame,
            DistressSerializer.HEADER_SIZE + payloadLen,
            DistressSerializer.CRC_SIZE,
        ).order(ByteOrder.BIG_ENDIAN).short

        val computedCrc = CrcVerifier.compute(dataWithoutCrc)
        if (receivedCrc != computedCrc) {
            throw DistressFrameCorruptException(
                "CRC mismatch: received 0x${(receivedCrc.toInt() and 0xFFFF).toString(16).uppercase()}, " +
                    "computed 0x${(computedCrc.toInt() and 0xFFFF).toString(16).uppercase()}"
            )
        }

        // -- Parse payload --
        val payloadBytes = ByteArray(payloadLen)
        buf.position(DistressSerializer.HEADER_SIZE)
        buf.get(payloadBytes)

        val parsed = parseDistressPayload(payloadBytes)

        return DistressPacket(
            version = version,
            severity = severity,
            language = language,
            sequenceId = sequenceId,
            timestampEpochMs = parsed.timestampEpochMs,
            gps = parsed.gps,
            sourceLanguage = parsed.sourceLanguage,
            message = parsed.message,
        )
    }

    /**
     * Quick check whether [rawFrame] looks like a distress frame:
     * valid magic bytes and type nibble == 0x2 (SILENT_SOS).
     *
     * Does NOT validate CRC or full structure — use [unpack] for full validation.
     *
     * @return `true` if the frame has valid magic and distress type nibble.
     */
    fun isDistressFrame(rawFrame: ByteArray): Boolean {
        if (rawFrame.size < DistressSerializer.HEADER_SIZE) return false
        if (rawFrame[0] != DistressSerializer.MAGIC_HIGH) return false
        if (rawFrame[1] != DistressSerializer.MAGIC_LOW) return false
        val type = rawFrame[2].toInt() and 0x0F
        return type == DistressSerializer.TYPE_SILENT_SOS
    }

    /**
     * Parsed distress payload fields (internal representation).
     */
    internal data class DistressPayloadFields(
        val timestampEpochMs: Long,
        val gps: GpsCoordinate?,
        val sourceLanguage: DistressLanguage?,
        val message: String,
    )

    /**
     * Parses the distress-specific payload:
     * timestamp(6) + flags(1) + [GPS(8)] + [sourceLang(1)] + text.
     *
     * @throws DistressFrameCorruptException if the payload is malformed or truncated.
     */
    internal fun parseDistressPayload(payload: ByteArray): DistressPayloadFields {
        val minPayload = DistressSerializer.TIMESTAMP_SIZE + DistressSerializer.FLAGS_SIZE
        if (payload.size < minPayload) {
            throw DistressFrameCorruptException(
                "Distress payload too short: ${payload.size} bytes (minimum $minPayload)"
            )
        }

        var offset = 0

        // -- Timestamp: uint48, big-endian (6 bytes) --
        var timestamp = 0L
        for (i in 0 until DistressSerializer.TIMESTAMP_SIZE) {
            timestamp = (timestamp shl 8) or (payload[offset++].toLong() and 0xFF)
        }

        // -- Flags byte --
        val flags = payload[offset++].toInt() and 0xFF
        val hasGps = (flags and DistressSerializer.FLAG_HAS_GPS) != 0
        val hasSourceLang = (flags and DistressSerializer.FLAG_HAS_SOURCE_LANG) != 0

        // -- Optional GPS (float32 lat + float32 lon) --
        var gps: GpsCoordinate? = null
        if (hasGps) {
            if (offset + DistressSerializer.GPS_SIZE > payload.size) {
                throw DistressFrameCorruptException(
                    "Payload truncated: GPS flag set but only " +
                        "${payload.size - offset} bytes remaining (need ${DistressSerializer.GPS_SIZE})"
                )
            }
            val gpsBuf = ByteBuffer.wrap(payload, offset, DistressSerializer.GPS_SIZE)
                .order(ByteOrder.BIG_ENDIAN)
            val lat = gpsBuf.float
            val lon = gpsBuf.float
            gps = try {
                GpsCoordinate(lat, lon)
            } catch (e: IllegalArgumentException) {
                throw DistressFrameCorruptException("Invalid GPS in payload: ${e.message}")
            }
            offset += DistressSerializer.GPS_SIZE
        }

        // -- Optional source language (1 byte) --
        var sourceLanguage: DistressLanguage? = null
        if (hasSourceLang) {
            if (offset >= payload.size) {
                throw DistressFrameCorruptException(
                    "Payload truncated: source language flag set but no byte remaining"
                )
            }
            sourceLanguage = try {
                DistressLanguage.fromCode(payload[offset].toInt() and 0xFF)
            } catch (e: IllegalArgumentException) {
                throw DistressFrameCorruptException("Invalid source language: ${e.message}")
            }
            offset += DistressSerializer.SOURCE_LANG_SIZE
        }

        // -- UTF-8 message text (remaining bytes) --
        val message = if (offset < payload.size) {
            String(payload, offset, payload.size - offset, Charsets.UTF_8)
        } else {
            ""
        }

        return DistressPayloadFields(
            timestampEpochMs = timestamp,
            gps = gps,
            sourceLanguage = sourceLanguage,
            message = message,
        )
    }
}

/**
 * Thrown when a distress binary frame fails validation during unpacking.
 *
 * Per docs/NETWORK_PROTOCOL.md §5, corrupted frames should be silently dropped
 * (not retransmitted at this layer).
 */
class DistressFrameCorruptException(message: String) : Exception(message)
