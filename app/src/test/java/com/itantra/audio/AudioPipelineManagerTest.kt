package com.itantra.audio

import com.itantra.network.Language
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioPipelineManagerTest {

    private lateinit var simulatedCapture: SimulatedAudioCapture
    private lateinit var vadDetector: SileroVadDetector
    private lateinit var sttManager: IndicConformerSttManager
    private lateinit var pipelineManager: AudioPipelineManager
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        simulatedCapture = SimulatedAudioCapture()
        vadDetector = SileroVadDetector(
            context = null,
            config = VadConfig(
                silenceThresholdFrames = 20,
                fallbackEnergyThreshold = 500,
            ),
        )
        sttManager = IndicConformerSttManager(
            context = null,
            enableMockFallback = true,
        )
        pipelineManager = AudioPipelineManager(
            captureSource = simulatedCapture,
            vadDetector = vadDetector,
            sttManager = sttManager,
            dispatcher = testDispatcher,
        )
    }

    @After
    fun tearDown() {
        pipelineManager.release()
    }

    @Test
    fun `initial state is idle and transcribedText is empty`() {
        assertEquals("", pipelineManager.transcribedText.value)
        assertEquals(AudioPipelineState.Idle, pipelineManager.pipelineState.value)
        assertFalse(pipelineManager.isListening.value)
        assertEquals(Language.HINDI, pipelineManager.currentLanguage.value)
    }

    @Test
    fun `direct frame feeding through VAD and STT emits transcribedText StateFlow`() {
        var listenerEvent: TranscriptionEvent? = null
        pipelineManager.addTranscriptionListener { event ->
            listenerEvent = event
        }

        // 1. Feed speech frames (5 frames of 30ms = 150ms speech)
        val speechFrame = ShortArray(AudioConfig.SAMPLES_PER_FRAME) { if (it % 2 == 0) 3000 else -3000 }
        for (i in 1..5) {
            pipelineManager.feedAudioFrame(speechFrame)
        }

        // 2. Feed 20 frames of silence to hit the 600ms utterance boundary
        val silentFrame = ShortArray(AudioConfig.SAMPLES_PER_FRAME) { 0 }
        for (i in 1..20) {
            pipelineManager.feedAudioFrame(silentFrame)
        }

        // 3. Verify transcribedText StateFlow was updated
        val text = pipelineManager.transcribedText.value
        assertTrue("transcribedText should contain transcribed output", text.isNotBlank())
        assertTrue("Text should indicate Hindi", text.contains("Hindi"))

        // 4. Verify latestTranscription StateFlow
        val event = pipelineManager.latestTranscription.value
        assertNotNull("latestTranscription should not be null", event)
        assertEquals(Language.HINDI, event?.language)
        assertEquals(5 * AudioConfig.SAMPLES_PER_FRAME, event?.sampleCount)

        // 5. Verify listener was invoked
        assertNotNull("Listener should have received event", listenerEvent)
        assertEquals(text, listenerEvent?.text)
    }

    @Test
    fun `switchLanguage updates language and subsequent transcriptions`() {
        pipelineManager.switchLanguage(Language.KANNADA)
        assertEquals(Language.KANNADA, pipelineManager.currentLanguage.value)

        // Feed speech + silence
        val speechFrame = ShortArray(AudioConfig.SAMPLES_PER_FRAME) { 3000 }
        for (i in 1..3) pipelineManager.feedAudioFrame(speechFrame)
        for (i in 1..20) pipelineManager.feedAudioFrame(ShortArray(AudioConfig.SAMPLES_PER_FRAME))

        val text = pipelineManager.transcribedText.value
        assertTrue("Text should reflect Kannada recognition", text.contains("Kannada"))
    }

    @Test
    fun `stopListening flushes lingering speech buffer and emits transcription`() {
        // Feed speech without silence
        val speechFrame = ShortArray(AudioConfig.SAMPLES_PER_FRAME) { 2500 }
        pipelineManager.feedAudioFrame(speechFrame)
        pipelineManager.feedAudioFrame(speechFrame)

        // Stop listening should trigger flush
        pipelineManager.stopListening()

        val text = pipelineManager.transcribedText.value
        assertTrue("Stopping should flush and transcribe pending speech", text.isNotBlank())
        assertFalse(pipelineManager.isListening.value)
    }

    @Test
    fun `reset clears transcribedText and pipeline state`() {
        val speechFrame = ShortArray(AudioConfig.SAMPLES_PER_FRAME) { 2500 }
        pipelineManager.feedAudioFrame(speechFrame)
        for (i in 1..20) pipelineManager.feedAudioFrame(ShortArray(AudioConfig.SAMPLES_PER_FRAME))

        assertTrue(pipelineManager.transcribedText.value.isNotBlank())

        pipelineManager.reset()
        assertEquals("", pipelineManager.transcribedText.value)
        assertEquals(AudioPipelineState.Idle, pipelineManager.pipelineState.value)
    }
}
