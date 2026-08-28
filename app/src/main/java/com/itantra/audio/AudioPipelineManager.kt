package com.itantra.audio

import android.content.Context
import android.util.Log
import com.itantra.network.Language
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Main manager and orchestration facade for Phase 5:
 * Coordinates [AudioRecordCapture] (or custom [AudioCaptureSource]),
 * [SileroVadDetector], and [IndicConformerSttManager].
 *
 * Exposes the transcribed text stream via the reactive [transcribedText] StateFlow<String>,
 * along with lifecycle states, speech activity flags, and event callbacks.
 */
class AudioPipelineManager(
    context: Context? = null,
    val captureSource: AudioCaptureSource = AudioRecordCapture(),
    val vadDetector: VadDetector = SileroVadDetector(context),
    val sttManager: SttManager = IndicConformerSttManager(context),
    private val externalScope: CoroutineScope? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {

    private val tag = "AudioPipelineManager"

    private val managerScope = externalScope ?: CoroutineScope(SupervisorJob() + dispatcher)
    private var pipelineJob: Job? = null

    // ── Public Reactive StateFlows ─────────────────────────────────────────

    /**
     * Emits the latest transcribed speech string.
     * Updates automatically whenever an utterance boundary is reached and decoded.
     */
    private val _transcribedText = MutableStateFlow("")
    val transcribedText: StateFlow<String> = _transcribedText.asStateFlow()

    /**
     * Rich event StateFlow containing recognized text, confidence score,
     * language, sample count, and timestamp.
     */
    private val _latestTranscription = MutableStateFlow<TranscriptionEvent?>(null)
    val latestTranscription: StateFlow<TranscriptionEvent?> = _latestTranscription.asStateFlow()

    /** Comprehensive pipeline state machine. */
    private val _pipelineState = MutableStateFlow<AudioPipelineState>(AudioPipelineState.Idle)
    val pipelineState: StateFlow<AudioPipelineState> = _pipelineState.asStateFlow()

    /** True if microphone capture and VAD analysis are actively running. */
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    /** Exposes active VAD speech detection state. */
    val isSpeechDetected: StateFlow<Boolean> = vadDetector.isSpeechActive

    /** Current target language for STT recognition. */
    val currentLanguage: StateFlow<Language> = sttManager.currentLanguage

    // ── Listener callbacks ─────────────────────────────────────────────────

    private val transcriptionListeners = mutableListOf<(TranscriptionEvent) -> Unit>()

    init {
        vadDetector.setUtteranceListener { utteranceAudio ->
            processUtterance(utteranceAudio)
        }
    }

    /**
     * Starts microphone capture and begins streaming PCM frames through
     * the Silero VAD and IndicConformer STT pipeline.
     */
    fun startListening() {
        if (_isListening.value) return
        _isListening.value = true
        _pipelineState.value = AudioPipelineState.Capturing

        pipelineJob?.cancel()
        pipelineJob = managerScope.launch(dispatcher) {
            try {
                captureSource.startCapture().collect { frame ->
                    feedAudioFrame(frame)
                }
            } catch (e: Exception) {
                Log.e(tag, "Audio pipeline stream error: ${e.message}", e)
                _pipelineState.value = AudioPipelineState.Error("Audio pipeline error", e)
            } finally {
                _isListening.value = false
                if (_pipelineState.value !is AudioPipelineState.Error) {
                    _pipelineState.value = AudioPipelineState.Idle
                }
            }
        }
    }

    /**
     * Stops audio capture and flushes any pending speech in the VAD buffer.
     */
    fun stopListening() {
        if (!_isListening.value) return
        captureSource.stopCapture()
        pipelineJob?.cancel()
        pipelineJob = null

        // Flush any lingering speech utterance
        val flushResult = vadDetector.processFrame(ShortArray(0))
        if (flushResult.completedUtterance != null) {
            processUtterance(flushResult.completedUtterance)
        }

        _isListening.value = false
        _pipelineState.value = AudioPipelineState.Idle
    }

    /**
     * Ingests a single 16-bit PCM frame (30ms = 480 samples @ 16kHz) directly.
     * Useful for external audio streams, simulated inputs, and unit tests.
     */
    fun feedAudioFrame(pcmFrame: ShortArray) {
        val result = vadDetector.processFrame(pcmFrame)

        if (result.isSpeech && _pipelineState.value is AudioPipelineState.Capturing) {
            _pipelineState.value = AudioPipelineState.SpeechDetected
        }

        if (result.isUtteranceComplete && result.completedUtterance != null) {
            processUtterance(result.completedUtterance)
        }
    }

    /**
     * Decodes a complete audio utterance with IndicConformer STT and emits results.
     */
    private fun processUtterance(utteranceAudio: ShortArray) {
        val activeLang = sttManager.currentLanguage.value
        _pipelineState.value = AudioPipelineState.Transcribing(activeLang)

        val result = sttManager.transcribe(utteranceAudio)
        if (result != null && result.text.isNotBlank()) {
            val durationMs = (utteranceAudio.size * 1000L) / AudioConfig.SAMPLE_RATE_HZ
            val event = TranscriptionEvent(
                text = result.text,
                language = result.language,
                confidence = result.confidence,
                sampleCount = utteranceAudio.size,
                durationMs = durationMs,
                timestampMillis = System.currentTimeMillis(),
            )

            _transcribedText.value = result.text
            _latestTranscription.value = event
            _pipelineState.value = AudioPipelineState.UtteranceReady(event)

            synchronized(transcriptionListeners) {
                transcriptionListeners.forEach { listener ->
                    try {
                        listener(event)
                    } catch (e: Exception) {
                        Log.w(tag, "Error in transcription listener: ${e.message}")
                    }
                }
            }
        }

        // Return state to Capturing if still running, or Idle
        if (_isListening.value) {
            _pipelineState.value = AudioPipelineState.Capturing
        } else {
            _pipelineState.value = AudioPipelineState.Idle
        }
    }

    /**
     * Switches the active language for STT recognition.
     * Adheres to the Single-Language Resident Policy.
     */
    fun switchLanguage(language: Language) {
        sttManager.loadLanguage(language)
    }

    /**
     * Registers a callback listener to receive [TranscriptionEvent] emissions.
     */
    fun addTranscriptionListener(listener: (TranscriptionEvent) -> Unit) {
        synchronized(transcriptionListeners) {
            transcriptionListeners.add(listener)
        }
    }

    /**
     * Removes a previously registered transcription listener.
     */
    fun removeTranscriptionListener(listener: (TranscriptionEvent) -> Unit) {
        synchronized(transcriptionListeners) {
            transcriptionListeners.remove(listener)
        }
    }

    /**
     * Resets internal states and clears buffers.
     */
    fun reset() {
        vadDetector.reset()
        _transcribedText.value = ""
        _latestTranscription.value = null
        _pipelineState.value = AudioPipelineState.Idle
    }

    /**
     * Releases all audio capture, VAD, STT, and coroutine resources.
     */
    fun release() {
        stopListening()
        captureSource.release()
        vadDetector.release()
        sttManager.release()
        synchronized(transcriptionListeners) {
            transcriptionListeners.clear()
        }
        if (externalScope == null) {
            managerScope.cancel()
        }
    }

    override fun close() = release()
}
