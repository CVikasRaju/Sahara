package com.itantra.audio

import com.itantra.network.Language

/**
 * Audio ingestion, VAD, and STT configuration constants and data models.
 * Strictly adheres to the 16kHz mono 16-bit PCM specification and
 * 30ms frame streaming architecture (docs/ARCHITECTURE.md §2.1 & docs/ML_PIPELINE.md).
 */
object AudioConfig {
    /** Target sample rate: 16 kHz mono linear PCM. */
    const val SAMPLE_RATE_HZ = 16000

    /** Frame duration in milliseconds (30ms per frame). */
    const val FRAME_DURATION_MS = 30

    /** 30ms @ 16kHz = 480 samples per frame. */
    const val SAMPLES_PER_FRAME = 480

    /** 16-bit PCM = 2 bytes per sample. */
    const val BYTES_PER_SAMPLE = 2

    /** 480 samples * 2 bytes = 960 bytes per 30ms frame. */
    const val FRAME_SIZE_BYTES = SAMPLES_PER_FRAME * BYTES_PER_SAMPLE

    /**
     * Silence threshold for utterance boundary detection.
     * 600ms continuous silence ÷ 30ms per frame = 20 frames.
     */
    const val SILENCE_THRESHOLD_FRAMES = 20

    /** Fallback energy threshold for simulated or heuristic speech detection. */
    const val FALLBACK_ENERGY_THRESHOLD = 500

    /** Default Silero VAD model path in assets. */
    const val DEFAULT_VAD_ASSET_PATH = "models/vad/silero_vad.onnx"

    /** Base asset directory for language-specific STT models. */
    const val DEFAULT_STT_ASSET_BASE_DIR = "models/stt"
}

/**
 * Configuration parameters for Silero Voice Activity Detection (VAD).
 */
data class VadConfig(
    val modelAssetPath: String = AudioConfig.DEFAULT_VAD_ASSET_PATH,
    val threshold: Float = 0.5f,
    val minSilenceDuration: Float = 0.5f,  // 500ms
    val minSpeechDuration: Float = 0.25f,  // 250ms
    val windowSize: Int = 512,
    val maxSpeechDuration: Float = 30.0f,  // 30 seconds max utterance
    val silenceThresholdFrames: Int = AudioConfig.SILENCE_THRESHOLD_FRAMES,
    val fallbackEnergyThreshold: Int = AudioConfig.FALLBACK_ENERGY_THRESHOLD,
)

/**
 * Configuration parameters for IndicConformer / Sherpa-ONNX Speech-to-Text.
 */
data class SttConfig(
    val assetBaseDir: String = AudioConfig.DEFAULT_STT_ASSET_BASE_DIR,
    val numThreads: Int = 2,
    val debug: Boolean = false,
    val defaultConfidence: Float = 0.85f,
)

/**
 * Result of a single STT transcription attempt.
 */
data class SttResult(
    val text: String,
    val confidence: Float,
    val language: Language = Language.HINDI,
)

/**
 * Result of feeding a single 30ms PCM frame into the VAD detector.
 */
data class VadFrameResult(
    val isSpeech: Boolean,
    val isUtteranceComplete: Boolean,
    val completedUtterance: ShortArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as VadFrameResult

        if (isSpeech != other.isSpeech) return false
        if (isUtteranceComplete != other.isUtteranceComplete) return false
        if (completedUtterance != null) {
            if (other.completedUtterance == null) return false
            if (!completedUtterance.contentEquals(other.completedUtterance)) return false
        } else if (other.completedUtterance != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = isSpeech.hashCode()
        result = 31 * result + isUtteranceComplete.hashCode()
        result = 31 * result + (completedUtterance?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * High-level transcription event emitted when an utterance is successfully recognized.
 */
data class TranscriptionEvent(
    val text: String,
    val language: Language,
    val confidence: Float,
    val sampleCount: Int = 0,
    val durationMs: Long = 0L,
    val timestampMillis: Long = System.currentTimeMillis(),
)

/**
 * Reactive states for the Audio-VAD-STT pipeline.
 */
sealed class AudioPipelineState {
    data object Idle : AudioPipelineState()
    data object Capturing : AudioPipelineState()
    data object SpeechDetected : AudioPipelineState()
    data class Transcribing(val language: Language) : AudioPipelineState()
    data class UtteranceReady(val event: TranscriptionEvent) : AudioPipelineState()
    data class Error(val message: String, val cause: Throwable? = null) : AudioPipelineState()
}
