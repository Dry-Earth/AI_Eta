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
        assertNotEquals(token, EdgeTtsCodec.drmToken(start + 300_000))
        assertTrue(Regex("[0-9A-F]{16}-[0-9A-F]{64}").matches(token))
    }

    /**
     * 参考值来自与微软接口实际握手成功的实现（HTTP 101）；改动算法前先确保这些向量仍然成立，
     * 否则线上会直接变成 403。
     */
    @Test fun drmTokenMatchesReferenceVectors() {
        val vectors = mapOf(
            1_760_000_000_000L to "6D50DDDB91812C33-DD68EFB01898B0750C9797D51A5FF93471DFDAE6888A1039D532CBDCCE54545D",
            1_760_000_300_000L to "BAF4076F572BFE4E-2D6FBE5C0E24F56622EB94F37395F2D153C6C0C451DAAE2249A459BB3AFAD62C",
            1_700_000_000_000L to "2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2",
            0L to "52BC7A17B50DFEC9-28C4394775C8751CD2F6859C030C06DB1489ACAB3B3A7651831FC26895177ED6",
        )
        for ((timestamp, expected) in vectors) {
            assertEquals("timestamp=$timestamp", expected, EdgeTtsCodec.drmToken(timestamp))
        }
    }

    @Test fun drmVersionUsesV2Scheme() {
        // v1 版本头搭配 v2 令牌同样会被拒绝，两者必须成对。
        assertTrue(EdgeTtsCodec.DRM_VERSION.startsWith("2-"))
    }

    @Test fun timestampMatchesJsDateFormat() {
        // edge-tts SDK 发送的是 JS `Date` 风格字符串，末尾由协议层补 Z。
        assertTrue(
            Regex("""\w{3} \w{3} \d{2} \d{4} \d{2}:\d{2}:\d{2} GMT\+0000 \(Coordinated Universal Time\)""")
                .matches(EdgeTtsCodec.timestamp(0)),
        )
    }

    @Test fun configMessageRequestsMp3At24k() {
        val config = EdgeTtsCodec.configMessage()
        assertTrue(config.contains(EdgeTtsCodec.OUTPUT_FORMAT))
        assertTrue(EdgeTtsCodec.OUTPUT_FORMAT.startsWith("audio-24khz"))
    }

    /** 服务端按 Path 分派文本帧，缺 `Path:speech.config` / `Path:ssml` 会拿不到音频。 */
    @Test fun textFramesCarryPathHeaders() {
        val config = EdgeTtsCodec.configMessage("Thu Jan 01 2026 00:00:00 GMT+0000 (Coordinated Universal Time)")
        assertTrue(config.startsWith("X-Timestamp:Thu Jan 01 2026"))
        assertTrue(config.contains("Content-Type:application/json; charset=utf-8"))
        assertTrue(config.contains("Path:speech.config"))
        assertTrue(config.contains("\r\n\r\n{"))
        assertTrue(config.endsWith("\r\n"))

        val speech = EdgeTtsCodec.speechMessage("abc123", "Thu Jan 01 2026 00:00:00 GMT+0000 (Coordinated Universal Time)", "<speak/>")
        assertTrue(speech.startsWith("X-RequestId:abc123"))
        assertTrue(speech.contains("Content-Type:application/ssml+xml"))
        assertTrue(speech.contains("Path:ssml"))
        assertTrue(speech.endsWith("\r\n\r\n<speak/>"))
    }
}
