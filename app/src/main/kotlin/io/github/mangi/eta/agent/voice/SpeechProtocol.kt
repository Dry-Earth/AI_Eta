package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.core.LocalEndpointPolicy
import io.github.mangi.eta.core.endpointErrorMessage
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.CustomAsrConfig
import io.github.mangi.eta.data.model.CustomTtsConfig
import io.github.mangi.eta.data.model.EdgeTtsConfig
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.data.repository.SpeechCredentialsUnavailable
import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal enum class SpeechErrorCode {
    CONFIGURATION, CREDENTIALS, PERMISSION, AUDIO, NETWORK, TIMEOUT, AUTHENTICATION,
    RATE_LIMITED, SERVER, PROTOCOL, NO_SPEECH, BACKPRESSURE, STORAGE,
}

internal class SpeechFailure(val code: SpeechErrorCode, val userMessage: String) : IOException(code.name)

internal fun Throwable.speechFailure(): SpeechFailure = when (this) {
    is SpeechFailure -> this
    is SpeechCredentialsUnavailable -> SpeechFailure(SpeechErrorCode.CREDENTIALS, "语音凭据无法解密，请重新填写并保存")
    is SecurityException -> SpeechFailure(SpeechErrorCode.PERMISSION, "请允许 Eta 使用麦克风")
    is kotlinx.coroutines.TimeoutCancellationException -> SpeechFailure(SpeechErrorCode.TIMEOUT, "语音服务响应超时，请重试")
    is IOException -> SpeechFailure(SpeechErrorCode.NETWORK, "无法连接语音服务，请检查网络")
    else -> SpeechFailure(SpeechErrorCode.PROTOCOL, "语音服务返回了无法处理的数据")
}

internal fun validateSpeechSettings(settings: SpeechSettings, secrets: SpeechCredentials, synthesis: Boolean) {
    if (!synthesis && !settings.recognitionEnabled) return
    fun required(value: String, name: String) {
        if (value.isBlank()) throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "请先配置$name")
    }
    if (synthesis) {
        when (settings.tts) {
            TtsProvider.NONE -> throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "请先在设置中配置语音播报")
            TtsProvider.QWEN -> {
                required(secrets.qwenTts, "千问播报 API Key")
                required(settings.qwenVoice, "音色")
                speechBaseUrl(settings.qwenTts.endpoint())
            }
            TtsProvider.DOUBAO -> {
                required(secrets.doubaoTts, "豆包播报凭据")
                required(settings.doubaoVoice, "音色")
                speechBaseUrl(settings.doubaoTts.baseUrl)
                if (settings.doubaoTts.legacyAuth) required(settings.doubaoTts.appId, "豆包 App ID")
            }
            TtsProvider.GEMINI -> {
                required(secrets.geminiTts, "Gemini 播报 API Key")
                required(settings.geminiTts.voice, "音色")
                speechBaseUrl(settings.geminiTts.baseUrl)
                if (settings.geminiTts.style.length > 500) {
                    throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "朗读风格请控制在 500 字以内")
                }
            }
            TtsProvider.MINIMAX -> {
                required(secrets.minimaxTts, "MiniMax 播报 API Key")
                required(settings.minimaxTts.voice, "音色")
                speechBaseUrl(settings.minimaxTts.baseUrl)
                if (settings.minimaxTts.speed !in 0.5f..2f) {
                    throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "MiniMax 语速须在 0.5～2 倍之间")
                }
            }
            // Edge 朗读是免凭据的公开接口，只有音色和取值范围需要校验。
            TtsProvider.EDGE -> {
                required(settings.edgeTts.voice, "Edge 音色")
                // 音色名会进入 SSML 属性，保存时就按允许的字符集校验，避免运行时才失败。
                EdgeTtsCodec.requireVoice(settings.edgeTts.voice)
                edgeSpeechBase(settings.edgeTts.baseUrl)
                if (settings.edgeTts.rate !in EdgeTtsConfig.MIN_RATE..EdgeTtsConfig.MAX_RATE) {
                    throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "语速须在 -50～100 之间")
                }
                if (settings.edgeTts.pitch !in EdgeTtsConfig.MIN_PITCH..EdgeTtsConfig.MAX_PITCH) {
                    throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "音调须在 -50～50 之间")
                }
            }
            // 自建服务可以没有 Key，但地址、模型和音色必须明确，失败后不自动切换到云端服务。
            TtsProvider.CUSTOM -> {
                customServiceUrl(settings.customTts, settings.customTts.path)
                required(settings.customTts.model, "语音合成模型")
                required(settings.customTts.voice, "音色")
                if (settings.customTts.speed !in CustomTtsConfig.MIN_SPEED..CustomTtsConfig.MAX_SPEED) {
                    throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "语速须在 0.25～4 倍之间")
                }
            }
        }
    } else when (settings.asr) {
        AsrProvider.DOUBAO -> {
            required(secrets.doubaoAsr, "豆包识别凭据")
            speechBaseUrl(settings.doubaoAsr.baseUrl)
            if (settings.doubaoAsr.legacyAuth) required(settings.doubaoAsr.appId, "豆包 App ID")
        }
        AsrProvider.CUSTOM -> {
            customServiceUrl(settings.customAsr, settings.customAsr.path)
            required(settings.customAsr.model, "语音识别模型")
        }
        // 本地 whisper 使用内置模型，地址与凭据都不是必需项。
        AsrProvider.WHISPER_LOCAL -> {
            if (settings.localWhisper.threads !in 0..8) {
                throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "本地识别线程数须在 0～8 之间")
            }
        }
        AsrProvider.QWEN_REALTIME,
        AsrProvider.QWEN_FLASH,
        AsrProvider.QWEN_FILE,
        -> {
            required(secrets.qwenAsr, "千问识别 API Key")
            speechBaseUrl(settings.qwenAsr.endpoint())
            if (settings.asr == AsrProvider.QWEN_FILE) {
                required(settings.oss.bucket, "OSS Bucket")
                required(settings.oss.region, "OSS 地域")
                required(secrets.ossAccessKeyId, "OSS AccessKey ID")
                required(secrets.ossAccessKeySecret, "OSS AccessKey Secret")
                speechBaseUrl(settings.oss.endpoint)
                if (!Regex("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]").matches(settings.oss.bucket) ||
                    settings.oss.prefix.split('/').any { it == ".." || it == "." }
                ) throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "请检查 OSS Bucket 和目录前缀")
            }
        }
    }
}

