package io.github.mangi.eta.data.model

import io.github.mangi.eta.agent.voice.SpeechFailure
import io.github.mangi.eta.agent.voice.validateSpeechSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CustomSpeechProviderTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    @Test fun edgeProviderNeedsNoCredentialsButValidatesVoiceAndRanges() {
        validateSpeechSettings(SpeechSettings(tts = TtsProvider.EDGE), SpeechCredentials(), synthesis = true)
        assertThrows(SpeechFailure::class.java) {
            validateSpeechSettings(
                SpeechSettings(tts = TtsProvider.EDGE, edgeTts = EdgeTtsConfig(voice = "bad voice")),
                SpeechCredentials(),
                synthesis = true,
            )
        }
        assertThrows(SpeechFailure::class.java) {
            validateSpeechSettings(
                SpeechSettings(tts = TtsProvider.EDGE, edgeTts = EdgeTtsConfig(rate = 200)),
                SpeechCredentials(),
                synthesis = true,
            )
        }
        assertThrows(SpeechFailure::class.java) {
            validateSpeechSettings(
                SpeechSettings(tts = TtsProvider.EDGE, edgeTts = EdgeTtsConfig(baseUrl = "https://speech.example.com")),
                SpeechCredentials(),
                synthesis = true,
            )
        }
    }

    @Test fun customTtsAcceptsLanButRejectsPublicPlaintext() {
        validateSpeechSettings(
            SpeechSettings(tts = TtsProvider.CUSTOM, customTts = CustomTtsConfig(baseUrl = "http://192.168.1.10:8000")),
            SpeechCredentials(),
            synthesis = true,
        )
        assertThrows(SpeechFailure::class.java) {
            validateSpeechSettings(
                SpeechSettings(tts = TtsProvider.CUSTOM, customTts = CustomTtsConfig(baseUrl = "http://8.8.8.8:8000")),
                SpeechCredentials(),
                synthesis = true,
            )
        }
        assertThrows(SpeechFailure::class.java) {
            validateSpeechSettings(
                SpeechSettings(tts = TtsProvider.CUSTOM, customTts = CustomTtsConfig(model = "")),
                SpeechCredentials(),
                synthesis = true,
            )
        }
    }

    @Test fun customAsrRequiresModelAndLocalAddress() {
        validateSpeechSettings(SpeechSettings(asr = AsrProvider.CUSTOM), SpeechCredentials(), synthesis = false)
        assertThrows(SpeechFailure::class.java) {
            validateSpeechSettings(
                SpeechSettings(asr = AsrProvider.CUSTOM, customAsr = CustomAsrConfig(model = "")),
                SpeechCredentials(),
                synthesis = false,
            )
        }
        // 公网地址必须是 HTTPS；这里只拒绝明文公网，不拒绝合法的 HTTPS 网关。
        assertThrows(SpeechFailure::class.java) {
            validateSpeechSettings(
                SpeechSettings(asr = AsrProvider.CUSTOM, customAsr = CustomAsrConfig(baseUrl = "http://api.example.com")),
                SpeechCredentials(),
                synthesis = false,
            )
        }
        validateSpeechSettings(
            SpeechSettings(asr = AsrProvider.CUSTOM, customAsr = CustomAsrConfig(baseUrl = "https://api.example.com")),
            SpeechCredentials(),
            synthesis = false,
        )
    }

    @Test fun newProviderConfigurationRoundTrips() {
        val settings = SpeechSettings(
            asr = AsrProvider.CUSTOM,
            tts = TtsProvider.EDGE,
            edgeTts = EdgeTtsConfig(voice = "zh-CN-YunxiNeural", rate = 25, pitch = -10),
            customTts = CustomTtsConfig(baseUrl = "http://10.1.2.3:8080", path = "/v1/audio/speech", model = "kokoro"),
            customAsr = CustomAsrConfig(baseUrl = "http://10.1.2.3:9000", model = "whisper-large-v3"),
        )
        assertEquals(settings, json.decodeFromString<SpeechSettings>(json.encodeToString(settings)))
        // 旧版本枚举值必须保持原有顺序，避免升级后配置被解析成别的服务。
        assertEquals(0, TtsProvider.NONE.ordinal)
        assertEquals(5, TtsProvider.EDGE.ordinal)
        assertEquals(0, AsrProvider.QWEN_REALTIME.ordinal)
        assertEquals(4, AsrProvider.CUSTOM.ordinal)
    }

    @Test fun customCredentialsAreEncryptedStoreFields() {
        val credentials = SpeechCredentials(customTts = "local-tts", customAsr = "local-asr", vision = "local-vision")
        val updated = credentials.withValue(SpeechCredentialField.CUSTOM_TTS, "new-tts")
        assertEquals("new-tts", updated.customTts)
        assertEquals("local-asr", updated.customAsr)
        assertEquals("local-vision", updated.vision)
        val withKey = credentials.withValue(SpeechCredentialField.VISION, "new-key")
        assertEquals("local-tts", withKey.customTts)
        assertEquals("new-key", withKey.vision)
        assertEquals("SpeechCredentials(redacted)", credentials.toString())
    }
}
