package io.github.mangi.eta.agent.geniex

import android.content.Context
import com.geniex.sdk.VlmWrapper
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.VlmChatMessage
import com.geniex.sdk.bean.VlmContent
import com.geniex.sdk.bean.VlmCreateInput
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** 一次设备内识图的运行参数。 */
internal data class GenieXRunConfig(
    val modelPath: String,
    val mmprojPath: String? = null,
    val runtimeId: String = "llama_cpp",
    /** null 表示自动；npu / gpu / cpu 为用户显式指定。 */
    val computeUnit: String? = null,
    val contextSize: Int = 4096,
    val maxTokens: Int = 1024,
)

internal class GenieXVisionFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 设备内视觉识别（GenieX VlmWrapper）。
 *
 * 接口顺序严格按官方 quickstart：
 * `VlmCreateInput` → `build` → `injectMediaPathsToConfig` → `applyChatTemplate`
 * → `generateStreamFlow(formattedText)`。
 *
 * `compute_unit` 固定传 `null`：官方说明 null 表示自动，在 Snapdragon 上会选 NPU。
 * 模型只在这里持有，调用方按需 [close] 卸载。
 */
internal class GenieXVisionClient(private val context: Context) {
    private val mutex = Mutex()
    private var wrapper: VlmWrapper? = null
    private var loadedKey = ""

    suspend fun describe(run: GenieXRunConfig, question: String, imagePath: String): String = mutex.withLock {
        val vlm = ensureLoaded(run)
        val prompt = PREFIX + "\n" + question.trim().take(MAX_QUESTION_CHARS).ifBlank { DEFAULT_QUESTION }
        val messages = arrayListOf(
            VlmChatMessage(
                role = "user",
                contents = listOf(
                    VlmContent("image", imagePath),
                    VlmContent("text", prompt),
                ),
            ),
        )
        try {
            withTimeout(TIMEOUT_MS) {
                val generation = vlm.injectMediaPathsToConfig(
                    messages.toTypedArray(),
                    GenerationConfig(maxTokens = run.maxTokens),
                )
                val templated = vlm.applyChatTemplate(messages.toTypedArray(), null, false).getOrThrow()
                val text = StringBuilder()
                vlm.generateStreamFlow(templated.formattedText, generation).collect { result ->
                    when (result) {
                        is LlmStreamResult.Token -> if (text.length < MAX_DESCRIPTION_CHARS) text.append(result.text)
                        is LlmStreamResult.Completed -> Unit
                        is LlmStreamResult.Error -> throw GenieXVisionFailure(
                            "本地模型生成失败：${result.throwable.message ?: result.throwable.javaClass.simpleName}",
                            result.throwable,
                        )
                    }
                }
                text.toString().trim().ifEmpty {
                    throw GenieXVisionFailure("本地模型没有返回文字描述")
                }
            }
        } catch (error: GenieXVisionFailure) {
            throw error
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            throw GenieXVisionFailure("本地识图超时，请重试或换更小的模型")
        } catch (error: Throwable) {
            throw GenieXVisionFailure(
                "本地识图失败：${error.message ?: error.javaClass.simpleName}",
                error,
            )
        }
    }

    private suspend fun ensureLoaded(run: GenieXRunConfig): VlmWrapper {
        if (run.modelPath.isBlank()) throw GenieXVisionFailure("请先在设置中选择本地模型")
        val key = "${run.modelPath}|${run.mmprojPath}|${run.runtimeId}|${run.contextSize}|${run.computeUnit}"
        wrapper?.takeIf { loadedKey == key }?.let { return it }
        close()
        GenieXModelManager.ensureInitialized(context)
        val built = runCatching {
            VlmWrapper.builder()
                .vlmCreateInput(
                    VlmCreateInput(
                        model_path = run.modelPath,
                        mmproj_path = run.mmprojPath,
                        config = ModelConfig(nCtx = run.contextSize),
                        runtime_id = run.runtimeId,
                        compute_unit = run.computeUnit,
                    ),
                )
                .build()
                .getOrThrow()
        }.getOrElse { error ->
            throw GenieXVisionFailure(
                "本地模型加载失败：${error.message ?: error.javaClass.simpleName}",
                error,
            )
        }
        wrapper = built
        loadedKey = key
        return built
    }

    fun close() {
        runCatching { wrapper?.close() }
        wrapper = null
        loadedKey = ""
    }

    private companion object {
        const val TIMEOUT_MS = 120_000L
        const val MAX_DESCRIPTION_CHARS = 8_000
        const val MAX_QUESTION_CHARS = 500
        const val PREFIX =
            "你是运行在本机的图片识别服务。只描述图中真实可见的内容；看不清或不确定的地方要明确说明，不要推测或编造。"
        const val DEFAULT_QUESTION = "请客观描述这张图片：整体场景、关键对象、可见文字与界面布局。"
    }
}
