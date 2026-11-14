package io.github.mangi.eta.agent.geniex

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 设备内 GenieX 模型清单。
 *
 * 结构与官方示例 App（qualcomm/ai-hub-apps, BSD-3）的 `model_list.json` 一致：
 * SDK 本身没有"列出服务器全部模型"的接口，官方同样是随包一份清单。
 * 清单缺失或解析失败时返回空列表，界面回退到"自定义模型名"。
 */
@Serializable
data class GenieXModelEntry(
    val id: String,
    val displayName: String,
    val modelName: String,
    val quant: String = "Q4_0",
    val hub: String = "HUGGINGFACE",
    val type: String = "vlm",
    val runtime: String = "llama_cpp",
    /** qairt 运行时（Qualcomm AI Hub 模型）必须显式指定芯片。 */
    val chipset: String = "",
    val approxSizeGb: Double = 0.0,
)

internal object GenieXModelCatalog {
    private const val ASSET = "geniex/model_list.json"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun load(context: Context): List<GenieXModelEntry> = runCatching {
        val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        json.decodeFromString<List<GenieXModelEntry>>(text)
    }.getOrDefault(emptyList())

    fun byId(context: Context, id: String): GenieXModelEntry? =
        load(context).firstOrNull { it.id == id }

    /** 手填模型名时按官方规则推断运行时：qualcomm 前缀走 qairt（仅 NPU），其余走 llama.cpp。 */
    fun inferRuntime(modelName: String): String =
        if (modelName.trim().startsWith("qualcomm/")) "qairt" else "llama_cpp"
}
