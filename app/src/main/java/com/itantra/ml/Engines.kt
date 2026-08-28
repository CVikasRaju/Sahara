package com.itantra.ml

import com.itantra.network.Language

/**
 * Abstraction layer for the three neural engines described in docs/ML_PIPELINE.md.
 * The Mock* implementations below let the rest of the app (UI, networking, state
 * machine) be built and tested WITHOUT real model weights present — swap them for
 * Sherpa*Engine implementations once you've downloaded and quantized real models
 * per docs/ML_PIPELINE.md and scripts/fetch_models.py.
 *
 * DO NOT ship the Mock engines in a release build — they exist for development only.
 */

interface VadEngine {
    /** Feed a 30ms PCM frame (480 samples @ 16kHz). Returns true if speech detected. */
    fun isSpeech(pcmFrame: ShortArray): Boolean

    /** Called when 600ms+ of continuous silence follows speech — an utterance boundary. */
    fun onUtteranceComplete(callback: (ShortArray) -> Unit)
}

interface SttEngine {
    /** Must be called before transcribe() when the active language changes. */
    fun loadLanguage(language: Language)

    fun unloadCurrentLanguage()

    /**
     * Transcribes a chunk of 16kHz mono PCM audio to text.
     * Returns null if confidence is too low (see docs/ADDITIONAL_FEATURES.md #6,
     * adaptive fallback to raw audio — implement that branch at the call site).
     */
    fun transcribe(pcmAudio: ShortArray): SttResult?
}

data class SttResult(
    val text: String,
    val confidence: Float, // 0.0 - 1.0
)

interface TtsEngine {
    fun loadLanguage(language: Language)

    fun unloadCurrentLanguage()

    /** Synthesizes text to 16-bit PCM audio for playback. */
    fun synthesize(text: String): ShortArray
}

// ---------------------------------------------------------------------------
// Mock implementations — no real inference, deterministic stand-ins so the
// app builds and the UI/network flow can be exercised on day one.
// ---------------------------------------------------------------------------

class MockVadEngine : VadEngine {
    private var onComplete: ((ShortArray) -> Unit)? = null
    private val buffer = mutableListOf<Short>()
    private var silentFrameCount = 0
    private val silenceThresholdFrames = 20 // ~600ms at 30ms/frame

    override fun isSpeech(pcmFrame: ShortArray): Boolean {
        val amplitude = pcmFrame.map { kotlin.math.abs(it.toInt()) }.average()
        val speech = amplitude > 500 // crude energy threshold — NOT a real VAD
        if (speech) {
            buffer.addAll(pcmFrame.toList())
            silentFrameCount = 0
        } else {
            silentFrameCount++
            if (silentFrameCount >= silenceThresholdFrames && buffer.isNotEmpty()) {
                onComplete?.invoke(buffer.toShortArray())
                buffer.clear()
            }
        }
        return speech
    }

    override fun onUtteranceComplete(callback: (ShortArray) -> Unit) {
        onComplete = callback
    }
}

class MockSttEngine : SttEngine {
    private var currentLanguage: Language? = null

    override fun loadLanguage(language: Language) {
        currentLanguage = language
    }

    override fun unloadCurrentLanguage() {
        currentLanguage = null
    }

    override fun transcribe(pcmAudio: ShortArray): SttResult {
        // Deterministic placeholder so UI/network flow is testable end-to-end
        // without a real model. Replace with a real Sherpa-ONNX STT call.
        val lang = currentLanguage?.code ?: "unknown"
        return SttResult(text = "[mock transcription — $lang, ${pcmAudio.size} samples]", confidence = 0.99f)
    }
}

class MockTtsEngine : TtsEngine {
    private var currentLanguage: Language? = null

    override fun loadLanguage(language: Language) {
        currentLanguage = language
    }

    override fun unloadCurrentLanguage() {
        currentLanguage = null
    }

    override fun synthesize(text: String): ShortArray {
        // Returns 0.5s of silence as a placeholder PCM buffer (16kHz mono).
        // Replace with a real Sherpa-ONNX VITS TTS call.
        return ShortArray(8000)
    }
}

// ---------------------------------------------------------------------------
// Sherpa-ONNX implementations — real neural inference engines backed by
// on-device ONNX models. Require sherpa-onnx Android AAR dependency and
// model files placed under app/src/main/assets/models/{vad,stt,tts}/<lang>/.
//
// These follow the Single-Language Resident Policy (ARCHITECTURE.md §2.2):
// only one language's STT+TTS weights are loaded at a time. Calling
// loadLanguage() releases the old language before mapping the new one.
//
// All three engines degrade gracefully if model files are absent — they
// log a warning and behave as no-ops rather than crashing.
// ---------------------------------------------------------------------------

