package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class AsrProvider { QWEN_REALTIME, QWEN_FLASH, QWEN_FILE, DOUBAO, CUSTOM }

@Serializable
enum class TtsProvider { NONE, QWEN, DOUBAO, GEMINI, MINIMAX, EDGE, CUSTOM }

@Serializable
enum class SpeechRegion { BEIJING, SINGAPORE }

/** 是否由 Eta 与服务保持一条实时双向通道；其余服务都按句逐段请求。 */
internal fun AsrProvider.usesRealtimeSocket(): Boolean =
    this == AsrProvider.QWEN_REALTIME || this == AsrProvider.DOUBAO

@Serializable
data class QwenSpeechConfig(
    val region: SpeechRegion = SpeechRegion.BEIJING,
    val baseUrl: String = "",
) {
    fun endpoint(): String = baseUrl.trim().trimEnd('/').ifEmpty {
        if (region == SpeechRegion.BEIJING) "https://dashscope.aliyuncs.com"
        else "https://dashscope-intl.aliyuncs.com"
    }
}

@Serializable
data class DoubaoSpeechConfig(
    val baseUrl: String = "https://openspeech.bytedance.com",
    val legacyAuth: Boolean = false,
    val appId: String = "",
    val resourceId: String = "",
)

@Serializable
enum class GeminiTtsModel(val apiId: String) {
    FLASH("gemini-3.8-flash-tts"),
    FLASH_LITE("gemini-3.8-flash-lite-tts"),
}

@Serializable
data class GeminiTtsConfig(
    val baseUrl: String = "https://generativelanguage.googleapis.com",
    val model: GeminiTtsModel = GeminiTtsModel.FLASH,
    val voice: String = "Kore",
    val style: String = "",
)

@Serializable
enum class MiniMaxTtsModel(val apiId: String) {
    TURBO("speech-2.8-turbo"),
    HD("speech-2.8-hd"),
}

@Serializable
data class MiniMaxTtsConfig(
    val baseUrl: String = "https://api.minimax.cn",
    val model: MiniMaxTtsModel = MiniMaxTtsModel.TURBO,
    val voice: String = "female-chengshu",
    val speed: Float = 1f,
)

/**
 * 微软 Edge「大声朗读」的免费语音合成。无需账号或 API Key，协议与 Edge 浏览器朗读一致，
 * 属于非官方公开接口，服务地址可改以便接口变动时自行指向兼容网关。
 */
@Serializable
data class EdgeTtsConfig(
    val baseUrl: String = "wss://speech.platform.bing.com",
    val voice: String = "zh-CN-XiaoxiaoNeural",
    /** 语速百分比，与 Edge 朗读的 `rate` 属性一致。 */
    val rate: Int = 0,
    /** 音调，单位为 Hz，与 Edge 朗读的 `pitch` 属性一致。 */
    val pitch: Int = 0,
) {
    companion object {
        const val MIN_RATE = -50
        const val MAX_RATE = 100
        const val MIN_PITCH = -50
        const val MAX_PITCH = 50
    }
}

/**
 * 任意兼容 OpenAI 语音接口的自建服务，例如 llama.cpp、LocalAI、openedai-speech、
 * GPT-SoVITS 网关等，用于连接本机或局域网内的模型。
 */
@Serializable
data class CustomTtsConfig(
    val baseUrl: String = "http://127.0.0.1:8000",
    val path: String = "/v1/audio/speech",
    val model: String = "tts-1",
    val voice: String = "alloy",
    val speed: Float = 1f,
) {
    companion object {
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4f
    }
}

/** 任意兼容 OpenAI `audio/transcriptions` 的自建识别服务，例如 faster-whisper-server、whisper.cpp server。 */
@Serializable
data class CustomAsrConfig(
    val baseUrl: String = "http://127.0.0.1:8000",
    val path: String = "/v1/audio/transcriptions",
    val model: String = "whisper-1",
)

@Serializable
data class SpeechOssConfig(
    val region: String = "cn-beijing",
    val endpoint: String = "https://oss-cn-beijing.aliyuncs.com",
    val bucket: String = "",
    val prefix: String = "eta-speech/",
)

