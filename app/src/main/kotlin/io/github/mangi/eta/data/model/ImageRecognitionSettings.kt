package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

/** 识图引擎：本地 HTTP 服务，或设备内 GenieX（NPU/GPU/CPU）。 */
@Serializable
enum class VisionEngine { HTTP, GENIEX }

/**
 * 本地图片识别服务配置。
 *
 * 启用后 `read_image` 只把图片发给本机服务，结果以纯文本交给对话模型，
 * 图片本身不再进入对话请求，也不允许配置公网地址。
 */
@Serializable
data class ImageRecognitionSettings(
    val enabled: Boolean = false,
    val engine: VisionEngine = VisionEngine.HTTP,
    // HTTP 引擎
    val baseUrl: String = "http://127.0.0.1:11434",
    val path: String = "/v1/chat/completions",
    val model: String = "",
    val maxTokens: Int = 1024,
    // GenieX 引擎
    /** 官方模型名（org/repo、AI Hub id）或 HuggingFace 链接；保存时会按此下载。 */
    val geniexModel: String = "",
    /** 已经下载好的本地模型路径；填写后不再联网下载。 */
    val geniexLocalPath: String = "",
    /** 用完即卸载，避免 12 GB 机型长期占内存。 */
    val geniexOnDemand: Boolean = true,
    val geniexContextSize: Int = 4096,
)
