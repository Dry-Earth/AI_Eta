package io.github.mangi.eta.agent.geniex

import android.content.Context
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.ModelPaths
import com.geniex.sdk.bean.ModelPullInput
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.Flow

/**
 * GenieX 模型管理：官方 SDK 的薄封装。
 *
 * - 下载走 `pullFlow`（断点续传、逐文件进度），来源可选 HF / AI Hub / ModelScope；
 * - 本地已有的模型用 `local_path` 直接登记，不联网；
 * - 只有运行时才初始化 SDK，避免拖慢 App 启动。
 */
internal object GenieXModelManager {
    private val initialized = AtomicBoolean(false)

    suspend fun ensureInitialized(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        runCatching { GenieXSdk.getInstance().init(context.applicationContext) }
            .onFailure { initialized.set(false) }
    }

    fun pull(context: Context, entry: GenieXModelEntry): Flow<ModelManagerWrapper.PullEvent> =
        ModelManagerWrapper.pullFlow(
            ModelPullInput(
                model_name = entry.modelName,
                precision = entry.quant,
                hub = runCatching { HubSource.valueOf(entry.hub.uppercase()) }.getOrDefault(HubSource.AUTO),
                chipset = entry.chipset.takeIf { it.isNotBlank() },
            ),
        )

    /** 手填模型名：只给名字和来源，精度由 SDK 自己解析。 */
    fun pullByName(context: Context, modelName: String, hub: String = "HUGGINGFACE"):
        Flow<ModelManagerWrapper.PullEvent> = ModelManagerWrapper.pullFlow(
        ModelPullInput(
            model_name = modelName.trim(),
            hub = runCatching { HubSource.valueOf(hub.uppercase()) }.getOrDefault(HubSource.AUTO),
        ),
    )

    /** 使用用户已经下载好的模型文件（不经过 SDK 的下载目录）。 */
    fun registerLocal(context: Context, modelPath: String, runtime: String = "llama_cpp"):
        Flow<ModelManagerWrapper.PullEvent> = ModelManagerWrapper.pullFlow(
        ModelPullInput(
            model_name = modelPath.trim(),
            hub = HubSource.LOCALFS,
            local_path = modelPath.trim(),
        ),
    )

    suspend fun downloaded(): List<String> = runCatching { ModelManagerWrapper.list() }.getOrDefault(emptyList())

    suspend fun paths(modelName: String): ModelPaths? =
        runCatching { ModelManagerWrapper.getPaths(modelName.trim()) }.getOrNull()

    suspend fun remove(modelName: String) = runCatching { ModelManagerWrapper.remove(modelName.trim()) }

    suspend fun clean() = runCatching { ModelManagerWrapper.clean() }
}
