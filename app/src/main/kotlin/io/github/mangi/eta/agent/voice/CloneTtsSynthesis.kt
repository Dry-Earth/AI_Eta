package io.github.mangi.eta.agent.voice

import android.content.Context
import io.github.mangi.eta.data.model.CloneTtsConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * CloneTTS 内置的本地 HTTP 合成接口。
 *
 * `GET {base}/api/tts?text=...&voice=别名` 直接返回音频；返回 WAV 或 MP3 都能解码成
 * 24 kHz 单声道 PCM16，再交给 Eta 的播放链路。相比系统 `TextToSpeech`，这条链路不需要
 * 引擎注册，起播更快，但需要用户在 CloneTTS 里打开「本地 HTTP API 服务」。
 */
internal suspend fun synthesizeCloneTts(
    context: Context,
    config: CloneTtsConfig,
    text: String,
    onAudio: (ByteArray) -> Unit,
) {
    if (text.isBlank()) throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "这条消息没有可朗读的正文")
    val url = buildUrl(config, text)
    // 本地服务不需要鉴权；不走 followRedirects，避免把文字带到别的地址。
    val request = Request.Builder().url(url).get().build()
    SpeechHttp.read(request) { response ->
        val body = response.body
        val source = body.source()
        if (source.request(MAX_AUDIO_BYTES + 1)) {
            throw SpeechFailure(SpeechErrorCode.PROTOCOL, "CloneTTS 返回的音频过大")
        }
        val bytes = source.readByteArray()
        val pcm = SystemAudioDecode.toPcm24kMono(bytes, body.contentType()?.toString())
        var offset = 0
        while (offset < pcm.size) {
            val count = minOf(32 * 1024, pcm.size - offset)
            onAudio(pcm.copyOfRange(offset, offset + count))
            offset += count
        }
    }
}

private fun buildUrl(config: CloneTtsConfig, text: String): okhttp3.HttpUrl {
    val base = config.baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
        ?: throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "CloneTTS 服务地址无效")
    if (base.host.isBlank()) throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "CloneTTS 服务地址无效")
    if (base.scheme != "http" && base.scheme != "https") {
        throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "CloneTTS 服务地址必须是 http 或 https")
    }
    val builder = base.newBuilder().addPathSegments("api/tts").addQueryParameter("text", text)
    config.voice.trim().takeIf { it.isNotEmpty() }?.let { builder.addQueryParameter("voice", it) }
    return builder.build()
}

private const val MAX_AUDIO_BYTES = 16L * 1024 * 1024
