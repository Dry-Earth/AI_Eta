package io.github.mangi.eta.agent.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeTtsCodecTest {
    @Test fun ssmlEscapesMarkupAndControlCharacters() {
        val ssml = EdgeTtsCodec.ssml("zh-CN-XiaoxiaoNeural", 25, -10, "<b>a&b</b>\u0007")
        assertTrue(ssml.contains("<voice name='zh-CN-XiaoxiaoNeural'>"))
        assertTrue(ssml.contains("rate='+25%'"))
        assertTrue(ssml.contains("pitch='-10Hz'"))
        assertTrue(ssml.contains("&lt;b&gt;a&amp;b&lt;/b&gt;"))
        assertTrue(!ssml.contains("\u0007"))
    }

    @Test fun voiceNameCannotInjectSsml() {
        assertThrows(SpeechFailure::class.java) {
            EdgeTtsCodec.ssml("x' onload='alert(1)", 0, 0, "你好")
        }
        assertThrows(SpeechFailure::class.java) {
            EdgeTtsCodec.ssml("", 0, 0, "你好")
        }
        assertEquals("zh-CN-YunxiNeural", EdgeTtsCodec.requireVoice(" zh-CN-YunxiNeural "))
    }

    @Test fun blankTextIsRejectedBeforeAnyNetworkCall() {
        assertThrows(SpeechFailure::class.java) { EdgeTtsCodec.ssml("zh-CN-XiaoxiaoNeural", 0, 0, " \n\t ") }
    }

    @Test fun binaryFramesSplitHeaderAndAudio() {
        val header = """{"Path":"audio","ContentType":"audio/mpeg"}""".toByteArray(Charsets.UTF_8)
        val audio = byteArrayOf(0x49, 0x44, 0x33, 0x04)
        val frame = byteArrayOf((header.size shr 8).toByte(), header.size.toByte()) + header + audio
        val parsed = EdgeTtsCodec.parseBinaryFrame(frame)
        assertTrue(parsed is EdgeFrame.Audio)
        assertTrue((parsed as EdgeFrame.Audio).bytes.contentEquals(audio))
    }

    @Test fun nonAudioFramesAreReportedAsMetadata() {
        val header = """{"Path":"audio.metadata"}""".toByteArray(Charsets.UTF_8)
        val frame = byteArrayOf((header.size shr 8).toByte(), header.size.toByte()) + header
        val parsed = EdgeTtsCodec.parseBinaryFrame(frame)
        assertTrue(parsed is EdgeFrame.Metadata)
        assertEquals("audio.metadata", (parsed as EdgeFrame.Metadata).path)
    }

    @Test fun truncatedFramesAreIgnored() {
        assertNull(EdgeTtsCodec.parseBinaryFrame(byteArrayOf(0)))
        assertNull(EdgeTtsCodec.parseBinaryFrame(byteArrayOf(0x01, 0x00, 0x7B)))
    }

    @Test fun drmTokenIsStableWithinItsWindowAndRotates() {
        val start = 1_760_000_000_000L
        val token = EdgeTtsCodec.drmToken(start)
        assertEquals(token, EdgeTtsCodec.drmToken(start + 60_000))
        assertEquals(64, token.length)
        assertTrue(token.all { it in '0'..'9' || it in 'A'..'F' })
        assertNotEquals(token, EdgeTtsCodec.drmToken(start + 300_000))
    }

    @Test fun timestampIsSecondPrecisionUtc() {
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}""").matches(EdgeTtsCodec.timestamp(0)))
    }

    @Test fun configMessageRequestsMp3At24k() {
        val config = EdgeTtsCodec.configMessage()
        assertTrue(config.contains(EdgeTtsCodec.OUTPUT_FORMAT))
        assertTrue(EdgeTtsCodec.OUTPUT_FORMAT.startsWith("audio-24khz"))
    }
}
