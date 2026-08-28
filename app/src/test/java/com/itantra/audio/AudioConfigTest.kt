package com.itantra.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioConfigTest {

    @Test
    fun `audio constants match 16kHz 30ms specification`() {
        assertEquals("Sample rate must be 16kHz", 16000, AudioConfig.SAMPLE_RATE_HZ)
        assertEquals("Frame duration must be 30ms", 30, AudioConfig.FRAME_DURATION_MS)
        assertEquals("Samples per frame must be 480", 480, AudioConfig.SAMPLES_PER_FRAME)
        assertEquals("Bytes per sample must be 2 for 16-bit PCM", 2, AudioConfig.BYTES_PER_SAMPLE)
        assertEquals("Frame size must be 960 bytes", 960, AudioConfig.FRAME_SIZE_BYTES)
        assertEquals("Silence threshold must be 20 frames (600ms)", 20, AudioConfig.SILENCE_THRESHOLD_FRAMES)
    }

    @Test
    fun `vad frame result equality and hashcode work correctly`() {
        val utterance1 = shortArrayOf(100, 200, 300)
        val utterance2 = shortArrayOf(100, 200, 300)
        val utterance3 = shortArrayOf(400, 500)

        val result1 = VadFrameResult(isSpeech = true, isUtteranceComplete = true, completedUtterance = utterance1)
        val result2 = VadFrameResult(isSpeech = true, isUtteranceComplete = true, completedUtterance = utterance2)
        val result3 = VadFrameResult(isSpeech = false, isUtteranceComplete = false, completedUtterance = null)
        val result4 = VadFrameResult(isSpeech = true, isUtteranceComplete = true, completedUtterance = utterance3)

        assertEquals("Identical VadFrameResults should be equal", result1, result2)
        assertEquals("Identical VadFrameResults must have same hashCode", result1.hashCode(), result2.hashCode())
        assertNotEquals(result1, result3)
        assertNotEquals(result1, result4)
    }

    @Test
    fun `audio pipeline state model handles all variants`() {
        val idle: AudioPipelineState = AudioPipelineState.Idle
        val capturing: AudioPipelineState = AudioPipelineState.Capturing
        val speechDetected: AudioPipelineState = AudioPipelineState.SpeechDetected
        val transcribing: AudioPipelineState = AudioPipelineState.Transcribing(com.itantra.network.Language.HINDI)
        val error: AudioPipelineState = AudioPipelineState.Error("Test error")

        assertNotNull(idle)
        assertNotNull(capturing)
        assertNotNull(speechDetected)
        assertTrue(transcribing is AudioPipelineState.Transcribing)
        assertTrue(error is AudioPipelineState.Error)
    }
}
