package com.itantra.audio

import com.itantra.network.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IndicConformerSttManagerTest {

    private lateinit var sttManager: IndicConformerSttManager

    @Before
    fun setUp() {
        sttManager = IndicConformerSttManager(
            context = null,
            enableMockFallback = true,
        )
    }

    @Test
    fun `default language is Hindi`() {
        assertEquals(Language.HINDI, sttManager.currentLanguage.value)
    }

    @Test
    fun `loadLanguage updates currentLanguage StateFlow`() {
        sttManager.loadLanguage(Language.KANNADA)
        assertEquals(Language.KANNADA, sttManager.currentLanguage.value)

        sttManager.loadLanguage(Language.TAMIL)
        assertEquals(Language.TAMIL, sttManager.currentLanguage.value)
    }

    @Test
    fun `transcribe on empty audio returns null`() {
        val result = sttManager.transcribe(ShortArray(0))
        assertNull("Empty audio must return null", result)
    }

    @Test
    fun `transcribe on valid audio returns result with active language`() {
        sttManager.loadLanguage(Language.HINDI)
        val audio = ShortArray(4800) { 100 }

        val result = sttManager.transcribe(audio)
        assertNotNull(result)
        assertEquals(Language.HINDI, result?.language)
        assertTrue(result?.confidence ?: 0f > 0.5f)
        assertTrue(result?.text?.contains("Hindi") == true)
    }

    @Test
    fun `language switch changes output language in transcription`() {
        sttManager.loadLanguage(Language.KANNADA)
        val audio = ShortArray(4800) { 100 }

        val result = sttManager.transcribe(audio)
        assertNotNull(result)
        assertEquals(Language.KANNADA, result?.language)
        assertTrue(result?.text?.contains("Kannada") == true)
    }
}