/**
 * Real VAD engine backed by Silero VAD via sherpa-onnx.
 *
 * Model file expected at: assets/models/vad/silero_vad.onnx
 *
 * Unlike the Mock, this uses the actual Silero neural network to classify
 * speech vs. silence per 30ms frame. The sherpa-onnx Vad class manages its
 * own internal ring buffer and emits [SpeechSegment]s; we bridge that to
 * the simpler isSpeech() / onUtteranceComplete() contract expected by the
 * pipeline.
 */
class SherpaVadEngine(private val context: android.content.Context) : VadEngine {

    private val tag = "SherpaVadEngine"

    /**
     * Path to the Silero VAD model inside Android assets.
     * This is relative to the assets root — sherpa-onnx resolves it via AssetManager.
     */
    private val modelAssetPath = "models/vad/silero_vad.onnx"

    private var vad: com.k2fsa.sherpa.onnx.Vad? = null
    private var onComplete: ((ShortArray) -> Unit)? = null
    private var modelAvailable = false

    init {
        try {
            val config = com.k2fsa.sherpa.onnx.VadModelConfig(
                sileroVadModelConfig = com.k2fsa.sherpa.onnx.SileroVadModelConfig(
                    model = modelAssetPath,
                    threshold = 0.5f,
                    minSilenceDuration = 0.5f,   // 500ms — close to the 600ms pipeline threshold
                    minSpeechDuration = 0.25f,
                    windowSize = 512,
                    maxSpeechDuration = 30.0f,    // Allow long utterances in walkie-talkie use case
                ),
                sampleRate = 16000,
                numThreads = 1,
                provider = "cpu",
                debug = false,
            )
            vad = com.k2fsa.sherpa.onnx.Vad(
                assetManager = context.assets,
                config = config,
            )
            modelAvailable = true
            android.util.Log.i(tag, "Silero VAD initialized from assets/$modelAssetPath")
        } catch (e: Exception) {
            android.util.Log.w(tag, "Failed to load Silero VAD model, falling back to energy-based detection: ${e.message}")
            modelAvailable = false
        }
    }

    override fun isSpeech(pcmFrame: ShortArray): Boolean {
        val vadInstance = vad
        if (!modelAvailable || vadInstance == null) {
            // Fallback: crude energy-based detection (same as MockVadEngine)
            val amplitude = pcmFrame.map { kotlin.math.abs(it.toInt()) }.average()
            return amplitude > 500
        }

        // sherpa-onnx Vad.acceptWaveform expects FloatArray with values in [-1, 1]
        val floatSamples = FloatArray(pcmFrame.size) { pcmFrame[it].toFloat() / 32768.0f }
        vadInstance.acceptWaveform(floatSamples)

        // Check if a complete speech segment is available
        if (!vadInstance.empty()) {
            val segment = vadInstance.front()
            vadInstance.pop()

            // Convert FloatArray samples back to ShortArray for the pipeline
            val shortSamples = ShortArray(segment.samples.size) {
                (segment.samples[it] * 32768.0f).toInt().coerceIn(-32768, 32767).toShort()
            }
            onComplete?.invoke(shortSamples)
        }

        return vadInstance.isSpeechDetected()
    }

    override fun onUtteranceComplete(callback: (ShortArray) -> Unit) {
        onComplete = callback
    }

    /** Release native C++ resources. */
    fun release() {
        try {
            vad?.release()
        } catch (e: Exception) {
            android.util.Log.w(tag, "Error releasing VAD: ${e.message}")
        }
        vad = null
        modelAvailable = false
    }
}

/**
 * Real STT engine backed by sherpa-onnx OfflineRecognizer.
 *
 * Model files expected at:
 *   assets/models/stt/<lang_code>/encoder.int8.onnx
 *   assets/models/stt/<lang_code>/decoder.int8.onnx
 *   assets/models/stt/<lang_code>/tokens.txt
 *
 * Follows Single-Language Resident Policy: loadLanguage() releases the
 * previous recognizer before creating a new one.
 */
class SherpaSttEngine(private val context: android.content.Context) : SttEngine {

    private val tag = "SherpaSttEngine"
    private var recognizer: com.k2fsa.sherpa.onnx.OfflineRecognizer? = null
    private var currentLanguage: Language? = null

