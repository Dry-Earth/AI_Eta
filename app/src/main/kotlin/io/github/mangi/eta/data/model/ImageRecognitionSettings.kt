package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

/**
 * 本地图片识别服务配置。
 *
 * 启用后 `read_image` 只把图片发给这里配置的本机/局域网服务，结果以纯文本交给对话模型，
 * 图片本身不再进入对话请求，也不允许配置公网地址。
 */
@Serializable
data class ImageRecognitionSettings(
    val enabled: Boolean = false,
    val baseUrl: String = "http://127.0.0.1:11434",
    val path: String = "/v1/chat/completions",
    val model: String = "",
    val maxTokens: Int = 1024,
)
