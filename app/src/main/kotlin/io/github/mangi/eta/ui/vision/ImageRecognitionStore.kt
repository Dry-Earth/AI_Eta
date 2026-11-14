package io.github.mangi.eta.ui.vision

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.geniex.GenieXModelManager
import io.github.mangi.eta.agent.vision.VisionFailure
import io.github.mangi.eta.data.model.ImageRecognitionSettings
import io.github.mangi.eta.data.model.VisionEngine
import io.github.mangi.eta.data.repository.ImageRecognitionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 图片识别配置草稿：进入页面时加载，保存时才整体落盘。 */
internal class ImageRecognitionStore(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var settings by mutableStateOf(ImageRecognitionSettings())
        private set
    var loaded by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var messageIsError by mutableStateOf(false)
        private set

    init {
        scope.launch {
            try {
                settings = ImageRecognitionRepository.settings()
                loaded = true
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message = context.getString(R.string.speech_load_failed)
                messageIsError = true
            }
        }
    }

    fun edit(value: ImageRecognitionSettings) {
        settings = value
        message = null
    }

    fun save(validate: (ImageRecognitionSettings) -> Unit) {
        if (!loaded || saving) return
        val config = settings
        try {
            validate(config)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val reason = (error as? VisionFailure)?.message ?: context.getString(R.string.speech_save_retry)
            message = context.getString(R.string.speech_save_failed, reason)
            messageIsError = true
            return
        }
        saving = true
        scope.launch {
            try {
                prepareGenieXModel(config)
                ImageRecognitionRepository.save(config)
                message = context.getString(R.string.speech_saved)
                messageIsError = false
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message = context.getString(R.string.speech_save_retry)
                messageIsError = true
            } finally {
                saving = false
            }
        }
    }
    /** GenieX 引擎：保存时确保模型就绪；填了本地路径就不再联网。 */
    private suspend fun prepareGenieXModel(config: ImageRecognitionSettings) {
        if (config.engine != VisionEngine.GENIEX) return
        if (config.geniexLocalPath.isNotBlank()) return
        val name = config.geniexModel.trim()
        if (name.isBlank()) throw VisionFailure("请填写模型名，或选择已有模型文件")
        if (GenieXModelManager.paths(name) != null) return
        message = "正在下载模型，可切到后台"
        messageIsError = false
        GenieXModelManager.ensureInitialized(context)
        GenieXModelManager.pullByName(context, name).collect { }
        if (GenieXModelManager.paths(name) == null) {
            throw VisionFailure("模型下载未完成，请重试")
        }
    }
}
