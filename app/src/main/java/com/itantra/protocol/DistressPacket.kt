package com.itantra.protocol

/**
 * Distress-specific severity levels mapped to the iBFS-v1 priority nibble.
 *
 * While [com.itantra.network.Priority] covers general packet priority,
 * this enum provides distress-specific semantics for emergency protocol handling.
 *
 * @property value 4-bit nibble value packed into byte 3 (high nibble) of the iBFS-v1 header.
 */
enum class DistressSeverity(val value: Int) {
    LOW(0x0),
    MEDIUM(0x1),
    HIGH(0x2),
    CRITICAL(0xF);

    companion object {
        fun fromValue(v: Int): DistressSeverity = entries.firstOrNull { it.value == v }
            ?: throw IllegalArgumentException("Unknown distress severity: 0x${v.toString(16)}")
    }
}

/**
 * Language codes per docs/NETWORK_PROTOCOL.md §3.
 *
 * Self-contained within the protocol package to avoid coupling to
 * [com.itantra.network.Language]. The 4-bit codes and language set are identical.
 *
 * @property code 4-bit nibble value packed into byte 3 (low nibble) of the iBFS-v1 header.
 * @property tag IETF BCP-47 language tag.
 */
enum class DistressLanguage(val code: Int, val tag: String) {
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
        fun fromCode(v: Int): DistressLanguage = entries.firstOrNull { it.code == v }
            ?: throw IllegalArgumentException("Unknown language code: 0x${v.toString(16)}")
    }
}

/**
 * GPS coordinate pair with validation.
 *
 * @property lat Latitude in decimal degrees, must be in [-90, 90].
 * @property lon Longitude in decimal degrees, must be in [-180, 180].
 * @throws IllegalArgumentException if coordinates are out of valid range.
 */
data class GpsCoordinate(val lat: Float, val lon: Float) {
    init {
        require(lat in -90f..90f) { "Latitude $lat out of range [-90, 90]" }
        require(lon in -180f..180f) { "Longitude $lon out of range [-180, 180]" }
    }
}

/**
 * Domain data class for distress / emergency packets.
 *
 * This is the protocol package's primary data model, purpose-built for SOS and
 * emergency scenarios. It extends the general iBFS-v1 packet concept with:
 * - A [timestampEpochMs] field (uint48 on the wire) for ordering out-of-order
 *   messages in mesh relay scenarios.
 * - A [severity] field with distress-specific semantics.
 * - First-class [gps] and [sourceLanguage] fields (not buried in extended-payload
 *   flag bits).
 *
 * Serialized via [DistressSerializer], deserialized via [DistressUnpacker].
 * Use [DistressCodecManager] as the public facade.
 *
 * @property version Protocol version, always 1 for iBFS-v1.
 * @property severity Distress severity level.
 * @property language Language of the [message] text.
 * @property sequenceId Monotonic uint32 for ack/retransmit and mesh dedup.
 * @property timestampEpochMs Sender's wall-clock time in milliseconds since Unix epoch.
 *   Stored as uint48 on the wire (good until year ~10889).
 * @property gps Optional GPS fix at time of distress.
 * @property sourceLanguage Optional original sender language for cross-language relay.
 * @property message UTF-8 distress message text.
 */
data class DistressPacket(
    val version: Int = 1,
    val severity: DistressSeverity,
    val language: DistressLanguage,
    val sequenceId: Long,
    val timestampEpochMs: Long,
    val gps: GpsCoordinate? = null,
    val sourceLanguage: DistressLanguage? = null,
    val message: String,
)
