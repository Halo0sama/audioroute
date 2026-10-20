package com.halo.audioroute

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class PcmWavTest {
    // Android's compile classpath omits Java Sound; use the host JDK decoder via reflection.
    private fun decode(wav: ByteArray): InputStream = Class.forName("javax.sound.sampled.AudioSystem")
        .getMethod("getAudioInputStream", InputStream::class.java)
        .invoke(null, ByteArrayInputStream(wav)) as InputStream
    private fun format(stream: InputStream): Any = checkNotNull(stream.javaClass.getMethod("getFormat").invoke(stream))
    private fun value(obj: Any, method: String) = obj.javaClass.getMethod(method).invoke(obj)

    @Test fun standardDecoderReadsFourSecondsOfAudio() {
        val pcm = ByteArray(16000 * 2 * 4) { (it % 127).toByte() }
        val wav = PcmWav.encode(pcm, 16000)
        assertEquals(44 + pcm.size, wav.size)
        decode(wav).use { decoded ->
            assertEquals(16000f, value(format(decoded), "getSampleRate"))
            assertEquals(1, value(format(decoded), "getChannels"))
            assertEquals(16, value(format(decoded), "getSampleSizeInBits"))
            assertEquals(64000L, value(decoded, "getFrameLength"))
            assertArrayEquals(pcm, decoded.readBytes())
        }
    }
    @Test fun emptyAudioStillHasValidHeader() {
        decode(PcmWav.encode(byteArrayOf(), 48000)).use {
            assertEquals(48000f, value(format(it), "getSampleRate"))
            assertEquals(0L, value(it, "getFrameLength"))
        }
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsTruncatedSample() {
        PcmWav.encode(byteArrayOf(1), 16000)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidSampleRate() {
        PcmWav.encode(byteArrayOf(), 0)
    }
}