    override fun loadLanguage(language: Language) {
        if (language == currentLanguage && recognizer != null) return

        // Release previous language's native memory first (Single-Language Resident Policy)
        unloadCurrentLanguage()

        val langCode = language.code
        val modelBasePath = "models/stt/$langCode"

        // Verify model files exist in assets before attempting to load
        if (!assetExists(context, "$modelBasePath/tokens.txt")) {
            android.util.Log.w(tag, "STT model files not found for language '$langCode' at assets/$modelBasePath/ — transcription will return null")
            currentLanguage = language
            return
        }

        try {
            // Use Whisper-style config for IndicWhisper / IndicConformer models.
            // The exact config depends on which model family was downloaded via
            // scripts/fetch_models.py. Adjust the model config type if using
            // a different architecture (e.g., transducer for IndicConformer).
            val config = com.k2fsa.sherpa.onnx.OfflineRecognizerConfig(
                modelConfig = com.k2fsa.sherpa.onnx.OfflineModelConfig(
                    whisper = com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig(
                        encoder = "$modelBasePath/encoder.int8.onnx",
                        decoder = "$modelBasePath/decoder.int8.onnx",
                        language = langCode,
                        task = "transcribe",
                    ),
                    tokens = "$modelBasePath/tokens.txt",
                    numThreads = 2,
                    debug = false,
                ),
            )

            recognizer = com.k2fsa.sherpa.onnx.OfflineRecognizer(
                assetManager = context.assets,
                config = config,
            )
            currentLanguage = language
            android.util.Log.i(tag, "STT model loaded for language: $langCode")

            // Warm-up inference to avoid first-utterance latency spike
            // (per ARCHITECTURE.md §2.2 mismatch handling)
            warmUp()
        } catch (e: Exception) {
            android.util.Log.e(tag, "Failed to load STT model for '$langCode': ${e.message}")
            recognizer = null
            currentLanguage = language  // Still set language so we don't retry on every call
        }
    }

    override fun unloadCurrentLanguage() {
        try {
            recognizer?.release()
        } catch (e: Exception) {
            android.util.Log.w(tag, "Error releasing STT recognizer: ${e.message}")
        }
        recognizer = null
        currentLanguage = null
    }

    override fun transcribe(pcmAudio: ShortArray): SttResult? {
        val rec = recognizer ?: run {
            android.util.Log.w(tag, "No STT recognizer loaded — returning null")
            return null
        }

        return try {
            val stream = rec.createStream()

            // Convert ShortArray (16-bit PCM) to FloatArray normalized to [-1, 1]
            val floatSamples = FloatArray(pcmAudio.size) { pcmAudio[it].toFloat() / 32768.0f }
            stream.acceptWaveform(floatSamples, sampleRate = 16000)

            rec.decode(stream)

            val result = rec.getResult(stream)
            stream.release()

            if (result.text.isBlank()) {
                null
            } else {
                SttResult(
                    text = result.text.trim(),
                    confidence = 0.85f,  // sherpa-onnx doesn't expose per-utterance confidence;
                                          // use a reasonable default. TODO: compute from token-level
                                          // timestamps/scores if available in future API versions.
                )
            }
        } catch (e: Exception) {
            android.util.Log.e(tag, "STT transcription failed: ${e.message}")
            null
        }
    }

    /**
     * Warm up the recognizer with a short silent buffer to force JIT compilation
     * and model weight loading, avoiding a latency spike on the first real utterance.
     */
    private fun warmUp() {
        try {
            val silentBuffer = FloatArray(480) // 30ms of silence
            val stream = recognizer?.createStream() ?: return
            stream.acceptWaveform(silentBuffer, sampleRate = 16000)
            recognizer?.decode(stream)
            stream.release()
            android.util.Log.d(tag, "STT warm-up complete")
        } catch (e: Exception) {
            android.util.Log.w(tag, "STT warm-up failed (non-fatal): ${e.message}")
        }
    }
}

/**
 * Real TTS engine backed by sherpa-onnx OfflineTts with VITS architecture.
 *
 * Model files expected at:
 *   assets/models/tts/<lang_code>/vits-<lang_code>.int8.onnx
 *   assets/models/tts/<lang_code>/tokens.txt
 *
 * Follows Single-Language Resident Policy: loadLanguage() releases the
 * previous TTS instance before creating a new one.
 */
class SherpaTtsEngine(private val context: android.content.Context) : TtsEngine {

    private val tag = "SherpaTtsEngine"
    private var tts: com.k2fsa.sherpa.onnx.OfflineTts? = null
    private var currentLanguage: Language? = null

