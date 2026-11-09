package io.github.mangi.eta.agent.vision

import io.github.mangi.eta.agent.media.MAX_AGENT_IMAGE_BYTES
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.core.LocalEndpointPolicy
import io.github.mangi.eta.core.endpointErrorMessage
import io.github.mangi.eta.data.model.ImageRecognitionSettings
import java.util.Base64
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal class VisionFailure(message: String) : Exception(message)

/**
 * 本地图片识别客户端（OpenAI `chat/completions` 视觉协议）。
 *
 * 安全边界：
 * 1. 只允许本机、私有网段、链路本地与 mDNS 地址，公网域名一律拒绝；
 * 2. 只接受 HTTPS 或上述地址的明文 HTTP，避免图片被发到互联网；
 * 3. 失败直接抛错，调用方不得退回对话模型的视觉能力，也不做跨服务重试。
 */
internal object LocalVisionClient {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        AgentHttpClient.client.newBuilder()
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /** 返回纯文本描述。图片只进入这次请求，不会出现在返回结构里。 */
    fun describe(
        settings: ImageRecognitionSettings,
        apiKey: String,
        imageBytes: ByteArray,
        mimeType: String,
    ): String {
        val url = endpoint(settings)
        if (imageBytes.isEmpty() || imageBytes.size > MAX_AGENT_IMAGE_BYTES) {
            throw VisionFailure("图片内容为空或超过大小限制")
        }
        val prompt = settings.prompt.trim().ifEmpty { ImageRecognitionSettings.DEFAULT_PROMPT }
        if (prompt.length > ImageRecognitionSettings.MAX_PROMPT_CHARS) {
            throw VisionFailure("识图提示词请控制在 ${ImageRecognitionSettings.MAX_PROMPT_CHARS} 字以内")
        }
        val body = JSONObject()
            .put("model", settings.model.trim())
            .put("max_tokens", settings.maxTokens.coerceIn(
                ImageRecognitionSettings.MIN_MAX_TOKENS,
                ImageRecognitionSettings.MAX_MAX_TOKENS,
            ))
            .put("stream", false)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put(
                        "content",
                        JSONArray()
                            .put(JSONObject().put("type", "text").put("text", prompt))
                            .put(
                                JSONObject().put("type", "image_url").put(
                                    "image_url",
                                    JSONObject().put(
                                        "url",
                                        "data:${mimeType.normalizedVisionMime()};base64," +
                                            Base64.getEncoder().encodeToString(imageBytes),
                                    ),
                                ),
                            ),
                    ),
                ),
            )
        val request = Request.Builder()
            .url(url)
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer ${apiKey.trim()}") }
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        val payload = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = runCatching { response.body.string().take(240) }.getOrDefault("")
                throw VisionFailure(
                    "本地识图服务请求失败（HTTP ${response.code}）" + if (detail.isBlank()) "" else "：$detail",
                )
            }
            val source = response.body.source()
            if (source.request(MAX_RESPONSE_BYTES)) throw VisionFailure("识图服务响应过大")
            source.readUtf8()
        }
        return parseContent(payload)
    }

    /** 保存前校验：地址必须落在本机/局域网，路径、模型与提示词长度都要合法。 */
    fun validate(settings: ImageRecognitionSettings) {
        endpoint(settings)
        if (settings.model.isBlank()) throw VisionFailure("请填写本地识图模型名称")
        if (settings.prompt.length > ImageRecognitionSettings.MAX_PROMPT_CHARS) {
            throw VisionFailure("识图提示词请控制在 ${ImageRecognitionSettings.MAX_PROMPT_CHARS} 字以内")
        }
    }

    /** 地址必须是本机/局域网，且不能带认证信息、查询参数或上级目录。 */
    fun endpoint(settings: ImageRecognitionSettings): String {
        val base = try {
            LocalEndpointPolicy.requireServiceBase(settings.baseUrl, allowPrivateHttp = true)
        } catch (error: IllegalArgumentException) {
            throw VisionFailure(endpointErrorMessage(error))
        }
        if (!LocalEndpointPolicy.isLocalHost(base.host)) {
            throw VisionFailure("本地图片识别只允许本机或局域网地址，已拒绝公网地址")
        }
        val path = try {
            LocalEndpointPolicy.requireServicePath(settings.path)
        } catch (error: IllegalArgumentException) {
            throw VisionFailure(endpointErrorMessage(error))
        }
        return (base.toString().trimEnd('/') + path).toHttpUrl().toString()
    }

    private fun parseContent(payload: String): String {
        val root = try {
            JSONObject(payload)
        } catch (_: Exception) {
            throw VisionFailure("识图服务返回了无法解析的响应")
        }
        root.optJSONObject("error")?.let { error ->
            val message = error.optString("message").take(200)
            throw VisionFailure(message.ifBlank { "识图服务返回错误" })
        }
        val choices = root.optJSONArray("choices") ?: throw VisionFailure("识图服务没有返回结果")
        if (choices.length() == 0) throw VisionFailure("识图服务没有返回结果")
        val content = choices.optJSONObject(0)?.optJSONObject("message")?.opt("content")
        val text = when (content) {
            is String -> content
            is JSONArray -> (0 until content.length()).joinToString("") { index ->
                content.optJSONObject(index)?.optString("text").orEmpty()
            }
            else -> ""
        }.trim()
        if (text.isEmpty()) throw VisionFailure("识图服务没有返回文字描述")
        return text.take(MAX_DESCRIPTION_CHARS)
    }

    private fun String.normalizedVisionMime(): String =
        substringBefore(';').trim().takeIf { it.startsWith("image/") } ?: "image/jpeg"

    private const val TIMEOUT_SECONDS = 120L
    private const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024
    private const val MAX_DESCRIPTION_CHARS = 8_000
}
