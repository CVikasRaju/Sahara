package com.itantra.audio

import android.content.Context
import android.util.Log
import com.itantra.network.Language
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

/**
 * Interface representing a Speech-to-Text inference manager.
 */
interface SttManager : AutoCloseable {
    /** Currently selected language. */
    val currentLanguage: StateFlow<Language>

    /** True if an ONNX neural model is actively loaded in memory. */
    val isModelLoaded: StateFlow<Boolean>

    /**
     * Loads the model for [language].
     * Releases any previously loaded language weights first (Single-Language Resident Policy).
     */
    fun loadLanguage(language: Language)

    /** Unloads the current language model and releases native memory. */
    fun unloadLanguage()

    /**
     * Transcribes 16kHz mono linear PCM audio into recognized text.
     * Returns null if transcription is blank or recognition failed.
     */
    fun transcribe(pcmAudio: ShortArray): SttResult?

    /** Releases all resources. */
    fun release()

    override fun close() = release()
}

/**
 * STT Manager implementation for IndicConformer / Sherpa-ONNX speech models.
 * Strictly adheres to the **Single-Language Resident Policy** (docs/ARCHITECTURE.md §2.2):
 * Only one language's STT weights are memory-mapped at any given time.
 */
class IndicConformerSttManager(
    private val context: Context? = null,
    private val config: SttConfig = SttConfig(),
    private val enableMockFallback: Boolean = true,
) : SttManager {

    private val tag = "IndicConformerSttManager"

    private val _currentLanguage = MutableStateFlow(Language.HINDI)
    override val currentLanguage: StateFlow<Language> = _currentLanguage.asStateFlow()

    private val _isModelLoaded = MutableStateFlow(false)
    override val isModelLoaded: StateFlow<Boolean> = _isModelLoaded.asStateFlow()

    private var recognizer: com.k2fsa.sherpa.onnx.OfflineRecognizer? = null

    override fun loadLanguage(language: Language) {
        if (language == _currentLanguage.value && recognizer != null) {
            return
        }

        // Single-Language Resident Policy: Release previous language model first
        unloadLanguage()
        _currentLanguage.value = language

        val ctx = context ?: run {
            Log.d(tag, "No Android Context provided (unit test environment). Using mock transcriber.")
            return
        }

        val langCode = language.code
        val modelBasePath = "${config.assetBaseDir}/$langCode"
        val tokensPath = "$modelBasePath/tokens.txt"

        if (!assetFileExists(ctx, tokensPath)) {
            Log.w(tag, "STT tokens file not found at assets/$tokensPath. Mock fallback enabled: $enableMockFallback")
            return
        }

        try {
            val recognizerConfig = com.k2fsa.sherpa.onnx.OfflineRecognizerConfig(
                modelConfig = com.k2fsa.sherpa.onnx.OfflineModelConfig(
                    whisper = com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig(
                        encoder = "$modelBasePath/encoder.int8.onnx",
                        decoder = "$modelBasePath/decoder.int8.onnx",
                        language = langCode,
                        task = "transcribe",
                    ),
                    tokens = tokensPath,
                    numThreads = config.numThreads,
                    debug = config.debug,
                ),
            )

            recognizer = com.k2fsa.sherpa.onnx.OfflineRecognizer(
                assetManager = ctx.assets,
                config = recognizerConfig,
            )
            _isModelLoaded.value = true
            Log.i(tag, "IndicConformer STT model loaded successfully for: ${language.displayName} ($langCode)")

            // Warm up recognizer to prevent latency spike on first real utterance
            warmUp()
        } catch (e: Throwable) {
            Log.e(tag, "Failed to load IndicConformer model for '$langCode': ${e.message}")
            recognizer = null
            _isModelLoaded.value = false
        }
    }

    override fun unloadLanguage() {
        try {
            recognizer?.release()
        } catch (e: Exception) {
            Log.w(tag, "Error releasing STT recognizer: ${e.message}")
        }
        recognizer = null
        _isModelLoaded.value = false
    }

    override fun transcribe(pcmAudio: ShortArray): SttResult? {
        if (pcmAudio.isEmpty()) return null

        val rec = recognizer
        if (rec != null) {
            return try {
                val stream = rec.createStream()
                val floatSamples = FloatArray(pcmAudio.size) { pcmAudio[it].toFloat() / 32768.0f }
                stream.acceptWaveform(floatSamples, sampleRate = AudioConfig.SAMPLE_RATE_HZ)

                rec.decode(stream)
                val result = rec.getResult(stream)
                stream.release()

                val text = result.text.trim()
                if (text.isBlank()) {
                    null
                } else {
                    SttResult(
                        text = text,
                        confidence = config.defaultConfidence,
                        language = _currentLanguage.value,
                    )
                }
            } catch (e: Exception) {
                Log.e(tag, "Transcription failed: ${e.message}", e)
                null
            }
        }

        if (enableMockFallback) {
            val lang = _currentLanguage.value
            return SttResult(
                text = "[Recognized ${lang.displayName}: ${pcmAudio.size} samples]",
                confidence = 0.95f,
                language = lang,
            )
        }

        return null
    }

    /**
     * Warm-up dummy inference using 30ms of silence to initialize ONNX runtime buffers.
     */
    private fun warmUp() {
        try {
            val silentBuffer = FloatArray(AudioConfig.SAMPLES_PER_FRAME)
            val stream = recognizer?.createStream() ?: return
            stream.acceptWaveform(silentBuffer, sampleRate = AudioConfig.SAMPLE_RATE_HZ)
            recognizer?.decode(stream)
            stream.release()
            Log.d(tag, "IndicConformer STT warm-up inference completed")
        } catch (e: Exception) {
            Log.w(tag, "STT warm-up failed (non-fatal): ${e.message}")
        }
    }

    private fun assetFileExists(context: Context, assetPath: String): Boolean {
        return try {
            context.assets.open(assetPath).use { true }
        } catch (_: IOException) {
            false
        }
    }

    override fun release() {
        unloadLanguage()
    }
}
