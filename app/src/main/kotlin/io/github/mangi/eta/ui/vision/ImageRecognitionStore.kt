package io.github.mangi.eta.ui.vision

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.vision.VisionFailure
import io.github.mangi.eta.data.model.ImageRecognitionSettings
import io.github.mangi.eta.data.repository.ImageRecognitionRepository
import io.github.mangi.eta.data.repository.SpeechCredentialsUnavailable
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
    var apiKey by mutableStateOf("")
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
                try {
                    apiKey = ImageRecognitionRepository.apiKey(context)
                } catch (_: SpeechCredentialsUnavailable) {
                    message = context.getString(R.string.speech_credentials_lost)
                    messageIsError = true
                }
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

    fun key(value: String) {
        apiKey = value
        message = null
    }

    /** 地址必须在本机/局域网内，公网地址在保存时就被拒绝。 */
    fun save(validate: (ImageRecognitionSettings) -> Unit) {
        if (!loaded || saving) return
        val config = settings
        val key = apiKey
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
                ImageRecognitionRepository.saveApiKey(context, key)
                ImageRecognitionRepository.save(config)
                message = context.getString(R.string.speech_saved)
                messageIsError = false
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val reason = (error as? VisionFailure)?.message ?: context.getString(R.string.speech_save_retry)
                message = context.getString(R.string.speech_save_failed, reason)
                messageIsError = true
            } finally {
                saving = false
            }
        }
    }
}
