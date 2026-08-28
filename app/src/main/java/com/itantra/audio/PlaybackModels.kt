package com.itantra.audio

/**
 * Describes the priority level for audio playback routing.
 *
 * Per docs/ARCHITECTURE.md §2.3:
 *   - [NORMAL]: Route to `STREAM_MUSIC`, respect system volume.
 *   - [EMERGENCY]: Route to `STREAM_ALARM`, force max volume, request
 *     `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`, bypass ringer/DND.
 */
enum class PlaybackPriority {
    NORMAL,
    EMERGENCY,
}

/**
 * Request to synthesize text and play the resulting audio.
 *
 * @param text The text to synthesize via the TTS engine.
 * @param priority Playback routing priority (normal media or emergency alarm).
 * @param requestId Unique identifier for this playback request, used to track
 *   state transitions and cancel in-flight requests. Defaults to the current
 *   timestamp in millis.
 */
data class PlaybackRequest(
    val text: String,
    val priority: PlaybackPriority = PlaybackPriority.NORMAL,
    val requestId: Long = System.currentTimeMillis(),
)

/**
 * Observable state of the [TtsPlaybackManager].
 *
 * Consumers (e.g. TransceiverService, ViewModel) can collect the
 * [TtsPlaybackManager.playbackState] StateFlow to react to state changes.
 */
sealed class PlaybackState {
    /** No playback activity. */
    data object Idle : PlaybackState()

    /** TTS engine is synthesizing audio from text. */
    data class Synthesizing(val requestId: Long, val text: String) : PlaybackState()

    /** AudioTrack is actively playing synthesized audio. */
    data class Playing(val requestId: Long, val isEmergency: Boolean) : PlaybackState()

    /** Playback completed successfully. */
    data class Completed(val requestId: Long) : PlaybackState()

    /** An error occurred during synthesis or playback. */
    data class Error(val requestId: Long, val message: String) : PlaybackState()
}
