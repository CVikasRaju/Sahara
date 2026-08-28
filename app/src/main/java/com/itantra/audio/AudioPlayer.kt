package com.itantra.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log

/**
 * Low-level AudioTrack wrapper handling normal and emergency playback routing.
 *
 * This class encapsulates the Android AudioTrack lifecycle, audio focus
 * negotiation, and the emergency override path described in
 * docs/ARCHITECTURE.md §2.3:
 *
 *   Normal:    STREAM_MUSIC, system volume, no special focus
 *   Emergency: STREAM_ALARM, max volume, AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
 *              bypass ringer/DND (MODIFY_AUDIO_SETTINGS permission required)
 *
 * Thread-safety: [play] is blocking and NOT thread-safe — callers must
 * serialize access (e.g. via a single-threaded coroutine dispatcher).
 * [cancel] is safe to call from any thread.
 */
class AudioPlayer(private val context: Context) {

    companion object {
        private const val TAG = "AudioPlayer"
        const val SAMPLE_RATE_HZ = 16000
    }

    @Volatile
    private var currentTrack: AudioTrack? = null

    @Volatile
    private var cancelled = false

    /**
     * Play 16-bit mono PCM audio at 16kHz through the appropriate stream.
     *
     * This call is **blocking** — it returns only after all samples have been
     * written to AudioTrack and playback has completed (or been cancelled/errored).
     *
     * @param pcmAudio 16-bit mono PCM samples at 16kHz.
     * @param isEmergency If true, routes to STREAM_ALARM at max volume with
     *   exclusive audio focus per ARCHITECTURE.md §2.3.
     * @throws AudioPlaybackException on AudioTrack init or write failure.
     */
    fun play(pcmAudio: ShortArray, isEmergency: Boolean) {
        if (pcmAudio.isEmpty()) {
            Log.w(TAG, "Empty PCM buffer, skipping playback")
            return
        }

        cancelled = false
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        var focusRequest: AudioFocusRequest? = null
        var previousAlarmVolume: Int? = null

        val usage = if (isEmergency) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA

        val attributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE_HZ)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val minBufSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufSize <= 0) {
            throw AudioPlaybackException("AudioTrack.getMinBufferSize returned $minBufSize — unsupported audio config")
        }

        val bufferSizeInBytes = maxOf(minBufSize, pcmAudio.size * 2) // 2 bytes per Short sample

        // ── Emergency: acquire exclusive focus + force max alarm volume ────
        if (isEmergency && audioManager != null) {
            try {
                // Save current alarm volume to restore later
                previousAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)

                // Force alarm stream to maximum
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                audioManager.setStreamVolume(
                    AudioManager.STREAM_ALARM,
                    maxVolume,
                    0 // no UI flag — silent set
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    focusRequest = AudioFocusRequest.Builder(
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                    )
                        .setAudioAttributes(attributes)
                        .setAcceptsDelayedFocusGain(false)
                        .build()
                    audioManager.requestAudioFocus(focusRequest)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.requestAudioFocus(
                        null,
                        AudioManager.STREAM_ALARM,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                    )
                }
                Log.d(TAG, "Emergency: acquired exclusive audio focus, alarm volume set to max ($maxVolume)")
            } catch (e: SecurityException) {
                Log.w(TAG, "SecurityException setting alarm volume — missing MODIFY_AUDIO_SETTINGS? ${e.message}")
            }
        }

        // ── Create AudioTrack, play, and clean up ─────────────────────────
        var track: AudioTrack? = null
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSizeInBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                throw AudioPlaybackException("AudioTrack failed to initialize (state=${track.state})")
            }

            currentTrack = track
            track.play()

            // Write in chunks to allow cancellation between writes
            val chunkSize = minOf(4096, pcmAudio.size) // ~128ms chunks at 16kHz mono
            var offset = 0
            while (offset < pcmAudio.size && !cancelled) {
                val remaining = pcmAudio.size - offset
                val toWrite = minOf(chunkSize, remaining)
                val written = track.write(pcmAudio, offset, toWrite)
                if (written < 0) {
                    throw AudioPlaybackException("AudioTrack.write returned error code: $written")
                }
                offset += written
            }

            if (!cancelled) {
                // Drain: wait for the last buffer to actually finish playing
                // AudioTrack.stop() flushes remaining buffered audio
                track.stop()
            }
        } catch (e: AudioPlaybackException) {
            throw e
        } catch (e: Exception) {
            throw AudioPlaybackException("AudioTrack playback failed: ${e.message}", e)
        } finally {
            currentTrack = null

            // Release AudioTrack
            try {
                track?.release()
            } catch (_: Exception) {}

            // ── Emergency: restore audio state ────────────────────────────
            if (isEmergency && audioManager != null) {
                // Restore previous alarm volume
                previousAlarmVolume?.let { prevVol ->
                    try {
                        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, prevVol, 0)
                    } catch (_: SecurityException) {}
                }

                // Release audio focus
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                    audioManager.abandonAudioFocusRequest(focusRequest)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.abandonAudioFocus(null)
                }
                Log.d(TAG, "Emergency: released audio focus, restored alarm volume")
            }
        }
    }

    /**
     * Cancel any in-progress playback. Safe to call from any thread.
     *
     * The currently playing [play] call will stop writing new chunks and return
     * early. The AudioTrack is stopped and released.
     */
    fun cancel() {
        cancelled = true
        try {
            currentTrack?.stop()
        } catch (_: IllegalStateException) {
            // Already stopped or not initialized
        }
    }

    /**
     * Release all resources. Call when the player is no longer needed.
     */
    fun release() {
        cancel()
        try {
            currentTrack?.release()
        } catch (_: Exception) {}
        currentTrack = null
    }
}

/**
 * Exception thrown when AudioTrack initialization or playback fails.
 */
class AudioPlaybackException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
