package com.itantra.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/**
 * Interface representing a source of streaming 16-bit PCM audio frames.
 */
interface AudioCaptureSource {
    /** True if audio capture is actively running. */
    val isCapturing: StateFlow<Boolean>

    /**
     * Starts audio capture and returns a cold Flow emitting 30ms PCM frames
     * (480 samples @ 16kHz mono). The flow runs until cancelled or [stopCapture] is called.
     */
    fun startCapture(): Flow<ShortArray>

    /** Stops active audio capture. */
    fun stopCapture()

    /** Releases any underlying hardware or buffer resources. */
    fun release()
}

/**
 * Real microphone capture implementation backed by Android's [AudioRecord] API.
 * Configured strictly for 16 kHz Mono 16-bit linear PCM with 30ms (480 samples) chunking.
 */
class AudioRecordCapture(
    private val sampleRate: Int = AudioConfig.SAMPLE_RATE_HZ,
    private val samplesPerFrame: Int = AudioConfig.SAMPLES_PER_FRAME,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AudioCaptureSource {

    private val tag = "AudioRecordCapture"

    private val _isCapturing = MutableStateFlow(false)
    override val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    @Volatile
    private var isStopRequested = false

    @SuppressLint("MissingPermission")
    override fun startCapture(): Flow<ShortArray> = flow {
        isStopRequested = false
        _isCapturing.value = true

        var audioRecord: AudioRecord? = null
        try {
            val minBufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )

            // Ensure buffer can hold at least 2 frames (60ms) of audio
            val bufferSizeInBytes = maxOf(minBufferSize, samplesPerFrame * AudioConfig.BYTES_PER_SAMPLE * 2)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeInBytes,
            )

            if (audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                audioRecord.startRecording()
                Log.i(tag, "AudioRecord started at ${sampleRate}Hz Mono 16-bit")

                val frameBuffer = ShortArray(samplesPerFrame)

                while (currentCoroutineContext().isActive && !isStopRequested) {
                    val samplesRead = audioRecord.read(frameBuffer, 0, samplesPerFrame)
                    if (samplesRead > 0) {
                        val frameToEmit = if (samplesRead == samplesPerFrame) {
                            frameBuffer.copyOf()
                        } else {
                            frameBuffer.copyOf(samplesRead)
                        }
                        emit(frameToEmit)
                    } else if (samplesRead < 0) {
                        Log.e(tag, "AudioRecord read error code: $samplesRead")
                        break
                    }
                }
            } else {
                Log.w(tag, "AudioRecord uninitialized (permission missing or headless test). Emitting simulated silence.")
                while (currentCoroutineContext().isActive && !isStopRequested) {
                    delay(AudioConfig.FRAME_DURATION_MS.toLong())
                    emit(ShortArray(samplesPerFrame))
                }
            }
        } catch (e: SecurityException) {
            Log.e(tag, "SecurityException: RECORD_AUDIO permission not granted: ${e.message}")
        } catch (e: Exception) {
            Log.e(tag, "Exception during microphone capture: ${e.message}", e)
        } finally {
            _isCapturing.value = false
            try {
                if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop()
                }
                audioRecord?.release()
            } catch (e: Exception) {
                Log.w(tag, "Error releasing AudioRecord: ${e.message}")
            }
            Log.i(tag, "AudioRecord stopped and released")
        }
    }.flowOn(ioDispatcher)

    override fun stopCapture() {
        isStopRequested = true
        _isCapturing.value = false
    }

    override fun release() {
        stopCapture()
    }
}

/**
 * Simulated / Mock audio source for automated testing, offline benchmarks,
 * and deterministic playback of synthetic PCM waveforms.
 */
class SimulatedAudioCapture(
    private val samplesPerFrame: Int = AudioConfig.SAMPLES_PER_FRAME,
) : AudioCaptureSource {

    private val _isCapturing = MutableStateFlow(false)
    override val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val queuedFrames = mutableListOf<ShortArray>()

    @Volatile
    private var isStopRequested = false

    fun enqueueFrames(frames: List<ShortArray>) {
        synchronized(queuedFrames) {
            queuedFrames.addAll(frames)
        }
    }

    fun enqueueSyntheticSpeech(frameCount: Int, amplitude: Short = 3000) {
        val frames = (0 until frameCount).map {
            ShortArray(samplesPerFrame) { i ->
                if (i % 2 == 0) amplitude else (-amplitude).toShort()
            }
        }
        enqueueFrames(frames)
    }

    fun enqueueSilence(frameCount: Int) {
        val frames = (0 until frameCount).map { ShortArray(samplesPerFrame) }
        enqueueFrames(frames)
    }

    override fun startCapture(): Flow<ShortArray> = flow {
        isStopRequested = false
        _isCapturing.value = true

        try {
            while (currentCoroutineContext().isActive && !isStopRequested) {
                val frame = synchronized(queuedFrames) {
                    if (queuedFrames.isNotEmpty()) queuedFrames.removeAt(0) else null
                }
                if (frame != null) {
                    emit(frame)
                } else {
                    // Default to silent frame if queue is empty
                    delay(AudioConfig.FRAME_DURATION_MS.toLong())
                    emit(ShortArray(samplesPerFrame))
                }
            }
        } finally {
            _isCapturing.value = false
        }
    }

    override fun stopCapture() {
        isStopRequested = true
        _isCapturing.value = false
    }

    override fun release() {
        stopCapture()
        synchronized(queuedFrames) {
            queuedFrames.clear()
        }
    }
}