    override fun loadLanguage(language: Language) {
        if (language == currentLanguage && tts != null) return

        // Release previous language's native memory first (Single-Language Resident Policy)
        unloadCurrentLanguage()

        val langCode = language.code
        val modelBasePath = "models/tts/$langCode"
        val modelFile = "$modelBasePath/vits-$langCode.int8.onnx"
        val tokensFile = "$modelBasePath/tokens.txt"

        // Verify model files exist in assets before attempting to load
        if (!assetExists(context, tokensFile)) {
            android.util.Log.w(tag, "TTS model files not found for language '$langCode' at assets/$modelBasePath/ — synthesis will return silence")
            currentLanguage = language
            return
        }

        try {
            val config = com.k2fsa.sherpa.onnx.OfflineTtsConfig(
                model = com.k2fsa.sherpa.onnx.OfflineTtsModelConfig(
                    vits = com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig(
                        model = modelFile,
                        tokens = tokensFile,
                        lengthScale = 1.0f,     // Normal speaking speed
                        noiseScale = 0.667f,    // VITS default
                        noiseScaleW = 0.8f,     // VITS default
                    ),
                    numThreads = 2,
                    debug = false,
                ),
            )

            tts = com.k2fsa.sherpa.onnx.OfflineTts(
                assetManager = context.assets,
                config = config,
            )
            currentLanguage = language
            android.util.Log.i(tag, "TTS model loaded for language: $langCode")
        } catch (e: Exception) {
            android.util.Log.e(tag, "Failed to load TTS model for '$langCode': ${e.message}")
            tts = null
            currentLanguage = language  // Still set so we don't retry on every call
        }
    }

    override fun unloadCurrentLanguage() {
        try {
            tts?.release()
        } catch (e: Exception) {
            android.util.Log.w(tag, "Error releasing TTS engine: ${e.message}")
        }
        tts = null
        currentLanguage = null
    }

    override fun synthesize(text: String): ShortArray {
        val ttsInstance = tts ?: run {
            android.util.Log.w(tag, "No TTS engine loaded — returning silence")
            return ShortArray(8000)  // 0.5s silence fallback, same as MockTtsEngine
        }

        return try {
            val audio = ttsInstance.generate(
                text = text,
                sid = 0,        // Speaker ID 0 (default voice)
                speed = 1.0f,   // Normal speed
            )

            // sherpa-onnx returns FloatArray with samples in [-1, 1];
            // convert to ShortArray (16-bit PCM) for the audio pipeline.
            val samples = audio.samples
            if (samples.isEmpty()) {
                android.util.Log.w(tag, "TTS generated empty audio for text: '${text.take(50)}...'")
                return ShortArray(8000)
            }

            // The TTS output sample rate may differ from our pipeline's 16kHz.
            // sherpa-onnx VITS typically outputs at 22050Hz — resample if needed.
            val outputSampleRate = audio.sampleRate
            val pcm16 = floatToShort(samples)

            if (outputSampleRate != 16000 && outputSampleRate > 0) {
                resampleTo16kHz(pcm16, outputSampleRate)
            } else {
                pcm16
            }
        } catch (e: Exception) {
            android.util.Log.e(tag, "TTS synthesis failed: ${e.message}")
            ShortArray(8000)  // 0.5s silence fallback
        }
    }

    /**
     * Convert FloatArray [-1, 1] to ShortArray [-32768, 32767].
     */
    private fun floatToShort(floatSamples: FloatArray): ShortArray {
        return ShortArray(floatSamples.size) {
            (floatSamples[it] * 32768.0f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /**
     * Simple linear interpolation resampler from [sourceSampleRate] to 16kHz.
     * This is a basic quality-of-life resampler — for production, consider a
     * proper sinc-interpolation resampler or configuring the TTS model to
     * output at 16kHz directly.
     */
    private fun resampleTo16kHz(samples: ShortArray, sourceSampleRate: Int): ShortArray {
        val ratio = sourceSampleRate.toDouble() / 16000.0
        val outputLength = (samples.size / ratio).toInt()
        return ShortArray(outputLength) { i ->
            val srcIndex = i * ratio
            val srcIndexInt = srcIndex.toInt()
            val fraction = srcIndex - srcIndexInt
            if (srcIndexInt + 1 < samples.size) {
                val a = samples[srcIndexInt].toDouble()
                val b = samples[srcIndexInt + 1].toDouble()
                (a + fraction * (b - a)).toInt().coerceIn(-32768, 32767).toShort()
            } else if (srcIndexInt < samples.size) {
                samples[srcIndexInt]
            } else {
                0
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Utility: check if an asset file exists without throwing
// ---------------------------------------------------------------------------

/**
 * Returns true if the given [path] exists as a file in the app's assets.
 * Does NOT throw on missing files — used for graceful model-absent detection.
 */
private fun assetExists(context: android.content.Context, path: String): Boolean {
    return try {
        context.assets.open(path).use { true }
    } catch (_: java.io.IOException) {
        false
    }
}

