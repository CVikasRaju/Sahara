package com.itantra.audio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Interface representing a Voice Activity Detector for processing streaming PCM frames.
 */
interface VadDetector : AutoCloseable {
    /** Reactive StateFlow indicating whether speech is currently detected. */
    val isSpeechActive: StateFlow<Boolean>

    /**
     * Feeds a single 30ms PCM frame (480 samples @ 16kHz mono).
     * Returns [VadFrameResult] indicating whether the frame contains speech
     * and whether an utterance boundary was triggered.
     */
    fun processFrame(pcmFrame: ShortArray): VadFrameResult

    /** Registers a callback to be invoked whenever a full speech utterance is finalized. */
    fun setUtteranceListener(listener: (ShortArray) -> Unit)

    /** Clears internal utterance and silence buffers. */
    fun reset()

    /** Releases native ONNX resources. */
    fun release()

    override fun close() = release()
}

/**
 * Real Silero VAD implementation backed by sherpa-onnx [com.k2fsa.sherpa.onnx.Vad].
 *
 * If the Silero ONNX model is not present in Android assets or if running in a
 * headless JVM environment, it gracefully falls back to an energy-based threshold detector
 * with the exact same 600ms continuous silence boundary contract.
 */
class SileroVadDetector(
    context: Context? = null,
    private val config: VadConfig = VadConfig(),
) : VadDetector {

    private val tag = "SileroVadDetector"

    private val _isSpeechActive = MutableStateFlow(false)
    override val isSpeechActive: StateFlow<Boolean> = _isSpeechActive.asStateFlow()

    private var onUtteranceComplete: ((ShortArray) -> Unit)? = null

    // Native sherpa-onnx VAD instance
    private var nativeVad: com.k2fsa.sherpa.onnx.Vad? = null
    private var isModelLoaded = false

    // Utterance accumulation buffer (for boundary extraction)
    private val utteranceBuffer = mutableListOf<Short>()
    private var silentFrameCount = 0

    init {
        if (context != null) {
            try {
                val vadConfig = com.k2fsa.sherpa.onnx.VadModelConfig(
                    sileroVadModelConfig = com.k2fsa.sherpa.onnx.SileroVadModelConfig(
                        model = config.modelAssetPath,
                        threshold = config.threshold,
                        minSilenceDuration = config.minSilenceDuration,
                        minSpeechDuration = config.minSpeechDuration,
                        windowSize = config.windowSize,
                        maxSpeechDuration = config.maxSpeechDuration,
                    ),
                    sampleRate = AudioConfig.SAMPLE_RATE_HZ,
                    numThreads = 1,
                    provider = "cpu",
                    debug = false,
                )

                nativeVad = com.k2fsa.sherpa.onnx.Vad(
                    assetManager = context.assets,
                    config = vadConfig,
                )
                isModelLoaded = true
                Log.i(tag, "Silero VAD initialized from assets/${config.modelAssetPath}")
            } catch (e: Throwable) {
                Log.w(tag, "Silero ONNX model could not be loaded (${e.message}). Using energy-based fallback.")
                nativeVad = null
                isModelLoaded = false
            }
        }
    }

    override fun processFrame(pcmFrame: ShortArray): VadFrameResult {
        if (pcmFrame.isEmpty()) {
            return flushIfNonEmpty()
        }

        val vadInstance = nativeVad
        if (isModelLoaded && vadInstance != null) {
            return processWithSherpaOnnx(vadInstance, pcmFrame)
        }

        return processWithEnergyFallback(pcmFrame)
    }

    /**
     * Process audio frame using sherpa-onnx native Silero VAD.
     */
    private fun processWithSherpaOnnx(
        vad: com.k2fsa.sherpa.onnx.Vad,
        pcmFrame: ShortArray,
    ): VadFrameResult {
        // Convert 16-bit short samples to normalized float [-1.0, 1.0]
        val floatSamples = FloatArray(pcmFrame.size) { pcmFrame[it].toFloat() / 32768.0f }
        vad.acceptWaveform(floatSamples)

        val isSpeech = vad.isSpeechDetected()
        _isSpeechActive.value = isSpeech

        var completedUtterance: ShortArray? = null
        var isUtteranceComplete = false

        if (!vad.empty()) {
            val segment = vad.front()
            vad.pop()

            completedUtterance = ShortArray(segment.samples.size) {
                (segment.samples[it] * 32768.0f).toInt().coerceIn(-32768, 32767).toShort()
            }
            isUtteranceComplete = true
            onUtteranceComplete?.invoke(completedUtterance)
            _isSpeechActive.value = false
        }

        return VadFrameResult(
            isSpeech = isSpeech,
            isUtteranceComplete = isUtteranceComplete,
            completedUtterance = completedUtterance,
        )
    }

    /**
     * Fallback processor using average amplitude energy calculation.
     * Detects 600ms silence boundary (20 consecutive frames of 30ms).
     */
    private fun processWithEnergyFallback(pcmFrame: ShortArray): VadFrameResult {
        val totalEnergy = pcmFrame.fold(0L) { acc, sample -> acc + abs(sample.toInt()) }
        val avgAmplitude = totalEnergy / pcmFrame.size
        val isSpeech = avgAmplitude > config.fallbackEnergyThreshold

        _isSpeechActive.value = isSpeech

        var isUtteranceComplete = false
        var completedUtterance: ShortArray? = null

        if (isSpeech) {
            pcmFrame.forEach { utteranceBuffer.add(it) }
            silentFrameCount = 0
        } else {
            silentFrameCount++
            if (silentFrameCount >= config.silenceThresholdFrames && utteranceBuffer.isNotEmpty()) {
                completedUtterance = utteranceBuffer.toShortArray()
                isUtteranceComplete = true
                utteranceBuffer.clear()
                silentFrameCount = 0
                _isSpeechActive.value = false
                onUtteranceComplete?.invoke(completedUtterance)
            }
        }

        return VadFrameResult(
            isSpeech = isSpeech,
            isUtteranceComplete = isUtteranceComplete,
            completedUtterance = completedUtterance,
        )
    }

    /**
     * Flushes any remaining buffered speech frames as a completed utterance.
     */
    private fun flushIfNonEmpty(): VadFrameResult {
        if (utteranceBuffer.isNotEmpty()) {
            val completedUtterance = utteranceBuffer.toShortArray()
            utteranceBuffer.clear()
            silentFrameCount = 0
            _isSpeechActive.value = false
            onUtteranceComplete?.invoke(completedUtterance)
            return VadFrameResult(
                isSpeech = false,
                isUtteranceComplete = true,
                completedUtterance = completedUtterance,
            )
        }
        return VadFrameResult(isSpeech = false, isUtteranceComplete = false)
    }

    override fun setUtteranceListener(listener: (ShortArray) -> Unit) {
        onUtteranceComplete = listener
    }

    override fun reset() {
        utteranceBuffer.clear()
        silentFrameCount = 0
        _isSpeechActive.value = false
        nativeVad?.clear()
    }

    override fun release() {
        try {
            nativeVad?.release()
        } catch (e: Exception) {
            Log.w(tag, "Error releasing Silero VAD: ${e.message}")
        }
        nativeVad = null
        isModelLoaded = false
        utteranceBuffer.clear()
        _isSpeechActive.value = false
    }
}