/** 云端语音服务地址：只允许 HTTPS，且不能携带认证信息或查询参数。 */
internal fun speechBaseUrl(value: String): String = try {
    LocalEndpointPolicy.requireServiceBase(value, allowPrivateHttp = false).toString().trimEnd('/')
} catch (_: IllegalArgumentException) {
    throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "服务地址须为不含认证信息和查询参数的 HTTPS 地址")
}

/**
 * 自建服务地址：HTTPS 不限；明文 HTTP 只允许本机与局域网，用于连接本机部署的模型。
 */
internal fun customBaseUrl(value: String): String = try {
    LocalEndpointPolicy.requireServiceBase(value, allowPrivateHttp = true).toString().trimEnd('/')
} catch (error: IllegalArgumentException) {
    throw SpeechFailure(SpeechErrorCode.CONFIGURATION, endpointErrorMessage(error))
}

internal fun customServiceUrl(baseUrl: String, path: String): HttpUrl {
    val base = customBaseUrl(baseUrl)
    val servicePath = try {
        LocalEndpointPolicy.requireServicePath(path)
    } catch (error: IllegalArgumentException) {
        throw SpeechFailure(SpeechErrorCode.CONFIGURATION, endpointErrorMessage(error))
    }
    return (base.toString().trimEnd('/') + servicePath).toHttpUrlOrNull()
        ?: throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "服务地址无效")
}

internal fun customServiceUrl(config: CustomTtsConfig, path: String): HttpUrl = customServiceUrl(config.baseUrl, path)

internal fun customServiceUrl(config: CustomAsrConfig, path: String): HttpUrl = customServiceUrl(config.baseUrl, path)

/** Edge 朗读使用 WebSocket；缺省为 wss，要求显式协议以避免误发到明文端口。 */
internal fun edgeSpeechBase(value: String): String {
    val trimmed = value.trim().trimEnd('/')
    val host = trimmed.removePrefix("wss://")
    val valid = trimmed.startsWith("wss://") && host.isNotEmpty() && host.length <= 256 &&
        !host.any { it == '?' || it == '#' || it == '@' || it == ' ' } && host.contains('.')
    if (!valid) throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "Edge 服务地址须为 wss:// 开头的域名地址")
    return trimmed
}
