package com.itantra.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * CRC-16-CCITT checksum utility for the iTantra distress binary protocol.
 *
 * Uses the standard CCITT polynomial `0x1021` with initial value `0xFFFF`.
 * This is the same algorithm used in the general iBFS-v1 framing
 * ([com.itantra.network.ProtocolCodec.crc16Ccitt]) but self-contained
 * within the protocol package to avoid cross-package coupling.
 *
 * All methods are pure functions with no side effects.
 */
object CrcVerifier {

    private const val CRC_POLYNOMIAL = 0x1021
    private const val CRC_INIT = 0xFFFF
    private const val CRC_SIZE_BYTES = 2

    /**
     * Computes CRC-16-CCITT over [data].
     *
     * @return The CRC as a signed [Short] (the raw 16-bit value).
     */
    fun compute(data: ByteArray): Short {
        var crc = CRC_INIT
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) {
                    (crc shl 1) xor CRC_POLYNOMIAL
                } else {
                    crc shl 1
                }
                crc = crc and 0xFFFF
            }
        }
        return crc.toShort()
    }

    /**
     * Computes CRC-16-CCITT over [data] and returns it as an unsigned 16-bit integer.
     *
     * This avoids the signed-[Short] confusion that can occur when comparing CRC values.
     */
    fun computeAsUnsigned(data: ByteArray): Int = compute(data).toInt() and 0xFFFF

    /**
     * Verifies that the CRC-16-CCITT of [data] matches [expectedCrc].
     *
     * @return `true` if the computed CRC equals [expectedCrc], `false` otherwise.
     */
    fun verify(data: ByteArray, expectedCrc: Short): Boolean = compute(data) == expectedCrc

    /**
     * Appends a 2-byte big-endian CRC-16-CCITT to [data].
     *
     * @return A new byte array of length `data.size + 2` with the CRC appended.
     */
    fun appendCrc(data: ByteArray): ByteArray {
        val crc = compute(data)
        val result = ByteArray(data.size + CRC_SIZE_BYTES)
        data.copyInto(result)
        val crcBytes = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(crc).array()
        crcBytes.copyInto(result, destinationOffset = data.size)
        return result
    }

    /**
     * Strips the trailing 2-byte CRC from [frameWithCrc], verifies it, and returns
     * the data portion.
     *
     * @return The data bytes (everything except the last 2 CRC bytes).
     * @throws CrcMismatchException if the CRC does not match the data.
     * @throws CrcMismatchException if the frame is too short to contain a CRC.
     */
    fun stripAndVerify(frameWithCrc: ByteArray): ByteArray {
        if (frameWithCrc.size < CRC_SIZE_BYTES) {
            throw CrcMismatchException("Frame too short for CRC: ${frameWithCrc.size} bytes")
        }
        val dataLen = frameWithCrc.size - CRC_SIZE_BYTES
        val data = frameWithCrc.copyOfRange(0, dataLen)
        val receivedCrcBuf = ByteBuffer.wrap(frameWithCrc, dataLen, CRC_SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
        val receivedCrc = receivedCrcBuf.short
        val computedCrc = compute(data)
        if (receivedCrc != computedCrc) {
            throw CrcMismatchException(
                "CRC mismatch: received 0x${(receivedCrc.toInt() and 0xFFFF).toString(16).uppercase()}, " +
                    "computed 0x${(computedCrc.toInt() and 0xFFFF).toString(16).uppercase()}"
            )
        }
        return data
    }
}

/**
 * Thrown when a CRC-16-CCITT checksum verification fails.
 */
class CrcMismatchException(message: String) : Exception(message)
