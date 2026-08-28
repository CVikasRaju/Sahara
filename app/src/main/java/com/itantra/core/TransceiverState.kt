package com.itantra.core

/**
 * State machine per docs/ARCHITECTURE.md §3.
 *
 *   IDLE --press PTT--> RECORDING
 *   RECORDING --release or VAD silence--> PROCESSING (STT running)
 *   PROCESSING --transcription ready--> TRANSMITTING
 *   TRANSMITTING --ack or timeout--> IDLE
 *
 * "Transceiver Mode" off is a distinct top-level state, not just a UI toggle —
 * see docs/ARCHITECTURE.md §2 for why it must actually disable the P2P service
 * and mic/VAD loop, not just hide the button.
 */
sealed class TransceiverState {
    data object TransceiverOff : TransceiverState()
    data object Idle : TransceiverState()
    data object Recording : TransceiverState()
    data class Processing(val languageBeingTranscribed: String) : TransceiverState()
    data class Transmitting(val text: String) : TransceiverState()
    data class ReceivingPlayback(val text: String, val isEmergency: Boolean) : TransceiverState()
    data class ConnectionLost(val queuedMessageCount: Int) : TransceiverState()
}

/** Simple explicit transition validator — reject illegal transitions early rather than
 *  discover a broken state machine mid-demo. Extend as you add features. */
object TransceiverStateMachine {
    fun canTransition(from: TransceiverState, to: TransceiverState): Boolean = when (from) {
        is TransceiverState.TransceiverOff -> to is TransceiverState.Idle
        is TransceiverState.Idle -> to is TransceiverState.Recording ||
            to is TransceiverState.ReceivingPlayback ||
            to is TransceiverState.TransceiverOff ||
            to is TransceiverState.ConnectionLost
        is TransceiverState.Recording -> to is TransceiverState.Processing ||
            to is TransceiverState.Idle // cancel mid-recording
        is TransceiverState.Processing -> to is TransceiverState.Transmitting ||
            to is TransceiverState.Idle // STT failed / low confidence, see ADDITIONAL_FEATURES.md #6
        is TransceiverState.Transmitting -> to is TransceiverState.Idle ||
            to is TransceiverState.ConnectionLost
        is TransceiverState.ReceivingPlayback -> to is TransceiverState.Idle
        is TransceiverState.ConnectionLost -> to is TransceiverState.Idle
    }
}
