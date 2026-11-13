package io.github.mangi.eta.agent.vision

import io.github.mangi.eta.agent.media.MAX_AGENT_IMAGE_BYTES
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.data.model.ImageRecognitionSettings
import java.util.Base64
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal class VisionFailure(message: String) : Exception(message)

/**
 * 本机/局域网视觉服务（OpenAI chat/completions 视觉协议）。
 *
 * 提示词由两部分组成：固定的安全前缀（只描述可见内容、不许编造）加上**模型这次调用
 * 想问的问题**；模型没带问题时使用内置的客观描述兜底。这样"问什么"由模型结合用户
 * 当轮问题决定，而不是被一个全局模板绑死。
 *
 * 只允许本机与私网地址；失败直接报错，调用方不得回退到把图片交给云端模型。
 */
internal object LocalVisionClient {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private const val PREFIX =
        "你是运行在本机的图片识别服务。只描述图中真实可见的内容；看不清或不确定的地方要明确说明，不要推测或编造。"
    private const val DEFAULT_QUESTION = "请客观描述这张图片：整体场景、关键对象、可见文字与界面布局。"

    private val client: OkHttpClient by lazy {
        AgentHttpClient.client.newBuilder()
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    fun validate(settings: ImageRecognitionSettings) {
        endpoint(settings)
        if (settings.model.isBlank()) throw VisionFailure("请填写本地识图模型名称")
    }

    fun describe(
        settings: ImageRecognitionSettings,
        question: String,
        imageBytes: ByteArray,
        mimeType: String,
    ): String {
        val url = endpoint(settings)
        if (imageBytes.isEmpty() || imageBytes.size > MAX_AGENT_IMAGE_BYTES) {
            throw VisionFailure("图片内容为空或超过大小限制")
        }
        val prompt = PREFIX + "\n" + question.trim().take(MAX_QUESTION_CHARS)
            .ifBlank { DEFAULT_QUESTION }
        val body = JSONObject()
            .put("model", settings.model.trim())
            .put("max_tokens", settings.maxTokens.coerceIn(64, 8192))
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
                                        "data:${mimeType.normalizedMime()};base64," +
                                            Base64.getEncoder().encodeToString(imageBytes),
                                    ),
                                ),
                            ),
                    ),
                ),
            )
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        val payload = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = runCatching { response.body.string().take(200) }.getOrDefault("")
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

    fun endpoint(settings: ImageRecognitionSettings): HttpUrl {
        val base = settings.baseUrl.trim().toHttpUrlOrNull()
            ?: throw VisionFailure("识图服务地址无效")
        if (base.scheme != "http" && base.scheme != "https") {
            throw VisionFailure("识图服务地址必须是 http 或 https")
        }
        if (!isLocalHost(base.host)) {
            throw VisionFailure("本地图片识别只允许本机或局域网地址，已拒绝公网地址")
        }
        val path = settings.path.trim()
        val valid = path.startsWith("/") && !path.contains('?') && !path.contains('#') &&
            path.split('/').none { it == ".." || it == "." }
        if (!valid) throw VisionFailure("识图服务路径须以 / 开头，且不能包含查询参数或上级目录")
        return (base.toString().trimEnd('/') + path.trimEnd('/')).toHttpUrlOrNull()
            ?: throw VisionFailure("识图服务地址无效")
    }

    /** 环回、私有网段、链路本地、CGNAT、唯一本地 IPv6 与 mDNS 域名。 */
    private fun isLocalHost(host: String): Boolean {
        val value = host.trim().trim('[', ']').lowercase()
        if (value.isEmpty()) return false
        if (value == "localhost" || value == "::1") return true
        if (value.startsWith("fe80:") || value.startsWith("fc") || value.startsWith("fd")) return true
        if (listOf(".local", ".lan", ".home.arpa", ".localhost").any { value.endsWith(it) }) return true
        val parts = value.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val (a, b) = octets
        return a == 10 || a == 127 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254) ||
            (a == 100 && b in 64..127)
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

    private fun String.normalizedMime(): String =
        substringBefore(';').trim().takeIf { it.startsWith("image/") } ?: "image/jpeg"

    private const val TIMEOUT_SECONDS = 120L
    private const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024
    private const val MAX_DESCRIPTION_CHARS = 8_000
    private const val MAX_QUESTION_CHARS = 500
}