@Serializable
data class SpeechSettings(
    val asr: AsrProvider = AsrProvider.QWEN_REALTIME,
    val tts: TtsProvider = TtsProvider.NONE,
    val autoSpeak: Boolean = false,
    /** 聊天页回答完成后自动朗读最终回答；与助手浮窗的自动播报分开保存。 */
    val chatAutoSpeak: Boolean = false,
    val language: String = "",
    val qwenAsr: QwenSpeechConfig = QwenSpeechConfig(),
    val qwenTts: QwenSpeechConfig = QwenSpeechConfig(),
    val doubaoAsr: DoubaoSpeechConfig = DoubaoSpeechConfig(),
    val doubaoTts: DoubaoSpeechConfig = DoubaoSpeechConfig(),
    val qwenVoice: String = "Cherry",
    val doubaoVoice: String = "zh_female_vv_uranus_bigtts",
    val geminiTts: GeminiTtsConfig = GeminiTtsConfig(),
    val minimaxTts: MiniMaxTtsConfig = MiniMaxTtsConfig(),
    val edgeTts: EdgeTtsConfig = EdgeTtsConfig(),
    val customTts: CustomTtsConfig = CustomTtsConfig(),
    val customAsr: CustomAsrConfig = CustomAsrConfig(),
    val oss: SpeechOssConfig = SpeechOssConfig(),
    /** 助手浮窗停顿多久后自动发送；0 表示只在用户点击完成或松手时发送。 */
    val autoSendSilenceMs: Int = DEFAULT_AUTO_SEND_SILENCE_MS,
    /** 识别结果交付前使用当前对话模型保守纠错。 */
    val refineTranscript: Boolean = false,
    /** 只控制 Eta 自身的语音输入，关闭时保留服务配置以便再次开启。 */
    val recognitionEnabled: Boolean = true,
) {
    companion object {
        const val DEFAULT_AUTO_SEND_SILENCE_MS = 2_000
        val AUTO_SEND_SILENCE_OPTIONS = listOf(1_000, 1_500, 2_000, 3_000, 0)
    }
}

internal enum class SpeechCredentialField {
    QWEN_ASR, QWEN_TTS, DOUBAO_ASR, DOUBAO_TTS, GEMINI_TTS, MINIMAX_TTS, OSS_KEY_ID, OSS_KEY_SECRET,
    CUSTOM_TTS, CUSTOM_ASR, VISION,
}

/** 凭据不属于 Settings，避免被普通配置导出或调试输出带出。 */
@Serializable
class SpeechCredentials(
    val qwenAsr: String = "",
    val qwenTts: String = "",
    val doubaoAsr: String = "",
    val doubaoTts: String = "",
    val ossAccessKeyId: String = "",
    val ossAccessKeySecret: String = "",
    val geminiTts: String = "",
    val minimaxTts: String = "",
    /** 自建语音服务通常不校验凭据；有些网关需要固定 Key，因此留一个可空字段。 */
    val customTts: String = "",
    val customAsr: String = "",
    /** 图片识别的可选 Key；与语音凭据共用同一套 Keystore 加密存储。 */
    val vision: String = "",
) {
    internal fun withValue(field: SpeechCredentialField, value: String): SpeechCredentials = SpeechCredentials(
        qwenAsr = if (field == SpeechCredentialField.QWEN_ASR) value else qwenAsr,
        qwenTts = if (field == SpeechCredentialField.QWEN_TTS) value else qwenTts,
        doubaoAsr = if (field == SpeechCredentialField.DOUBAO_ASR) value else doubaoAsr,
        doubaoTts = if (field == SpeechCredentialField.DOUBAO_TTS) value else doubaoTts,
        ossAccessKeyId = if (field == SpeechCredentialField.OSS_KEY_ID) value else ossAccessKeyId,
        ossAccessKeySecret = if (field == SpeechCredentialField.OSS_KEY_SECRET) value else ossAccessKeySecret,
        geminiTts = if (field == SpeechCredentialField.GEMINI_TTS) value else geminiTts,
        minimaxTts = if (field == SpeechCredentialField.MINIMAX_TTS) value else minimaxTts,
        customTts = if (field == SpeechCredentialField.CUSTOM_TTS) value else customTts,
        customAsr = if (field == SpeechCredentialField.CUSTOM_ASR) value else customAsr,
        vision = if (field == SpeechCredentialField.VISION) value else vision,
    )

    override fun toString(): String = "SpeechCredentials(redacted)"
}
