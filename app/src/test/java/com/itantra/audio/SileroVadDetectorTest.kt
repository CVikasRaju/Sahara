package com.itantra.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SileroVadDetectorTest {

    private lateinit var vadDetector: SileroVadDetector

    @Before
    fun setUp() {
        vadDetector = SileroVadDetector(
            context = null,
            config = VadConfig(
                silenceThresholdFrames = 20,
                fallbackEnergyThreshold = 500,
            ),
        )
    }

    private fun createSpeechFrame(amplitude: Short = 2000): ShortArray {
        return ShortArray(AudioConfig.SAMPLES_PER_FRAME) { i ->
            if (i % 2 == 0) amplitude else (-amplitude).toShort()
        }
    }

    private fun createSilentFrame(): ShortArray {
        return ShortArray(AudioConfig.SAMPLES_PER_FRAME) { 0 }
    }

    @Test
    fun `speech frame triggers speech active state`() {
        val speechFrame = createSpeechFrame(amplitude = 2500)
        val result = vadDetector.processFrame(speechFrame)

        assertTrue("Frame should be identified as speech", result.isSpeech)
        assertFalse("Utterance should not be complete after single frame", result.isUtteranceComplete)
        assertNull(result.completedUtterance)
        assertTrue("isSpeechActive StateFlow should be true", vadDetector.isSpeechActive.value)
    }

    @Test
    fun `silent frame without prior speech does not complete utterance`() {
        val silentFrame = createSilentFrame()
        val result = vadDetector.processFrame(silentFrame)

        assertFalse("Silent frame should not be identified as speech", result.isSpeech)
        assertFalse("Utterance should not be complete", result.isUtteranceComplete)
        assertNull(result.completedUtterance)
        assertFalse("isSpeechActive StateFlow should be false", vadDetector.isSpeechActive.value)
    }

    @Test
    fun `20 consecutive silent frames after speech triggers 600ms boundary`() {
        var callbackUtterance: ShortArray? = null
        vadDetector.setUtteranceListener { utterance ->
            callbackUtterance = utterance
        }

        // Feed 5 frames of speech (150ms)
        val speechFrame = createSpeechFrame(amplitude = 3000)
        for (i in 1..5) {
            val res = vadDetector.processFrame(speechFrame)
            assertTrue("Speech frame $i should be speech", res.isSpeech)
            assertFalse(res.isUtteranceComplete)
        }

        // Feed 19 frames of silence (570ms) - should NOT trigger yet
        val silentFrame = createSilentFrame()
        for (i in 1..19) {
            val res = vadDetector.processFrame(silentFrame)
            assertFalse("Frame $i should be silence", res.isSpeech)
            assertFalse("19 frames is under 600ms threshold", res.isUtteranceComplete)
            assertNull(res.completedUtterance)
        }
        assertNull("Callback should not have fired yet at 19 frames", callbackUtterance)

        // Feed the 20th silent frame (reaching 600ms continuous silence boundary)
        val finalRes = vadDetector.processFrame(silentFrame)
        assertFalse(finalRes.isSpeech)
        assertTrue("20th silent frame must complete utterance", finalRes.isUtteranceComplete)
        assertNotNull("Completed utterance must be emitted", finalRes.completedUtterance)
        assertEquals("Buffer should contain 5 speech frames", 5 * AudioConfig.SAMPLES_PER_FRAME, finalRes.completedUtterance?.size)
        assertNotNull("Listener callback must receive utterance", callbackUtterance)
        assertEquals(5 * AudioConfig.SAMPLES_PER_FRAME, callbackUtterance?.size)
    }

    @Test
    fun `reset clears buffered audio and speech flags`() {
        // Feed speech frame
        vadDetector.processFrame(createSpeechFrame(3000))
        assertTrue(vadDetector.isSpeechActive.value)

        // Reset
        vadDetector.reset()
        assertFalse("Speech active must be reset to false", vadDetector.isSpeechActive.value)

        // Feed 20 silent frames — should NOT emit anything since buffer was cleared
        for (i in 1..20) {
            val res = vadDetector.processFrame(createSilentFrame())
            assertFalse(res.isUtteranceComplete)
        }
    }

    @Test
    fun `empty frame flushes pending speech buffer immediately`() {
        vadDetector.processFrame(createSpeechFrame(3000))
        vadDetector.processFrame(createSpeechFrame(3000))

        val flushRes = vadDetector.processFrame(ShortArray(0))
        assertTrue("Empty frame should flush buffered speech", flushRes.isUtteranceComplete)
        assertEquals(2 * AudioConfig.SAMPLES_PER_FRAME, flushRes.completedUtterance?.size)
    }
}
