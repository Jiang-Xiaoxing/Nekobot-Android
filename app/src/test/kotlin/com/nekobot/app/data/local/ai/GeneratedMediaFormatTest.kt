package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class GeneratedMediaFormatTest {
    @Test
    fun jpegAndWebpKeepTheirRealFileType() {
        val jpeg = generatedImageFormat(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1))
        assertEquals("jpg", jpeg.extension)
        assertEquals("image/jpeg", jpeg.mimeType)
        val webp = generatedImageFormat("RIFF0000WEBPpayload".toByteArray())
        assertEquals("webp", webp.extension)
        assertEquals("image/webp", webp.mimeType)
    }

    @Test(expected = IllegalStateException::class)
    fun errorPageIsNotSavedAsAnImage() {
        generatedImageFormat("<html>upstream error</html>".toByteArray())
    }

    @Test
    fun wavOverridesConfiguredMp3Format() {
        val wav = generatedAudioFormat("RIFF0000WAVEpayload".toByteArray(), "mp3")
        assertEquals("wav", wav.extension)
        assertEquals("audio/wav", wav.mimeType)
        assertEquals("audio/mpeg", generatedAudioFormat(byteArrayOf(1), "mp3").mimeType)
    }
}
