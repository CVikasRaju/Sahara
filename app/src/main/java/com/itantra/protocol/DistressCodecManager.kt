package com.itantra.protocol

/**
 * Public facade for the iTantra distress binary protocol.
 *
 * This singleton is the integration point for other packages. During the final
 * merge, the network layer or UI can call [encode] / [decode] to handle
 * distress-specific packets without any changes to
 * [com.itantra.network.ProtocolCodec] or [com.itantra.core.TransceiverService].
 *
 * ## Usage
 * ```kotlin
 * // Encoding a distress packet
 * val packet = DistressCodecManager.createSosPacket(
 *     message = "Need immediate help",
 *     language = DistressLanguage.HINDI,
 *     sequenceId = 42L,
 *     gps = GpsCoordinate(12.9716f, 77.5946f),
 * )
 * val frame: ByteArray = DistressCodecManager.encode(packet)
 *
 * // Decoding an incoming frame
 * if (DistressCodecManager.isDistressFrame(incomingFrame)) {
 *     val decoded = DistressCodecManager.decode(incomingFrame)
 *     // handle decoded.severity, decoded.gps, decoded.message, etc.
 * }
 * ```
 *
 * @see DistressPacket for the data model
 * @see DistressSerializer for the wire format specification
 * @see DistressUnpacker for deserialization and validation details
 */
object DistressCodecManager {

    /**
     * Serializes a [DistressPacket] into a complete binary frame.
     *
     * The frame includes the iBFS-v1 header (magic, version, type, severity,
     * language, sequence ID, payload length), the distress payload (timestamp,
     * flags, optional GPS/source-language, message text), and a CRC-16-CCITT
     * trailer.
     *
     * @param packet The distress packet to serialize.
     * @return The complete binary frame as a byte array.
     * @throws IllegalArgumentException if the payload exceeds 512 bytes.
     * @see DistressSerializer.serialize
     */
    fun encode(packet: DistressPacket): ByteArray = DistressSerializer.serialize(packet)

    /**
     * Deserializes a raw binary frame into a [DistressPacket].
     *
     * Performs full validation: magic bytes, packet type, frame length, CRC-16,
     * and payload structure.
     *
     * @param rawFrame The complete binary frame including header, payload, and CRC.
     * @return The deserialized [DistressPacket].
     * @throws DistressFrameCorruptException on any validation failure.
     * @see DistressUnpacker.unpack
     */
    fun decode(rawFrame: ByteArray): DistressPacket = DistressUnpacker.unpack(rawFrame)

    /**
     * Quick check whether [rawFrame] looks like a distress frame
     * (valid magic + type nibble == 0x2 SILENT_SOS).
     *
     * Does NOT validate CRC or full structure — use [decode] for that.
     *
     * @return `true` if the frame has valid magic bytes and distress type nibble.
     */
    fun isDistressFrame(rawFrame: ByteArray): Boolean = DistressUnpacker.isDistressFrame(rawFrame)

    /**
     * Verifies the CRC-16-CCITT of a raw frame without fully decoding it.
     *
     * Useful for quick integrity checks before committing to a full decode.
     *
     * @return `true` if the CRC is valid, `false` if the frame is too short or corrupted.
     */
    fun verifyCrc(rawFrame: ByteArray): Boolean {
        if (rawFrame.size < DistressSerializer.HEADER_SIZE + DistressSerializer.CRC_SIZE) {
            return false
        }
        return try {
            CrcVerifier.stripAndVerify(rawFrame)
            true
        } catch (_: CrcMismatchException) {
            false
        }
    }

    /**
     * Convenience builder for a standard SOS distress packet.
     *
     * Sets severity to [DistressSeverity.HIGH] and timestamp to the current
     * system time ([System.currentTimeMillis]).
     *
     * @param message The distress message text (UTF-8).
     * @param language The language of the message.
     * @param sequenceId Monotonic sequence ID for ack/retransmit and mesh dedup.
     * @param gps Optional GPS fix at time of distress.
     * @param sourceLanguage Optional original sender language for cross-language relay.
     * @return A fully populated [DistressPacket] ready for [encode].
     */
    fun createSosPacket(
        message: String,
        language: DistressLanguage,
        sequenceId: Long,
        gps: GpsCoordinate? = null,
        sourceLanguage: DistressLanguage? = null,
    ): DistressPacket = DistressPacket(
        severity = DistressSeverity.HIGH,
        language = language,
        sequenceId = sequenceId,
        timestampEpochMs = System.currentTimeMillis(),
        gps = gps,
        sourceLanguage = sourceLanguage,
        message = message,
    )

    /**
     * Convenience builder for a critical alert with mandatory GPS.
     *
     * Sets severity to [DistressSeverity.CRITICAL] and timestamp to the current
     * system time. Critical alerts always include GPS to enable rescue operations.
     *
     * @param message The alert message text (UTF-8).
     * @param language The language of the message.
     * @param sequenceId Monotonic sequence ID for ack/retransmit and mesh dedup.
     * @param gps GPS fix — mandatory for critical alerts.
     * @param sourceLanguage Optional original sender language for cross-language relay.
     * @return A fully populated [DistressPacket] ready for [encode].
     */
    fun createCriticalAlert(
        message: String,
        language: DistressLanguage,
        sequenceId: Long,
        gps: GpsCoordinate,
        sourceLanguage: DistressLanguage? = null,
    ): DistressPacket = DistressPacket(
        severity = DistressSeverity.CRITICAL,
        language = language,
        sequenceId = sequenceId,
        timestampEpochMs = System.currentTimeMillis(),
        gps = gps,
        sourceLanguage = sourceLanguage,
        message = message,
    )
}
