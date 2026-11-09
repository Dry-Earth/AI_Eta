package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.data.model.CustomAsrConfig
import io.github.mangi.eta.data.model.CustomTtsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private val CUSTOM_JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
private val CUSTOM_WAV_MEDIA = "audio/wav".toMediaType()

/**
 * 兼容 OpenAI `audio/speech` 的自建语音合成。
 *
 * 契约与 OpenAI 一致：POST {base}{path}，`response_format=pcm` 时返回 24 kHz 单声道 PCM16，
 * 部分网关会返回 WAV，[SpeechPcmDecoder] 两种都接受。请求失败直接报错，
 * 绝不会自动切到云端供应商。
 */
internal suspend fun synthesizeCustomSpeech(
    config: CustomTtsConfig,
    key: String,
    text: String,
    onAudio: (ByteArray) -> Unit,
) {
    val body = JSONObject()
        .put("model", config.model.trim())
        .put("input", text)
        .put("voice", config.voice.trim())
        .put("response_format", "pcm")
        .put("speed", config.speed.toDouble())
    val request = Request.Builder()
        .url(customServiceUrl(config, config.path))
        .header("Accept", "audio/pcm, audio/wav, application/octet-stream")
        .apply { if (key.isNotBlank()) header("Authorization", "Bearer ${key.trim()}") }
        .post(body.toString().toRequestBody(CUSTOM_JSON_MEDIA))
        .build()
    SpeechHttp.read(request) { response ->
        if (response.body.contentType()?.subtype?.contains("json", ignoreCase = true) == true) {
            throw customSpeechFailure(response.body.source().readUtf8())
        }
        val decoder = SpeechPcmDecoder(onAudio)
        val source = response.body.source()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = source.read(buffer, 0, buffer.size)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > MAX_CUSTOM_PCM_BYTES) {
                throw SpeechFailure(SpeechErrorCode.PROTOCOL, "语音音频过长，请缩短文本")
            }
            decoder.write(buffer.copyOf(read))
        }
        decoder.end()
    }
}

/**
 * 兼容 OpenAI `audio/transcriptions` 的自建语音识别。录音已由 [SpeechRecorder] 封装成 WAV，
 * 这里只负责上传一次并读取 `text` 字段，不做流式重试。
 */
internal suspend fun recognizeCustomSpeech(
    config: CustomAsrConfig,
    key: String,
    wav: ByteArray,
    language: String,
    onProgress: (String) -> Unit,
): String = withContext(Dispatchers.IO) {
    onProgress("正在识别")
    val multipart = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("file", "audio.wav", wav.toRequestBody(CUSTOM_WAV_MEDIA))
        .addFormDataPart("model", config.model.trim())
        .addFormDataPart("response_format", "json")
        .apply {
            val tag = language.trim()
            if (tag.matches(CUSTOM_LANGUAGE_PATTERN)) addFormDataPart("language", tag)
        }
        .build()
    val request = Request.Builder()
        .url(customServiceUrl(config, config.path))
        .apply { if (key.isNotBlank()) header("Authorization", "Bearer ${key.trim()}") }
        .post(multipart)
        .build()
    val response = SpeechHttp.json(request)
    val text = response.optString("text").trim()
    if (text.isEmpty() && response.has("error")) {
        throw customSpeechFailure(response.toString())
    }
    text
}

/** 自建服务常见的错误体是 OpenAI 风格的 `{"error":{"message":...}}`。 */
private fun customSpeechFailure(body: String): SpeechFailure {
    val message = runCatching {
        JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
    }.getOrDefault("")
    val detail = message.take(200).ifBlank { "自建语音服务返回了错误响应" }
    return SpeechFailure(SpeechErrorCode.SERVER, detail)
}

private val CUSTOM_LANGUAGE_PATTERN = Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})?")
private const val MAX_CUSTOM_PCM_BYTES = 24_000L * 2 * 300
