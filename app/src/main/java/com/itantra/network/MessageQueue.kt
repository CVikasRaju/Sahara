package com.itantra.network

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Store-and-forward queueing per docs/ADDITIONAL_FEATURES.md #3.
 * If the peer is out of range when a message is sent, it queues here and is
 * flushed automatically once BluetoothTransport reports a reconnect.
 *
 * Also provides sequence-ID dedup for mesh relay per docs/NETWORK_PROTOCOL.md §5 —
 * a relay node should call [hasForwarded] before re-broadcasting a RELAY packet.
 */
class MessageQueue {
    private val pending = ConcurrentLinkedQueue<ItantraPacket>()
    private val seenSequenceIds = mutableSetOf<Long>() // dedup for mesh relay
    private val sequenceCounter = AtomicLong(0)

    fun nextSequenceId(): Long = sequenceCounter.incrementAndGet()

    fun enqueue(packet: ItantraPacket) {
        pending.add(packet)
    }

    /** Call when the transport reports connected == true. Drains and returns encoded frames. */
    fun drainAsFrames(): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var packet = pending.poll()
        while (packet != null) {
            frames.add(ProtocolCodec.encode(packet))
            packet = pending.poll()
        }
        return frames
    }

    fun pendingCount(): Int = pending.size

    /** For mesh relay dedup — has this sequence ID already been forwarded by this node? */
    fun hasForwarded(sequenceId: Long): Boolean = !seenSequenceIds.add(sequenceId)

    fun clear() {
        pending.clear()
        seenSequenceIds.clear()
    }
}
