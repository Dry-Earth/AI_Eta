package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

/**
 * 本地图片识别服务配置。
 *
 * 这是一个独立于对话模型的视觉接口：启用后 `read_image` 只把图片发给这里配置的服务，
 * 结果以纯文本交给对话模型，图片本身不再进入对话请求，也不允许配置公网地址。
 */
@Serializable
data class ImageRecognitionSettings(
    val enabled: Boolean = false,
    val baseUrl: String = "http://127.0.0.1:11434",
    val path: String = "/v1/chat/completions",
    val model: String = "",
    val prompt: String = DEFAULT_PROMPT,
    val maxTokens: Int = 1024,
) {
    companion object {
        const val MAX_PROMPT_CHARS = 4_000
        const val MIN_MAX_TOKENS = 64
        const val MAX_MAX_TOKENS = 8_192
        const val DEFAULT_PROMPT =
            "请用中文客观描述这张图片：先说明整体场景，再按位置列出关键对象、可见文字与界面布局。" +
                "看不清或不确定的内容要明确说明不确定，不要猜测或编造。"
    }
}
