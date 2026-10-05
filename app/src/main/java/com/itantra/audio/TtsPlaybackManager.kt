package com.itantra.audio

import android.content.Context
import android.util.Log
import com.itantra.ml.TtsEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * High-level manager that orchestrates TTS synthesis → AudioTrack playback.
 *
 * This is the primary public API for Phase 6 — other modules (e.g. TransceiverService)
 * interact with TTS playback through this class rather than directly touching
 * [TtsEngine] or [AudioPlayer].
 *
 * ## Usage
 * ```kotlin
 * val manager = TtsPlaybackManager(context, ttsEngine)
 *
 * // Observe playback state reactively
 * manager.playbackState.collect { state -> updateUi(state) }
 *
 * // Play text
 * manager.play(PlaybackRequest(text = "Hello world"))
 *
 * // Play emergency alert (STREAM_ALARM, max volume, exclusive focus)
 * manager.play(PlaybackRequest(text = "Emergency!", priority = PlaybackPriority.EMERGENCY))
 *
 * // Cancel in-flight playback
 * manager.cancelCurrent()
 *
 * // Release all resources
 * manager.release()
 * ```
 *
 * ## Threading
 * - [play] is safe to call from any thread — it dispatches to a background
 *   coroutine on [Dispatchers.IO].
 * - Concurrent play requests are serialized via a [Mutex] — a new request
 *   cancels any in-flight playback before starting.
 * - [playbackState] is a [StateFlow] safe to collect from any thread.
 *
 * ## Integration Point
 * During the final integration merge, wire this into TransceiverService's
 * receive path to replace the inline `playAudio()` method:
 * ```kotlin
 * // In TransceiverService.onFrameReceived():
 * val priority = if (isEmergency) PlaybackPriority.EMERGENCY else PlaybackPriority.NORMAL
 * ttsPlaybackManager.play(PlaybackRequest(text = packet.text, priority = priority))
 * ```
 */
class TtsPlaybackManager(
    context: Context,
    private val ttsEngine: TtsEngine,
) {
    companion object {
        private const val TAG = "TtsPlaybackManager"
    }

    private val audioPlayer = AudioPlayer(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playbackMutex = Mutex()

    private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)

    /**
     * Observable playback state. Collect this in a ViewModel or service to
     * react to synthesis/playback lifecycle events.
     */
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private var currentJob: Job? = null
    private var currentRequestId: Long? = null

    /**
     * Synthesize [request]'s text via the TTS engine and play the resulting
     * audio through the appropriate stream.
     *
     * If a previous playback is in progress, it is cancelled before the new
     * one begins. This ensures PTT radio semantics where the latest incoming
     * message always takes priority.
     *
     * The method returns immediately — synthesis and playback happen
     * asynchronously. Observe [playbackState] to track progress.
     *
     * @param request The text, priority, and request ID for this playback.
     */
    fun play(request: PlaybackRequest) {
        currentJob?.cancel()
        currentJob = scope.launch {
            playbackMutex.withLock {
                executePlayback(request)
            }
        }
    }

    /**
     * Blocking variant for callers that need to wait for playback to complete
     * (e.g. a coroutine in TransceiverService that must transition state after
     * playback finishes).
     *
     * @param request The text, priority, and request ID for this playback.
     */
    suspend fun playAndAwait(request: PlaybackRequest) {
        // Cancel any existing playback
        currentJob?.cancel()
        currentJob?.join()

        playbackMutex.withLock {
            executePlayback(request)
        }
    }

    private fun executePlayback(request: PlaybackRequest) {
        currentRequestId = request.requestId
        val isEmergency = request.priority == PlaybackPriority.EMERGENCY

        // ── Phase 1: Synthesize ───────────────────────────────────────────
        _playbackState.value = PlaybackState.Synthesizing(request.requestId, request.text)
        Log.d(TAG, "Synthesizing text (${request.text.length} chars, priority=${request.priority})")

        val pcmAudio: ShortArray
        try {
            pcmAudio = ttsEngine.synthesize(request.text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = "TTS synthesis failed: ${e.message}"
            Log.e(TAG, msg, e)
            _playbackState.value = PlaybackState.Error(request.requestId, msg)
            return
        }

        if (pcmAudio.isEmpty()) {
            Log.w(TAG, "TTS returned empty audio for '${request.text.take(40)}...'")
            _playbackState.value = PlaybackState.Error(request.requestId, "TTS returned empty audio")
            return
        }

        Log.d(TAG, "Synthesis complete: ${pcmAudio.size} samples (${pcmAudio.size / 16000.0}s)")

        // ── Phase 2: Play ─────────────────────────────────────────────────
        _playbackState.value = PlaybackState.Playing(request.requestId, isEmergency)

        try {
            audioPlayer.play(pcmAudio, isEmergency)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AudioPlaybackException) {
            val msg = "Audio playback failed: ${e.message}"
            Log.e(TAG, msg, e)
            _playbackState.value = PlaybackState.Error(request.requestId, msg)
            return
        } catch (e: Exception) {
            val msg = "Unexpected playback error: ${e.message}"
            Log.e(TAG, msg, e)
            _playbackState.value = PlaybackState.Error(request.requestId, msg)
            return
        }

        // ── Done ──────────────────────────────────────────────────────────
        _playbackState.value = PlaybackState.Completed(request.requestId)
        Log.d(TAG, "Playback completed for request ${request.requestId}")
    }

    /**
     * Cancel any in-progress synthesis or playback.
     *
     * The [playbackState] will NOT transition to [PlaybackState.Completed] —
     * it remains at whichever state it was in when cancelled, and the next
     * [play] call will reset it.
     */
    fun cancelCurrent() {
        Log.d(TAG, "Cancelling current playback (requestId=${currentRequestId})")
        currentJob?.cancel()
        audioPlayer.cancel()
        _playbackState.value = PlaybackState.Idle
    }

    /**
     * Whether a playback operation is currently in progress.
     */
    val isPlaying: Boolean
        get() {
            val state = _playbackState.value
            return state is PlaybackState.Synthesizing || state is PlaybackState.Playing
        }

    /**
     * Release all resources — AudioTrack, coroutine scope.
     * Call this when the owning service or activity is destroyed.
     *
     * After [release], this manager instance must not be reused.
     */
    fun release() {
        Log.d(TAG, "Releasing TtsPlaybackManager resources")
        cancelCurrent()
        audioPlayer.release()
        scope.cancel()
    }
}
